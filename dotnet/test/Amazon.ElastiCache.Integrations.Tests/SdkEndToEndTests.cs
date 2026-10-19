using System.Text;
using Amazon.DynamoDBv2;
using Amazon.DynamoDBv2.Model;
using Amazon.Runtime;

namespace Amazon.ElastiCache.Integrations.Tests;

/// <summary>A <see cref="ElastiCacheIntegrationsDynamoDBClient"/> over a real session and <see cref="CacheServer"/>, through the SDK's public API.</summary>
public class SdkEndToEndTests
{
    internal static readonly AWSCredentials Credentials = new BasicAWSCredentials("AKID", "SECRET");

    internal static readonly Dictionary<string, AttributeValue> Key = new() { ["pk"] = new AttributeValue { S = "a" } };

    private static readonly Dictionary<string, AttributeValue> Item = new() { ["pk"] = new AttributeValue { S = "a" }, ["v"] = new AttributeValue { N = "1" } };

    private static CancellationToken Token => TestContext.Current.CancellationToken;

    internal static RespValue Cached(string body, int status = 200) =>
        new RespArray(
        [
            new RespInteger(status),
            new RespBlobString("content-type:application/x-amz-json-1.0\nx-amzn-requestid:cached"u8.ToArray()),
            new RespBlobString(Encoding.UTF8.GetBytes(body)),
        ]);

    internal static Task<ElastiCacheIntegrationsDynamoDBClient> ClientAsync(CacheServer server, HttpMessageHandler origin, TimeSpan? fallbackDuration = null, int maxErrorRetry = 0) =>
        ElastiCacheIntegrationsClient.CreateDynamoDBClientAsync(
            server.Options(origin, fallbackDuration),
            new AmazonDynamoDBConfig { RegionEndpoint = RegionEndpoint.USEast1, MaxErrorRetry = maxErrorRetry },
            Credentials,
            Token);

    private static Task<AmazonWebServiceResponse> CallAsync(IAmazonDynamoDB client, string operation) => operation switch
    {
        "GetItem" => Cast(client.GetItemAsync(new GetItemRequest { TableName = "t", Key = Key }, Token)),
        "Query" => Cast(client.QueryAsync(
            new QueryRequest { TableName = "t", KeyConditionExpression = "pk = :v", ExpressionAttributeValues = new() { [":v"] = new AttributeValue { S = "a" } } }, Token)),
        "Scan" => Cast(client.ScanAsync(new ScanRequest { TableName = "t" }, Token)),
        "PutItem" => Cast(client.PutItemAsync(new PutItemRequest { TableName = "t", Item = Item }, Token)),
        "UpdateItem" => Cast(client.UpdateItemAsync(
            new UpdateItemRequest { TableName = "t", Key = Key, UpdateExpression = "SET v = :v", ExpressionAttributeValues = new() { [":v"] = new AttributeValue { N = "2" } } }, Token)),
        "DeleteItem" => Cast(client.DeleteItemAsync(new DeleteItemRequest { TableName = "t", Key = Key }, Token)),
        "BatchGetItem" => Cast(client.BatchGetItemAsync(new BatchGetItemRequest { RequestItems = new() { ["t"] = new KeysAndAttributes { Keys = [Key] } } }, Token)),
        "BatchWriteItem" => Cast(client.BatchWriteItemAsync(
            new BatchWriteItemRequest { RequestItems = new() { ["t"] = [new WriteRequest { PutRequest = new PutRequest { Item = Item } }] } }, Token)),
        "TransactGetItems" => Cast(client.TransactGetItemsAsync(
            new TransactGetItemsRequest { TransactItems = [new TransactGetItem { Get = new Get { TableName = "t", Key = Key } }] }, Token)),
        "TransactWriteItems" => Cast(client.TransactWriteItemsAsync(
            new TransactWriteItemsRequest { TransactItems = [new TransactWriteItem { Put = new Put { TableName = "t", Item = Item } }] }, Token)),
        _ => throw new ArgumentOutOfRangeException(nameof(operation)),
    };

    private static async Task<AmazonWebServiceResponse> Cast<T>(Task<T> call)
        where T : AmazonWebServiceResponse => await call;

    internal static async Task WaitUntilAsync(Func<bool> condition)
    {
        for (var i = 0; i < 500 && !condition(); i++)
        {
            await Task.Delay(10, Token);
        }

        Assert.True(condition(), "the condition never held");
    }

    [Theory]
    [InlineData("GetItem")]
    [InlineData("Query")]
    [InlineData("Scan")]
    [InlineData("PutItem")]
    [InlineData("UpdateItem")]
    [InlineData("DeleteItem")]
    [InlineData("BatchGetItem")]
    [InlineData("BatchWriteItem")]
    [InlineData("TransactGetItems")]
    [InlineData("TransactWriteItems")]
    public async Task EachAdvertisedOperationIsServedFromTheCache(string operation)
    {
        await using var server = new CacheServer { OnRequest = _ => Cached("{}") };
        var origin = new HttpHandlerTests.RecordingOrigin();
        await using var client = await ClientAsync(server, origin);

        var response = await CallAsync(client, operation);

        Assert.Equal("cached", response.ResponseMetadata.RequestId);
        var command = Assert.Single(server.Requests);
        Assert.Equal(6, command.Length);
        Assert.Equal(["aws.request", "POST", "https://dynamodb.us-east-1.amazonaws.com/"], command[..3]);
        var headers = command[3].Split('\n');
        Assert.Contains($"x-amz-target:DynamoDB_20120810.{operation}", headers);
        Assert.Contains("host:dynamodb.us-east-1.amazonaws.com", headers);
        Assert.DoesNotContain(headers, h => h.StartsWith("authorization:", StringComparison.Ordinal) || h.StartsWith("x-amz-date:", StringComparison.Ordinal));
        Assert.Contains("\"t\"", command[4]);
        Assert.Equal($"service=dynamodb&op={operation}", command[5]);
        Assert.Equal(0, origin.Calls);
    }

    [Fact]
    public async Task AnOperationTheCacheDoesNotAdvertiseGoesToDynamoDB()
    {
        await using var server = new CacheServer();
        var origin = new HttpHandlerTests.RecordingOrigin("""{"Table":{"TableName":"t"}}""");
        await using var client = await ClientAsync(server, origin);

        var response = await client.DescribeTableAsync("t", Token);

        Assert.Equal("t", response.Table.TableName);
        Assert.Equal(1, origin.Calls);
        Assert.True(origin.LastSigned);
        Assert.Empty(server.Requests);
    }

    [Theory]
    [InlineData("GetItem", "ResourceNotFoundException", typeof(ResourceNotFoundException),
        """{"__type":"com.amazonaws.dynamodb.v20120810#ResourceNotFoundException","message":"from the cache"}""")]
    [InlineData("PutItem", "ConditionalCheckFailedException", typeof(ConditionalCheckFailedException),
        """{"__type":"com.amazonaws.dynamodb.v20120810#ConditionalCheckFailedException","message":"from the cache"}""")]
    [InlineData("TransactWriteItems", "TransactionCanceledException", typeof(TransactionCanceledException),
        """{"__type":"com.amazonaws.dynamodb.v20120810#TransactionCanceledException","Message":"from the cache","CancellationReasons":[{"Code":"ConditionalCheckFailed"}]}""")]
    public async Task CachedDynamoDBErrorsSurfaceAsTheSameSdkExceptions(string operation, string errorType, Type expected, string body)
    {
        await using var server = new CacheServer { OnRequest = _ => Cached(body, 400) };
        var origin = new HttpHandlerTests.RecordingOrigin();
        await using var client = await ClientAsync(server, origin);

        var thrown = await Assert.ThrowsAnyAsync<AmazonDynamoDBException>(() => CallAsync(client, operation));

        Assert.IsType(expected, thrown);
        Assert.Equal(errorType, thrown.ErrorCode);
        Assert.Equal(System.Net.HttpStatusCode.BadRequest, thrown.StatusCode);
        Assert.Equal("from the cache", thrown.Message);
        Assert.Equal("cached", thrown.RequestId);
        if (thrown is TransactionCanceledException canceled)
        {
            Assert.Equal("ConditionalCheckFailed", Assert.Single(canceled.CancellationReasons).Code);
        }

        Assert.Equal(0, origin.Calls);
    }

    [Fact]
    public async Task FallsBackToDynamoDBDuringAnOutageAndUsesTheCacheAgainAfter()
    {
        await using var server = new CacheServer { OnRequest = _ => Cached("""{"Item":{"pk":{"S":"cached"}}}""") };
        var origin = new HttpHandlerTests.RecordingOrigin("""{"Item":{"pk":{"S":"origin"}}}""");
        await using var client = await ClientAsync(server, origin);
        Assert.Equal("cached", (await client.GetItemAsync("t", Key, Token)).Item["pk"].S);

        server.Hold();
        server.Drop();
        await WaitUntilAsync(() => !client.Handle.IsOpen);
        Assert.Equal("origin", (await client.GetItemAsync("t", Key, Token)).Item["pk"].S);
        Assert.True(origin.LastSigned);

        server.Release();
        await WaitUntilAsync(() => client.Handle.IsOpen);
        Assert.Equal("cached", (await client.GetItemAsync("t", Key, Token)).Item["pk"].S);
        Assert.Equal(1, origin.Calls);
        Assert.Equal(2, server.Requests.Count);
    }

    [Fact]
    public async Task StrictModeSurfacesTheOutageToTheCaller()
    {
        await using var server = new CacheServer { OnRequest = _ => Cached("{}") };
        var origin = new HttpHandlerTests.RecordingOrigin();
        await using var client = await ClientAsync(server, origin, fallbackDuration: TimeSpan.Zero);

        server.Hold();
        server.Drop();
        await WaitUntilAsync(() => !client.Handle.IsOpen);

        await Assert.ThrowsAsync<ElastiCacheIntegrationsCacheUnavailableException>(() => client.GetItemAsync("t", Key, Token));
        Assert.Equal(0, origin.Calls);
        server.Release();
    }

    [Fact]
    public async Task SdkRetriesOfACachedServerErrorGoBackThroughTheCache()
    {
        const string body = """{"__type":"com.amazonaws.dynamodb.v20120810#InternalServerError","message":"try again"}""";
        await using var server = new CacheServer { OnRequest = _ => Cached(body, 500) };
        var origin = new HttpHandlerTests.RecordingOrigin();
        await using var client = await ClientAsync(server, origin, maxErrorRetry: 2);

        await Assert.ThrowsAsync<InternalServerErrorException>(() => client.GetItemAsync("t", Key, Token));
        Assert.Equal(3, server.Requests.Count);
        Assert.Equal(0, origin.Calls);
    }
}
