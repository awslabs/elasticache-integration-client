using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Time.Testing;

namespace Amazon.ElastiCache.Integrations.Tests;

public class SessionTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    private static RequestReply Rejected(string code) => new RequestReply.Rejected(new ElastiCacheIntegrationsServerException(code, "no"));

    [Fact]
    public async Task OpensAndServesThroughTheCache()
    {
        await using var rig = await SessionRig.StartAsync();

        var outcome = await rig.SendAsync();

        Assert.True(rig.Session.IsOpen);
        Assert.Equal("dynamodb", rig.Session.Service);
        Assert.Equal(ElastiCacheIntegrationsOutcome.Served(FakeConnection.Ok), outcome);
        Assert.Equal(1, rig.Connection.Requests);
    }

    [Fact]
    public async Task ARequestThePredicatesDeclineGoesToDynamoDBWithoutTheCache()
    {
        await using var rig = await SessionRig.StartAsync(config: SessionRig.Config("""[{"expr":"operation == 'Query'"}]"""));

        Assert.Equal(ElastiCacheIntegrationsOutcome.Origin, await rig.SendAsync("GetItem"));
        Assert.Equal(OutcomeKind.Served, (await rig.SendAsync("Query")).Kind);
        Assert.Equal(1, rig.Connection.Requests);
    }

    [Fact]
    public async Task AnEmptyPredicateArrayRoutesNothing()
    {
        await using var rig = await SessionRig.StartAsync(config: SessionRig.Config("[]"));

        Assert.Equal(ElastiCacheIntegrationsOutcome.Origin, await rig.SendAsync());
        Assert.Equal(0, rig.Connection.Requests);
    }

    [Fact]
    public async Task OpenFailsWhenEveryAttemptBringsAConfigItCannotUse()
    {
        var connector = new FakeConnector();
        var time = new FakeTimeProvider();
        var logger = new ListLogger();
        var open = SessionRig.OpenAsync(SessionRig.Options(), connector, time, logger);
        var connections = new List<FakeConnection>();
        for (var i = 0; i < 3; i++)
        {
            connections.Add((await connector.NextAsync(time)).Succeed(new ResolvedConfig("1.0", TimeSpan.FromSeconds(60), System.Text.Json.JsonDocument.Parse("{}").RootElement)));
        }

        var error = await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionException>(() => open);

        Assert.Equal("aws.config failed during bring-up: Unusable aws.config reply: it has no predicates.", error.Message);
        Assert.IsType<ElastiCacheIntegrationsProtocolException>(error.InnerException);
        Assert.Equal(3, connector.Count);
        Assert.All(connections, c => Assert.True(c.Disposed));
        Assert.Equal([LogLevel.Error], logger.Levels.Where((_, i) => logger.Ids[i] == ElastiCacheIntegrationsEvents.ConfigUnusable));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task AFullConnectionIsRetryableAndStaysUp(bool noFallback)
    {
        await using var rig = await SessionRig.StartAsync(SessionRig.Options(fallback: noFallback ? TimeSpan.Zero : null));
        rig.Connection.OnRequest = (_, _) => Task.FromException<RequestReply>(new ElastiCacheIntegrationsPendingLimitException("full"));

        var outcome = await rig.SendAsync();

        Assert.Equal(OutcomeKind.Retryable, outcome.Kind);
        Assert.IsType<ElastiCacheIntegrationsPendingLimitException>(outcome.Error);
        Assert.True(rig.Session.IsOpen);
    }

    [Fact]
    public async Task AFullConnectionLogsOncePerBurst()
    {
        await using var rig = await SessionRig.StartAsync();
        rig.Connection.OnRequest = (_, _) => Task.FromException<RequestReply>(new ElastiCacheIntegrationsPendingLimitException("full"));
        int Logged() => rig.Logger.Ids.Count(id => id == ElastiCacheIntegrationsEvents.PendingLimitReached);

        await rig.SendAsync();
        await rig.SendAsync();
        Assert.Equal(1, Logged());

        // Hits closer together than the gap keep the burst going, however long it lasts.
        for (var i = 0; i < 5; i++)
        {
            rig.Time.Advance(ElastiCacheIntegrationsSession.PendingBurstGap - TimeSpan.FromMilliseconds(100));
            await rig.SendAsync();
        }

        Assert.Equal(1, Logged());

        rig.Time.Advance(ElastiCacheIntegrationsSession.PendingBurstGap);
        await rig.SendAsync();
        Assert.Equal(2, Logged());
        Assert.Equal(LogLevel.Warning, rig.Logger.Levels[Array.LastIndexOf(rig.Logger.Ids, ElastiCacheIntegrationsEvents.PendingLimitReached)]);
    }

    [Fact]
    public async Task ATimeoutIsRetryable()
    {
        await using var rig = await SessionRig.StartAsync();
        rig.Connection.OnRequest = (_, _) => Task.FromException<RequestReply>(new ElastiCacheIntegrationsTimeoutException("slow"));

        Assert.Equal(OutcomeKind.Retryable, (await rig.SendAsync()).Kind);
        Assert.True(rig.Session.IsOpen);
    }

    [Fact]
    public async Task ADeterministicRejectFailsInStrictMode()
    {
        await using var rig = await SessionRig.StartAsync(SessionRig.Options(fallback: TimeSpan.Zero));
        rig.Connection.OnRequest = (_, _) => Task.FromResult(Rejected("UNSUPPORTED"));

        Assert.Equal(OutcomeKind.Terminal, (await rig.SendAsync()).Kind);
    }

    [Fact]
    public async Task TheCallersCancellationPropagates()
    {
        await using var rig = await SessionRig.StartAsync();
        rig.Connection.OnRequest = (_, ct) => Task.FromCanceled<RequestReply>(ct);
        using var cancel = new CancellationTokenSource();
        await cancel.CancelAsync();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => rig.Session.RequestAsync(SessionRig.Request(), cancel.Token));
    }

    [Fact]
    public async Task BringUpRetriesWithBackoffAndThrowsTheLastFailure()
    {
        var connector = new FakeConnector();
        var time = new FakeTimeProvider();
        var logger = new ListLogger();
        var open = SessionRig.OpenAsync(SessionRig.Options(), connector, time, logger);

        (await connector.NextAsync()).Fail(new ElastiCacheIntegrationsConnectionException("one"));
        (await connector.NextAsync(time, TimeSpan.FromMilliseconds(50))).Fail(new ElastiCacheIntegrationsConnectionException("two"));
        (await connector.NextAsync(time, TimeSpan.FromMilliseconds(50))).Fail(new ElastiCacheIntegrationsConnectionException("three"));

        Assert.Equal("three", (await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionException>(() => open)).Message);
        Assert.Equal(3, connector.Count);
        Assert.Equal(2, logger.Ids.Count(id => id == ElastiCacheIntegrationsEvents.BringUpAttemptFailed));
    }

    [Fact]
    public async Task BringUpSucceedsOnALaterAttempt()
    {
        var connector = new FakeConnector();
        var time = new FakeTimeProvider();
        var open = SessionRig.OpenAsync(SessionRig.Options(), connector, time, new ListLogger());

        (await connector.NextAsync()).Fail(new ElastiCacheIntegrationsConnectionException("one"));
        (await connector.NextAsync(time)).Succeed();
        await using var session = await open;

        Assert.True(session.IsOpen);
    }

    [Fact]
    public async Task RejectedOverridesAreNotRetried()
    {
        var connector = new FakeConnector();
        var open = SessionRig.OpenAsync(SessionRig.Options(), connector, new FakeTimeProvider(), new ListLogger());

        (await connector.NextAsync()).Fail(new ElastiCacheIntegrationsConfigurationException("CONFIG_INVALID"));

        await Assert.ThrowsAsync<ElastiCacheIntegrationsConfigurationException>(() => open);
        Assert.Equal(1, connector.Count);
    }

    [Fact]
    public async Task EachAttemptIsCappedByAttemptTimeout()
    {
        var connector = new FakeConnector();
        var time = new FakeTimeProvider();
        var open = SessionRig.OpenAsync(
            new ElastiCacheIntegrationsClientOptions { Endpoint = "localhost:6379", CacheName = "c", Reconnect = new() { ConnectAttempts = 1, AttemptTimeout = TimeSpan.FromSeconds(5) } },
            connector, time, new ListLogger());

        await connector.NextAsync();
        time.Advance(TimeSpan.FromSeconds(5));

        var error = await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionException>(() => open);
        Assert.Contains("5000 ms", error.Message);
    }

    [Fact]
    public async Task TheCallersCancellationStopsBringUp()
    {
        var connector = new FakeConnector();
        using var cancel = new CancellationTokenSource();
        var open = ElastiCacheIntegrationsSession.OpenAsync(SessionRig.Options(), connector.ConnectAsync, "dynamodb", new ListLogger(), new FakeTimeProvider(), new Random(1), cancel.Token);

        await connector.NextAsync();
        await cancel.CancelAsync();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => open);
        Assert.Equal(1, connector.Count);
    }

    [Fact]
    public async Task BadOptionsFailBeforeAnyIo() =>
        await Assert.ThrowsAsync<ElastiCacheIntegrationsConfigurationException>(() => ElastiCacheIntegrationsSession.OpenAsync(
            new ElastiCacheIntegrationsClientOptions { Endpoint = "no-port", CacheName = "c" }, new ConnectionTests.StaticAuth("u", "t"), "dynamodb", Token));

    [Theory]
    [InlineData(null)]
    [InlineData(0)]
    public async Task AnAuthErrorReplacesTheConnectionAndGoesToDynamoDB(int? fallbackSeconds)
    {
        await using var rig = await SessionRig.StartAsync(SessionRig.Options(fallback: fallbackSeconds is { } s ? TimeSpan.FromSeconds(s) : null));
        rig.Connection.OnRequest = (_, _) => Task.FromResult(Rejected("AUTH_ERROR"));

        Assert.Equal(ElastiCacheIntegrationsOutcome.Origin, await rig.SendAsync("PutItem"));
        Assert.True(rig.Connection.Disposed);
        Assert.Equal(0, rig.Connection.Reauths);
        Assert.Single(rig.Logger.Ids, id => id == ElastiCacheIntegrationsEvents.AuthDegraded);

        var next = (await rig.Connector.NextAsync()).Succeed();
        await rig.EventuallyAsync(() => rig.Session.IsOpen);
        Assert.Equal(OutcomeKind.Served, (await rig.SendAsync()).Kind);
        Assert.Equal(1, next.Requests);
    }

    [Fact]
    public async Task ConcurrentAuthErrorsOnOneConnectionCloseItOnceAndLogOnce()
    {
        await using var rig = await SessionRig.StartAsync();
        var reply = new TaskCompletionSource<RequestReply>(TaskCreationOptions.RunContinuationsAsynchronously);
        rig.Connection.OnRequest = (_, _) => reply.Task;

        var calls = Enumerable.Range(0, 8).Select(_ => rig.SendAsync()).ToList();
        await rig.EventuallyAsync(() => rig.Connection.Requests == 8);
        reply.SetResult(Rejected("AUTH_ERROR"));

        Assert.All(await Task.WhenAll(calls), outcome => Assert.Equal(ElastiCacheIntegrationsOutcome.Origin, outcome));
        Assert.Single(rig.Logger.Ids, id => id == ElastiCacheIntegrationsEvents.AuthDegraded);
        (await rig.Connector.NextAsync()).Succeed();
        await rig.EventuallyAsync(() => rig.Session.IsOpen);
    }

    [Fact]
    public async Task ALateAuthErrorFromAReplacedConnectionLogsNothing()
    {
        await using var rig = await SessionRig.StartAsync();
        var reply = new TaskCompletionSource<RequestReply>();
        rig.Connection.OnRequest = (_, _) => reply.Task;

        var pending = rig.SendAsync();
        rig.Connection.Drop();
        (await rig.Connector.NextAsync()).Succeed();
        await rig.EventuallyAsync(() => rig.Session.IsOpen);
        reply.SetResult(Rejected("AUTH_ERROR"));

        Assert.Equal(ElastiCacheIntegrationsOutcome.Origin, await pending);
        Assert.True(rig.Session.IsOpen);
        Assert.DoesNotContain(ElastiCacheIntegrationsEvents.AuthDegraded, rig.Logger.Ids);
    }

    [Fact]
    public async Task ReauthenticatesTheLiveConnectionOnTheInterval()
    {
        await using var rig = await SessionRig.StartAsync(SessionRig.Options(reauth: TimeSpan.FromMinutes(1)));

        await rig.EventuallyAsync(() => rig.Connection.Reauths >= 2, TimeSpan.FromSeconds(10));
    }

    [Fact]
    public async Task AFailedReauthKeepsServing()
    {
        await using var rig = await SessionRig.StartAsync(SessionRig.Options(reauth: TimeSpan.FromMinutes(1)));
        rig.Connection.OnReauth = _ => Task.FromException(new ElastiCacheIntegrationsServerException("WRONGPASS", "no"));

        await rig.EventuallyAsync(() => rig.Connection.Reauths >= 2, TimeSpan.FromSeconds(10));

        Assert.Equal(OutcomeKind.Served, (await rig.SendAsync()).Kind);
        Assert.Contains(ElastiCacheIntegrationsEvents.PeriodicFailed, rig.Logger.Ids);
    }

    [Fact]
    public async Task DisposeClosesTheConnectionAndRefusesRequests()
    {
        var rig = await SessionRig.StartAsync();

        await rig.Session.DisposeAsync();
        await rig.Session.DisposeAsync();
        rig.Session.Dispose();

        Assert.True(rig.Connection.Disposed);
        Assert.False(rig.Session.IsOpen);
        await Assert.ThrowsAsync<ObjectDisposedException>(() => rig.SendAsync());
    }

    [Fact]
    public async Task SyncDisposeClosesTheConnection()
    {
        var rig = await SessionRig.StartAsync();

        rig.Session.Dispose();

        Assert.True(rig.Connection.Disposed);
    }
}
