using Microsoft.Extensions.Logging;

namespace Amazon.Photon.Tests;

public class SessionReauthStalenessTests
{
    private static readonly TimeSpan Interval = TimeSpan.FromMinutes(30);
    private static readonly TimeSpan FirstBackoff = TimeSpan.FromMilliseconds(100);

    [Fact]
    public async Task ReauthFailingPastTheFasLifetimeSkipsTheCacheUntilAnAuthSucceeds()
    {
        var time = new RecordingTimeProvider();
        var failing = true;
        await using var rig = await StaleAsync(time, SessionRig.Options(), () => Volatile.Read(ref failing));

        Assert.Equal(PhotonOutcome.Origin, await rig.SendAsync());
        Assert.Equal(1, rig.Connection.Requests);
        Assert.Single(rig.Logger.Ids, id => id == PhotonEvents.AuthStale);
        Assert.Equal(LogLevel.Error, rig.Logger.Levels[Array.IndexOf(rig.Logger.Ids, PhotonEvents.AuthStale)]);
        Assert.DoesNotContain(PhotonEvents.AuthDegraded, rig.Logger.Ids);

        Volatile.Write(ref failing, false);
        await rig.EventuallyAsync(() => rig.Logger.Ids.Contains(PhotonEvents.PeriodicRecovered), TimeSpan.FromSeconds(1));

        Assert.Equal(OutcomeKind.Served, (await rig.SendAsync()).Kind);
        Assert.Equal(2, rig.Connection.Requests);
    }

    [Fact]
    public async Task StaleRequestsFailOnceFallbackDurationIsSpent()
    {
        var time = new RecordingTimeProvider();
        await using var rig = await StaleAsync(time, SessionRig.Options(fallback: TimeSpan.FromMinutes(5)), () => true);

        Assert.Equal(PhotonOutcome.Origin, await rig.SendAsync());
        time.Advance(TimeSpan.FromMinutes(5));
        var outcome = await rig.SendAsync();

        Assert.Equal(OutcomeKind.Terminal, outcome.Kind);
        Assert.IsType<PhotonCacheUnavailableException>(outcome.Error);
        Assert.Single(rig.Logger.Ids, id => id == PhotonEvents.AuthWindowExhausted);
        Assert.Single(rig.Logger.Ids, id => id == PhotonEvents.AuthStale);
        Assert.Equal(1, rig.Connection.Requests);
    }

    [Fact]
    public async Task StrictModeFailsAStaleRequestAndAReconnectClearsIt()
    {
        var time = new RecordingTimeProvider();
        await using var rig = await StaleAsync(time, SessionRig.Options(fallback: TimeSpan.Zero), () => true);

        var outcome = await rig.SendAsync();

        Assert.Equal(OutcomeKind.Terminal, outcome.Kind);
        Assert.IsType<PhotonCacheUnavailableException>(outcome.Error);
        Assert.Equal(1, rig.Connection.Requests);

        rig.Connection.Drop();
        var next = (await rig.Connector.NextAsync()).Succeed();
        await rig.EventuallyAsync(() => rig.Session.IsOpen);

        Assert.Equal(OutcomeKind.Served, (await rig.SendAsync()).Kind);
        Assert.Equal(1, next.Requests);
    }

    /// <summary>
    /// Re-AUTH fails at 30 min, where a request is still served, then again at 60 min, the FAS
    /// lifetime. Each clock step waits until the runner has armed the wait it ends.
    /// </summary>
    private static async Task<SessionRig> StaleAsync(RecordingTimeProvider time, PhotonClientOptions options, Func<bool> failing)
    {
        var rig = await SessionRig.StartAsync(options, time: time);
        rig.Connection.OnReauth = _ => failing() ? Task.FromException(new PhotonConnectionException("AUTH rejected")) : Task.CompletedTask;

        await rig.EventuallyAsync(() => time.Armed(due => due == Interval) > 0);
        time.Advance(Interval);
        await rig.EventuallyAsync(() => rig.Connection.Reauths == 1 && time.Armed(due => due == FirstBackoff) > 0);

        Assert.Equal(OutcomeKind.Served, (await rig.SendAsync()).Kind);
        Assert.DoesNotContain(PhotonEvents.AuthStale, rig.Logger.Ids);

        time.Advance(ReauthScheduler.FasLifetime - Interval);
        await rig.EventuallyAsync(() => rig.Connection.Reauths == 2 && rig.Logger.Ids.Contains(PhotonEvents.AuthStale));
        return rig;
    }
}
