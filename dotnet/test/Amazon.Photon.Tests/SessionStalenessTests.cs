using Microsoft.Extensions.Time.Testing;

namespace Amazon.Photon.Tests;

public class SessionStalenessTests
{
    private static readonly TimeSpan Step = TimeSpan.FromSeconds(10);

    private static Task<ResolvedConfig?> Fail(CancellationToken _) =>
        Task.FromException<ResolvedConfig?>(new PhotonServerException("INTERNAL_ERROR", "aws.config was not processed"));

    private static readonly TimeSpan Interval = TimeSpan.FromSeconds(60);

    private static Task<SessionRig> StartAsync(int timeoutSeconds, TimeSpan? fallback = null, FakeTimeProvider? time = null) => SessionRig.StartAsync(
        SessionRig.Options(fallback: fallback, reconnectWait: TimeSpan.Zero, cachePolicyTimeout: TimeSpan.FromSeconds(timeoutSeconds)),
        SessionRig.Config(refreshSeconds: 60),
        time);

    private static int Applied(SessionRig rig) => rig.Logger.Ids.Count(id => id == PhotonEvents.ConfigApplied);

    [Fact]
    public async Task AFailureInsideTheTimeoutKeepsTheConfigAndTheConnection()
    {
        await using var rig = await StartAsync(timeoutSeconds: 300);
        var failures = 0;
        rig.Connection.OnRefresh = ct =>
        {
            Interlocked.Increment(ref failures);
            return Fail(ct);
        };

        // The first refresh at 60 s, then retries on the backoff, all well inside 300 s.
        await rig.EventuallyAsync(() => rig.Logger.Ids.Count(id => id == PhotonEvents.PeriodicFailed) >= 4, TimeSpan.FromSeconds(5));

        Assert.True(Volatile.Read(ref failures) >= 4);
        Assert.False(rig.Connection.Disposed);
        Assert.Equal(1, rig.Connector.Count);
        Assert.DoesNotContain(PhotonEvents.ConfigStale, rig.Logger.Ids);
        Assert.Equal(OutcomeKind.Served, (await rig.SendAsync()).Kind);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task AFailurePastTheTimeoutReconnectsForACurrentConfig(bool strict)
    {
        await using var rig = await StartAsync(timeoutSeconds: 90, strict ? TimeSpan.Zero : null);
        rig.Connection.OnRefresh = Fail;

        await rig.EventuallyAsync(() => rig.Connection.Disposed, Step);

        Assert.Single(rig.Logger.Ids, PhotonEvents.ConfigStale);
        var outcome = await rig.SendAsync();
        Assert.Equal(strict ? OutcomeKind.Terminal : OutcomeKind.Origin, outcome.Kind);

        (await rig.Connector.NextAsync()).Succeed(SessionRig.Config("""[{"expr":"operation == 'Query'"}]""", 60));
        await rig.EventuallyAsync(() => rig.Session.IsOpen && Applied(rig) == 2);

        Assert.Equal(PhotonOutcome.Origin, await rig.SendAsync("GetItem"));
        Assert.Equal(OutcomeKind.Served, (await rig.SendAsync("Query")).Kind);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task AReplyRestartsTheTimeout(bool newConfig)
    {
        var time = new RecordingTimeProvider();
        await using var rig = await StartAsync(timeoutSeconds: 90, time: time);
        var start = time.GetUtcNow();
        var calls = 0;
        var firstFailure = DateTimeOffset.MinValue;
        var lastFailure = DateTimeOffset.MinValue;
        rig.Connection.OnRefresh = ct =>
        {
            if (Interlocked.Increment(ref calls) == 1)
            {
                return Task.FromResult(newConfig ? SessionRig.Config(refreshSeconds: 60) : null);
            }

            lastFailure = rig.Time.GetUtcNow();
            if (firstFailure == DateTimeOffset.MinValue)
            {
                firstFailure = lastFailure;
            }

            return Fail(ct);
        };

        // Replied at 60 s, so failures from 120 s keep the connection until 150 s. Each step waits for
        // the refresh to arm its next wait; with no jitter and backoff under 10 s it fires one refresh.
        int Waits() => time.Armed(due => due > TimeSpan.Zero && due <= Interval);
        bool Stale() => rig.Logger.Ids.Contains(PhotonEvents.ConfigStale);
        await rig.EventuallyAsync(() => time.Armed(due => due == Interval) > 0);
        for (var i = 0; i < 10 && !Stale(); i++)
        {
            var waits = Waits();
            var before = Volatile.Read(ref calls);
            time.Advance(before < 2 ? Interval : Step);
            await rig.EventuallyAsync(() => Volatile.Read(ref calls) > before);
            await rig.EventuallyAsync(() => Stale() || Waits() > waits);
        }

        await rig.EventuallyAsync(() => rig.Connection.Disposed);

        Assert.Equal(newConfig ? 2 : 1, Applied(rig));
        Assert.True(firstFailure - start <= TimeSpan.FromSeconds(130), $"first failure at {firstFailure - start}");
        Assert.True(lastFailure - start >= TimeSpan.FromSeconds(150), $"dropped at {lastFailure - start}");
        Assert.Single(rig.Logger.Ids, PhotonEvents.ConfigStale);
    }

    [Fact]
    public async Task AFailureForAReplacedConnectionDropsNothing()
    {
        await using var rig = await StartAsync(timeoutSeconds: 30);
        var reply = new TaskCompletionSource<ResolvedConfig?>();
        rig.Connection.OnRefresh = ct => reply.Task.WaitAsync(ct);
        await rig.EventuallyAsync(() => rig.Connection.Refreshes == 1, Step);

        rig.Connection.Drop();
        var next = (await rig.Connector.NextAsync()).Succeed(SessionRig.Config(refreshSeconds: 60));
        await rig.EventuallyAsync(() => rig.Session.IsOpen);
        rig.Time.Advance(TimeSpan.FromSeconds(31));
        reply.SetException(new PhotonServerException("INTERNAL_ERROR", "aws.config was not processed"));
        await rig.EventuallyAsync(() => rig.Logger.Ids.Contains(PhotonEvents.PeriodicFailed));

        Assert.False(next.Disposed);
        Assert.True(rig.Session.IsOpen);
        Assert.DoesNotContain(PhotonEvents.ConfigStale, rig.Logger.Ids);
    }

    [Fact]
    public async Task ACloseDuringARefreshDropsNothing()
    {
        await using var rig = await StartAsync(timeoutSeconds: 30);
        rig.Connection.OnRefresh = ct => Task.Delay(Timeout.InfiniteTimeSpan, ct).ContinueWith<ResolvedConfig?>(_ => throw new PhotonTimeoutException("late"), TaskScheduler.Default);
        await rig.EventuallyAsync(() => rig.Connection.Refreshes == 1, Step);

        await rig.Session.DisposeAsync();

        Assert.Equal(1, rig.Connector.Count);
        Assert.DoesNotContain(PhotonEvents.ConfigStale, rig.Logger.Ids);
    }
}
