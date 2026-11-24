// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using System.Buffers;

namespace Amazon.ElastiCache.Integrations;

/// <summary>
/// One connection to the cache. Requests are pipelined: a lock covers registering the slot and
/// writing the command, so the FIFO order is the wire order, and one reader task resolves the
/// slots. Thread safe. A dead connection stays dead; reconnecting is the session's job.
/// </summary>
internal sealed class ElastiCacheIntegrationsConnection : IDisposable, IAsyncDisposable
{
    private readonly Stream _stream;
    private readonly ReplyReader _reader;
    private readonly PendingRequests _pending;
    private readonly SemaphoreSlim _writeLock = new(1, 1);
    private readonly CancellationTokenSource _shutdown = new();
    private readonly TaskCompletionSource _closed = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private readonly ElastiCacheIntegrationsClientOptions _options;
    private readonly IamAuthTokenProvider _auth;
    private ResolvedConfig? _config;
    private Task _readLoop = Task.CompletedTask;
    private const string ClosedReason = "The connection was closed.";

    private volatile bool _closing;
    private int _dead;

    private ElastiCacheIntegrationsConnection(Stream stream, ElastiCacheIntegrationsClientOptions options, IamAuthTokenProvider auth)
    {
        _stream = stream;
        _auth = auth;
        _options = options;
        _reader = new ReplyReader(stream, options.MaxResponseSize);
        _pending = new PendingRequests(options.MaxPending == int.MaxValue ? int.MaxValue : options.MaxPending + 1);
    }

    /// <summary>True after <c>HELLO 3</c> succeeded: the cache may defer <c>aws.request</c> replies.</summary>
    private bool Resp3 { get; set; }

    public bool IsOpen => Volatile.Read(ref _dead) == 0 && !_closing;

    /// <summary>Completes once the connection dies or is closed.</summary>
    public Task Closed => _closed.Task;

    /// <summary>The config from bring-up, or from the last refresh that changed it.</summary>
    public ResolvedConfig? Config => Volatile.Read(ref _config);

    private void Start() => _readLoop = ReadLoopAsync();

    /// <summary>
    /// One bring-up attempt: TCP, TLS, <c>AUTH</c>, <c>HELLO 3</c> (RESP2 if refused), then
    /// <c>aws.config</c> with the overrides. The session retries failed attempts.
    /// </summary>
    /// <exception cref="ElastiCacheIntegrationsConfigurationException">The cache rejected the overrides, or the auth provider found the options invalid.</exception>
    /// <exception cref="ElastiCacheIntegrationsConnectionException">Any other failure; after the transport, the message names the step.</exception>
    public static async Task<ElastiCacheIntegrationsConnection> OpenAsync(
        ElastiCacheIntegrationsClientOptions options,
        IamAuthTokenProvider auth,
        ReadOnlyMemory<byte> overridesJson,
        CancellationToken cancellationToken)
    {
        var stream = await Transport.ConnectAsync(options, cancellationToken).ConfigureAwait(false);
        var connection = new ElastiCacheIntegrationsConnection(stream, options, auth);
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
            var reply = await connection.ExecuteAsync(step, frame.WrittenMemory, false, options.ConfigTimeoutMs, cancellationToken).ConfigureAwait(false);
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

    /// <summary><c>AUTH</c> with a fresh token from the auth provider.</summary>
    /// <exception cref="ElastiCacheIntegrationsConnectionException">The auth provider failed. A <see cref="ElastiCacheIntegrationsException"/> from it passes unchanged.</exception>
    /// <exception cref="ElastiCacheIntegrationsServerException">The cache rejected the token.</exception>
    /// <exception cref="ElastiCacheIntegrationsTimeoutException">No reply within <see cref="ElastiCacheIntegrationsClientOptions.ConfigTimeoutMs"/>.</exception>
    /// <exception cref="ElastiCacheIntegrationsConnectionLostException">The connection died.</exception>
    /// <exception cref="ElastiCacheIntegrationsProtocolException">A reply other than <c>+OK</c> or an error.</exception>
    public async Task ReauthenticateAsync(CancellationToken cancellationToken)
    {
        using var deadline = new CancellationTokenSource(_options.ConfigTimeoutMs);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, deadline.Token);
        try
        {
            var auth = await _auth.GetAuthAsync(linked.Token).ConfigureAwait(false);
            var frame = new ArrayBufferWriter<byte>();
            RespEncoder.WriteCommand(frame, "AUTH", auth.UserId, auth.Token);
            switch (await ExecuteAsync("AUTH", frame.WrittenMemory, false, _options.ConfigTimeoutMs, linked.Token).ConfigureAwait(false))
            {
                case RespSimpleString { Value: "OK" }:
                    return;
                case RespError:
                    // AUTH errors can echo the submitted token.
                    throw new ElastiCacheIntegrationsServerException("AUTH_ERROR", "The cache rejected IAM authentication.");
                default:
                    throw new ElastiCacheIntegrationsProtocolException("Malformed AUTH reply: expected +OK.");
            }
        }
        catch (OperationCanceledException) when (deadline.IsCancellationRequested && !cancellationToken.IsCancellationRequested)
        {
            throw new ElastiCacheIntegrationsTimeoutException($"AUTH exceeded {_options.ConfigTimeoutMs} ms.");
        }
    }

    /// <summary>Sends the no-argument <c>aws.config</c>. Null means the config is unchanged.</summary>
    /// <exception cref="ElastiCacheIntegrationsServerException">An error reply; the config is kept.</exception>
    /// <exception cref="ElastiCacheIntegrationsTimeoutException">No reply within <see cref="ElastiCacheIntegrationsClientOptions.ConfigTimeoutMs"/>.</exception>
    /// <exception cref="ElastiCacheIntegrationsConnectionLostException">The connection died.</exception>
    /// <exception cref="ElastiCacheIntegrationsProtocolException">A reply that is not a config.</exception>
    public async Task<ResolvedConfig?> RefreshConfigAsync(CancellationToken cancellationToken)
    {
        var frame = new ArrayBufferWriter<byte>();
        ElastiCacheIntegrationsProtocol.WriteConfigRefresh(frame);
        var reply = await ExecuteAsync(ElastiCacheIntegrationsProtocol.ConfigCommand, frame.WrittenMemory, false, _options.ConfigTimeoutMs, cancellationToken)
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
        switch (await ExecuteAsync("HELLO", frame.WrittenMemory, false, _options.ConfigTimeoutMs, cancellationToken).ConfigureAwait(false))
        {
            case RespMap:
                Resp3 = true;
                break;
            // RESP2 only when the server has no HELLO (ERR) or no RESP3 (NOPROTO); any other error fails the attempt.
            case RespError { Code: "ERR" or "NOPROTO" }:
                break;
            case RespError error:
                throw ServerErrors.ToException(error);
            default:
                throw new ElastiCacheIntegrationsProtocolException("Malformed HELLO reply: expected a map.");
        }
    }

    /// <summary>Sends <c>aws.request</c> and returns its final reply: served, or rejected by the cache.</summary>
    /// <exception cref="ElastiCacheIntegrationsPendingLimitException">Too many requests in flight; nothing was sent.</exception>
    /// <exception cref="ElastiCacheIntegrationsTimeoutException">No reply within <see cref="ElastiCacheIntegrationsClientOptions.RequestTimeoutMs"/>.</exception>
    /// <exception cref="ElastiCacheIntegrationsConnectionLostException">The connection died before the reply.</exception>
    /// <exception cref="ElastiCacheIntegrationsProtocolException">The reply has the wrong shape; the connection stays up.</exception>
    public async Task<ElastiCacheIntegrationsResponse> RequestAsync(ElastiCacheIntegrationsRequest request, CancellationToken cancellationToken)
    {
        var frame = new ArrayBufferWriter<byte>();
        ElastiCacheIntegrationsProtocol.WriteRequest(frame, request);
        var value = await ExecuteAsync(
            ElastiCacheIntegrationsProtocol.RequestCommand, frame.WrittenMemory, Resp3, _options.RequestTimeoutMs, cancellationToken).ConfigureAwait(false);

        // A deferred request resolves with the push's inner reply, and +id is not valid there.
        // On RESP2 nothing is deferred, so a +id in band fails this request the same way.
        return ElastiCacheIntegrationsProtocol.ParseRequestReply(value, inBand: false) switch
        {
            RequestReply.Served reply => reply.Response,
            RequestReply.Rejected reply => throw reply.Error,
            _ => throw new ElastiCacheIntegrationsProtocolException("Unexpected deferred response."),
        };
    }

    private async Task<RespValue> ExecuteAsync(
        string command, ReadOnlyMemory<byte> frame, bool deferrable, int timeoutMs, CancellationToken cancellationToken)
    {
        using var deadline = new CancellationTokenSource(timeoutMs);
        using var wait = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, deadline.Token, _shutdown.Token);
        PendingSlot? slot = null;
        var written = false;
        try
        {
            await _writeLock.WaitAsync(wait.Token).ConfigureAwait(false);
            try
            {
                if (!IsOpen)
                {
                    throw new ElastiCacheIntegrationsConnectionLostException("The cache connection is closed.");
                }

                wait.Token.ThrowIfCancellationRequested();
                slot = _pending.TryAdd(deferrable) ?? throw new ElastiCacheIntegrationsPendingLimitException(
                    $"The connection already has {_options.MaxPending} requests in flight.");
                if (!slot.Reply.IsCompleted)
                {
                    await _stream.WriteAsync(frame, wait.Token).ConfigureAwait(false);
                    await _stream.FlushAsync(wait.Token).ConfigureAwait(false);
                    written = true;
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
            if (slot is not null && (!written || (!_pending.TryAbandonDeferred(slot) && !slot.Reply.IsCompleted)))
            {
                // Partial writes and late in-band replies cannot be correlated safely.
                Kill("A command was interrupted after entering the wire queue.", null);
            }

            cancellationToken.ThrowIfCancellationRequested();
            if (deadline.IsCancellationRequested)
            {
                throw new ElastiCacheIntegrationsTimeoutException($"{command} exceeded {timeoutMs} ms.");
            }

            if (slot?.Reply.IsCompleted == true)
            {
                return await slot.Reply.ConfigureAwait(false);
            }

            throw new ElastiCacheIntegrationsConnectionLostException("The cache connection is closed.");
        }
        catch (Exception e) when (e is IOException or ObjectDisposedException)
        {
            Kill("Writing to the cache failed.", e);
            throw new ElastiCacheIntegrationsConnectionLostException("The cache connection was lost.", e);
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
            case RespPush push when Resp3:
                var (requestId, reply) = ElastiCacheIntegrationsProtocol.ParseResponsePush(push);
                if (_pending.TryResolveDeferred(requestId, reply))
                {
                    KillOnConnectionDrop(reply);
                }

                break;
            case RespPush:
                throw new ElastiCacheIntegrationsProtocolException("Unexpected push before RESP3 negotiation.");
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
        if (reply is RespError error && error.Code is ServerErrors.Moved or ServerErrors.NoAuth or ServerErrors.AuthError)
        {
            Kill("The cache requires a new connection.", null);
        }
    }

    /// <summary>Marks the connection dead once: fails every pending request and closes the stream.</summary>
    private void Kill(string reason, Exception? cause)
    {
        if (Interlocked.Exchange(ref _dead, 1) != 0)
        {
            return;
        }

        _pending.FailAll(reason, cause);
        try
        {
            _shutdown.Cancel();
            _stream.Dispose();
        }
        finally
        {
            _closed.TrySetResult();
        }
    }

    /// <summary>Closes at once: pending requests fail with <see cref="ElastiCacheIntegrationsConnectionLostException"/>.</summary>
    public void Dispose()
    {
        _closing = true;
        Kill(ClosedReason, null);
    }

    /// <summary>
    /// Stops new requests, waits up to <see cref="ElastiCacheIntegrationsClientOptions.ConfigTimeoutMs"/> for the
    /// ones in flight, then closes and waits for the reader task.
    /// </summary>
    public async ValueTask DisposeAsync()
    {
        _closing = true;
        using (var grace = new CancellationTokenSource(_options.ConfigTimeoutMs))
        {
            try
            {
                // A command that passed the closing check before it was set finishes registering first.
                await _writeLock.WaitAsync(grace.Token).ConfigureAwait(false);
                _writeLock.Release();
                await _pending.WhenEmptyAsync().WaitAsync(grace.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                // A write held the lock for all of ConfigTimeoutMs.
            }
        }

        Kill(ClosedReason, null);
        await _readLoop.ConfigureAwait(false);
    }
}
