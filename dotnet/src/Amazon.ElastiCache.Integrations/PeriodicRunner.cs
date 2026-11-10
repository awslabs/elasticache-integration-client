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

/// <summary>
/// Background re-AUTH. A failed re-AUTH keeps serving and retries after backoff, until the last
/// successful <c>AUTH</c> is <see cref="FasLifetime"/> old: then the connection counts as
/// unauthenticated and the session replaces it.
/// </summary>
internal sealed class ReauthScheduler : IDisposable, IAsyncDisposable
{
    /// <summary>How long a FAS is expected to last after the <c>AUTH</c> that vended it.</summary>
    internal static readonly TimeSpan FasLifetime = TimeSpan.FromHours(1);

    private readonly Func<CancellationToken, Task<bool>> _reauthenticate;
    private readonly PeriodicRunner _runner;
    private readonly ILogger _logger;
    private readonly TimeProvider _time;
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
        _time = timeProvider ?? TimeProvider.System;
        _authenticatedAt = _time.GetTimestamp();
        _runner = new PeriodicRunner("Re-AUTH", options.ReauthInterval, options.Reconnect, TickAsync, logger, timeProvider, random);
    }

    /// <summary>The last re-AUTH failed and the last successful <c>AUTH</c> is at least <see cref="FasLifetime"/> ago.</summary>
    public bool IsStale => _failing && _time.GetElapsedTime(Interlocked.Read(ref _authenticatedAt)) >= FasLifetime;

    /// <summary>Starts the schedule. Call once bring-up has authenticated.</summary>
    public void Start() => _runner.Start();

    /// <summary>An <c>AUTH</c> succeeded: bring-up, reconnect or a scheduled re-AUTH.</summary>
    public void OnAuthenticated()
    {
        // The time first, so a reader that still sees the failure sees the new time too.
        Interlocked.Exchange(ref _authenticatedAt, _time.GetTimestamp());
        _failing = false;
    }

    /// <summary>Logs event 1106 once per successful <c>AUTH</c> it follows, so a check that raced a new <c>AUTH</c> logs nothing.</summary>
    public void LogStale()
    {
        var at = Interlocked.Read(ref _authenticatedAt);
        var age = _time.GetElapsedTime(at);
        if (age >= FasLifetime && Interlocked.Exchange(ref _staleLoggedFor, at) != at)
        {
            _logger.LogError(ElastiCacheIntegrationsEvents.AuthStale,
                "Re-authentication with the cache has failed for {Age} since the last successful AUTH, past the FAS lifetime {Lifetime}; the next call replaces the connection",
                age, FasLifetime);
        }
    }

    public void Dispose() => _runner.Dispose();

    public ValueTask DisposeAsync() => _runner.DisposeAsync();

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
}
