using Microsoft.Extensions.Logging;

namespace Amazon.ElastiCache.Integrations.Tests;

public class SessionReauthStalenessTests
{
    private static readonly TimeSpan Interval = TimeSpan.FromMinutes(30);
    private static readonly TimeSpan FirstBackoff = TimeSpan.FromMilliseconds(100);

    [Theory]
    [InlineData(null)]
    [InlineData(0)]
    public async Task ReauthFailingPastTheFasLifetimeReplacesTheConnection(int? fallbackSeconds)
    {
        var time = new RecordingTimeProvider();
        await using var rig = await StaleAsync(time, SessionRig.Options(fallback: fallbackSeconds is { } s ? TimeSpan.FromSeconds(s) : null), () => true);

        Assert.Equal(ElastiCacheIntegrationsOutcome.Origin, await rig.SendAsync());
        Assert.Equal(1, rig.Connection.Requests);
        Assert.True(rig.Connection.Disposed);
        Assert.Single(rig.Logger.Ids, id => id == ElastiCacheIntegrationsEvents.AuthStale);
        Assert.Equal(LogLevel.Error, rig.Logger.Levels[Array.IndexOf(rig.Logger.Ids, ElastiCacheIntegrationsEvents.AuthStale)]);

        // The new connection's AUTH ends the staleness.
        var next = (await rig.Connector.NextAsync()).Succeed();
        await rig.EventuallyAsync(() => rig.Session.IsOpen);
        Assert.Equal(OutcomeKind.Served, (await rig.SendAsync()).Kind);
        Assert.Equal(1, next.Requests);
    }

    /// <summary>
    /// Re-AUTH fails at 30 min, where a request is still served, then again at 60 min, the FAS
    /// lifetime. Each clock step waits until the runner has armed the wait it ends.
    /// </summary>
    private static async Task<SessionRig> StaleAsync(RecordingTimeProvider time, ElastiCacheIntegrationsClientOptions options, Func<bool> failing)
    {
        var rig = await SessionRig.StartAsync(options, time: time);
        rig.Connection.OnReauth = _ => failing() ? Task.FromException(new ElastiCacheIntegrationsConnectionException("AUTH rejected")) : Task.CompletedTask;

        await rig.EventuallyAsync(() => time.Armed(due => due == Interval) > 0);
        time.Advance(Interval);
        await rig.EventuallyAsync(() => rig.Connection.Reauths == 1 && time.Armed(due => due == FirstBackoff) > 0);

        Assert.Equal(OutcomeKind.Served, (await rig.SendAsync()).Kind);
        Assert.DoesNotContain(ElastiCacheIntegrationsEvents.AuthStale, rig.Logger.Ids);

        time.Advance(ReauthScheduler.FasLifetime - Interval);
        await rig.EventuallyAsync(() => rig.Connection.Reauths == 2 && rig.Logger.Ids.Contains(ElastiCacheIntegrationsEvents.AuthStale));
        return rig;
    }
}
