using Microsoft.Extensions.Time.Testing;

namespace Amazon.Photon.Tests;

public class AuthWindowTests
{
    private static readonly TimeSpan Budget = TimeSpan.FromMinutes(5);
    private static readonly TimeSpan Grace = TimeSpan.FromSeconds(3);

    [Fact]
    public void GoesToDynamoDBInsideTheBudgetAndFailsAfter()
    {
        var time = new FakeTimeProvider();
        var window = new AuthWindow(Budget, Grace, time);

        Assert.Equal(new AuthErrorOutcome(AuthErrorAction.Origin, true, false, false, TimeSpan.Zero), window.OnAuthError(window.Generation));
        time.Advance(Budget - TimeSpan.FromMilliseconds(1));
        Assert.Equal(AuthErrorAction.Origin, window.OnAuthError(window.Generation).Action);
        time.Advance(TimeSpan.FromMilliseconds(1));
        Assert.Equal(AuthErrorAction.Fail, window.OnAuthError(window.Generation).Action);
        Assert.True(window.IsOpen);
    }

    [Fact]
    public void ExhaustedIsReportedOncePerWindow()
    {
        var time = new FakeTimeProvider();
        var window = new AuthWindow(Budget, Grace, time);
        window.OnAuthError(window.Generation);
        time.Advance(Budget);

        Assert.True(window.OnAuthError(window.Generation).Exhausted);
        Assert.False(window.OnAuthError(window.Generation).Exhausted);

        window.Close();
        time.Advance(Grace);
        window.OnAuthError(window.Generation);
        time.Advance(Budget);
        Assert.True(window.OnAuthError(window.Generation).Exhausted);
    }

    [Fact]
    public void ZeroBudgetFailsAtOnce()
    {
        var window = new AuthWindow(TimeSpan.Zero, Grace, new FakeTimeProvider());

        Assert.Equal(AuthErrorAction.Fail, window.OnAuthError(window.Generation).Action);
    }

    [Fact]
    public void InfiniteBudgetNeverFails()
    {
        var time = new FakeTimeProvider();
        var window = new AuthWindow(Timeout.InfiniteTimeSpan, Grace, time);

        window.OnAuthError(window.Generation);
        time.Advance(TimeSpan.FromDays(30));

        Assert.Equal(AuthErrorAction.Origin, window.OnAuthError(window.Generation).Action);
    }

    [Fact]
    public void CloseAfterGraceStartsAFreshWindow()
    {
        var time = new FakeTimeProvider();
        var window = new AuthWindow(Budget, Grace, time);
        window.OnAuthError(window.Generation);
        time.Advance(Budget);

        Assert.True(window.Close());
        Assert.False(window.IsOpen);
        time.Advance(Grace);

        Assert.Equal(new AuthErrorOutcome(AuthErrorAction.Origin, true, false, false, TimeSpan.Zero), window.OnAuthError(window.Generation));
    }

    [Fact]
    public void ErrorInTheNewGenerationRightAfterCloseKeepsTheOldStart()
    {
        var time = new FakeTimeProvider();
        var window = new AuthWindow(Budget, Grace, time);
        window.OnAuthError(window.Generation);
        time.Advance(Budget);
        window.Close();
        time.Advance(Grace - TimeSpan.FromMilliseconds(1));

        var outcome = window.OnAuthError(window.Generation);

        Assert.Equal(AuthErrorAction.Fail, outcome.Action);
        Assert.False(outcome.Opened);
        Assert.True(outcome.Reopened);
    }

    [Fact]
    public void ReopenInsideTheGraceDefersTheReauth()
    {
        var time = new FakeTimeProvider();
        var window = new AuthWindow(Budget, Grace, time);
        window.OnAuthError(window.Generation);
        time.Advance(TimeSpan.FromMilliseconds(500));
        window.Close();
        time.Advance(TimeSpan.FromMilliseconds(500));

        var outcome = window.OnAuthError(window.Generation);

        Assert.True(outcome.Reopened);
        Assert.Equal(Grace - TimeSpan.FromSeconds(1), outcome.ReauthAfter);
        Assert.Null(window.OnAuthError(window.Generation).ReauthAfter);

        // The deferred re-AUTH at the end of the grace counts as the last one.
        time.Advance(Grace);
        Assert.Equal(TimeSpan.FromSeconds(2), window.OnAuthError(window.Generation).ReauthAfter);
        time.Advance(Grace * 2);
        Assert.Equal(TimeSpan.Zero, window.OnAuthError(window.Generation).ReauthAfter);
    }

    [Fact]
    public void LateErrorFromAnOlderGenerationDoesNotReopen()
    {
        var time = new FakeTimeProvider();
        var window = new AuthWindow(Budget, Grace, time);
        var sentIn = window.Generation;
        window.OnAuthError(sentIn);
        time.Advance(TimeSpan.FromMinutes(4));
        window.Close();

        Assert.Equal(new AuthErrorOutcome(AuthErrorAction.Origin, false, false, false, null), window.OnAuthError(sentIn));
        Assert.False(window.IsOpen);

        time.Advance(TimeSpan.FromMinutes(10));
        Assert.Equal(new AuthErrorOutcome(AuthErrorAction.Origin, true, false, false, TimeSpan.Zero), window.OnAuthError(window.Generation));
    }

    [Fact]
    public void ErrorFromAnOlderGenerationCountsWhileTheWindowIsOpen()
    {
        var time = new FakeTimeProvider();
        var window = new AuthWindow(Budget, Grace, time);
        var old = window.Generation;
        window.Close();
        window.OnAuthError(window.Generation);
        time.Advance(Budget);

        Assert.Equal(AuthErrorAction.Fail, window.OnAuthError(old).Action);
    }

    [Fact]
    public void ReauthIsAskedAtMostOncePerGraceAndNeverDropped()
    {
        var time = new FakeTimeProvider();
        var window = new AuthWindow(Budget, Grace, time);

        Assert.Equal(TimeSpan.Zero, window.OnAuthError(window.Generation).ReauthAfter);
        time.Advance(Grace - TimeSpan.FromMilliseconds(1));
        Assert.Equal(TimeSpan.FromMilliseconds(1), window.OnAuthError(window.Generation).ReauthAfter);
        Assert.Null(window.OnAuthError(window.Generation).ReauthAfter);
    }

    [Fact]
    public void CloseAdvancesTheGeneration()
    {
        var window = new AuthWindow(Budget, Grace, new FakeTimeProvider());
        var before = window.Generation;

        Assert.False(window.Close());
        Assert.Equal(before + 1, window.Generation);
    }
}

public class ReauthSchedulerTests
{
    private static PhotonClientOptions Options(TimeSpan? fallback = null) => new()
    {
        Endpoint = "h:6379",
        CacheName = "c",
        ReauthInterval = TimeSpan.FromMinutes(30),
        FallbackDuration = fallback ?? TimeSpan.FromMinutes(5),
        Reconnect = new PhotonReconnectPolicy { Jitter = 0 },
    };

    [Fact]
    public async Task AuthErrorReauthenticatesAtOnceAndASuccessClosesTheWindow()
    {
        var calls = new SemaphoreSlim(0);
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var logger = new ListLogger();
        await using var scheduler = new ReauthScheduler(async ct =>
        {
            calls.Release();
            await release.Task.WaitAsync(ct);
            return true;
        }, Options(), logger, new FakeTimeProvider());
        scheduler.Start();
        var generation = scheduler.Generation;

        Assert.Equal(AuthErrorAction.Origin, scheduler.OnAuthError(generation));
        Assert.True(scheduler.IsDegraded);
        Assert.True(await calls.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken));
        Assert.True(scheduler.IsDegraded);

        release.SetResult();
        await WaitUntilAsync(() => logger.Ids.Contains(PhotonEvents.AuthRecovered));
        Assert.False(scheduler.IsDegraded);
        Assert.Equal(generation + 1, scheduler.Generation);
        Assert.Contains(PhotonEvents.AuthDegraded, logger.Ids);
    }

    [Fact]
    public async Task SkippedReauthKeepsTheWindowOpen()
    {
        var skipped = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        await using var scheduler = new ReauthScheduler(async _ =>
        {
            await Task.Yield();
            skipped.TrySetResult();
            return false;
        }, Options(), new ListLogger(), new FakeTimeProvider());
        scheduler.Start();

        scheduler.OnAuthError(scheduler.Generation);
        await skipped.Task.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken);
        await scheduler.DisposeAsync();

        Assert.True(scheduler.IsDegraded);
    }

    [Fact]
    public async Task FailedReauthKeepsServingAndDoesNotCloseTheWindow()
    {
        var logger = new ListLogger();
        await using var scheduler = new ReauthScheduler(
            _ => Task.FromException<bool>(new PhotonConnectionException("AUTH rejected")), Options(), logger, new FakeTimeProvider());
        scheduler.Start();

        Assert.Equal(AuthErrorAction.Origin, scheduler.OnAuthError(scheduler.Generation));
        await WaitUntilAsync(() => logger.Ids.Contains(PhotonEvents.PeriodicFailed));

        Assert.True(scheduler.IsDegraded);
        Assert.DoesNotContain(PhotonEvents.AuthRecovered, logger.Ids);
    }

    [Fact]
    public async Task DeferredReauthRunsWhenTheGraceEnds()
    {
        var time = new FakeTimeProvider();
        var calls = new SemaphoreSlim(0);
        await using var scheduler = new ReauthScheduler(_ =>
        {
            calls.Release();
            return Task.FromResult(true);
        }, Options(), new ListLogger(), time);
        scheduler.Start();

        scheduler.OnAuthError(scheduler.Generation);
        Assert.True(await calls.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken));
        await WaitUntilAsync(() => !scheduler.IsDegraded);

        scheduler.OnAuthError(scheduler.Generation);
        Assert.False(await calls.WaitAsync(TimeSpan.FromMilliseconds(50), TestContext.Current.CancellationToken));
        time.Advance(TimeSpan.FromMilliseconds(3200));

        Assert.True(await calls.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken));
    }

    [Fact]
    public async Task BringUpAuthClosesTheWindow()
    {
        await using var scheduler = new ReauthScheduler(_ => Task.FromResult(false), Options(), new ListLogger(), new FakeTimeProvider());

        scheduler.OnAuthError(scheduler.Generation);
        scheduler.OnAuthenticated();

        Assert.False(scheduler.IsDegraded);
    }

    [Fact]
    public async Task ExhaustedWindowIsLoggedOnce()
    {
        var time = new FakeTimeProvider();
        var logger = new ListLogger();
        await using var scheduler = new ReauthScheduler(_ => Task.FromResult(false), Options(), logger, time);

        scheduler.OnAuthError(scheduler.Generation);
        time.Advance(TimeSpan.FromMinutes(5));
        Assert.Equal(AuthErrorAction.Fail, scheduler.OnAuthError(scheduler.Generation));
        scheduler.OnAuthError(scheduler.Generation);

        Assert.Single(logger.Ids, id => id == PhotonEvents.AuthWindowExhausted);
    }

    [Fact]
    public async Task StrictModeFailsSaysSoAndStillReauthenticates()
    {
        var calls = new SemaphoreSlim(0);
        var logger = new ListLogger();
        await using var scheduler = new ReauthScheduler(_ =>
        {
            calls.Release();
            return Task.FromResult(false);
        }, Options(TimeSpan.Zero), logger, new FakeTimeProvider());
        scheduler.Start();

        Assert.Equal(AuthErrorAction.Fail, scheduler.OnAuthError(scheduler.Generation));
        Assert.True(await calls.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken));
        Assert.Contains("requests fail until it recovers", logger.Messages[0]);
        Assert.DoesNotContain(PhotonEvents.AuthWindowExhausted, logger.Ids);
    }

    [Fact]
    public async Task AStaleRequestOpensTheWindowWithoutTheAuthErrorWarning()
    {
        var time = new RecordingTimeProvider();
        var calls = 0;
        var logger = new ListLogger();
        await using var scheduler = new ReauthScheduler(_ =>
        {
            Interlocked.Increment(ref calls);
            return Task.FromException<bool>(new PhotonConnectionException("AUTH rejected"));
        }, Options(), logger, time);
        scheduler.Start();
        await WaitUntilAsync(() => time.Armed(due => due == TimeSpan.FromMinutes(30)) > 0);
        Assert.False(scheduler.IsStale);

        time.Advance(ReauthScheduler.FasLifetime);
        await WaitUntilAsync(() => logger.Ids.Contains(PhotonEvents.AuthStale));

        Assert.True(scheduler.IsStale);
        Assert.Equal(AuthErrorAction.Origin, scheduler.OnStale());
        Assert.Equal(AuthErrorAction.Origin, scheduler.OnStale());
        await WaitUntilAsync(() => Volatile.Read(ref calls) >= 2);

        Assert.True(scheduler.IsDegraded);
        Assert.Single(logger.Ids, id => id == PhotonEvents.AuthStale);
        Assert.DoesNotContain(PhotonEvents.AuthDegraded, logger.Ids);

        scheduler.OnAuthenticated();
        Assert.False(scheduler.IsStale);
        Assert.False(scheduler.IsDegraded);
        Assert.Null(scheduler.OnStale());
        Assert.False(scheduler.IsDegraded);
    }

    private static async Task WaitUntilAsync(Func<bool> condition)
    {
        for (var i = 0; i < 500 && !condition(); i++)
        {
            await Task.Delay(10, TestContext.Current.CancellationToken);
        }

        Assert.True(condition());
    }
}
