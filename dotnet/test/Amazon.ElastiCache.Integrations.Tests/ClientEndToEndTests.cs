// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using System.Security.Cryptography.X509Certificates;
using Amazon.DynamoDBv2;
using Amazon.DynamoDBv2.Model;
using Amazon.Runtime;
using static Amazon.ElastiCache.Integrations.Tests.SdkEndToEndTests;

namespace Amazon.ElastiCache.Integrations.Tests;

/// <summary>The Node <c>client-e2e</c> and Python <c>test_ddb</c> suites, through the SDK's public API.</summary>
public class ClientEndToEndTests
{
    private static readonly Dictionary<string, AttributeValue> Id = new() { ["id"] = new AttributeValue { S = "42" } };

    private static CancellationToken Token => TestContext.Current.CancellationToken;

    private static GetItemRequest Get(string table = "my-table") => new() { TableName = table, Key = Id };

    [Fact]
    public async Task GetItemIsServedOverVerifyingTlsWithAnIamToken()
    {
        using var certificate = TestCertificate.Create();
        await using var server = new CacheServer(certificate: certificate) { OnRequest = _ => Cached("""{"Item":{"id":{"S":"42"},"name":{"S":"widget"}}}""") };
        var origin = new HttpHandlerTests.RecordingOrigin();
        var options = new ElastiCacheIntegrationsClientOptions
        {
            Endpoint = server.Endpoint,
            CacheName = "My-Cache",
            UseTls = true,
            CaCertificates = [certificate],
            OriginHandler = origin,
        };
        var credentials = new SessionAWSCredentials("AKID", "SECRET", "SESSION");
        var config = new AmazonDynamoDBConfig { RegionEndpoint = RegionEndpoint.USEast1, MaxErrorRetry = 0 };
        await using var client = await ElastiCacheIntegrationsClient.CreateDynamoDBClientAsync(options, config, credentials, Token);

        var result = await client.GetItemAsync(Get(), Token);

        Assert.Equal("widget", result.Item["name"].S);
        Assert.Equal(["AUTH", "HELLO", "aws.config", "aws.request"], server.Commands.Select(c => c[0]));
        var auth = server.Commands.First();
        Assert.Equal("default.iam-user", auth[1]);
        Assert.StartsWith("my-cache/?Action=connect&User=default.iam-user&ResourceType=ServerlessCache&", auth[2]);
        Assert.Contains("X-Amz-Security-Token=SESSION", auth[2]);
        var request = Assert.Single(server.Requests);
        Assert.Equal(["aws.request", "POST", "https://dynamodb.us-east-1.amazonaws.com/"], request[..3]);
        var headers = request[3].Split('\n');
        Assert.Contains("x-amz-target:DynamoDB_20120810.GetItem", headers);
        Assert.Contains("content-type:application/x-amz-json-1.0", headers);
        Assert.DoesNotContain(headers, h => h.Split(':')[0] is "authorization" or "x-amz-date" or "x-amz-security-token");
        Assert.Equal("""{"Key":{"id":{"S":"42"}},"TableName":"my-table"}""", request[4]);
        Assert.Equal("service=dynamodb&op=GetItem", request[5]);
        Assert.Equal(0, origin.Calls);
    }

    [Fact]
    public async Task RootsDisposedAfterOpenStillVerifyAReconnect()
    {
        using var certificate = TestCertificate.Create();
        await using var server = new CacheServer(certificate: certificate) { OnRequest = _ => Cached("""{"Item":{"id":{"S":"42"}}}""") };
        var roots = new X509Certificate2Collection { new X509Certificate2(certificate.RawData) };
        var options = new ElastiCacheIntegrationsClientOptions
        {
            Endpoint = server.Endpoint,
            CacheName = "my-cache",
            CaCertificates = roots,
            AuthProvider = new ConnectionTests.StaticAuth("user", "token"),
            OriginHandler = new HttpHandlerTests.RecordingOrigin(),
            ReconnectWait = TimeSpan.FromSeconds(5),
            Reconnect = new ElastiCacheIntegrationsReconnectPolicy { BaseDelay = TimeSpan.FromMilliseconds(10), MaxDelay = TimeSpan.FromMilliseconds(50) },
        };
        await using var client = await ElastiCacheIntegrationsClient.CreateDynamoDBClientAsync(options, new AmazonDynamoDBConfig { RegionEndpoint = RegionEndpoint.USEast1, MaxErrorRetry = 0 }, Credentials, Token);
        foreach (var root in roots)
        {
            root.Dispose();
        }

        roots.Clear();
        server.Drop();
        await WaitUntilAsync(() => server.Connections == 2 && client.Handle.IsOpen);

        await client.GetItemAsync(Get(), Token);
        Assert.Single(server.Requests);
    }

    [Fact]
    public async Task ARootDisposedBeforeOpenIsAConfigurationError()
    {
        await using var server = new CacheServer();
        using var certificate = TestCertificate.Create();
        var root = new X509Certificate2(certificate.RawData);
        root.Dispose();
        var options = new ElastiCacheIntegrationsClientOptions { Endpoint = server.Endpoint, CacheName = "my-cache", CaCertificates = [root] };

        await Assert.ThrowsAsync<ElastiCacheIntegrationsConfigurationException>(() => ElastiCacheIntegrationsClient.CreateDynamoDBClientAsync(
            options, new AmazonDynamoDBConfig { RegionEndpoint = RegionEndpoint.USEast1 }, Credentials, Token));
        Assert.Equal(0, server.Connections);
    }

    [Fact]
    public async Task AConfigWithoutPredicatesFailsOpen()
    {
        await using var server = new CacheServer("""{"version":"1.0","refresh_interval":60}""");

        var error = await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionException>(() => ClientAsync(server, new HttpHandlerTests.RecordingOrigin()));

        Assert.Equal("aws.config failed during bring-up: Unusable aws.config reply: it has no predicates.", error.Message);
        Assert.Equal(3, server.Connections);
    }

    [Theory]
    [InlineData("""{"version":"1.0","predicates":[]}""", "refresh_interval is missing")]
    [InlineData("""{"version":"1.0","refresh_interval":0,"predicates":[]}""", "refresh_interval must be a positive whole number of seconds")]
    public async Task AConfigWithoutAUsableRefreshIntervalFailsEveryAttempt(string config, string reason)
    {
        await using var server = new CacheServer(config);

        var error = await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionException>(() => ClientAsync(server, new HttpHandlerTests.RecordingOrigin()));

        Assert.Contains(reason, error.Message);
        Assert.Equal(3, server.Connections);
    }

    [Fact]
    public async Task MovedAfterTheCacheAcceptedACallIsRetriedByTheSdkOnANewConnection()
    {
        var calls = 0;
        await using var server = new CacheServer
        {
            OnRequest = _ => Interlocked.Increment(ref calls) == 1
                ? MockReply.Later(new RespError("MOVED", "3999 127.0.0.1:6381"))
                : Cached("""{"Item":{"id":{"S":"value"}}}"""),
        };
        var origin = new HttpHandlerTests.RecordingOrigin();
        await using var client = await ClientAsync(server, origin, maxErrorRetry: 1);

        var result = await client.GetItemAsync(Get(), Token);

        Assert.Equal("value", result.Item["id"].S);
        Assert.Equal(2, server.Requests.Count);
        Assert.Equal(2, server.Connections);
        Assert.Equal(0, origin.Calls);
    }

    [Fact]
    public async Task MovedAfterTheCacheAcceptedACallIsNotResentWithoutAnSdkRetry()
    {
        await using var server = new CacheServer { OnRequest = _ => MockReply.Later(new RespError("MOVED", "3999 127.0.0.1:6381")) };
        var origin = new HttpHandlerTests.RecordingOrigin();
        await using var client = await ClientAsync(server, origin);

        var error = await Assert.ThrowsAsync<HttpRequestException>(() => client.GetItemAsync(Get(), Token));

        Assert.IsType<ElastiCacheIntegrationsConnectionLostException>(error.InnerException);
        Assert.Single(server.Requests);
        Assert.Equal(0, origin.Calls);
    }

    [Fact]
    public async Task TwoCallsReuseOneConnection()
    {
        await using var server = new CacheServer();
        await using var client = await ClientAsync(server, new HttpHandlerTests.RecordingOrigin());

        await client.GetItemAsync(Get(), Token);
        await client.GetItemAsync(Get(), Token);

        Assert.Single(server.Overrides);
        Assert.Equal(2, server.Requests.Count);
        Assert.Equal(1, server.Connections);
    }

    [Fact]
    public async Task ACachedServerErrorIsRetriedThroughTheCacheUntilItSucceeds()
    {
        const string error = """{"__type":"com.amazonaws.dynamodb.v20120810#InternalServerError","message":"retry once"}""";
        var replies = 0;
        await using var server = new CacheServer
        {
            OnRequest = _ => Interlocked.Increment(ref replies) == 1 ? Cached(error, 500) : Cached("""{"Item":{"id":{"S":"value"}}}"""),
        };
        var origin = new HttpHandlerTests.RecordingOrigin();
        await using var client = await ClientAsync(server, origin, maxErrorRetry: 1);

        var result = await client.GetItemAsync(Get(), Token);

        Assert.Equal("value", result.Item["id"].S);
        Assert.Equal(2, server.Requests.Count);
        Assert.All(server.Requests, r => Assert.Equal(server.Requests.First(), r));
        Assert.Equal(0, origin.Calls);
    }

    [Fact]
    public async Task ARequestThePredicatesDeclineGoesToDynamoDBSigned()
    {
        await using var server = new CacheServer("""{"version":"1.0","refresh_interval":60,"predicates":[{"expr":"operation == 'GetItem' && body.TableName == 'ElastiCacheIntegrationsTest'"}]}""");
        var origin = new HttpHandlerTests.RecordingOrigin("""{"Item":{"id":{"S":"origin"}}}""");
        await using var client = await ClientAsync(server, origin);

        var result = await client.GetItemAsync(Get("other-table"), Token);

        Assert.Equal("origin", result.Item["id"].S);
        Assert.Equal(1, origin.Calls);
        Assert.True(origin.LastSigned);
        Assert.Equal(["AUTH", "HELLO", "aws.config"], server.Commands.Select(c => c[0]));
    }

    [Fact]
    public async Task DisposeStopsRoutingAndIsIdempotent()
    {
        await using var server = new CacheServer();
        var origin = new HttpHandlerTests.RecordingOrigin();
        var client = await ClientAsync(server, origin);
        try
        {
            await client.GetItemAsync(Get(), Token);

            await client.DisposeAsync();
            await client.DisposeAsync();
            client.Dispose();

            Assert.False(client.Handle.IsOpen);
            await Assert.ThrowsAsync<ObjectDisposedException>(() => client.GetItemAsync(Get(), Token));
            Assert.Single(server.Requests);
            Assert.Equal(0, origin.Calls);
        }
        finally
        {
            client.Dispose();
        }
    }

    [Theory]
    [InlineData(1)]
    [InlineData(32)]
    public async Task ClosingTheClientFailsARequestInFlight(int closers)
    {
        await using var server = new CacheServer { OnRequest = _ => new MockReply.Hang() };
        var client = await ElastiCacheIntegrationsClient.CreateDynamoDBClientAsync(
            server.Options(new HttpHandlerTests.RecordingOrigin(), requestTimeout: TimeSpan.FromSeconds(30)),
            new AmazonDynamoDBConfig { RegionEndpoint = RegionEndpoint.USEast1, MaxErrorRetry = 0 },
            Credentials,
            Token);

        var call = client.GetItemAsync(Get(), Token);
        try
        {
            await WaitUntilAsync(() => !server.Requests.IsEmpty);
            await Task.WhenAll(Enumerable.Range(0, closers).Select(_ => Task.Run(client.Dispose, Token)));
        }
        finally
        {
            client.Dispose();
        }

        // The close also disposes the SDK's HttpClient, which reports a call it was running as cancelled.
        var thrown = await Assert.ThrowsAnyAsync<Exception>(() => call);
        Assert.True(
            thrown is OperationCanceledException or HttpRequestException { InnerException: ElastiCacheIntegrationsConnectionLostException },
            thrown.ToString());
    }

    [Fact]
    public async Task ARespTwoCacheServesInBand()
    {
        await using var server = new CacheServer(resp3: false) { OnRequest = _ => Cached("""{"Item":{"id":{"S":"resp2"}}}""") };
        var logger = new ListLogger();
        var origin = new HttpHandlerTests.RecordingOrigin();
        await using var client = await ElastiCacheIntegrationsClient.CreateDynamoDBClientAsync(
            server.Options(origin, logger: logger), new AmazonDynamoDBConfig { RegionEndpoint = RegionEndpoint.USEast1 }, Credentials, Token);

        Assert.Equal("resp2", (await client.GetItemAsync(Get(), Token)).Item["id"].S);
        Assert.Contains(ElastiCacheIntegrationsEvents.Resp2Fallback, logger.Ids);
        Assert.Equal(0, origin.Calls);
    }
}
