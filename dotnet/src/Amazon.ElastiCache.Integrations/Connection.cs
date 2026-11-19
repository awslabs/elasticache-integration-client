using System.Buffers;
using Microsoft.Extensions.Logging;

namespace Amazon.ElastiCache.Integrations;

/// <summary>
/// One connection to the cache. Requests are pipelined: a lock covers registering the slot and
/// writing the command, so the FIFO order is the wire order, and one reader task resolves the
/// slots. Thread safe. A dead connection stays dead; reconnecting is the session's job.
/// </summary>
internal sealed class ElastiCacheIntegrationsConnection : ICacheConnection
{
    private readonly Stream _stream;
    private readonly ReplyReader _reader;
    private readonly PendingRequests _pending;
    private readonly SemaphoreSlim _writeLock = new(1, 1);
    private readonly CancellationTokenSource _shutdown = new();
    private readonly TaskCompletionSource<Exception> _closed = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private readonly ElastiCacheIntegrationsClientOptions _options;
    private readonly ILogger _logger;
    private readonly TimeProvider _time;
    private readonly IElastiCacheIntegrationsAuthProvider _auth;
    private ResolvedConfig? _config;
    private Task _readLoop = Task.CompletedTask;
    private const string ClosedReason = "The connection was closed.";

    private volatile bool _closing;
    private int _dead;

    internal ElastiCacheIntegrationsConnection(Stream stream, ElastiCacheIntegrationsClientOptions options, IElastiCacheIntegrationsAuthProvider auth, ILogger logger, TimeProvider time)
    {
        _stream = stream;
        _auth = auth;
        _options = options;
        _logger = logger;
        _time = time;
        _reader = new ReplyReader(stream, options.MaxResponseSize);
        _pending = new PendingRequests(options.MaxPending);
    }

    /// <summary>True after <c>HELLO 3</c> succeeded: the cache may defer <c>aws.request</c> replies.</summary>
    internal bool Resp3 { get; set; }

    public bool IsOpen => Volatile.Read(ref _dead) == 0 && !_closing;

    /// <summary>Completes with the cause once the connection dies or is closed.</summary>
    public Task<Exception> Closed => _closed.Task;

    /// <summary>The config from bring-up, or from the last refresh that changed it.</summary>
    public ResolvedConfig? Config => Volatile.Read(ref _config);

    internal int PendingCount => _pending.Count;

    internal void Start() => _readLoop = ReadLoopAsync();

    /// <summary>
    /// One bring-up attempt: TCP, TLS, <c>AUTH</c>, <c>HELLO 3</c> (RESP2 if refused), then
    /// <c>aws.config</c> with the overrides. The session retries failed attempts.
    /// </summary>
    /// <exception cref="ElastiCacheIntegrationsConfigurationException">The cache rejected the overrides, or the auth provider found the options invalid.</exception>
    /// <exception cref="ElastiCacheIntegrationsConnectionException">Any other failure; after the transport, the message names the step.</exception>
    public static async Task<ElastiCacheIntegrationsConnection> OpenAsync(
        ElastiCacheIntegrationsClientOptions options,
        IElastiCacheIntegrationsAuthProvider auth,
        ReadOnlyMemory<byte> overridesJson,
        ILogger logger,
        TimeProvider time,
        CancellationToken cancellationToken)
    {
        var stream = await Transport.ConnectAsync(options, logger, time, cancellationToken).ConfigureAwait(false);
        var connection = new ElastiCacheIntegrationsConnection(stream, options, auth, logger, time);
        connection.Start();
        var step = "AUTH";
        try
        {
            await connection.ReauthenticateAsync(cancellationToken).ConfigureAwait(false);
            step = "HELLO";
            await connection.HelloAsync(cancellationToken).ConfigureAwait(false);
            step = ElastiCacheIntegrationsProtocol.ConfigCommand;
            var frame = new ArrayBufferWriter<byte>();
            ElastiCacheIntegrationsProtocol.WriteConfig(frame, overridesJson);
            var reply = await connection.ExecuteAsync(step, frame.WrittenMemory, false, options.ConfigTimeout, cancellationToken).ConfigureAwait(false);
            connection._config = ElastiCacheIntegrationsProtocol.ParseConfigReply(reply, noArgumentForm: false);
            return connection;
        }
        catch (Exception e)
        {
            connection.Dispose();
            await connection._readLoop.ConfigureAwait(false);
            if (e is ElastiCacheIntegrationsConfigurationException || (e is OperationCanceledException && cancellationToken.IsCancellationRequested))
            {
                throw;
            }

            throw new ElastiCacheIntegrationsConnectionException($"{step} failed during bring-up: {e.Message}", e);
        }
    }

    /// <summary><c>AUTH</c> with a fresh token from the auth provider. A rejected token leaves the connection open.</summary>
    /// <exception cref="ElastiCacheIntegrationsConnectionException">The auth provider failed. A <see cref="ElastiCacheIntegrationsException"/> from it passes unchanged.</exception>
    /// <exception cref="ElastiCacheIntegrationsServerException">The cache rejected the token.</exception>
    /// <exception cref="ElastiCacheIntegrationsTimeoutException">No reply within <see cref="ElastiCacheIntegrationsClientOptions.ConfigTimeout"/>; the connection is closed.</exception>
    /// <exception cref="ElastiCacheIntegrationsConnectionLostException">The connection died.</exception>
    /// <exception cref="ElastiCacheIntegrationsProtocolException">A reply other than <c>+OK</c> or an error.</exception>
    public async Task ReauthenticateAsync(CancellationToken cancellationToken)
    {
        (string UserId, string Token) auth;
        try
        {
            auth = await _auth.GetAuthAsync(cancellationToken).ConfigureAwait(false);
        }
        catch (Exception e) when (e is not ElastiCacheIntegrationsException && !(e is OperationCanceledException && cancellationToken.IsCancellationRequested))
        {
            throw new ElastiCacheIntegrationsConnectionException("The auth provider failed.", e);
        }

        var frame = new ArrayBufferWriter<byte>();
        RespEncoder.WriteCommand(frame, "AUTH", auth.UserId, auth.Token);
        switch (await ExecuteAsync("AUTH", frame.WrittenMemory, false, _options.ConfigTimeout, cancellationToken).ConfigureAwait(false))
        {
            case RespSimpleString { Value: "OK" }:
                return;
            case RespError error:
                throw ServerErrors.ToException(error);
            default:
                throw new ElastiCacheIntegrationsProtocolException("Malformed AUTH reply: expected +OK.");
        }
    }

    /// <summary>Sends the no-argument <c>aws.config</c>. Null means the config is unchanged.</summary>
    /// <exception cref="ElastiCacheIntegrationsServerException">An error reply; the config is kept.</exception>
    /// <exception cref="ElastiCacheIntegrationsTimeoutException">No reply within <see cref="ElastiCacheIntegrationsClientOptions.ConfigTimeout"/>; the connection is closed.</exception>
    /// <exception cref="ElastiCacheIntegrationsConnectionLostException">The connection died.</exception>
    /// <exception cref="ElastiCacheIntegrationsProtocolException">A reply that is not a config.</exception>
    public async Task<ResolvedConfig?> RefreshConfigAsync(CancellationToken cancellationToken)
    {
        var frame = new ArrayBufferWriter<byte>();
        ElastiCacheIntegrationsProtocol.WriteConfigRefresh(frame);
        var reply = await ExecuteAsync(ElastiCacheIntegrationsProtocol.ConfigCommand, frame.WrittenMemory, false, _options.ConfigTimeout, cancellationToken)
            .ConfigureAwait(false);
        var config = ElastiCacheIntegrationsProtocol.ParseConfigReply(reply, noArgumentForm: true);
        if (config is not null)
        {
            Volatile.Write(ref _config, config);
        }

        return config;
    }

    private async Task HelloAsync(CancellationToken cancellationToken)
    {
        var frame = new ArrayBufferWriter<byte>();
        RespEncoder.WriteCommand(frame, "HELLO", "3");
        switch (await ExecuteAsync("HELLO", frame.WrittenMemory, false, _options.ConfigTimeout, cancellationToken).ConfigureAwait(false))
        {
            case RespMap:
                Resp3 = true;
                break;
            // RESP2 only when the server has no HELLO (ERR) or no RESP3 (NOPROTO); any other error fails the attempt.
            case RespError { Code: "ERR" or "NOPROTO" } error:
                _logger.LogInformation(ElastiCacheIntegrationsEvents.Resp2Fallback, "The cache refused HELLO 3 ({Code}); using RESP2", error.Code);
                break;
            case RespError error:
                throw ServerErrors.ToException(error);
            default:
                throw new ElastiCacheIntegrationsProtocolException("Malformed HELLO reply: expected a map.");
        }
    }

    /// <summary>Sends <c>aws.request</c> and returns its final reply: served, or rejected by the cache.</summary>
    /// <exception cref="ElastiCacheIntegrationsPendingLimitException">Too many requests in flight; nothing was sent.</exception>
    /// <exception cref="ElastiCacheIntegrationsTimeoutException">No reply within <see cref="ElastiCacheIntegrationsClientOptions.RequestTimeout"/>. Without a request id from the cache, the connection is closed too.</exception>
    /// <exception cref="ElastiCacheIntegrationsConnectionLostException">The connection died before the reply.</exception>
    /// <exception cref="ElastiCacheIntegrationsProtocolException">The reply has the wrong shape; the connection stays up.</exception>
    public async Task<RequestReply> RequestAsync(ElastiCacheIntegrationsRequest request, CancellationToken cancellationToken)
    {
        var frame = new ArrayBufferWriter<byte>();
        ElastiCacheIntegrationsProtocol.WriteRequest(frame, request);
        var value = await ExecuteAsync(
            ElastiCacheIntegrationsProtocol.RequestCommand, frame.WrittenMemory, Resp3, _options.RequestTimeout, cancellationToken).ConfigureAwait(false);

        // A deferred request resolves with the push's inner reply, and +id is not valid there.
        // On RESP2 nothing is deferred, so a +id in band fails this request the same way.
        return ElastiCacheIntegrationsProtocol.ParseRequestReply(value, inBand: false);
    }

    /// <summary>
    /// Sends one command and waits for its reply. <paramref name="timeout"/> bounds the wait for
    /// the write lock, then again the send and the reply. A timeout waiting for an in-band reply
    /// kills the connection; a timeout on a deferred request, or a cancellation, abandons only this
    /// command, and its reply is dropped when it arrives. A failed write kills the connection.
    /// </summary>
    internal async Task<RespValue> ExecuteAsync(
        string command, ReadOnlyMemory<byte> frame, bool deferrable, TimeSpan timeout, CancellationToken cancellationToken)
    {
        using var deadline = new CancellationTokenSource(timeout, _time);
        using var wait = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, deadline.Token);
        PendingSlot? slot = null;
        try
        {
            await _writeLock.WaitAsync(wait.Token).ConfigureAwait(false);
            try
            {
                if (_closing)
                {
                    throw new ElastiCacheIntegrationsConnectionLostException("The connection is closed.");
                }

                // The send and the reply get the full timeout, as when no request was ahead. Nothing
                // is sent once the deadline has passed in the queue for the lock.
                deadline.CancelAfter(timeout);
                wait.Token.ThrowIfCancellationRequested();

                slot = _pending.TryAdd(deferrable, _time.GetTimestamp()) ?? throw new ElastiCacheIntegrationsPendingLimitException(
                    $"The connection already has {_options.MaxPending} requests in flight.");
                if (!slot.Reply.IsCompleted)
                {
                    await WriteAsync(frame, deadline.Token).ConfigureAwait(false);
                }
            }
            finally
            {
                _writeLock.Release();
            }

            return await slot.Reply.WaitAsync(wait.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (wait.IsCancellationRequested)
        {
            if (cancellationToken.IsCancellationRequested)
            {
                if (slot is not null)
                {
                    Abandon(slot);
                }

                cancellationToken.ThrowIfCancellationRequested();
            }

            var message = $"{command} got no reply within {timeout.TotalMilliseconds} ms.";

            // Replies arrive in order, so a missing one means the connection is unusable.
            if (slot is not null && _pending.Abandon(slot))
            {
                Kill(message, null);
            }

            throw new ElastiCacheIntegrationsTimeoutException(message);
        }
    }

    private async Task WriteAsync(ReadOnlyMemory<byte> frame, CancellationToken deadline)
    {
        try
        {
            await _stream.WriteAsync(frame, deadline).ConfigureAwait(false);
            await _stream.FlushAsync(deadline).ConfigureAwait(false);
        }
        catch (Exception e)
        {
            // A partial command may be on the wire; no later reply can be trusted.
            // The slot fails with the connection, and the caller reads that failure.
            Kill("Writing to the cache failed.", e);
        }
    }

    /// <summary>A cancelled FIFO slot left waiting past <see cref="ElastiCacheIntegrationsClientOptions.HeadStallTimeout"/> means the head is stuck.</summary>
    private void Abandon(PendingSlot slot)
    {
        if (!_pending.Abandon(slot))
        {
            return;
        }

        var due = _options.HeadStallTimeout - _time.GetElapsedTime(slot.SentAt);
        if (due <= TimeSpan.Zero)
        {
            KillIfStalled(slot);
            return;
        }

        ITimer? timer = null;
        timer = _time.CreateTimer(
            _ =>
            {
                timer?.Dispose();
                KillIfStalled(slot);
            },
            null,
            due,
            Timeout.InfiniteTimeSpan);
    }

    private void KillIfStalled(PendingSlot slot)
    {
        if (_pending.IsInFifo(slot))
        {
            _logger.LogWarning(ElastiCacheIntegrationsEvents.HeadStalled, "Cache connection stalled: the oldest request got no reply within {TimeoutMs} ms", _options.HeadStallTimeout.TotalMilliseconds);
            Kill($"No reply from the cache within {_options.HeadStallTimeout.TotalMilliseconds} ms.", null);
        }
    }

    private async Task ReadLoopAsync()
    {
        try
        {
            while (true)
            {
                Dispatch(await _reader.ReadAsync(_shutdown.Token).ConfigureAwait(false));
            }
        }
        catch (Exception) when (Volatile.Read(ref _dead) != 0)
        {
            // Closed on purpose; the cause is already recorded.
        }
        catch (ElastiCacheIntegrationsConnectionLostException e)
        {
            Kill(e.Message, e.InnerException);
        }
        catch (ElastiCacheIntegrationsProtocolException e)
        {
            Kill("The reply stream is corrupt.", e);
        }
        catch (Exception e)
        {
            Kill("Reading from the cache failed.", e);
        }
    }

    private void Dispatch(RespValue value)
    {
        switch (value)
        {
            case RespPush push:
                var (requestId, reply) = ElastiCacheIntegrationsProtocol.ParseResponsePush(push);

                // The cache accepted this request, so it must not be resent through the cache: it fails with the connection.
                if (reply is RespError { Code: ServerErrors.Moved } moved)
                {
                    Kill($"The cache replied {moved.Code}.", ServerErrors.ToException(moved));
                    break;
                }

                if (!_pending.TryResolveDeferred(requestId, reply))
                {
                    _logger.LogDebug(ElastiCacheIntegrationsEvents.LatePushDropped, "Dropped the reply for request {RequestId}: it already timed out", requestId);
                }

                KillOnConnectionDrop(reply);
                break;
            case RespSimpleString { Value: { Length: > 0 } id } when id != "OK" && _pending.TryDefer(id):
                break;
            default:
                _pending.ResolveHead(value);
                KillOnConnectionDrop(value);
                break;
        }
    }

    /// <summary><c>MOVED</c> and <c>NOAUTH</c> mean no later command can succeed here, whoever sent the one they answer.</summary>
    private void KillOnConnectionDrop(RespValue reply)
    {
        if (reply is RespError error && ServerErrors.Classify(error.Code) == ServerErrorKind.ConnectionDrop)
        {
            Kill($"The cache replied {error.Code}.", ServerErrors.ToException(error));
        }
    }

    /// <summary>Marks the connection dead once: fails every pending request and closes the stream.</summary>
    private void Kill(string reason, Exception? cause, bool closedByOwner = false)
    {
        if (Interlocked.Exchange(ref _dead, 1) != 0)
        {
            return;
        }

        if (!closedByOwner)
        {
            _logger.LogInformation(ElastiCacheIntegrationsEvents.ConnectionLost, cause, "Cache connection lost: {Reason}", reason);
        }

        _pending.FailAll(reason, cause);
        _shutdown.Cancel();
        _stream.Dispose();
        _closed.TrySetResult(new ElastiCacheIntegrationsConnectionLostException(reason, cause));
    }

    /// <summary>Closes at once: pending requests fail with <see cref="ElastiCacheIntegrationsConnectionLostException"/>.</summary>
    public void Dispose()
    {
        _closing = true;
        Kill(ClosedReason, null, closedByOwner: true);
    }

    /// <summary>
    /// Stops new requests, waits up to <see cref="ElastiCacheIntegrationsClientOptions.ConfigTimeout"/> for the
    /// ones in flight, then closes and waits for the reader task.
    /// </summary>
    public async ValueTask DisposeAsync()
    {
        _closing = true;
        using (var grace = new CancellationTokenSource(_options.ConfigTimeout, _time))
        {
            try
            {
                // A command that passed the closing check before it was set finishes registering first.
                await _writeLock.WaitAsync(grace.Token).ConfigureAwait(false);
                _writeLock.Release();
                var expired = Task.Delay(Timeout.InfiniteTimeSpan, _time, grace.Token);
                await Task.WhenAny(_pending.WhenEmptyAsync(), _closed.Task, expired).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                // A write held the lock for all of ConfigTimeout.
            }
        }

        Kill(ClosedReason, null, closedByOwner: true);
        await _readLoop.ConfigureAwait(false);
    }
}
