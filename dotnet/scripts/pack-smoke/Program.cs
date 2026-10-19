using System.Reflection;
using Amazon;
using Amazon.DynamoDBv2;
using Amazon.DynamoDBv2.Model;
using Amazon.ElastiCache.Integrations;
using Amazon.Runtime;

// A consumer of the installed Amazon.ElastiCache.Integrations package. args[0] is a plaintext mock cache endpoint.
// Exit code 0 means the package loaded, connected and served a GetItem from the cache.
var version = typeof(ElastiCacheIntegrationsClient).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion;
if (version?.Split('+')[0] != "0.0.1")
{
    Console.Error.WriteLine($"Unexpected package version: {version}");
    return 1;
}

var options = new ElastiCacheIntegrationsClientOptions
{
    Endpoint = args[0],
    CacheName = "pack-smoke",
    Region = "us-east-1",
    UseTls = false,
    AuthProvider = new StaticAuth(),
    OriginHandler = new NoOrigin(),
};
var config = new AmazonDynamoDBConfig { RegionEndpoint = RegionEndpoint.USEast1, MaxErrorRetry = 0 };

await using var client = await ElastiCacheIntegrationsClient.CreateDynamoDBClientAsync(options, config, new BasicAWSCredentials("AKID", "SECRET"));
var reply = await client.GetItemAsync(new GetItemRequest { TableName = "t", Key = new() { ["pk"] = new AttributeValue { S = "a" } } });
Console.WriteLine($"served v={reply.Item["v"].N}");
return 0;

internal sealed class StaticAuth : IElastiCacheIntegrationsAuthProvider
{
    public ValueTask<(string UserId, string Token)> GetAuthAsync(CancellationToken cancellationToken) =>
        ValueTask.FromResult(("user", "token"));
}

// Keeps the run hermetic: a call that is not served from the cache fails instead of reaching DynamoDB.
internal sealed class NoOrigin : HttpMessageHandler
{
    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) =>
        throw new InvalidOperationException($"The origin was called for {request.RequestUri}.");
}
