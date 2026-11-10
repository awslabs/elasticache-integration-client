using System.Collections.Concurrent;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Time.Testing;

namespace Amazon.ElastiCache.Integrations.Tests;

public class PeriodicRunnerTests
{
    private static readonly TimeSpan Interval = TimeSpan.FromMinutes(1);
    private static readonly ElastiCacheIntegrationsReconnectPolicy NoJitter = new() { Jitter = 0 };

    [Fact]
    public async Task RunsOncePerInterval()
    {
        var time = new TimerSignalingTime();
        var ticks = new Ticks();
        await using var runner = new PeriodicRunner("t", Interval, NoJitter, ticks.RunAsync, new ListLogger(), time);

        runner.Start();
        await time.NextTimerAsync();
        time.Advance(Interval - TimeSpan.FromSeconds(1));
        await ticks.ExpectNoneAsync();
        time.Advance(TimeSpan.FromSeconds(1));
        await ticks.ExpectAsync(1);

        await time.NextTimerAsync();
        time.Advance(Interval);
        await ticks.ExpectAsync(1);
    }

    [Fact]
    public async Task FailureRetriesAfterBackoffAndSuccessRestoresTheInterval()
    {
        var time = new TimerSignalingTime();
        var logger = new ListLogger();
        var ticks = new Ticks(failFirst: 2);
        await using var runner = new PeriodicRunner("reauth", Interval, NoJitter, ticks.RunAsync, logger, time);

        runner.Start();
        await time.NextTimerAsync();
        time.Advance(Interval);
        await ticks.ExpectAsync(1);

        foreach (var backoff in new[] { 100, 200 })
        {
            await time.NextTimerAsync();
            time.Advance(TimeSpan.FromMilliseconds(backoff - 1));
            await ticks.ExpectNoneAsync();
            time.Advance(TimeSpan.FromMilliseconds(1));
            await ticks.ExpectAsync(1);
        }

        await time.NextTimerAsync();
        time.Advance(Interval - TimeSpan.FromMilliseconds(1));
        await ticks.ExpectNoneAsync();
        time.Advance(TimeSpan.FromMilliseconds(1));
        await ticks.ExpectAsync(1);
        await time.NextTimerAsync();

        Assert.Equal([LogLevel.Warning, LogLevel.Debug, LogLevel.Information], logger.Levels);
        Assert.Contains("reauth failed (attempt 1)", logger.Messages[0]);
    }

    [Fact]
    public async Task ASkippedRunNeitherRecoversNorResetsTheBackoff()
    {
        var time = new TimerSignalingTime();
        var logger = new ListLogger();
        var ran = new SemaphoreSlim(0);
        var calls = 0;
        await using var runner = new PeriodicRunner("t", Interval, NoJitter, _ =>
        {
            ran.Release();
            return ++calls switch
            {
                1 => Task.FromException<bool>(new InvalidOperationException("down")),
                2 or 3 => Task.FromResult(false),
                _ => Task.FromResult(true),
            };
        }, logger, time);

        runner.TriggerNow();
        runner.Start();
        Assert.True(await ran.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken));
        await time.NextTimerAsync(); // the wait the trigger cut short
        for (var i = 0; i < 3; i++)
        {
            await time.NextTimerAsync();
            time.Advance(TimeSpan.FromMilliseconds(100));
            Assert.True(await ran.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken));
        }

        await time.NextTimerAsync();
        Assert.Equal([LogLevel.Warning, LogLevel.Information], logger.Levels);
        Assert.Contains("succeeded after 1 failed attempts", logger.Messages[1]);
    }

    [Fact]
    public async Task ASkippedRunWithNoFailuresKeepsTheInterval()
    {
        var time = new TimerSignalingTime();
        var ran = new SemaphoreSlim(0);
        await using var runner = new PeriodicRunner("t", Interval, NoJitter, _ =>
        {
            ran.Release();
            return Task.FromResult(false);
        }, new ListLogger(), time);

        runner.TriggerNow();
        runner.Start();
        Assert.True(await ran.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken));
        await time.NextTimerAsync(); // the wait the trigger cut short
        await time.NextTimerAsync();
        time.Advance(Interval - TimeSpan.FromMilliseconds(1));
        Assert.False(await ran.WaitAsync(TimeSpan.FromMilliseconds(50), TestContext.Current.CancellationToken));
        time.Advance(TimeSpan.FromMilliseconds(1));

        Assert.True(await ran.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken));
    }

    [Fact]
    public async Task TriggerNowRunsWithoutWaiting()
    {
        var time = new TimerSignalingTime();
        var ticks = new Ticks();
        await using var runner = new PeriodicRunner("t", Interval, NoJitter, ticks.RunAsync, new ListLogger(), time);

        runner.Start();
        await time.NextTimerAsync();
        runner.TriggerNow();

        await ticks.ExpectAsync(1);
    }

    [Fact]
    public async Task PendingTriggersCoalesce()
    {
        var time = new TimerSignalingTime();
        var ticks = new Ticks();
        await using var runner = new PeriodicRunner("t", Interval, NoJitter, ticks.RunAsync, new ListLogger(), time);

        runner.TriggerNow();
        runner.TriggerNow();
        runner.TriggerNow();
        runner.Start();

        await ticks.ExpectAsync(1);
        await time.NextTimerAsync();
        await ticks.ExpectNoneAsync();
    }

    [Fact]
    public async Task DisposeStopsTheLoop()
    {
        var time = new TimerSignalingTime();
        var ticks = new Ticks();
        var runner = new PeriodicRunner("t", Interval, NoJitter, ticks.RunAsync, new ListLogger(), time);

        runner.Start();
        await time.NextTimerAsync();
        await runner.DisposeAsync();
        time.Advance(Interval);
        runner.TriggerNow();

        await ticks.ExpectNoneAsync();
    }

    [Fact]
    public async Task DisposeCancelsARunningCallbackAndWaitsForIt()
    {
        var cancelled = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var running = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var runner = new PeriodicRunner("t", Interval, NoJitter, async ct =>
        {
            running.SetResult();
            try
            {
                await Task.Delay(Timeout.Infinite, ct);
            }
            finally
            {
                cancelled.SetResult();
            }

            return true;
        }, new ListLogger(), new TimerSignalingTime());

        runner.TriggerNow();
        runner.Start();
        await running.Task.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken);
        await runner.DisposeAsync();

        Assert.True(cancelled.Task.IsCompleted);
    }

    [Fact]
    public async Task CancellationNotCausedByStopIsAFailure()
    {
        var time = new TimerSignalingTime();
        var logger = new ListLogger();
        var calls = 0;
        var ran = new SemaphoreSlim(0);
        await using var runner = new PeriodicRunner("t", Interval, NoJitter, _ =>
        {
            ran.Release();
            return ++calls == 1 ? Task.FromCanceled<bool>(new CancellationToken(true)) : Task.FromResult(true);
        }, logger, time);

        runner.TriggerNow();
        runner.Start();
        Assert.True(await ran.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken));
        await time.NextTimerAsync(); // the wait the trigger cut short
        await time.NextTimerAsync(); // the backoff wait
        time.Advance(TimeSpan.FromMilliseconds(99));
        Assert.False(await ran.WaitAsync(TimeSpan.FromMilliseconds(50), TestContext.Current.CancellationToken));
        time.Advance(TimeSpan.FromMilliseconds(1));

        Assert.True(await ran.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken));
        Assert.Equal(LogLevel.Warning, logger.Levels[0]);
    }

    [Fact]
    public async Task FailureCausedByStopIsNotLogged()
    {
        var logger = new ListLogger();
        var running = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var runner = new PeriodicRunner("t", Interval, NoJitter, async ct =>
        {
            running.SetResult();
            await Task.Delay(Timeout.Infinite, ct).ContinueWith(_ => throw new ObjectDisposedException("connection"), TaskScheduler.Default);
            return true;
        }, logger, new TimerSignalingTime());

        runner.TriggerNow();
        runner.Start();
        await running.Task.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken);
        await runner.DisposeAsync();

        Assert.Empty(logger.Levels);
    }

    [Fact]
    public async Task TriggerWhileRunningGivesOneMoreRun()
    {
        var time = new TimerSignalingTime();
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var started = new SemaphoreSlim(0);
        await using var runner = new PeriodicRunner("t", Interval, NoJitter, async _ =>
        {
            started.Release();
            await release.Task;
            return true;
        }, new ListLogger(), time);

        runner.TriggerNow();
        runner.Start();
        Assert.True(await started.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken));
        runner.TriggerNow();
        runner.TriggerNow();
        release.SetResult();

        Assert.True(await started.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken));
        for (var i = 0; i < 3; i++)
        {
            await time.NextTimerAsync();
        }

        Assert.False(await started.WaitAsync(TimeSpan.FromMilliseconds(50), TestContext.Current.CancellationToken));
    }

    [Fact]
    public async Task IntervalIsJittered()
    {
        var time = new TimerSignalingTime();
        var ticks = new Ticks();
        await using var runner = new PeriodicRunner("t", Interval, new ElastiCacheIntegrationsReconnectPolicy { Jitter = 0.2 }, ticks.RunAsync, new ListLogger(), time, new Random(3));

        runner.Start();
        await time.NextTimerAsync();
        time.Advance((Interval * 0.8) - TimeSpan.FromMilliseconds(1));
        await ticks.ExpectNoneAsync();
        time.Advance(Interval * 0.4);
        await ticks.ExpectAsync(1);
    }

    [Fact]
    public async Task SyncDisposeStopsTheLoop()
    {
        var time = new TimerSignalingTime();
        var ticks = new Ticks();
        var runner = new PeriodicRunner("t", Interval, NoJitter, ticks.RunAsync, new ListLogger(), time);

        runner.Start();
        await time.NextTimerAsync();
        runner.Dispose();
        time.Advance(Interval);

        await ticks.ExpectNoneAsync();
    }

    [Theory]
    [InlineData(0)]
    [InlineData(-1)]
    public void NonPositiveIntervalIsRejected(int ms) =>
        Assert.Throws<ArgumentOutOfRangeException>(() =>
            new PeriodicRunner("t", TimeSpan.FromMilliseconds(ms), NoJitter, _ => Task.FromResult(true), new ListLogger()));

    [Fact]
    public async Task HugeIntervalWaitsTheLongestTimer()
    {
        var time = new TimerSignalingTime();
        var ticks = new Ticks();
        await using var runner = new PeriodicRunner("t", TimeSpan.FromDays(365), NoJitter, ticks.RunAsync, new ListLogger(), time);

        runner.Start();
        await time.NextTimerAsync();
        time.Advance(PeriodicRunner.MaxWait);

        await ticks.ExpectAsync(1);
    }

    [Fact]
    public async Task StartTwiceThrows()
    {
        await using var runner = new PeriodicRunner("t", Interval, NoJitter, _ => Task.FromResult(true), new ListLogger(), new TimerSignalingTime());

        runner.Start();

        Assert.Throws<InvalidOperationException>(runner.Start);
    }

    [Theory]
    [InlineData(1, 100)]
    [InlineData(2, 200)]
    [InlineData(6, 3200)]
    [InlineData(7, 3200)]
    [InlineData(int.MaxValue, 3200)]
    public void BackoffDoublesUpToTheCap(int attempt, int expectedMs) =>
        Assert.Equal(TimeSpan.FromMilliseconds(expectedMs), Backoff.Delay(NoJitter, attempt, new Random(1)));

    [Fact]
    public void JitterStaysInItsBand()
    {
        var random = new Random(7);
        for (var i = 0; i < 1000; i++)
        {
            var delay = Backoff.Jitter(TimeSpan.FromSeconds(10), 0.2, random);
            Assert.InRange(delay, TimeSpan.FromSeconds(8), TimeSpan.FromSeconds(12));
        }
    }

    private sealed class TimerSignalingTime : FakeTimeProvider
    {
        private readonly SemaphoreSlim _created = new(0);

        public override ITimer CreateTimer(TimerCallback callback, object? state, TimeSpan dueTime, TimeSpan period)
        {
            var timer = base.CreateTimer(callback, state, dueTime, period);
            _created.Release();
            return timer;
        }

        public async Task NextTimerAsync() =>
            Assert.True(await _created.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken), "no timer was armed");
    }

    private sealed class Ticks(int failFirst = 0)
    {
        private readonly SemaphoreSlim _ran = new(0);
        private int _count;

        public Task<bool> RunAsync(CancellationToken cancellationToken)
        {
            var n = Interlocked.Increment(ref _count);
            _ran.Release();
            return n <= failFirst ? Task.FromException<bool>(new InvalidOperationException($"tick {n}")) : Task.FromResult(true);
        }

        public async Task ExpectAsync(int newTicks)
        {
            for (var i = 0; i < newTicks; i++)
            {
                Assert.True(await _ran.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken), "the callback did not run");
            }
        }

        public async Task ExpectNoneAsync() =>
            Assert.False(await _ran.WaitAsync(TimeSpan.FromMilliseconds(50), TestContext.Current.CancellationToken), "the callback ran early");
    }
}

internal sealed class ListLogger : ILogger
{
    private readonly ConcurrentQueue<(LogLevel Level, EventId Id, string Message)> _entries = new();

    public LogLevel[] Levels => [.. _entries.Select(e => e.Level)];

    public string[] Messages => [.. _entries.Select(e => e.Message)];

    public EventId[] Ids => [.. _entries.Select(e => e.Id)];

    public IDisposable? BeginScope<TState>(TState state)
        where TState : notnull => null;

    public bool IsEnabled(LogLevel logLevel) => true;

    public void Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception? exception, Func<TState, Exception?, string> formatter) =>
        _entries.Enqueue((logLevel, eventId, formatter(state, exception)));
}
