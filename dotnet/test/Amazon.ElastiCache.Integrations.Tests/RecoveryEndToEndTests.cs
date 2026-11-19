// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using System.Runtime.CompilerServices;
using Amazon.DynamoDBv2;
using Amazon.DynamoDBv2.Model;
using Microsoft.Extensions.Time.Testing;
using static Amazon.ElastiCache.Integrations.Tests.SdkEndToEndTests;

namespace Amazon.ElastiCache.Integrations.Tests;

/// <summary>Reconnect, bring-up faults, re-AUTH and config refresh, through the SDK.</summary>
public class RecoveryEndToEndTests
{
    private const string Refreshing =
        """{"version":"1.0","refresh_interval":60,"predicates":[{"expr":"operation in ['GetItem','Query']"}]}""";

    /// <summary>Longer than any clock step, so a slow reply is never timed out by a jump of the fake clock.</summary>
    private static readonly TimeSpan FakeClockConfigTimeout = TimeSpan.FromMinutes(5);

    private static CancellationToken Token => TestContext.Current.CancellationToken;

    private static GetItemRequest Get() => new() { TableName = "t", Key = Key };

    private static AmazonDynamoDBConfig SdkConfig() => new() { RegionEndpoint = RegionEndpoint.USEast1, MaxErrorRetry = 0 };

    private static string[] Auths(CacheServer server) => [.. server.Commands.Where(c => c[0] == "AUTH").Select(c => c[2])];

    /// <summary>A client whose session runs on <paramref name="time"/>, over a real connection.</summary>
    private static async Task<(AmazonDynamoDBClient Client, ElastiCacheIntegrationsHandle Handle)> FakeTimeClientAsync(
        ElastiCacheIntegrationsClientOptions options, FakeTimeProvider time, ListLogger logger)
    {
        var overrides = ElastiCacheIntegrationsProtocol.SerializeOverrides(options.Overrides);
        var session = await ElastiCacheIntegrationsSession.OpenAsync(
            options,
            async ct => await ElastiCacheIntegrationsConnection.OpenAsync(options, options.AuthProvider!, overrides, logger, time, ct),
            "dynamodb",
            logger,
            time,
            new Random(1),
            Token);
        var handle = new ElastiCacheIntegrationsHandle(session, options);
        var config = SdkConfig();
        handle.Attach(config);
        return (new AmazonDynamoDBClient(Credentials, config), handle);
    }

    private static async Task AdvanceUntilAsync(FakeTimeProvider time, Func<bool> condition, TimeSpan step)
    {
        for (var i = 0; i < 500 && !condition(); i++)
        {
            time.Advance(step);
            await Task.Delay(10, Token);
        }

        Assert.True(condition(), "the condition never held");
    }

    [Fact]
    public async Task ADropRightAfterAReplyReconnectsWithAFreshTokenAndServesAgain()
    {
        var drop = new StrongBox<bool>(true);
        await using var server = new CacheServer { OnRequest = _ => Volatile.Read(ref drop.Value) ? new MockReply.Drop(Cached("""{"Item":{"pk":{"S":"one"}}}""")) : Cached("""{"Item":{"pk":{"S":"two"}}}""") };
        var auth = new ConnectionTests.StaticAuth("user", "token-1", "token-2");
        await using var client = await ElastiCacheIntegrationsClient.CreateDynamoDBClientAsync(server.Options(auth: auth), SdkConfig(), Credentials, Token);

        Assert.Equal("one", (await client.GetItemAsync(Get(), Token)).Item["pk"].S);
        Volatile.Write(ref drop.Value, false);
        await WaitUntilAsync(() => server.Connections == 2 && client.Handle.IsOpen);

        Assert.Equal("two", (await client.GetItemAsync(Get(), Token)).Item["pk"].S);
        Assert.Equal(["token-1", "token-2"], Auths(server));
        Assert.Equal(2, server.Overrides.Count);
    }

    [Fact]
    public async Task AHungCacheTimesTheCallOutThenStallsAndIsReplaced()
    {
        var hang = new StrongBox<bool>(true);
        var logger = new ListLogger();
        await using var server = new CacheServer { OnRequest = _ => Volatile.Read(ref hang.Value) ? new MockReply.Hang() : Cached("""{"Item":{"pk":{"S":"served"}}}""") };
        await using var client = await ElastiCacheIntegrationsClient.CreateDynamoDBClientAsync(
            server.Options(requestTimeout: TimeSpan.FromMilliseconds(100), logger: logger, headStallTimeout: TimeSpan.FromSeconds(1)), SdkConfig(), Credentials, Token);

        var thrown = await Assert.ThrowsAsync<HttpRequestException>(() => client.GetItemAsync(Get(), Token));

        Assert.IsType<ElastiCacheIntegrationsTimeoutException>(thrown.InnerException);
        Volatile.Write(ref hang.Value, false);
        await WaitUntilAsync(() => server.Connections == 2 && client.Handle.IsOpen);
        Assert.Contains(ElastiCacheIntegrationsEvents.HeadStalled, logger.Ids);
        Assert.Equal("served", (await client.GetItemAsync(Get(), Token)).Item["pk"].S);
    }

    [Fact]
    public async Task ALoggerThatThrowsDoesNotStopAStallFromBeingReplaced()
    {
        var hang = new StrongBox<bool>(true);
        await using var server = new CacheServer { OnRequest = _ => Volatile.Read(ref hang.Value) ? new MockReply.Hang() : Cached("""{"Item":{"pk":{"S":"served"}}}""") };
        var logger = new ThrowingLogger(enabled: true);
        var options = server.Options(requestTimeout: TimeSpan.FromMilliseconds(100), logger: logger, headStallTimeout: TimeSpan.FromSeconds(1));
        await using var client = await ElastiCacheIntegrationsClient.CreateDynamoDBClientAsync(options, SdkConfig(), Credentials, Token);

        var thrown = await Assert.ThrowsAsync<HttpRequestException>(() => client.GetItemAsync(Get(), Token));

        Assert.IsType<ElastiCacheIntegrationsTimeoutException>(thrown.InnerException);
        Volatile.Write(ref hang.Value, false);
        await WaitUntilAsync(() => server.Connections == 2 && client.Handle.IsOpen);
        Assert.Contains(ElastiCacheIntegrationsEvents.HeadStalled, logger.Attempted);
        Assert.Equal("served", (await client.GetItemAsync(Get(), Token)).Item["pk"].S);
    }

    [Theory]
    [InlineData("AUTH", "WRONGPASS", typeof(ElastiCacheIntegrationsConnectionException), 3)]
    [InlineData("HELLO", "NOAUTH", typeof(ElastiCacheIntegrationsConnectionException), 3)]
    [InlineData("aws.config", "CONFIG_INVALID", typeof(ElastiCacheIntegrationsConfigurationException), 1)]
    public async Task ABringUpFaultFailsClientCreation(string command, string code, Type expected, int attempts)
    {
        await using var server = new CacheServer { Intercept = c => c[0] == command ? new MockReply.Now(new RespError(code, "injected")) : null };
        var config = SdkConfig();

        var thrown = await Assert.ThrowsAnyAsync<ElastiCacheIntegrationsException>(() => ElastiCacheIntegrationsClient.CreateDynamoDBClientAsync(server.Options(), config, Credentials, Token));

        Assert.IsType(expected, thrown);
        Assert.Equal(attempts, server.Commands.Count(c => c[0] == command));
        Assert.Null(config.HttpClientFactory);
    }

    [Fact]
    public async Task ADropDuringTheHandshakeIsRetried()
    {
        var drops = 0;
        await using var server = new CacheServer { Intercept = c => c[0] == "HELLO" && Interlocked.Increment(ref drops) == 1 ? new MockReply.Drop() : null };

        await using var client = await ElastiCacheIntegrationsClient.CreateDynamoDBClientAsync(server.Options(), SdkConfig(), Credentials, Token);

        Assert.Equal(1, server.Connections);
        Assert.Equal(2, server.Commands.Count(c => c[0] == "AUTH"));
        await client.GetItemAsync(Get(), Token);
    }

    [Fact]
    public async Task ReauthUsesAFreshTokenOnTheSameConnection()
    {
        await using var server = new CacheServer();
        var time = new FakeTimeProvider();
        var logger = new ListLogger();
        var (client, handle) = await FakeTimeClientAsync(
            server.Options(configTimeout: FakeClockConfigTimeout, auth: new ConnectionTests.StaticAuth("user", "token-1", "token-2")), time, logger);
        await using var _ = handle;
        using var __ = client;

        await AdvanceUntilAsync(time, () => Auths(server).Length == 2, TimeSpan.FromMinutes(1));

        Assert.Equal(["token-1", "token-2"], Auths(server));
        await client.GetItemAsync(Get(), Token);
        Assert.Equal(1, server.Connections);
        Assert.Single(server.Requests);
    }

    [Fact]
    public async Task ConfigRefreshKeepsAnUnchangedConfigAndAppliesANewOne()
    {
        var refreshes = 0;
        await using var server = new CacheServer(Refreshing);
        server.OnRefresh = () => Interlocked.Increment(ref refreshes) <= 2 ? null : Refreshing.Replace("'GetItem',", "", StringComparison.Ordinal);
        var origin = new HttpHandlerTests.RecordingOrigin();
        var time = new FakeTimeProvider();
        var logger = new ListLogger();
        var (client, handle) = await FakeTimeClientAsync(server.Options(origin, configTimeout: FakeClockConfigTimeout), time, logger);
        await using var _ = handle;
        using var __ = client;

        // Two unchanged refreshes, then a config without GetItem.
        await AdvanceUntilAsync(time, () => Volatile.Read(ref refreshes) == 2, TimeSpan.FromSeconds(5));
        await client.GetItemAsync(Get(), Token);
        Assert.Single(server.Requests);
        Assert.Single(logger.Ids, id => id == ElastiCacheIntegrationsEvents.ConfigApplied);
        Assert.DoesNotContain(ElastiCacheIntegrationsEvents.PeriodicFailed, logger.Ids);

        await AdvanceUntilAsync(time, () => logger.Ids.Count(id => id == ElastiCacheIntegrationsEvents.ConfigApplied) == 2, TimeSpan.FromSeconds(5));
        await client.GetItemAsync(Get(), Token);
        Assert.Single(server.Requests);
        Assert.Equal(1, origin.Calls);
        Assert.Equal(1, server.Connections);
    }
}
