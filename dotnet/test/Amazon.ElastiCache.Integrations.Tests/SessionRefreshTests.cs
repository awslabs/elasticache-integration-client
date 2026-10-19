using Microsoft.Extensions.Logging;

namespace Amazon.ElastiCache.Integrations.Tests;

public class SessionRefreshTests
{
    private static readonly TimeSpan Step = TimeSpan.FromSeconds(10);

    private static int Applied(SessionRig rig) => rig.Logger.Ids.Count(id => id == ElastiCacheIntegrationsEvents.ConfigApplied);

    [Fact]
    public async Task ANewConfigReplacesThePredicates()
    {
        await using var rig = await SessionRig.StartAsync(config: SessionRig.Config(refreshSeconds: 60));
        rig.Connection.OnRefresh = _ => Task.FromResult<ResolvedConfig?>(SessionRig.Config("""[{"expr":"operation == 'Query'"}]""", 60));

        await rig.EventuallyAsync(() => Applied(rig) == 2, Step);

        Assert.Equal(ElastiCacheIntegrationsOutcome.Origin, await rig.SendAsync("GetItem"));
        Assert.Equal(OutcomeKind.Served, (await rig.SendAsync("Query")).Kind);
    }

    [Fact]
    public async Task ANullReplyKeepsTheConfig()
    {
        await using var rig = await SessionRig.StartAsync(config: SessionRig.Config(refreshSeconds: 60));

        await rig.EventuallyAsync(() => rig.Connection.Refreshes >= 2, Step);

        Assert.Equal(1, Applied(rig));
        Assert.Equal(OutcomeKind.Served, (await rig.SendAsync()).Kind);
    }

    [Fact]
    public async Task AFailedRefreshKeepsTheConfigAndRetries()
    {
        await using var rig = await SessionRig.StartAsync(config: SessionRig.Config(refreshSeconds: 60));
        rig.Connection.OnRefresh = _ => Task.FromException<ResolvedConfig?>(new ElastiCacheIntegrationsServerException("INTERNAL_ERROR", "aws.config was not processed"));

        await rig.EventuallyAsync(() => rig.Connection.Refreshes == 1, Step);

        // Retries come on the backoff (100 ms, 200 ms), long before the 60 s interval.
        for (var i = 0; i < 15 && rig.Connection.Refreshes < 3; i++)
        {
            rig.Time.Advance(TimeSpan.FromSeconds(1));
            await Task.Delay(10, TestContext.Current.CancellationToken);
        }

        Assert.True(rig.Connection.Refreshes >= 3, $"{rig.Connection.Refreshes} refreshes");
        Assert.Equal(1, Applied(rig));
        Assert.Equal(OutcomeKind.Served, (await rig.SendAsync()).Kind);
        Assert.Contains(ElastiCacheIntegrationsEvents.PeriodicFailed, rig.Logger.Ids);
    }

    [Fact]
    public async Task AConfigItCannotUseIsNotAppliedUntilAUsableOneArrives()
    {
        await using var rig = await SessionRig.StartAsync(config: SessionRig.Config(refreshSeconds: 60));
        var replies = 0;
        rig.Connection.OnRefresh = _ => Task.FromResult(Interlocked.Increment(ref replies) switch
        {
            <= 2 => SessionRig.Config("""[{"expr":"operation == 'Query'"},{"expr":1}]""", 60),

            // Unchanged: the cache still holds the config this client cannot use.
            3 => null,
            _ => SessionRig.Config("""[{"expr":"operation == 'Query'"}]""", 60),
        });

        await rig.EventuallyAsync(() => rig.Logger.Ids.Count(id => id == ElastiCacheIntegrationsEvents.PeriodicFailed) == 3, Step);

        Assert.Equal(3, Volatile.Read(ref replies));
        Assert.Equal(1, Applied(rig));
        Assert.Equal(OutcomeKind.Served, (await rig.SendAsync("GetItem")).Kind);

        await rig.EventuallyAsync(() => Applied(rig) == 2, Step);

        Assert.Equal(ElastiCacheIntegrationsOutcome.Origin, await rig.SendAsync("GetItem"));
        Assert.Equal([LogLevel.Error], rig.Logger.Levels.Where((_, i) => rig.Logger.Ids[i] == ElastiCacheIntegrationsEvents.ConfigUnusable));
        Assert.True(rig.Session.IsOpen);
    }

    [Fact]
    public async Task AConfigItCannotUsePastTheCachePolicyTimeoutReplacesTheConnection()
    {
        await using var rig = await SessionRig.StartAsync(
            SessionRig.Options(cachePolicyTimeout: TimeSpan.FromSeconds(90)), SessionRig.Config(refreshSeconds: 60));
        rig.Connection.OnRefresh = _ => Task.FromResult<ResolvedConfig?>(SessionRig.Config("{}", 60));

        await rig.EventuallyAsync(() => rig.Connection.Disposed, Step);

        Assert.Contains(ElastiCacheIntegrationsEvents.ConfigStale, rig.Logger.Ids);
        Assert.Single(rig.Logger.Ids, id => id == ElastiCacheIntegrationsEvents.ConfigUnusable);
        (await rig.Connector.NextAsync()).Succeed();
        await rig.EventuallyAsync(() => rig.Session.IsOpen);
    }

    [Fact]
    public async Task ANewIntervalReschedulesTheRefresh()
    {
        await using var rig = await SessionRig.StartAsync(config: SessionRig.Config(refreshSeconds: 60));
        rig.Connection.OnRefresh = _ => Task.FromResult<ResolvedConfig?>(SessionRig.Config(refreshSeconds: 600));
        await rig.EventuallyAsync(() => Applied(rig) == 2, Step);

        for (var i = 0; i < 30; i++)
        {
            rig.Time.Advance(Step);
            await Task.Delay(10, TestContext.Current.CancellationToken);
        }

        Assert.Equal(1, rig.Connection.Refreshes);
        for (var i = 0; i < 40 && rig.Connection.Refreshes < 2; i++)
        {
            rig.Time.Advance(Step);
            await Task.Delay(10, TestContext.Current.CancellationToken);
        }

        Assert.Equal(2, rig.Connection.Refreshes);
    }

    [Fact]
    public async Task NoIntervalMeansNoRefresh()
    {
        await using var rig = await SessionRig.StartAsync();

        for (var i = 0; i < 20; i++)
        {
            rig.Time.Advance(TimeSpan.FromMinutes(1));
            await Task.Delay(5, TestContext.Current.CancellationToken);
        }

        Assert.Equal(0, rig.Connection.Refreshes);
    }

    [Fact]
    public async Task ARefreshForAReplacedConnectionIsDropped()
    {
        await using var rig = await SessionRig.StartAsync(config: SessionRig.Config(refreshSeconds: 60));
        var reply = new TaskCompletionSource<ResolvedConfig?>();
        rig.Connection.OnRefresh = _ => reply.Task;
        await rig.EventuallyAsync(() => rig.Connection.Refreshes == 1, Step);

        rig.Connection.Drop();
        var next = (await rig.Connector.NextAsync()).Succeed(SessionRig.Config(refreshSeconds: 60));
        await rig.EventuallyAsync(() => Applied(rig) == 2);
        reply.SetResult(SessionRig.Config("[]", 60));

        // The same runner refreshes the new connection only after the old tick has ended.
        await rig.EventuallyAsync(() => next.Refreshes == 1, Step);
        Assert.Equal(OutcomeKind.Served, (await rig.SendAsync()).Kind);
        Assert.Equal(2, Applied(rig));
    }
}
