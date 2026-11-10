using System.Security.Cryptography.X509Certificates;
using Microsoft.Extensions.Logging;

namespace Amazon.ElastiCache.Integrations;

/// <summary>What the session needs from one cache connection.</summary>
internal interface ICacheConnection : IDisposable, IAsyncDisposable
{
    bool IsOpen { get; }

    Task<Exception> Closed { get; }

    ResolvedConfig? Config { get; }

    Task<RequestReply> RequestAsync(ElastiCacheIntegrationsRequest request, CancellationToken cancellationToken);

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

    /// <summary>Fail the call with <see cref="ElastiCacheIntegrationsOutcome.Error"/>; the SDK does not retry it.</summary>
    Terminal,
}

/// <summary>What one request through the session ended in.</summary>
/// <remarks>The default value is <see cref="OutcomeKind.Origin"/>.</remarks>
internal readonly record struct ElastiCacheIntegrationsOutcome
{
    public static readonly ElastiCacheIntegrationsOutcome Origin = new(OutcomeKind.Origin, null, null);

    private ElastiCacheIntegrationsOutcome(OutcomeKind kind, ElastiCacheIntegrationsResponse? response, Exception? error)
    {
        Kind = kind;
        Response = response;
        Error = error;
    }

    public OutcomeKind Kind { get; }

    /// <summary>Set for <see cref="OutcomeKind.Served"/>.</summary>
    public ElastiCacheIntegrationsResponse? Response { get; }

    /// <summary>Set for <see cref="OutcomeKind.Retryable"/> and <see cref="OutcomeKind.Terminal"/>.</summary>
    public Exception? Error { get; }

    public static ElastiCacheIntegrationsOutcome Served(ElastiCacheIntegrationsResponse response) => new(OutcomeKind.Served, response, null);

    public static ElastiCacheIntegrationsOutcome Retryable(Exception error) => new(OutcomeKind.Retryable, null, error);

    public static ElastiCacheIntegrationsOutcome Terminal(Exception error) => new(OutcomeKind.Terminal, null, error);
}

/// <summary>
/// The request outcome table: what a reply or a failure on a live connection means. A call that
/// may have run is retried only when <c>replayable</c>: its operation is in
/// <see cref="IdempotentOperations"/>.
/// </summary>
internal static class RequestOutcomes
{
    /// <summary>Maps a reply. An <c>ORIGIN_ERROR</c> is retried only while <paramref name="connectionOpen"/>.</summary>
    public static ElastiCacheIntegrationsOutcome FromReply(RequestReply reply, bool replayable, bool connectionOpen) => reply switch
    {
        RequestReply.Served served => ElastiCacheIntegrationsOutcome.Served(served.Response),
        RequestReply.Rejected { Error: var error } => ServerErrors.Classify(error.Code) switch
        {
            // Refused before any origin call; the session replaces the connection.
            ServerErrorKind.Auth => ElastiCacheIntegrationsOutcome.Origin,

            // The cache did not run it, so any operation is safe to retry.
            ServerErrorKind.ConnectionDrop => ElastiCacheIntegrationsOutcome.Retryable(error),
            ServerErrorKind.Origin when replayable && connectionOpen => ElastiCacheIntegrationsOutcome.Retryable(error),
            _ => ElastiCacheIntegrationsOutcome.Terminal(error),
        },
        _ => Indeterminate(new ElastiCacheIntegrationsProtocolException($"Unexpected reply {reply.GetType().Name} for a final aws.request reply."), replayable),
    };

    /// <summary>Maps a failure of <see cref="ElastiCacheIntegrationsConnection.RequestAsync"/>; null when it is not one this table owns.</summary>
    public static ElastiCacheIntegrationsOutcome? FromException(Exception error, bool replayable) => error switch
    {
        // Nothing was sent: the connection is healthy but full, so the SDK retries.
        ElastiCacheIntegrationsPendingLimitException => ElastiCacheIntegrationsOutcome.Retryable(error),

        // The request may have run. It is never replayed by this client.
        ElastiCacheIntegrationsTimeoutException or ElastiCacheIntegrationsConnectionLostException or ElastiCacheIntegrationsProtocolException => Indeterminate(error, replayable),
        _ => null,
    };

    private static ElastiCacheIntegrationsOutcome Indeterminate(Exception error, bool replayable) =>
        replayable ? ElastiCacheIntegrationsOutcome.Retryable(error) : ElastiCacheIntegrationsOutcome.Terminal(error);
}

/// <summary>
/// One cache session: a connection that is rebuilt in the background whenever it dies, the
/// predicates of its latest config, re-AUTH and config refresh, and the request outcome table.
/// Thread safe.
/// </summary>
internal sealed class ElastiCacheIntegrationsSession : IDisposable, IAsyncDisposable
{
    private readonly ElastiCacheIntegrationsClientOptions _options;
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
    private TimeSpan _refreshInterval;
    private long _confirmedAt;
    private long _pendingLimitHitAt = -1;

    // Set from the first config the client cannot use until a usable one is applied. Written under the gate.
    private bool _unusableConfig;
    private Task _reconnectLoop = Task.CompletedTask;
    private volatile bool _disposed;

    private ElastiCacheIntegrationsSession(ElastiCacheIntegrationsClientOptions options, ConnectionFactory connect, string service, ILogger logger, TimeProvider time, Random random)
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

    private bool NoFallback => _options.FallbackDuration == TimeSpan.Zero;

    /// <summary>
    /// Validates the options, serializes the overrides once, and brings up the first connection:
    /// <see cref="ElastiCacheIntegrationsReconnectPolicy.ConnectAttempts"/> attempts with backoff, each capped by
    /// <see cref="ElastiCacheIntegrationsReconnectPolicy.AttemptTimeout"/>.
    /// </summary>
    /// <exception cref="ElastiCacheIntegrationsConfigurationException">Bad options, or the cache rejected the overrides. Never retried.</exception>
    /// <exception cref="ElastiCacheIntegrationsConnectionException">Every attempt failed; the last failure.</exception>
    public static async Task<ElastiCacheIntegrationsSession> OpenAsync(
        ElastiCacheIntegrationsClientOptions options, IElastiCacheIntegrationsAuthProvider auth, string service, CancellationToken cancellationToken)
    {
        OptionsValidator.Validate(options);
        ArgumentNullException.ThrowIfNull(auth);
        ArgumentException.ThrowIfNullOrEmpty(service);
        options = options.Snapshot();
        try
        {
            var logger = ElastiCacheIntegrationsLog.CreateLogger(options.LoggerFactory);
            var overrides = ElastiCacheIntegrationsProtocol.SerializeOverrides(options.Overrides);
            var time = TimeProvider.System;
            var session = await OpenAsync(
                options,
                async ct => await ElastiCacheIntegrationsConnection.OpenAsync(options, auth, overrides, logger, time, ct).ConfigureAwait(false),
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
            ElastiCacheIntegrationsClientOptions.DisposeAll(options.CaCertificates);
            throw;
        }
    }

    internal static async Task<ElastiCacheIntegrationsSession> OpenAsync(
        ElastiCacheIntegrationsClientOptions options, ConnectionFactory connect, string service, ILogger logger, TimeProvider time, Random random,
        CancellationToken cancellationToken)
    {
        var session = new ElastiCacheIntegrationsSession(options, connect, service.ToLowerInvariant(), logger, time, random);
        try
        {
            var (connection, predicates) = await session.BringUpAsync(cancellationToken).ConfigureAwait(false);
            if (!session.Install(connection, predicates))
            {
                await connection.DisposeAsync().ConfigureAwait(false);
                throw new ObjectDisposedException(nameof(ElastiCacheIntegrationsSession));
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
    public async Task<ElastiCacheIntegrationsOutcome> RequestAsync(ElastiCacheIntegrationsRequest request, CancellationToken cancellationToken)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (!Volatile.Read(ref _predicates).ShouldRoute(request))
        {
            return ElastiCacheIntegrationsOutcome.Origin;
        }

        for (var resent = false; ; resent = true)
        {
            var (connection, outage) = await GetConnectionAsync(cancellationToken).ConfigureAwait(false);
            if (connection is null)
            {
                return NoConnection(outage);
            }

            // Past the FAS lifetime with re-AUTH failing, the connection counts as unauthenticated.
            if (_reauth.IsStale)
            {
                _reauth.LogStale();
                OnClosed(connection);
                return ElastiCacheIntegrationsOutcome.Origin;
            }

            var replayable = IdempotentOperations.Contains(request.Metadata);
            RequestReply reply;
            try
            {
                reply = await connection.RequestAsync(request, cancellationToken).ConfigureAwait(false);
            }
            catch (ElastiCacheIntegrationsPendingLimitException e)
            {
                OnPendingLimit();
                return ElastiCacheIntegrationsOutcome.Retryable(e);
            }
            catch (Exception e) when (RequestOutcomes.FromException(e, replayable) is { } outcome)
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

            // The cache holds no FAS for this connection: replace it, as the other clients do.
            if (reply is RequestReply.Rejected { Error.Code: ServerErrors.AuthError } && OnClosed(connection))
            {
                _logger.LogWarning(ElastiCacheIntegrationsEvents.AuthDegraded,
                    "The cache has no valid AWS credentials for this connection; reconnecting, and the call goes to DynamoDB");
            }

            return RequestOutcomes.FromReply(reply, replayable, connection.IsOpen);
        }
    }

    /// <summary>Logs the first pending-limit hit of a burst.</summary>
    private void OnPendingLimit()
    {
        var now = _time.GetTimestamp();
        var last = Interlocked.Exchange(ref _pendingLimitHitAt, now);
        if (last < 0 || _time.GetElapsedTime(last, now) >= PendingBurstGap)
        {
            _logger.LogWarning(ElastiCacheIntegrationsEvents.PendingLimitReached,
                "The connection has MaxPending {MaxPending} calls in flight; calls over the limit fail with a retryable error",
                _options.MaxPending);
        }
    }

    /// <summary>The live connection; with none, waits up to <see cref="ElastiCacheIntegrationsClientOptions.ReconnectWait"/> for the reconnect.</summary>
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
    private ElastiCacheIntegrationsOutcome NoConnection(Outage? outage)
    {
        if (outage is null || _disposed)
        {
            return ElastiCacheIntegrationsOutcome.Terminal(new ObjectDisposedException(nameof(ElastiCacheIntegrationsSession)));
        }

        // The fallback window starts after the reconnect wait, so the wait does not use it up.
        var down = _time.GetElapsedTime(outage.DroppedAt);
        if (!NoFallback && (_options.FallbackDuration == Timeout.InfiniteTimeSpan || down - _options.ReconnectWait < _options.FallbackDuration))
        {
            return ElastiCacheIntegrationsOutcome.Origin;
        }

        if (outage.Exhausted.TryEnter())
        {
            _logger.LogWarning(ElastiCacheIntegrationsEvents.CacheUnavailable,
                "The cache has been unreachable for {Down}, past ReconnectWait plus FallbackDuration {Budget}; requests fail until it reconnects",
                down, _options.FallbackDuration);
        }

        return ElastiCacheIntegrationsOutcome.Terminal(new ElastiCacheIntegrationsCacheUnavailableException(
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
            catch (Exception e) when (attempt < _options.Reconnect.ConnectAttempts && e is not ElastiCacheIntegrationsConfigurationException
                && !cancellationToken.IsCancellationRequested)
            {
                _logger.LogDebug(ElastiCacheIntegrationsEvents.BringUpAttemptFailed, e, "Bring-up attempt {Attempt} failed; retrying", attempt);
                await Task.Delay(Backoff.Delay(_options.Reconnect, attempt, _random), _time, cancellationToken).ConfigureAwait(false);
            }
        }
    }

    /// <summary>One bring-up attempt, capped by <see cref="ElastiCacheIntegrationsReconnectPolicy.AttemptTimeout"/>, and its compiled config.</summary>
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
            throw new ElastiCacheIntegrationsConnectionException($"A bring-up attempt took longer than {limit.TotalMilliseconds} ms.", e);
        }

        try
        {
            return (connection, Compile(connection.Config));
        }
        catch (ElastiCacheIntegrationsProtocolException e)
        {
            await connection.DisposeAsync().ConfigureAwait(false);
            throw new ElastiCacheIntegrationsConnectionException($"{ElastiCacheIntegrationsProtocol.ConfigCommand} failed during bring-up: {e.Message}", e);
        }
    }

    /// <summary>
    /// Compiles a config. The first one the client cannot use logs an error, until a usable one is
    /// applied. A refresh reply counts only while <paramref name="refreshed"/> is the live connection.
    /// </summary>
    /// <exception cref="ElastiCacheIntegrationsProtocolException">The client cannot use all of the config.</exception>
    private PredicateEvaluator Compile(ResolvedConfig? config, ICacheConnection? refreshed = null)
    {
        try
        {
            return PredicateEvaluator.Compile(config ?? throw new ElastiCacheIntegrationsProtocolException($"No {ElastiCacheIntegrationsProtocol.ConfigCommand} reply."));
        }
        catch (ElastiCacheIntegrationsProtocolException e)
        {
            OnUnusableConfig(e, refreshed);
            throw;
        }
    }

    /// <summary>Marks the cache's config unusable and logs event 1307 once, until a usable one is applied.</summary>
    private void OnUnusableConfig(ElastiCacheIntegrationsProtocolException error, ICacheConnection? refreshed)
    {
        bool first;
        lock (_gate)
        {
            first = !_unusableConfig && (refreshed is null || ReferenceEquals(_state.Connection, refreshed));
            _unusableConfig |= first;
        }

        if (first)
        {
            _logger.LogError(ElastiCacheIntegrationsEvents.ConfigUnusable, "The cache sent a config this client cannot use: {Reason} the client keeps asking for a usable one",
                error.Message);
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

            // Clears staleness before the connection is visible, so no request on it sees the old AUTH.
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

    /// <summary>
    /// The live connection died or must be replaced: start an outage and reconnect in the background.
    /// True only for the first call for the live connection.
    /// </summary>
    private bool OnClosed(ICacheConnection connection)
    {
        lock (_gate)
        {
            if (_disposed || !ReferenceEquals(_state.Connection, connection))
            {
                return false;
            }

            var outage = new Outage(_time.GetTimestamp());
            Volatile.Write(ref _state, new State(null, outage));
            var loop = Task.Run(() => ReconnectAsync(outage, _stop.Token));

            // An earlier loop may still be logging after it installed a connection that died at once.
            _reconnectLoop = _reconnectLoop.IsCompleted ? loop : Task.WhenAll(_reconnectLoop, loop);
        }

        connection.Dispose();
        return true;
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

                _logger.Log(attempt > 1 ? LogLevel.Information : LogLevel.Debug, ElastiCacheIntegrationsEvents.Reconnected,
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
                    _logger.LogWarning(ElastiCacheIntegrationsEvents.Reconnecting, e,
                        "The client cannot reconnect to the cache; retrying in the background. Requests {Mode} meanwhile",
                        NoFallback ? "fail" : _options.FallbackDuration == Timeout.InfiniteTimeSpan ? "go to DynamoDB" : $"go to DynamoDB for up to {_options.FallbackDuration}");
                }
                else
                {
                    _logger.LogDebug(ElastiCacheIntegrationsEvents.Reconnecting, e, "Reconnect attempt {Attempt} failed", attempt);
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
            try
            {
                config = await connection.RefreshConfigAsync(cancellationToken).ConfigureAwait(false);
            }
            catch (ElastiCacheIntegrationsProtocolException e)
            {
                OnUnusableConfig(e, connection);
                throw;
            }

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
                throw new ElastiCacheIntegrationsProtocolException($"The cache still holds the {ElastiCacheIntegrationsProtocol.ConfigCommand} reply this client cannot use.");
            }
        }
        catch (Exception e) when (!cancellationToken.IsCancellationRequested && UnconfirmedFor(connection) is { } age)
        {
            _logger.LogWarning(ElastiCacheIntegrationsEvents.ConfigStale, e,
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

    /// <summary>How long the live config has gone unconfirmed, when that is at least <see cref="ElastiCacheIntegrationsClientOptions.CachePolicyTimeout"/>.</summary>
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
        _logger.LogDebug(ElastiCacheIntegrationsEvents.ConfigApplied, "Applied config version {Version}, refresh every {Interval}", config.Version,
            config.RefreshInterval);

        if (config.RefreshInterval == _refreshInterval)
        {
            return null;
        }

        var old = _refresh;
        _refreshInterval = config.RefreshInterval;
        _refresh = new PeriodicRunner("Config refresh", config.RefreshInterval, _options.Reconnect, RefreshConfigAsync, _logger, _time, _random);
        _refresh.Start();
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
        ElastiCacheIntegrationsClientOptions.DisposeAll(_ownedRoots);
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

        ElastiCacheIntegrationsClientOptions.DisposeAll(_ownedRoots);
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
