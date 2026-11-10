namespace Amazon.ElastiCache.Integrations.Tests;

/// <summary>A background run with no live connection is not a recovery.</summary>
public class SessionRecoveryLogTests
{
    private static readonly TimeSpan Step = TimeSpan.FromSeconds(10);
    private static readonly TimeSpan FirstBackoff = TimeSpan.FromMilliseconds(100);
    private static readonly TimeSpan MaxBackoff = TimeSpan.FromMilliseconds(3200);

    // A reconnect attempt that hangs for the whole test, so every short timer is a background run's wait.
    private static ElastiCacheIntegrationsClientOptions Options(TimeSpan? reauth = null) => SessionRig.Options(
        reconnectWait: TimeSpan.Zero, reauth: reauth, cachePolicyTimeout: TimeSpan.FromSeconds(90),
        reconnect: new ElastiCacheIntegrationsReconnectPolicy { Jitter = 0, AttemptTimeout = TimeSpan.FromHours(1) });

    private static bool Recovered(SessionRig rig) => rig.Logger.Ids.Contains(ElastiCacheIntegrationsEvents.PeriodicRecovered);

    [Fact]
    public async Task ARefreshDuringTheOutageAfterAStaleDropIsNoRecovery()
    {
        var time = new RecordingTimeProvider();
        await using var rig = await SessionRig.StartAsync(Options(), SessionRig.Config(refreshSeconds: 60), time);
        rig.Connection.OnRefresh = _ => Task.FromException<ResolvedConfig?>(new ElastiCacheIntegrationsServerException("INTERNAL_ERROR", "aws.config was not processed"));
        await rig.EventuallyAsync(() => rig.Logger.Ids.Contains(ElastiCacheIntegrationsEvents.ConfigStale), Step);
        var attempt = await rig.Connector.NextAsync();

        await RunDuringTheOutageAsync(rig, time);

        var next = attempt.Succeed(SessionRig.Config(refreshSeconds: 60));
        await rig.EventuallyAsync(() => rig.Session.IsOpen);
        await rig.EventuallyAsync(() => Recovered(rig), MaxBackoff);

        Assert.True(next.Refreshes >= 1);
        Assert.Single(rig.Logger.Ids, ElastiCacheIntegrationsEvents.PeriodicRecovered);
    }

    [Fact]
    public async Task ARefreshThatEndsAfterItsConnectionWasReplacedIsNoRecovery()
    {
        var time = new RecordingTimeProvider();
        await using var rig = await SessionRig.StartAsync(Options(), SessionRig.Config(refreshSeconds: 60), time);
        var reply = new TaskCompletionSource<ResolvedConfig?>();
        var calls = 0;
        rig.Connection.OnRefresh = ct => Interlocked.Increment(ref calls) == 1
            ? Task.FromException<ResolvedConfig?>(new ElastiCacheIntegrationsServerException("INTERNAL_ERROR", "aws.config was not processed"))
            : reply.Task.WaitAsync(ct);
        await rig.EventuallyAsync(() => rig.Connection.Refreshes == 2, Step);

        rig.Connection.Drop();
        var next = (await rig.Connector.NextAsync()).Succeed(SessionRig.Config(refreshSeconds: 60));
        await rig.EventuallyAsync(() => rig.Session.IsOpen);
        var waits = time.Armed(due => due == FirstBackoff);
        reply.SetResult(null);
        await rig.EventuallyAsync(() => Recovered(rig) || time.Armed(due => due == FirstBackoff) > waits);
        Assert.False(Recovered(rig), "a refresh of a replaced connection logged a recovery");

        await rig.EventuallyAsync(() => Recovered(rig), FirstBackoff);

        Assert.True(next.Refreshes >= 1);
        Assert.Single(rig.Logger.Ids, ElastiCacheIntegrationsEvents.PeriodicRecovered);
    }

    [Fact]
    public async Task AReauthDuringTheOutageAfterAFailedReauthIsNoRecovery()
    {
        var time = new RecordingTimeProvider();
        await using var rig = await SessionRig.StartAsync(Options(reauth: TimeSpan.FromSeconds(60)), time: time);
        rig.Connection.OnReauth = _ => Task.FromException(new ElastiCacheIntegrationsServerException("WRONGPASS", "no"));
        await rig.EventuallyAsync(() => rig.Logger.Ids.Contains(ElastiCacheIntegrationsEvents.PeriodicFailed), Step);
        rig.Connection.Drop();
        var attempt = await rig.Connector.NextAsync();

        await RunDuringTheOutageAsync(rig, time);

        var next = attempt.Succeed();
        await rig.EventuallyAsync(() => rig.Session.IsOpen);
        await rig.EventuallyAsync(() => Recovered(rig), MaxBackoff);

        Assert.True(next.Reauths >= 1);
        Assert.Single(rig.Logger.Ids, ElastiCacheIntegrationsEvents.PeriodicRecovered);
    }

    // Fires the backoff wait several times. After each step it waits for the next wait to be armed,
    // so each step past the first runs the callback once with no connection.
    private static async Task RunDuringTheOutageAsync(SessionRig rig, RecordingTimeProvider time)
    {
        int Waits() => time.Armed(due => due > TimeSpan.Zero && due <= MaxBackoff);
        for (var i = 0; i < 5; i++)
        {
            var waits = Waits();
            time.Advance(MaxBackoff);
            await rig.EventuallyAsync(() => Recovered(rig) || Waits() > waits);
            Assert.False(Recovered(rig), "a run with no connection logged a recovery");
        }
    }
}
