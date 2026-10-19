using Microsoft.Extensions.Logging;

namespace Amazon.ElastiCache.Integrations;

/// <summary>Jittered exponential backoff from a <see cref="ElastiCacheIntegrationsReconnectPolicy"/>.</summary>
internal static class Backoff
{
    /// <summary>Delay before a 1-based retry: <c>BaseDelay * 2^(attempt-1)</c>, capped at <c>MaxDelay</c>, then jittered.</summary>
    public static TimeSpan Delay(ElastiCacheIntegrationsReconnectPolicy policy, int attempt, Random random)
    {
        var exponent = Math.Clamp(attempt - 1, 0, 30);
        var ticks = Math.Min(policy.MaxDelay.Ticks, policy.BaseDelay.Ticks * Math.Pow(2, exponent));
        return Jitter(TimeSpan.FromTicks((long)ticks), policy.Jitter, random);
    }

    /// <summary>Scales <paramref name="delay"/> by a random factor in <c>[1 - jitter, 1 + jitter)</c>.</summary>
    public static TimeSpan Jitter(TimeSpan delay, double jitter, Random random) =>
        TimeSpan.FromTicks((long)(delay.Ticks * (1 + (jitter * ((2 * random.NextDouble()) - 1)))));
}

/// <summary>
/// Runs a callback in the background every jittered interval until disposed. A failing callback is
/// logged and retried after backoff; one success restores the interval. A callback that returns false
/// had nothing to do: the failure count and the schedule stay as they are.
/// </summary>
internal sealed class PeriodicRunner : IDisposable, IAsyncDisposable
{
    // The longest timer a TimeProvider accepts.
    internal static readonly TimeSpan MaxWait = TimeSpan.FromMilliseconds(uint.MaxValue - 1);

    private readonly string _name;
    private readonly TimeSpan _interval;
    private readonly ElastiCacheIntegrationsReconnectPolicy _backoff;
    private readonly Func<CancellationToken, Task<bool>> _tick;
    private readonly ILogger _logger;
    private readonly TimeProvider _time;
    private readonly Random _random;
    private readonly SemaphoreSlim _wake = new(0, 1);
    private readonly CancellationTokenSource _stop = new();
    private Task _loop = Task.CompletedTask;
    private int _started;

    public PeriodicRunner(string name, TimeSpan interval, ElastiCacheIntegrationsReconnectPolicy backoff, Func<CancellationToken, Task<bool>> tick,
        ILogger logger, TimeProvider? timeProvider = null, Random? random = null)
    {
        ArgumentOutOfRangeException.ThrowIfLessThanOrEqual(interval, TimeSpan.Zero);
        _name = name;
        _interval = interval;
        _backoff = backoff;
        _tick = tick;
        _logger = logger;
        _time = timeProvider ?? TimeProvider.System;
        _random = random ?? Random.Shared;
    }

    /// <summary>Starts the loop. The first run is one interval from now.</summary>
    public void Start()
    {
        if (Interlocked.Exchange(ref _started, 1) != 0)
        {
            throw new InvalidOperationException($"{_name} was already started.");
        }

        Volatile.Write(ref _loop, Task.Run(() => RunAsync(_stop.Token)));
    }

    /// <summary>Runs the callback as soon as possible. Calls while one is pending coalesce.</summary>
    public void TriggerNow()
    {
        try
        {
            _wake.Release();
        }
        catch (SemaphoreFullException)
        {
        }
    }

    /// <summary>Stops the loop without waiting for a running callback.</summary>
    public void Dispose() => _stop.Cancel();

    /// <summary>Stops the loop and waits for a running callback to end.</summary>
    public async ValueTask DisposeAsync()
    {
        _stop.Cancel();
        await Volatile.Read(ref _loop).ConfigureAwait(false);
    }

    private async Task RunAsync(CancellationToken stop)
    {
        var failures = 0;
        while (await WaitAsync(failures == 0 ? Backoff.Jitter(_interval, _backoff.Jitter, _random) : Backoff.Delay(_backoff, failures, _random), stop)
            .ConfigureAwait(false))
        {
            try
            {
                if (!await _tick(stop).ConfigureAwait(false))
                {
                    continue;
                }

                if (failures > 0)
                {
                    _logger.LogInformation(ElastiCacheIntegrationsEvents.PeriodicRecovered, "{Name} succeeded after {Failures} failed attempts", _name, failures);
                }

                failures = 0;
            }
            catch (Exception) when (stop.IsCancellationRequested)
            {
                return;
            }
            catch (Exception e)
            {
                failures++;
                _logger.Log(failures == 1 ? LogLevel.Warning : LogLevel.Debug, ElastiCacheIntegrationsEvents.PeriodicFailed, e,
                    "{Name} failed (attempt {Failures}); retrying with backoff", _name, failures);
            }
        }
    }

    // True when the callback should run; false once stopped.
    private async Task<bool> WaitAsync(TimeSpan delay, CancellationToken stop)
    {
        using var timer = new CancellationTokenSource(delay < MaxWait ? delay : MaxWait, _time);
        using var either = CancellationTokenSource.CreateLinkedTokenSource(stop, timer.Token);
        try
        {
            await _wake.WaitAsync(either.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
        }

        return !stop.IsCancellationRequested;
    }
}

/// <summary>What a request does after the cache answered <c>-AUTH_ERROR</c>.</summary>
internal enum AuthErrorAction
{
    /// <summary>Send it to DynamoDB with the application's own credentials.</summary>
    Origin,

    /// <summary>Fail it with <see cref="ElastiCacheIntegrationsCacheUnavailableException"/>.</summary>
    Fail,
}

/// <summary>
/// The time since the first <c>-AUTH_ERROR</c> with no successful <c>AUTH</c> after it. Requests go
/// to DynamoDB while it is shorter than the budget, and fail after.
/// </summary>
/// <remarks>
/// Each successful <c>AUTH</c> starts a new generation. An error on a request sent in an older
/// generation says nothing about the current FAS, so it never opens the window. An error within
/// <c>grace</c> of a close means that <c>AUTH</c> left the cache without a FAS, so the window reopens
/// with its old start time.
/// </remarks>
internal sealed class AuthWindow(TimeSpan budget, TimeSpan grace, TimeProvider timeProvider)
{
    private readonly object _gate = new();
    private long _generation;
    private long? _openedAt;
    private long _lastOpenedAt;
    private long? _closedAt;
    private long? _reauthAt;
    private bool _exhausted;

    /// <summary>
    /// The current <c>AUTH</c> generation. Read it when a request is sent. A request sent while an
    /// <c>AUTH</c> is in flight reads the old one; the next error in the new generation covers it.
    /// </summary>
    public long Generation => Interlocked.Read(ref _generation);

    public bool IsOpen
    {
        get
        {
            lock (_gate)
            {
                return _openedAt is not null;
            }
        }
    }

    /// <summary>Records an <c>-AUTH_ERROR</c> for a request sent in <paramref name="generation"/>.</summary>
    public AuthErrorOutcome OnAuthError(long generation)
    {
        lock (_gate)
        {
            var now = timeProvider.GetTimestamp();
            if (generation < _generation && _openedAt is null)
            {
                return new(AuthErrorAction.Origin, false, false, false, null);
            }

            var opened = false;
            var reopened = false;
            if (_openedAt is null)
            {
                opened = _closedAt is not { } closedAt || Elapsed(closedAt, now) >= grace;
                reopened = !opened;
                _openedAt = opened ? now : _lastOpenedAt;
                _exhausted &= !opened;
            }

            var inside = budget == Timeout.InfiniteTimeSpan || Elapsed(_openedAt.Value, now) < budget;
            var exhausted = !inside && !_exhausted;
            _exhausted |= !inside;
            return new(inside ? AuthErrorAction.Origin : AuthErrorAction.Fail, opened, reopened, exhausted, NextReauth(now));
        }
    }

    /// <summary>Starts a new generation and closes the window. Returns true if it was open.</summary>
    public bool Close()
    {
        lock (_gate)
        {
            Interlocked.Increment(ref _generation);
            if (_openedAt is not { } openedAt)
            {
                return false;
            }

            _lastOpenedAt = openedAt;
            _closedAt = timeProvider.GetTimestamp();
            _openedAt = null;
            return true;
        }
    }

    // At most one re-AUTH per grace, and none is dropped: a request inside the grace is deferred to
    // its end. Null when one is already due.
    private TimeSpan? NextReauth(long now)
    {
        if (_reauthAt is not { } last || Elapsed(last, now) >= grace)
        {
            _reauthAt = now;
            return TimeSpan.Zero;
        }

        if (now < last)
        {
            return null;
        }

        _reauthAt = last + (long)(grace.TotalSeconds * timeProvider.TimestampFrequency);
        return grace - Elapsed(last, now);
    }

    private TimeSpan Elapsed(long from, long to) => timeProvider.GetElapsedTime(from, to);
}

/// <summary>The result of <see cref="AuthWindow.OnAuthError"/>.</summary>
/// <param name="Action">What the request does.</param>
/// <param name="Opened">A new window started.</param>
/// <param name="Reopened">The window opened again with its old start time.</param>
/// <param name="Exhausted">The window answers <see cref="AuthErrorAction.Fail"/> for the first time.</param>
/// <param name="ReauthAfter">Re-AUTH after this delay; zero for now, null when one is already due.</param>
internal readonly record struct AuthErrorOutcome(AuthErrorAction Action, bool Opened, bool Reopened, bool Exhausted, TimeSpan? ReauthAfter);

/// <summary>
/// Background re-AUTH and the auth window. A failed re-AUTH keeps serving and retries after backoff,
/// until the last successful <c>AUTH</c> is <see cref="FasLifetime"/> old: then the connection counts as
/// unauthenticated. <c>-AUTH_ERROR</c> or staleness opens the window and re-AUTHs at once; any
/// successful <c>AUTH</c> closes it.
/// </summary>
internal sealed class ReauthScheduler : IDisposable, IAsyncDisposable
{
    /// <summary>How long a FAS is expected to last after the <c>AUTH</c> that vended it.</summary>
    internal static readonly TimeSpan FasLifetime = TimeSpan.FromHours(1);

    private readonly Func<CancellationToken, Task<bool>> _reauthenticate;
    private readonly AuthWindow _window;
    private readonly PeriodicRunner _runner;
    private readonly ILogger _logger;
    private readonly TimeSpan _budget;
    private readonly TimeProvider _time;
    private ITimer? _deferred;
    private long _authenticatedAt;
    private volatile bool _failing;
    private long _staleLoggedFor = -1;

    /// <summary>
    /// <paramref name="reauthenticate"/> re-AUTHs the live connection and returns false when there is none.
    /// </summary>
    public ReauthScheduler(Func<CancellationToken, Task<bool>> reauthenticate, ElastiCacheIntegrationsClientOptions options, ILogger logger,
        TimeProvider? timeProvider = null, Random? random = null)
    {
        _reauthenticate = reauthenticate;
        _logger = logger;
        _budget = options.FallbackDuration;
        _time = timeProvider ?? TimeProvider.System;
        _window = new AuthWindow(_budget, options.Reconnect.MaxDelay, _time);
        _authenticatedAt = _time.GetTimestamp();
        _runner = new PeriodicRunner("Re-AUTH", options.ReauthInterval, options.Reconnect, TickAsync, logger, timeProvider, random);
    }

    /// <summary>True while requests that got <c>-AUTH_ERROR</c> go to DynamoDB or fail.</summary>
    public bool IsDegraded => _window.IsOpen;

    /// <summary>The current <c>AUTH</c> generation. Read it when a request is sent; pass it to <see cref="OnAuthError"/>.</summary>
    public long Generation => _window.Generation;

    /// <summary>The last re-AUTH failed and the last successful <c>AUTH</c> is at least <see cref="FasLifetime"/> ago.</summary>
    public bool IsStale => _failing && _time.GetElapsedTime(Interlocked.Read(ref _authenticatedAt)) >= FasLifetime;

    /// <summary>Starts the schedule. Call once bring-up has authenticated.</summary>
    public void Start() => _runner.Start();

    /// <summary>A request sent in <paramref name="generation"/> got <c>-AUTH_ERROR</c>: the cache held no FAS for it.</summary>
    public AuthErrorAction OnAuthError(long generation) => Record(generation, announce: true);

    /// <summary>
    /// For a request about to be sent: what it does instead while <see cref="IsStale"/>, the
    /// <c>-AUTH_ERROR</c> path. Null when it is not stale, or an <c>AUTH</c> succeeded meanwhile.
    /// </summary>
    public AuthErrorAction? OnStale()
    {
        var generation = Generation;
        if (!IsStale)
        {
            return null;
        }

        var action = Record(generation, announce: false);
        if (generation != Generation && !IsDegraded)
        {
            return null;
        }

        LogStale();
        return action;
    }

    private AuthErrorAction Record(long generation, bool announce)
    {
        var outcome = _window.OnAuthError(generation);
        if (announce)
        {
            Announce(outcome);
        }

        if (outcome.Exhausted && _budget != TimeSpan.Zero)
        {
            _logger.LogWarning(ElastiCacheIntegrationsEvents.AuthWindowExhausted,
                "Re-authentication has not recovered within {Budget}; requests now fail", _budget);
        }

        if (outcome.ReauthAfter == TimeSpan.Zero)
        {
            _runner.TriggerNow();
        }
        else if (outcome.ReauthAfter is { } delay)
        {
            Interlocked.Exchange(ref _deferred, _time.CreateTimer(static r => ((PeriodicRunner)r!).TriggerNow(), _runner, delay, Timeout.InfiniteTimeSpan))
                ?.Dispose();
        }

        return outcome.Action;
    }

    private void Announce(AuthErrorOutcome outcome)
    {
        if (outcome.Opened && _budget == TimeSpan.Zero)
        {
            _logger.LogWarning(ElastiCacheIntegrationsEvents.AuthDegraded,
                "The cache has no valid AWS credentials for this client; re-authenticating, requests fail until it recovers");
        }
        else if (outcome.Opened)
        {
            _logger.LogWarning(ElastiCacheIntegrationsEvents.AuthDegraded,
                "The cache has no valid AWS credentials for this client; re-authenticating, requests go to DynamoDB for up to {Budget}",
                _budget == Timeout.InfiniteTimeSpan ? "no limit" : _budget);
        }
        else if (outcome.Reopened)
        {
            _logger.LogDebug(ElastiCacheIntegrationsEvents.AuthDegraded, "The cache still has no valid AWS credentials for this client after re-authenticating");
        }
    }

    /// <summary>An <c>AUTH</c> succeeded: bring-up, reconnect or a scheduled re-AUTH.</summary>
    public void OnAuthenticated()
    {
        // The time first, so a reader that still sees the failure sees the new time too.
        Interlocked.Exchange(ref _authenticatedAt, _time.GetTimestamp());
        _failing = false;
        if (_window.Close())
        {
            _logger.LogDebug(ElastiCacheIntegrationsEvents.AuthRecovered, "Re-authenticated with the cache; the AUTH_ERROR window is closed");
        }
    }

    public void Dispose()
    {
        Interlocked.Exchange(ref _deferred, null)?.Dispose();
        _runner.Dispose();
    }

    public async ValueTask DisposeAsync()
    {
        Interlocked.Exchange(ref _deferred, null)?.Dispose();
        await _runner.DisposeAsync().ConfigureAwait(false);
    }

    private async Task<bool> TickAsync(CancellationToken cancellationToken)
    {
        bool ran;
        try
        {
            ran = await _reauthenticate(cancellationToken).ConfigureAwait(false);
        }
        catch (Exception) when (!cancellationToken.IsCancellationRequested)
        {
            _failing = true;
            if (IsStale)
            {
                LogStale();
            }

            throw;
        }

        if (!ran)
        {
            return false;
        }

        OnAuthenticated();
        return true;
    }

    // Once per successful AUTH it follows, so a check that raced a new AUTH logs nothing.
    private void LogStale()
    {
        var at = Interlocked.Read(ref _authenticatedAt);
        var age = _time.GetElapsedTime(at);
        if (age >= FasLifetime && Interlocked.Exchange(ref _staleLoggedFor, at) != at)
        {
            _logger.LogError(ElastiCacheIntegrationsEvents.AuthStale,
                "Re-authentication with the cache has failed for {Age} since the last successful AUTH, past the FAS lifetime {Lifetime}; calls skip the cache until it succeeds",
                age, FasLifetime);
        }
    }
}
