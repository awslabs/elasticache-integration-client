using System.Security.Cryptography.X509Certificates;
using Microsoft.Extensions.Logging;

namespace Amazon.Photon;

/// <summary>What the session needs from one cache connection.</summary>
internal interface ICacheConnection : IDisposable, IAsyncDisposable
{
    bool IsOpen { get; }

    Task<Exception> Closed { get; }

    ResolvedConfig? Config { get; }

    Task<RequestReply> RequestAsync(PhotonRequest request, CancellationToken cancellationToken);

    Task ReauthenticateAsync(CancellationToken cancellationToken);

    Task<ResolvedConfig?> RefreshConfigAsync(CancellationToken cancellationToken);
}

/// <summary>One bring-up attempt: a live, authenticated and configured connection, or an exception.</summary>
internal delegate Task<ICacheConnection> ConnectionFactory(CancellationToken cancellationToken);

internal enum OutcomeKind
{
    /// <summary>Nothing reached DynamoDB through the cache; send the signed request to DynamoDB.</summary>
    Origin,

    /// <summary>The cache answered; return its response.</summary>
    Served,

    /// <summary>A transient fault; surface a retryable error so the SDK retries.</summary>
    Retryable,

    /// <summary>Fail the call with <see cref="PhotonOutcome.Error"/>; the SDK does not retry it.</summary>
    Terminal,
}

/// <summary>What one request through the session ended in.</summary>
/// <remarks>The default value is <see cref="OutcomeKind.Origin"/>.</remarks>
internal readonly record struct PhotonOutcome
{
    public static readonly PhotonOutcome Origin = new(OutcomeKind.Origin, null, null);

    private PhotonOutcome(OutcomeKind kind, PhotonResponse? response, Exception? error)
    {
        Kind = kind;
        Response = response;
        Error = error;
    }

    public OutcomeKind Kind { get; }

    /// <summary>Set for <see cref="OutcomeKind.Served"/>.</summary>
    public PhotonResponse? Response { get; }

    /// <summary>Set for <see cref="OutcomeKind.Retryable"/> and <see cref="OutcomeKind.Terminal"/>.</summary>
    public Exception? Error { get; }

    public static PhotonOutcome Served(PhotonResponse response) => new(OutcomeKind.Served, response, null);

    public static PhotonOutcome Retryable(Exception error) => new(OutcomeKind.Retryable, null, error);

    public static PhotonOutcome Terminal(Exception error) => new(OutcomeKind.Terminal, null, error);
}

/// <summary>The request outcome table: what a reply or a failure on a live connection means.</summary>
internal static class RequestOutcomes
{
    /// <summary>Maps a reply. <paramref name="onAuthError"/> runs only for <c>-AUTH_ERROR</c>.</summary>
    public static PhotonOutcome FromReply(RequestReply reply, bool strict, Func<AuthErrorAction> onAuthError) => reply switch
    {
        RequestReply.Served served => PhotonOutcome.Served(served.Response),
        RequestReply.Rejected { Error: var error } => ServerErrors.Classify(error.Code) switch
        {
            // Refused before any origin call, so a read or a write is safe to send to DynamoDB.
            ServerErrorKind.Deterministic => strict ? PhotonOutcome.Terminal(error) : PhotonOutcome.Origin,
            ServerErrorKind.Auth => onAuthError() == AuthErrorAction.Origin
                ? PhotonOutcome.Origin
                : PhotonOutcome.Terminal(new PhotonCacheUnavailableException("The cache holds no valid AWS credentials for this client.", error)),
            _ => PhotonOutcome.Retryable(error),
        },
        _ => PhotonOutcome.Retryable(new PhotonProtocolException($"Unexpected reply {reply.GetType().Name} for a final aws.request reply.")),
    };

    /// <summary>Maps a failure of <see cref="PhotonConnection.RequestAsync"/>; null when it is not one this table owns.</summary>
    public static PhotonOutcome? FromException(Exception error) => error switch
    {
        // Nothing was sent: the connection is healthy but full, so the SDK retries.
        PhotonPendingLimitException => PhotonOutcome.Retryable(error),

        // The request may have reached the cache. It is never replayed through Photon.
        PhotonTimeoutException or PhotonConnectionLostException or PhotonProtocolException => PhotonOutcome.Retryable(error),
        _ => null,
    };
}

/// <summary>
/// One Photon session: a connection that is rebuilt in the background whenever it dies, the
/// predicates of its latest config, re-AUTH and config refresh, and the request outcome table.
/// Thread safe.
/// </summary>
internal sealed class PhotonSession : IDisposable, IAsyncDisposable
{
    private readonly PhotonClientOptions _options;
    private readonly ConnectionFactory _connect;
    private readonly ILogger _logger;
    private readonly TimeProvider _time;
    private readonly Random _random;
    private readonly ReauthScheduler _reauth;
    private readonly object _gate = new();
    private readonly CancellationTokenSource _stop = new();
    private readonly List<Task> _retired = [];

    // A pending-limit hit this long after the previous one starts a new burst, and logs again.
    internal static readonly TimeSpan PendingBurstGap = TimeSpan.FromSeconds(1);

    // The session's own copy of the trusted roots, disposed with it.
    private X509Certificate2Collection? _ownedRoots;
    private State _state = new(null, null);
    private PredicateEvaluator _predicates = PredicateEvaluator.Empty;
    private PeriodicRunner? _refresh;
    private TimeSpan? _refreshInterval;
    private long _confirmedAt;
    private long _pendingLimitHitAt = -1;

    // Set from the first config the client cannot use until a usable one is applied. Written under the gate.
    private bool _unusableConfig;
    private Task _reconnectLoop = Task.CompletedTask;
    private volatile bool _disposed;

    private PhotonSession(PhotonClientOptions options, ConnectionFactory connect, string service, ILogger logger, TimeProvider time, Random random)
    {
        _options = options;
        _connect = connect;
        Service = service;
        _logger = logger;
        _time = time;
        _random = random;
        _reauth = new ReauthScheduler(ReauthenticateLiveAsync, options, logger, time, random);
    }

    /// <summary>The SDK service this session serves, lowercased.</summary>
    public string Service { get; }

    /// <summary>True while a live connection is installed.</summary>
    public bool IsOpen => Volatile.Read(ref _state).Connection is { IsOpen: true };

    internal long AuthGeneration => _reauth.Generation;

    private bool Strict => _options.FallbackDuration == TimeSpan.Zero;

    /// <summary>
    /// Validates the options, serializes the overrides once, and brings up the first connection:
    /// <see cref="PhotonReconnectPolicy.ConnectAttempts"/> attempts with backoff, each capped by
    /// <see cref="PhotonReconnectPolicy.AttemptTimeout"/>.
    /// </summary>
    /// <exception cref="PhotonConfigurationException">Bad options, or the cache rejected the overrides. Never retried.</exception>
    /// <exception cref="PhotonConnectionException">Every attempt failed; the last failure.</exception>
    public static async Task<PhotonSession> OpenAsync(
        PhotonClientOptions options, IPhotonAuthProvider auth, string service, CancellationToken cancellationToken)
    {
        OptionsValidator.Validate(options);
        ArgumentNullException.ThrowIfNull(auth);
        ArgumentException.ThrowIfNullOrEmpty(service);
        options = options.Snapshot();
        try
        {
            var logger = PhotonLog.CreateLogger(options.LoggerFactory);
            var overrides = PhotonProtocol.SerializeOverrides(options.Overrides);
            var time = TimeProvider.System;
            var session = await OpenAsync(
                options,
                async ct => await PhotonConnection.OpenAsync(options, auth, overrides, logger, time, ct).ConfigureAwait(false),
                service,
                logger,
                time,
                Random.Shared,
                cancellationToken).ConfigureAwait(false);
            session._ownedRoots = options.CaCertificates;
            return session;
        }
        catch
        {
            PhotonClientOptions.DisposeAll(options.CaCertificates);
            throw;
        }
    }

    internal static async Task<PhotonSession> OpenAsync(
        PhotonClientOptions options, ConnectionFactory connect, string service, ILogger logger, TimeProvider time, Random random,
        CancellationToken cancellationToken)
    {
        var session = new PhotonSession(options, connect, service.ToLowerInvariant(), logger, time, random);
        try
        {
            var (connection, predicates) = await session.BringUpAsync(cancellationToken).ConfigureAwait(false);
            if (!session.Install(connection, predicates))
            {
                await connection.DisposeAsync().ConfigureAwait(false);
                throw new ObjectDisposedException(nameof(PhotonSession));
            }

            session._reauth.Start();
            return session;
        }
        catch
        {
            await session.DisposeAsync().ConfigureAwait(false);
            throw;
        }
    }

    /// <summary>Sends one request through the cache and returns exactly one outcome.</summary>
    /// <exception cref="OperationCanceledException">The caller cancelled.</exception>
    /// <exception cref="ObjectDisposedException">The session is closed. A close that races the request returns Terminal <see cref="ObjectDisposedException"/> instead.</exception>
    public async Task<PhotonOutcome> RequestAsync(PhotonRequest request, CancellationToken cancellationToken)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (!Volatile.Read(ref _predicates).ShouldRoute(request))
        {
            return PhotonOutcome.Origin;
        }

        for (var resent = false; ; resent = true)
        {
            var (connection, outage) = await GetConnectionAsync(cancellationToken).ConfigureAwait(false);
            if (connection is null)
            {
                return NoConnection(outage);
            }

            // Past the FAS lifetime with re-AUTH failing, the connection counts as unauthenticated.
            if (_reauth.IsStale && _reauth.OnStale() is { } stale)
            {
                return stale == AuthErrorAction.Origin
                    ? PhotonOutcome.Origin
                    : PhotonOutcome.Terminal(new PhotonCacheUnavailableException("Re-authentication with the cache has failed past the FAS lifetime."));
            }

            var generation = _reauth.Generation;
            RequestReply reply;
            try
            {
                reply = await connection.RequestAsync(request, cancellationToken).ConfigureAwait(false);
            }
            catch (PhotonPendingLimitException e)
            {
                OnPendingLimit();
                return PhotonOutcome.Retryable(e);
            }
            catch (Exception e) when (RequestOutcomes.FromException(e) is { } outcome)
            {
                return outcome;
            }

            // MOVED in place of +id, or NOAUTH in band or in a push: the command did not run and the
            // connection is gone. Send it once more the way a request with no connection goes.
            if (!resent && reply is RequestReply.Rejected { Error.Code: var code } && ServerErrors.Classify(code) == ServerErrorKind.ConnectionDrop)
            {
                OnClosed(connection);
                continue;
            }

            return RequestOutcomes.FromReply(reply, Strict, () => _reauth.OnAuthError(generation));
        }
    }

    /// <summary>Logs the first pending-limit hit of a burst.</summary>
    private void OnPendingLimit()
    {
        var now = _time.GetTimestamp();
        var last = Interlocked.Exchange(ref _pendingLimitHitAt, now);
        if (last < 0 || _time.GetElapsedTime(last, now) >= PendingBurstGap)
        {
            _logger.LogWarning(PhotonEvents.PendingLimitReached,
                "The connection has MaxPending {MaxPending} calls in flight; calls over the limit fail with a retryable error",
                _options.MaxPending);
        }
    }

    /// <summary>The live connection; with none, waits up to <see cref="PhotonClientOptions.ReconnectWait"/> for the reconnect.</summary>
    private async Task<(ICacheConnection? Connection, Outage? Outage)> GetConnectionAsync(CancellationToken cancellationToken)
    {
        var state = Volatile.Read(ref _state);
        if (state.Connection is { } connection)
        {
            if (connection.IsOpen)
            {
                return (connection, null);
            }

            // Dead, and its Closed continuation has not run yet.
            OnClosed(connection);
            state = Volatile.Read(ref _state);
            if (state.Connection is { IsOpen: true } replaced)
            {
                return (replaced, null);
            }
        }

        if (state.Outage is not { } outage || _options.ReconnectWait <= TimeSpan.Zero)
        {
            return (null, state.Outage);
        }

        try
        {
            return (await outage.Landed.Task.WaitAsync(_options.ReconnectWait, _time, cancellationToken).ConfigureAwait(false), outage);
        }
        catch (TimeoutException)
        {
            return (null, outage);
        }
    }

    /// <summary>No connection after the wait: DynamoDB inside the fallback window, else cache unavailable.</summary>
    private PhotonOutcome NoConnection(Outage? outage)
    {
        if (outage is null || _disposed)
        {
            return PhotonOutcome.Terminal(new ObjectDisposedException(nameof(PhotonSession)));
        }

        // The fallback window starts after the reconnect wait, so the wait does not use it up.
        var down = _time.GetElapsedTime(outage.DroppedAt);
        if (!Strict && (_options.FallbackDuration == Timeout.InfiniteTimeSpan || down - _options.ReconnectWait < _options.FallbackDuration))
        {
            return PhotonOutcome.Origin;
        }

        if (outage.Exhausted.TryEnter())
        {
            _logger.LogWarning(PhotonEvents.CacheUnavailable,
                "The cache has been unreachable for {Down}, past ReconnectWait plus FallbackDuration {Budget}; requests fail until it reconnects",
                down, _options.FallbackDuration);
        }

        return PhotonOutcome.Terminal(new PhotonCacheUnavailableException(
            $"No cache connection for {down.TotalMilliseconds:F0} ms; ReconnectWait is {_options.ReconnectWait.TotalMilliseconds:F0} ms and FallbackDuration {_options.FallbackDuration.TotalMilliseconds:F0} ms."));
    }

    private async Task<(ICacheConnection Connection, PredicateEvaluator Predicates)> BringUpAsync(CancellationToken cancellationToken)
    {
        for (var attempt = 1; ; attempt++)
        {
            try
            {
                return await AttemptAsync(cancellationToken).ConfigureAwait(false);
            }
            catch (Exception e) when (attempt < _options.Reconnect.ConnectAttempts && e is not PhotonConfigurationException
                && !cancellationToken.IsCancellationRequested)
            {
                _logger.LogDebug(PhotonEvents.BringUpAttemptFailed, e, "Bring-up attempt {Attempt} failed; retrying", attempt);
                await Task.Delay(Backoff.Delay(_options.Reconnect, attempt, _random), _time, cancellationToken).ConfigureAwait(false);
            }
        }
    }

    /// <summary>One bring-up attempt, capped by <see cref="PhotonReconnectPolicy.AttemptTimeout"/>, and its compiled config.</summary>
    private async Task<(ICacheConnection Connection, PredicateEvaluator Predicates)> AttemptAsync(CancellationToken cancellationToken)
    {
        var limit = _options.Reconnect.AttemptTimeout;
        using var cap = new CancellationTokenSource(limit, _time);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, cap.Token);
        ICacheConnection connection;
        try
        {
            connection = await _connect(linked.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException e) when (cap.IsCancellationRequested && !cancellationToken.IsCancellationRequested)
        {
            throw new PhotonConnectionException($"A bring-up attempt took longer than {limit.TotalMilliseconds} ms.", e);
        }

        try
        {
            return (connection, Compile(connection.Config));
        }
        catch (PhotonProtocolException e)
        {
            await connection.DisposeAsync().ConfigureAwait(false);
            throw new PhotonConnectionException($"{PhotonProtocol.ConfigCommand} failed during bring-up: {e.Message}", e);
        }
    }

    /// <summary>
    /// Compiles a config. The first one the client cannot use logs an error, until a usable one is
    /// applied. A refresh reply counts only while <paramref name="refreshed"/> is the live connection.
    /// </summary>
    /// <exception cref="PhotonProtocolException">The client cannot use all of the config.</exception>
    private PredicateEvaluator Compile(ResolvedConfig? config, ICacheConnection? refreshed = null)
    {
        try
        {
            return PredicateEvaluator.Compile(config ?? throw new PhotonProtocolException($"No {PhotonProtocol.ConfigCommand} reply."));
        }
        catch (PhotonProtocolException e)
        {
            bool first;
            lock (_gate)
            {
                first = !_unusableConfig && (refreshed is null || ReferenceEquals(_state.Connection, refreshed));
                _unusableConfig |= first;
            }

            if (first)
            {
                _logger.LogError(PhotonEvents.ConfigUnusable, "The cache sent a config this client cannot use: {Reason} Photon keeps asking for a usable one",
                    e.Message);
            }

            throw;
        }
    }

    /// <summary>Makes <paramref name="connection"/> the live one. False once the session is closed.</summary>
    private bool Install(ICacheConnection connection, PredicateEvaluator predicates)
    {
        Outage? outage;
        PeriodicRunner? retired;
        lock (_gate)
        {
            if (_disposed)
            {
                return false;
            }

            retired = ApplyConfig(connection.Config, predicates);
            _confirmedAt = _time.GetTimestamp();

            // A new generation before the connection is visible, so every request on it reads the new one.
            _reauth.OnAuthenticated();
            outage = _state.Outage;
            Volatile.Write(ref _state, new State(connection, null));
        }

        Retire(retired);
        outage?.Landed.TrySetResult(connection);
        connection.Closed.ContinueWith(
            _ => OnClosed(connection),
            CancellationToken.None,
            TaskContinuationOptions.ExecuteSynchronously,
            TaskScheduler.Default);
        return true;
    }

    /// <summary>The live connection died: start an outage and reconnect in the background. Runs once per connection.</summary>
    private void OnClosed(ICacheConnection connection)
    {
        lock (_gate)
        {
            if (_disposed || !ReferenceEquals(_state.Connection, connection))
            {
                return;
            }

            var outage = new Outage(_time.GetTimestamp());
            Volatile.Write(ref _state, new State(null, outage));
            var loop = Task.Run(() => ReconnectAsync(outage, _stop.Token));

            // An earlier loop may still be logging after it installed a connection that died at once.
            _reconnectLoop = _reconnectLoop.IsCompleted ? loop : Task.WhenAll(_reconnectLoop, loop);
        }

        connection.Dispose();
    }

    /// <summary>Retries bring-up with backoff, with no attempt limit, until one lands or the session closes.</summary>
    private async Task ReconnectAsync(Outage outage, CancellationToken stop)
    {
        for (var attempt = 1; ; attempt++)
        {
            try
            {
                var (connection, predicates) = await AttemptAsync(stop).ConfigureAwait(false);
                if (!Install(connection, predicates))
                {
                    await connection.DisposeAsync().ConfigureAwait(false);
                    return;
                }

                _logger.Log(attempt > 1 ? LogLevel.Information : LogLevel.Debug, PhotonEvents.Reconnected,
                    "Reconnected to the cache after {Attempts} attempts in {Down}", attempt, _time.GetElapsedTime(outage.DroppedAt));
                return;
            }
            catch (Exception) when (stop.IsCancellationRequested)
            {
                return;
            }
            catch (Exception e)
            {
                if (attempt == 1)
                {
                    _logger.LogWarning(PhotonEvents.Reconnecting, e,
                        "Photon cannot reconnect to the cache; retrying in the background. Requests {Mode} meanwhile",
                        Strict ? "fail" : _options.FallbackDuration == Timeout.InfiniteTimeSpan ? "go to DynamoDB" : $"go to DynamoDB for up to {_options.FallbackDuration}");
                }
                else
                {
                    _logger.LogDebug(PhotonEvents.Reconnecting, e, "Reconnect attempt {Attempt} failed", attempt);
                }
            }

            try
            {
                await Task.Delay(Backoff.Delay(_options.Reconnect, attempt, _random), _time, stop).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
        }
    }

    /// <summary>Re-AUTHs the live connection. False when there is none, or it was replaced meanwhile.</summary>
    private async Task<bool> ReauthenticateLiveAsync(CancellationToken cancellationToken)
    {
        if (Volatile.Read(ref _state).Connection is not { IsOpen: true } connection)
        {
            return false;
        }

        await connection.ReauthenticateAsync(cancellationToken).ConfigureAwait(false);
        return ReferenceEquals(Volatile.Read(ref _state).Connection, connection);
    }

    /// <summary>Refreshes the live connection's config. False when there is none, or it was replaced meanwhile.</summary>
    private async Task<bool> RefreshConfigAsync(CancellationToken cancellationToken)
    {
        if (Volatile.Read(ref _state).Connection is not { IsOpen: true } connection)
        {
            return false;
        }

        ResolvedConfig? config;
        PredicateEvaluator? predicates = null;
        try
        {
            config = await connection.RefreshConfigAsync(cancellationToken).ConfigureAwait(false);
            if (!ReferenceEquals(Volatile.Read(ref _state).Connection, connection))
            {
                return false;
            }

            if (config is not null)
            {
                predicates = Compile(config, connection);
            }
            else if (Volatile.Read(ref _unusableConfig))
            {
                throw new PhotonProtocolException($"The cache still holds the {PhotonProtocol.ConfigCommand} reply this client cannot use.");
            }
        }
        catch (Exception e) when (!cancellationToken.IsCancellationRequested && UnconfirmedFor(connection) is { } age)
        {
            _logger.LogWarning(PhotonEvents.ConfigStale, e,
                "The config has not been confirmed for {Age}, past CachePolicyTimeout {Timeout}; reconnecting to fetch a current one",
                age, _options.CachePolicyTimeout);
            OnClosed(connection);
            throw;
        }

        PeriodicRunner? retired = null;
        var live = false;
        lock (_gate)
        {
            if (!_disposed && ReferenceEquals(_state.Connection, connection))
            {
                live = true;
                _confirmedAt = _time.GetTimestamp();
                retired = ApplyConfig(config, predicates);
            }
        }

        Retire(retired);
        return live;
    }

    /// <summary>How long the live config has gone unconfirmed, when that is at least <see cref="PhotonClientOptions.CachePolicyTimeout"/>.</summary>
    private TimeSpan? UnconfirmedFor(ICacheConnection connection)
    {
        lock (_gate)
        {
            if (_disposed || !connection.IsOpen || !ReferenceEquals(_state.Connection, connection))
            {
                return null;
            }

            var age = _time.GetElapsedTime(_confirmedAt);
            return age >= _options.CachePolicyTimeout ? age : null;
        }
    }

    /// <summary>Installs the predicates and reschedules the refresh. Null means unchanged. Called under the gate; returns the runner it replaced.</summary>
    private PeriodicRunner? ApplyConfig(ResolvedConfig? config, PredicateEvaluator? predicates)
    {
        if (config is null || predicates is null)
        {
            return null;
        }

        Volatile.Write(ref _predicates, predicates);
        _unusableConfig = false;
        _logger.LogDebug(PhotonEvents.ConfigApplied, "Applied config version {Version}, refresh every {Interval}", config.Version,
            config.RefreshInterval?.ToString() ?? "never");

        var interval = config.RefreshInterval is { } i && i > TimeSpan.Zero ? i : (TimeSpan?)null;
        if (interval == _refreshInterval)
        {
            return null;
        }

        var old = _refresh;
        _refreshInterval = interval;
        _refresh = interval is { } every
            ? new PeriodicRunner("Config refresh", every, _options.Reconnect, RefreshConfigAsync, _logger, _time, _random)
            : null;
        _refresh?.Start();
        return old;
    }

    /// <summary>Stops a replaced runner outside the gate. A tick that rescheduled runs inside it, so its stop is awaited at close.</summary>
    private void Retire(PeriodicRunner? runner)
    {
        if (runner is null)
        {
            return;
        }

        var stopped = runner.DisposeAsync().AsTask();
        lock (_gate)
        {
            _retired.RemoveAll(task => task.IsCompleted);
            _retired.Add(stopped);
        }
    }

    /// <summary>Closes at once: in-flight requests fail and every background loop stops.</summary>
    public void Dispose()
    {
        if (BeginClose() is not { } closing)
        {
            return;
        }

        _reauth.Dispose();
        closing.Refresh?.Dispose();
        closing.Connection?.Dispose();
        PhotonClientOptions.DisposeAll(_ownedRoots);
    }

    /// <summary>Stops the background loops and waits for them, then closes the connection, which drains its requests.</summary>
    public async ValueTask DisposeAsync()
    {
        if (BeginClose() is not { } closing)
        {
            return;
        }

        await _reauth.DisposeAsync().ConfigureAwait(false);
        if (closing.Refresh is not null)
        {
            await closing.Refresh.DisposeAsync().ConfigureAwait(false);
        }

        await Task.WhenAll(closing.Retired).ConfigureAwait(false);
        await closing.ReconnectLoop.ConfigureAwait(false);
        _stop.Dispose();
        if (closing.Connection is not null)
        {
            await closing.Connection.DisposeAsync().ConfigureAwait(false);
        }

        PhotonClientOptions.DisposeAll(_ownedRoots);
    }

    private Closing? BeginClose()
    {
        Closing closing;
        lock (_gate)
        {
            if (_disposed)
            {
                return null;
            }

            _disposed = true;
            closing = new Closing(_state.Connection, _refresh, [.. _retired], _reconnectLoop);
            _state.Outage?.Landed.TrySetResult(null);
            Volatile.Write(ref _state, new State(null, null));
            _refresh = null;
        }

        _stop.Cancel();
        return closing;
    }

    /// <summary>The live connection, or the outage while there is none. Both null once closed.</summary>
    private sealed record State(ICacheConnection? Connection, Outage? Outage);

    private sealed record Closing(ICacheConnection? Connection, PeriodicRunner? Refresh, Task[] Retired, Task ReconnectLoop);

    /// <summary>The time from a drop to the next installed connection.</summary>
    private sealed class Outage(long droppedAt)
    {
        public long DroppedAt { get; } = droppedAt;

        /// <summary>Completes with the new connection, or with null when the session closes.</summary>
        public TaskCompletionSource<ICacheConnection?> Landed { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);

        public OnceFlag Exhausted { get; } = new();
    }
}
