using Microsoft.Extensions.Logging;

namespace Amazon.ElastiCache.Integrations.Tests;

public class SessionReconnectTests
{
    [Fact]
    public async Task ADropReconnectsAtOnceAndServesOnTheNewConnection()
    {
        await using var rig = await SessionRig.StartAsync();

        rig.Connection.Drop();
        var next = (await rig.Connector.NextAsync()).Succeed();
        await rig.EventuallyAsync(() => rig.Logger.Ids.Contains(ElastiCacheIntegrationsEvents.Reconnected) && rig.Connection.Disposed);

        Assert.Equal(OutcomeKind.Served, (await rig.SendAsync()).Kind);
        Assert.Equal(1, next.Requests);
        Assert.DoesNotContain(ElastiCacheIntegrationsEvents.Reconnecting, rig.Logger.Ids);
    }

    [Fact]
    public async Task ARequestWaitsForAReconnectThatLandsWithinReconnectWait()
    {
        await using var rig = await SessionRig.StartAsync();
        rig.Connection.Drop();
        var attempt = await rig.Connector.NextAsync();

        var pending = rig.SendAsync();
        var next = attempt.Succeed();

        Assert.Equal(OutcomeKind.Served, (await pending).Kind);
        Assert.Equal(1, next.Requests);
    }

    [Fact]
    public async Task ARequestThatFindsNoConnectionAfterReconnectWaitGoesToDynamoDB()
    {
        await using var rig = await SessionRig.StartAsync();
        rig.Connection.Drop();
        await rig.Connector.NextAsync();

        var pending = rig.SendAsync();
        await rig.EventuallyAsync(() => pending.IsCompleted, TimeSpan.FromMilliseconds(50));

        Assert.Equal(ElastiCacheIntegrationsOutcome.Origin, await pending);
    }

    [Fact]
    public async Task ARequestLaterInTheOutageWaitsOnlyForWhatIsLeftOfReconnectWait()
    {
        var time = new RecordingTimeProvider();
        await using var rig = await SessionRig.StartAsync(time: time);
        rig.Connection.Drop();
        await rig.Connector.NextAsync();
        time.Advance(TimeSpan.FromMilliseconds(60));

        var pending = rig.SendAsync();
        await rig.EventuallyAsync(() => pending.IsCompleted || time.Armed(due => due == TimeSpan.FromMilliseconds(40)) > 0);
        Assert.False(pending.IsCompleted);
        time.Advance(TimeSpan.FromMilliseconds(40));

        Assert.Equal(ElastiCacheIntegrationsOutcome.Origin, await pending);
    }

    [Fact]
    public async Task PastReconnectWaitRequestsInTheSameOutageDoNotWait()
    {
        await using var rig = await SessionRig.StartAsync();
        rig.Connection.Drop();
        await rig.Connector.NextAsync();
        rig.Time.Advance(TimeSpan.FromMilliseconds(100));

        for (var i = 0; i < 3; i++)
        {
            var pending = rig.SendAsync();
            await rig.EventuallyAsync(() => pending.IsCompleted);
            Assert.Equal(ElastiCacheIntegrationsOutcome.Origin, await pending);
        }
    }

    [Fact]
    public async Task ADeadConnectionIsNoticedByTheNextRequest()
    {
        await using var rig = await SessionRig.StartAsync(SessionRig.Options(reconnectWait: TimeSpan.Zero));
        rig.Connection.MarkDead();

        Assert.Equal(ElastiCacheIntegrationsOutcome.Origin, await rig.SendAsync());
        Assert.Equal(0, rig.Connection.Requests);
        Assert.True(rig.Connection.Disposed);
        Assert.False(rig.Session.IsOpen);
    }

    [Fact]
    public async Task TheReconnectWaitDoesNotUseUpTheFallbackWindow()
    {
        await using var rig = await SessionRig.StartAsync(SessionRig.Options(fallback: TimeSpan.FromSeconds(10)));
        rig.Connection.Drop();
        await rig.Connector.NextAsync();
        rig.Time.Advance(TimeSpan.FromMilliseconds(9950));

        var pending = rig.SendAsync();
        await rig.EventuallyAsync(() => pending.IsCompleted, TimeSpan.FromMilliseconds(50));

        Assert.Equal(ElastiCacheIntegrationsOutcome.Origin, await pending);
    }

    [Fact]
    public async Task TheCallersCancellationEndsTheReconnectWait()
    {
        await using var rig = await SessionRig.StartAsync();
        rig.Connection.Drop();
        await rig.Connector.NextAsync();
        using var cancel = new CancellationTokenSource();

        var pending = rig.Session.RequestAsync(SessionRig.Request(), cancel.Token);
        await cancel.CancelAsync();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => pending);
    }

    [Fact]
    public async Task PastFallbackDurationRequestsFailAndTheWarningIsLoggedOnce()
    {
        await using var rig = await SessionRig.StartAsync(SessionRig.Options(fallback: TimeSpan.FromSeconds(10), reconnectWait: TimeSpan.Zero));
        rig.Connection.Drop();
        await rig.Connector.NextAsync();

        rig.Time.Advance(TimeSpan.FromSeconds(9));
        Assert.Equal(ElastiCacheIntegrationsOutcome.Origin, await rig.SendAsync());
        rig.Time.Advance(TimeSpan.FromSeconds(1));
        var failed = await rig.SendAsync();
        await rig.SendAsync();

        Assert.Equal(OutcomeKind.Terminal, failed.Kind);
        Assert.IsType<ElastiCacheIntegrationsCacheUnavailableException>(failed.Error);
        Assert.Single(rig.Logger.Ids, ElastiCacheIntegrationsEvents.CacheUnavailable);
    }

    [Fact]
    public async Task WithNoFallbackACallFailsAtOnceWithNoConnection()
    {
        await using var rig = await SessionRig.StartAsync(SessionRig.Options(fallback: TimeSpan.Zero, reconnectWait: TimeSpan.Zero));
        rig.Connection.Drop();

        var outcome = await rig.SendAsync();

        Assert.Equal(OutcomeKind.Terminal, outcome.Kind);
        Assert.IsType<ElastiCacheIntegrationsCacheUnavailableException>(outcome.Error);
    }

    [Fact]
    public async Task AReconnectThatLandsStartsANewFallbackWindow()
    {
        await using var rig = await SessionRig.StartAsync(SessionRig.Options(fallback: TimeSpan.FromSeconds(10), reconnectWait: TimeSpan.Zero));
        rig.Connection.Drop();
        var second = (await rig.Connector.NextAsync()).Succeed();
        await rig.EventuallyAsync(() => rig.Session.IsOpen);
        rig.Time.Advance(TimeSpan.FromMinutes(1));

        second.Drop();
        await rig.Connector.NextAsync();

        Assert.Equal(ElastiCacheIntegrationsOutcome.Origin, await rig.SendAsync());
    }

    [Fact]
    public async Task ReconnectRetriesWithBackoffWithNoAttemptLimit()
    {
        await using var rig = await SessionRig.StartAsync();
        rig.Connection.Drop();

        for (var i = 0; i < 6; i++)
        {
            (await rig.Connector.NextAsync(rig.Time)).Fail(new ElastiCacheIntegrationsConnectionException($"attempt {i + 1}"));
        }

        (await rig.Connector.NextAsync(rig.Time)).Succeed();
        await rig.EventuallyAsync(() => rig.Logger.Ids.Contains(ElastiCacheIntegrationsEvents.Reconnected));

        Assert.Equal(8, rig.Connector.Count);
        var entries = rig.Logger.Ids.Zip(rig.Logger.Levels).ToArray();
        Assert.Equal(ElastiCacheIntegrationsEvents.Reconnecting, Assert.Single(entries, e => e.Second == LogLevel.Warning).First);
        Assert.Contains((ElastiCacheIntegrationsEvents.Reconnected, LogLevel.Information), entries);
    }

    [Fact]
    public async Task ReconnectKeepsRetryingAfterRejectedOverrides()
    {
        await using var rig = await SessionRig.StartAsync();
        rig.Connection.Drop();

        (await rig.Connector.NextAsync(rig.Time)).Fail(new ElastiCacheIntegrationsConfigurationException("CONFIG_INVALID"));
        (await rig.Connector.NextAsync(rig.Time)).Succeed();

        await rig.EventuallyAsync(() => rig.Session.IsOpen);
    }

    [Theory]
    [InlineData("MOVED")]
    [InlineData("NOAUTH")]
    public async Task AConnectionDropReplyIsSentAgainOnTheNewConnection(string code)
    {
        await using var rig = await SessionRig.StartAsync();
        rig.Connection.OnRequest = (_, _) => Task.FromResult<RequestReply>(new RequestReply.Rejected(new ElastiCacheIntegrationsServerException(code, "no")));

        var pending = rig.SendAsync();
        var next = (await rig.Connector.NextAsync()).Succeed();

        Assert.Equal(OutcomeKind.Served, (await pending).Kind);
        Assert.Equal(1, next.Requests);
    }

    [Fact]
    public async Task ASecondMovedReplyIsRetryable()
    {
        await using var rig = await SessionRig.StartAsync();
        var moved = new RequestReply.Rejected(new ElastiCacheIntegrationsServerException("MOVED", "1 10.0.0.1:6379"));
        rig.Connection.OnRequest = (_, _) => Task.FromResult<RequestReply>(moved);

        var pending = rig.SendAsync();
        (await rig.Connector.NextAsync()).Succeed(configure: c => c.OnRequest = (_, _) => Task.FromResult<RequestReply>(moved));

        Assert.Equal(OutcomeKind.Retryable, (await pending).Kind);
    }

    [Fact]
    public async Task WithNoFallbackAMovedReplyFailsWhenNoConnectionIsBack()
    {
        await using var rig = await SessionRig.StartAsync(SessionRig.Options(fallback: TimeSpan.Zero, reconnectWait: TimeSpan.Zero));
        rig.Connection.OnRequest = (_, _) => Task.FromResult<RequestReply>(new RequestReply.Rejected(new ElastiCacheIntegrationsServerException("MOVED", "1 10.0.0.1:6379")));

        var outcome = await rig.SendAsync();

        Assert.Equal(OutcomeKind.Terminal, outcome.Kind);
        Assert.IsType<ElastiCacheIntegrationsCacheUnavailableException>(outcome.Error);
    }

    [Fact]
    public async Task AReconnectWithAConfigItCannotUseKeepsRetryingWhileRequestsGoToDynamoDB()
    {
        await using var rig = await SessionRig.StartAsync(SessionRig.Options(reconnectWait: TimeSpan.Zero));
        rig.Connection.Drop();
        var unusable = (await rig.Connector.NextAsync()).Succeed(SessionRig.Config("""[{"expr":"this is not (valid CEL"}]"""));
        await rig.EventuallyAsync(() => unusable.Disposed);

        Assert.False(rig.Session.IsOpen);
        Assert.Equal(ElastiCacheIntegrationsOutcome.Origin, await rig.SendAsync());
        Assert.Equal(0, unusable.Requests);

        (await rig.Connector.NextAsync(rig.Time)).Succeed();
        await rig.EventuallyAsync(() => rig.Session.IsOpen);

        Assert.Equal(OutcomeKind.Served, (await rig.SendAsync()).Kind);
        Assert.Single(rig.Logger.Ids, id => id == ElastiCacheIntegrationsEvents.ConfigUnusable);
    }

    [Fact]
    public async Task ANewConnectionBringsItsOwnPredicates()
    {
        await using var rig = await SessionRig.StartAsync();
        rig.Connection.Drop();
        (await rig.Connector.NextAsync()).Succeed(SessionRig.Config("""[{"expr":"operation == 'Query'"}]"""));
        await rig.EventuallyAsync(() => rig.Session.IsOpen);

        Assert.Equal(ElastiCacheIntegrationsOutcome.Origin, await rig.SendAsync("GetItem"));
    }

    [Fact]
    public async Task DisposeStopsTheReconnectLoop()
    {
        var rig = await SessionRig.StartAsync();
        rig.Connection.Drop();
        await rig.Connector.NextAsync();

        await rig.Session.DisposeAsync();
        for (var i = 0; i < 10; i++)
        {
            rig.Time.Advance(TimeSpan.FromSeconds(5));
            await Task.Delay(5, TestContext.Current.CancellationToken);
        }

        Assert.Equal(2, rig.Connector.Count);
        Assert.False(rig.Session.IsOpen);
    }

    [Fact]
    public async Task DisposeReleasesARequestWaitingForTheReconnect()
    {
        var rig = await SessionRig.StartAsync(SessionRig.Options(reconnectWait: TimeSpan.FromMinutes(1)));
        rig.Connection.Drop();
        await rig.Connector.NextAsync();
        var pending = rig.SendAsync();

        await rig.Session.DisposeAsync();

        var outcome = await pending;
        Assert.Equal(OutcomeKind.Terminal, outcome.Kind);
        Assert.IsType<ObjectDisposedException>(outcome.Error);
    }

    [Fact]
    public async Task AHandleSendsARequestWaitingForTheReconnectToTheOriginWhenItCloses()
    {
        var rig = await SessionRig.StartAsync(SessionRig.Options(reconnectWait: TimeSpan.FromMinutes(1)));
        var handle = new ElastiCacheIntegrationsHandle(rig.Session, SessionRig.Options());
        rig.Connection.Drop();
        await rig.Connector.NextAsync();
        var pending = handle.SendAsync(SessionRig.Request(), TestContext.Current.CancellationToken);

        await handle.DisposeAsync();

        Assert.Equal(OutcomeKind.Origin, (await pending).Kind);
    }

    [Fact]
    public async Task AConnectionThatLandsAfterCloseIsDisposed()
    {
        var connector = new FakeConnector { HonorsCancellation = false };
        var time = new Microsoft.Extensions.Time.Testing.FakeTimeProvider();
        var open = SessionRig.OpenAsync(SessionRig.Options(), connector, time, new ListLogger());
        var first = (await connector.NextAsync()).Succeed();
        var session = await open;
        first.Drop();
        var attempt = await connector.NextAsync();

        var closing = session.DisposeAsync().AsTask();
        var late = attempt.Succeed();
        await closing;

        Assert.True(late.Disposed);
        Assert.False(session.IsOpen);
    }
}
