using Amazon.DynamoDBv2;
using Amazon.DynamoDBv2.Model;
using Amazon.Runtime;

namespace Amazon.ElastiCache.Integrations.Tests;

public class ElastiCacheIntegrationsClientTests
{
    private static readonly AWSCredentials Credentials = new BasicAWSCredentials("AKID", "SECRET");

    private static CancellationToken Token => TestContext.Current.CancellationToken;

    // Nothing listens here; a test that reaches the network fails.
    private static readonly ElastiCacheIntegrationsClientOptions Unreachable = new() { Endpoint = "127.0.0.1:1", CacheName = "my-cache", UseTls = false };

    [Fact]
    public async Task CreatesAClientThatOwnsItsSession()
    {
        await using var server = new CacheServer();
        var config = new AmazonDynamoDBConfig { RegionEndpoint = RegionEndpoint.USEast1 };

        await using var client = await ElastiCacheIntegrationsClient.CreateDynamoDBClientAsync(server.Options(), config, Credentials, Token);

        Assert.IsType<ElastiCacheIntegrationsHttpClientFactory>(config.HttpClientFactory);
        Assert.True(client.Handle.IsOpen);
        await client.DisposeAsync();
        Assert.False(client.Handle.IsOpen);
    }

    [Fact]
    public async Task SyncDisposeClosesTheSessionAndIsIdempotent()
    {
        await using var server = new CacheServer();
        var client = await ElastiCacheIntegrationsClient.CreateDynamoDBClientAsync(
            server.Options(), new AmazonDynamoDBConfig { RegionEndpoint = RegionEndpoint.USEast1 }, Credentials, Token);

        client.Dispose();
        client.Dispose();
        await client.DisposeAsync();

        Assert.False(client.Handle.IsOpen);
    }

    [Fact]
    public async Task OneHandleAttachesToManyConfigsButNotToOneWithAFactory()
    {
        await using var server = new CacheServer();
        await using var handle = await ElastiCacheIntegrationsHandle.OpenAsync(server.Options(), Credentials, RegionEndpoint.USEast1, cancellationToken: Token);
        var first = new AmazonDynamoDBConfig();
        var second = new AmazonDynamoDBConfig();

        handle.Attach(first);
        handle.Attach(second);

        Assert.IsType<ElastiCacheIntegrationsHttpClientFactory>(first.HttpClientFactory);
        Assert.IsType<ElastiCacheIntegrationsHttpClientFactory>(second.HttpClientFactory);
        Assert.Throws<ElastiCacheIntegrationsConfigurationException>(() => handle.Attach(first));
    }

    [Fact]
    public async Task RefusesNullAndBadOptionsBeforeConnecting()
    {
        await Assert.ThrowsAsync<ArgumentNullException>(() => ElastiCacheIntegrationsClient.CreateDynamoDBClientAsync(null!, cancellationToken: Token));
        var bad = new ElastiCacheIntegrationsClientOptions { Endpoint = "no-port", CacheName = "my-cache" };
        await Assert.ThrowsAsync<ElastiCacheIntegrationsConfigurationException>(() => ElastiCacheIntegrationsClient.CreateDynamoDBClientAsync(bad, new AmazonDynamoDBConfig { RegionEndpoint = RegionEndpoint.USEast1 }, Credentials, Token));
    }

    [Fact]
    public async Task RefusesAConfigWithItsOwnFactoryBeforeConnecting()
    {
        var config = new AmazonDynamoDBConfig { RegionEndpoint = RegionEndpoint.USEast1, HttpClientFactory = new ElastiCacheIntegrationsHttpClientFactory((_, _) => null!, "dynamodb", null, () => { }) };

        await Assert.ThrowsAsync<ElastiCacheIntegrationsConfigurationException>(() => ElastiCacheIntegrationsClient.CreateDynamoDBClientAsync(Unreachable, config, Credentials, Token));
    }

    [Fact]
    public async Task RefusesARegionMismatchBeforeConnecting()
    {
        var options = new ElastiCacheIntegrationsClientOptions { Endpoint = Unreachable.Endpoint, CacheName = "my-cache", UseTls = false, Region = "us-west-2" };
        var config = new AmazonDynamoDBConfig { RegionEndpoint = RegionEndpoint.USEast1 };

        var thrown = await Assert.ThrowsAsync<ElastiCacheIntegrationsConfigurationException>(() => ElastiCacheIntegrationsClient.CreateDynamoDBClientAsync(options, config, Credentials, Token));

        Assert.Contains("us-west-2", thrown.Message);
        Assert.Null(config.HttpClientFactory);
    }

    [Fact]
    public async Task AFailedBringUpThrowsAndAttachesNothing()
    {
        var options = new ElastiCacheIntegrationsClientOptions
        {
            Endpoint = Unreachable.Endpoint,
            CacheName = "my-cache",
            UseTls = false,
            AuthProvider = new ConnectionTests.StaticAuth("user", "token"),
            Reconnect = new ElastiCacheIntegrationsReconnectPolicy { ConnectAttempts = 1 },
        };
        var config = new AmazonDynamoDBConfig { RegionEndpoint = RegionEndpoint.USEast1 };

        await Assert.ThrowsAnyAsync<ElastiCacheIntegrationsConnectionException>(() => ElastiCacheIntegrationsClient.CreateDynamoDBClientAsync(options, config, Credentials, Token));
        Assert.Null(config.HttpClientFactory);
    }

    [Fact]
    public async Task AFailedBringUpLeavesTheRegionUnset()
    {
        var options = new ElastiCacheIntegrationsClientOptions
        {
            Endpoint = Unreachable.Endpoint,
            CacheName = "my-cache",
            UseTls = false,
            Region = "us-west-2",
            AuthProvider = new ConnectionTests.StaticAuth("user", "token"),
            Reconnect = new ElastiCacheIntegrationsReconnectPolicy { ConnectAttempts = 1 },
        };
        var config = new AmazonDynamoDBConfig();

        await Assert.ThrowsAnyAsync<ElastiCacheIntegrationsConnectionException>(() => ElastiCacheIntegrationsClient.CreateDynamoDBClientAsync(options, config, Credentials, Token));
        Assert.Null(config.RegionEndpoint);
    }

    [Fact]
    public async Task CopiesTheRegionIntoAConfigWithoutOne()
    {
        await using var server = new CacheServer();
        var options = server.Options();
        var config = new AmazonDynamoDBConfig();

        await using var client = await ElastiCacheIntegrationsClient.CreateDynamoDBClientAsync(
            new ElastiCacheIntegrationsClientOptions { Endpoint = options.Endpoint, CacheName = "my-cache", UseTls = false, AuthProvider = options.AuthProvider, Region = "us-west-2" },
            config, Credentials, Token);

        Assert.Equal(RegionEndpoint.USWest2, config.RegionEndpoint);
    }

    [Fact]
    public async Task KeepsAServiceUrl()
    {
        await using var server = new CacheServer();
        var options = server.Options();
        var config = new AmazonDynamoDBConfig { ServiceURL = "http://localhost:8000" };

        await using var client = await ElastiCacheIntegrationsClient.CreateDynamoDBClientAsync(
            new ElastiCacheIntegrationsClientOptions { Endpoint = options.Endpoint, CacheName = "my-cache", UseTls = false, AuthProvider = options.AuthProvider, Region = "us-west-2" },
            config, Credentials, Token);

        Assert.Null(config.RegionEndpoint);
        Assert.Equal("http://localhost:8000/", config.ServiceURL);
    }

    [Fact]
    public async Task AClosedHandleRefusesAttachAndSendsRequestsToTheOrigin()
    {
        await using var server = new CacheServer();
        var origin = new HttpHandlerTests.RecordingOrigin();
        var handle = await ElastiCacheIntegrationsHandle.OpenAsync(server.Options(origin), Credentials, RegionEndpoint.USEast1, cancellationToken: Token);
        var config = new AmazonDynamoDBConfig { RegionEndpoint = RegionEndpoint.USEast1 };
        handle.Attach(config);
        using var client = new AmazonDynamoDBClient(Credentials, config);

        handle.Dispose();

        Assert.False(handle.IsOpen);
        Assert.Throws<ObjectDisposedException>(() => handle.Attach(new AmazonDynamoDBConfig()));
        await client.GetItemAsync(new GetItemRequest { TableName = "t", Key = new() { ["pk"] = new AttributeValue { S = "a" } } }, Token);
        Assert.Equal(1, origin.Calls);
        Assert.Empty(server.Requests);
    }
}
