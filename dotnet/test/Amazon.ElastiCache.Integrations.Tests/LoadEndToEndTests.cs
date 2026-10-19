using System.Collections.Concurrent;
using System.Runtime.CompilerServices;
using System.Text.Json;
using Amazon.DynamoDBv2;
using Amazon.DynamoDBv2.Model;
using static Amazon.ElastiCache.Integrations.Tests.SdkEndToEndTests;

namespace Amazon.ElastiCache.Integrations.Tests;

/// <summary>Many calls on one connection, large items, and cancelled calls, through the SDK.</summary>
public class LoadEndToEndTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    private static GetItemRequest Get(string id) => new() { TableName = "t", Key = new() { ["id"] = new AttributeValue { S = id } } };

    private static string KeyOf(string[] request) => JsonDocument.Parse(request[4]).RootElement.GetProperty("Key").GetProperty("id").GetProperty("S").GetString()!;

    private static RespValue Echo(string[] request) => Cached(ItemJson("id", KeyOf(request)));

    private static string ItemJson(string attribute, string value) => $$$"""{"Item":{"{{{attribute}}}":{"S":"{{{value}}}"}}""" + "}";

    private static Task<ElastiCacheIntegrationsDynamoDBClient> ClientAsync(CacheServer server, int? maxResponseSize = null, ListLogger? logger = null) =>
        ElastiCacheIntegrationsClient.CreateDynamoDBClientAsync(
            server.Options(logger: logger, maxResponseSize: maxResponseSize),
            new AmazonDynamoDBConfig { RegionEndpoint = RegionEndpoint.USEast1, MaxErrorRetry = 0 },
            Credentials,
            Token);

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task ParallelCallsOnOneConnectionEachGetTheirOwnReply(bool resp3)
    {
        const int calls = 64;
        var replies = new ConcurrentQueue<(string[] Request, TaskCompletionSource<RespValue> Reply)>();
        await using var server = new CacheServer(resp3: resp3);
        server.OnRequest = request =>
        {
            if (!resp3)
            {
                return Echo(request);
            }

            var reply = new TaskCompletionSource<RespValue>(TaskCreationOptions.RunContinuationsAsynchronously);
            replies.Enqueue((request, reply));
            return new MockReply.Deferred(reply.Task);
        };
        await using var client = await ClientAsync(server);

        var results = Enumerable.Range(0, calls).Select(i => client.GetItemAsync(Get($"k{i}"), Token)).ToArray();
        if (resp3)
        {
            // Every reply is held until all requests are in, then sent newest first.
            await WaitUntilAsync(() => replies.Count == calls);
            foreach (var (request, reply) in replies.Reverse())
            {
                reply.SetResult(Echo(request));
            }
        }

        var items = await Task.WhenAll(results);
        Assert.Equal(Enumerable.Range(0, calls).Select(i => $"k{i}"), items.Select(r => r.Item["id"].S));
        Assert.Equal(calls, server.Requests.Count);
        Assert.Equal(1, server.Connections);
    }

    [Fact]
    public async Task LargeItemsCrossUnchanged()
    {
        var big = new string('x', 400 * 1024);
        var items = string.Join(",", Enumerable.Range(0, 5).Select(i => $$$"""{"id":{"S":"{{{i}}}"},"v":{"S":"{{{big}}}"}}"""));
        await using var server = new CacheServer { OnRequest = r => r[5].EndsWith("PutItem", StringComparison.Ordinal) ? Cached("{}") : Cached($$"""{"Count":5,"Items":[{{items}}]}""") };
        await using var client = await ClientAsync(server);

        await client.PutItemAsync(new PutItemRequest { TableName = "t", Item = new() { ["id"] = new AttributeValue { S = "a" }, ["v"] = new AttributeValue { S = big } } }, Token);
        var query = await client.QueryAsync(
            new QueryRequest { TableName = "t", KeyConditionExpression = "id = :v", ExpressionAttributeValues = new() { [":v"] = new AttributeValue { S = "a" } } }, Token);

        Assert.Contains(big, server.Requests.First()[4]);
        Assert.Equal(5, query.Items.Count);
        Assert.All(query.Items, item => Assert.Equal(big, item["v"].S));
    }

    [Fact]
    public async Task AReplyOverMaxResponseSizeDropsTheConnectionAndTheNextCallIsServed()
    {
        var large = new StrongBox<bool>(true);
        await using var server = new CacheServer { OnRequest = r => Volatile.Read(ref large.Value) ? Cached(ItemJson("v", new string('x', 128 * 1024))) : Echo(r) };
        await using var client = await ClientAsync(server, maxResponseSize: 64 * 1024);

        var thrown = await Assert.ThrowsAsync<HttpRequestException>(() => client.GetItemAsync(Get("a"), Token));

        Assert.IsType<ElastiCacheIntegrationsProtocolException>(Assert.IsType<ElastiCacheIntegrationsConnectionLostException>(thrown.InnerException).InnerException);
        Volatile.Write(ref large.Value, false);
        await WaitUntilAsync(() => server.Connections == 2 && client.Handle.IsOpen);
        Assert.Equal("b", (await client.GetItemAsync(Get("b"), Token)).Item["id"].S);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task ACancelledCallLeavesTheConnectionUsable(bool resp3)
    {
        var first = new TaskCompletionSource<RespValue>(TaskCreationOptions.RunContinuationsAsynchronously);
        var logger = new ListLogger();
        await using var server = new CacheServer(resp3: resp3);
        // RESP2 holds the first reply in the server's loop, so later replies queue behind it.
        server.OnRequest = request => KeyOf(request) != "first"
            ? Echo(request)
            : resp3 ? new MockReply.Deferred(first.Task) : first.Task.WaitAsync(TimeSpan.FromSeconds(10), Token).GetAwaiter().GetResult();
        await using var client = await ClientAsync(server, logger: logger);
        using var cancel = CancellationTokenSource.CreateLinkedTokenSource(Token);

        var call = client.GetItemAsync(Get("first"), cancel.Token);
        await WaitUntilAsync(() => !server.Requests.IsEmpty);
        await cancel.CancelAsync();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => call);
        first.SetResult(Cached("""{"Item":{"id":{"S":"late"}}}"""));

        Assert.Equal("next", (await client.GetItemAsync(Get("next"), Token)).Item["id"].S);
        Assert.Equal(1, server.Connections);
        if (resp3)
        {
            await WaitUntilAsync(() => logger.Ids.Contains(ElastiCacheIntegrationsEvents.LatePushDropped));
        }
    }
}
