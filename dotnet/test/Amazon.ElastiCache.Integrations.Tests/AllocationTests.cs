using System.Buffers;
using System.Text;

namespace Amazon.ElastiCache.Integrations.Tests;

/// <summary>What the client itself allocates for one cached GetItem, outside the SDK and the socket.</summary>
public class AllocationTests
{
    private const int Calls = 200;

    // The headers the SDK sends with a signed GetItem, and a 1 KiB item.
    private static HttpRequestMessage SdkGetItem()
    {
        var request = new HttpRequestMessage(HttpMethod.Post, "https://dynamodb.us-east-1.amazonaws.com/")
        {
            Content = new ReadOnlyMemoryContent("""{"TableName":"items","Key":{"pk":{"S":"dotnet-iad-inv-bench-6-r1-hit-1"}}}"""u8.ToArray()),
        };
        request.Headers.TryAddWithoutValidation("X-Amz-Target", "DynamoDB_20120810.GetItem");
        request.Headers.TryAddWithoutValidation("x-amz-api-version", "2012-08-10");
        request.Headers.TryAddWithoutValidation("amz-sdk-invocation-id", "bb542b8a-21a9-40d3-b9f8-2cdc47dca29c");
        request.Headers.TryAddWithoutValidation("amz-sdk-request", "attempt=1; max=1");
        request.Headers.TryAddWithoutValidation("Host", "dynamodb.us-east-1.amazonaws.com");
        request.Headers.TryAddWithoutValidation("X-Amz-Date", "20261119T113511Z");
        request.Headers.TryAddWithoutValidation("X-Amz-Content-SHA256", "2bea498f052922ba49f84355520d3f40d62f62547646cfb5e9457064fee27079");
        request.Headers.TryAddWithoutValidation(
            "Authorization",
            "AWS4-HMAC-SHA256 Credential=AKID/20261119/us-east-1/dynamodb/aws4_request, SignedHeaders=content-type;host;x-amz-api-version;x-amz-content-sha256;x-amz-date;x-amz-target, Signature=bce3414a2f904210859032b646445025cc4b2592dc894a9692d02c90e22569a7");
        request.Headers.TryAddWithoutValidation(
            "User-Agent",
            "aws-sdk-dotnet-coreclr/4.0.107.3 ua/2.1 os/linux#5.10.273.289 md/ARCH#Arm64 lang/.NET_Core#8.0.32 md/aws-sdk-dotnet-core#4.0.102.11 api/DynamoDB#4.0.107.3 md/ClientAsync cfg/init-coll#0 m/b,P,e,E");
        request.Content.Headers.TryAddWithoutValidation("Content-Type", "application/x-amz-json-1.0");
        return request;
    }

    private static byte[] CachedReply()
    {
        var body = Encoding.UTF8.GetBytes("{\"Item\":{\"pk\":{\"S\":\"dotnet-iad-inv-bench-6-r1-hit-1\"},\"a0\":{\"S\":\"" + new string('a', 1024) + "\"}}}");
        var headers = Encoding.UTF8.GetBytes(
            $"content-type:application/x-amz-json-1.0\nx-amz-crc32:12345\nx-amzn-requestid:ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCDEFGHIJKLMNOP\ncontent-length:{body.Length}\nserver:Server\ndate:Thu, 19 Nov 2026 11:00:00 GMT");
        var writer = new ArrayBufferWriter<byte>();
        RespEncoder.WriteValue(writer, new RespArray([new RespInteger(200), new RespBlobString(headers), new RespBlobString(body)]));
        return writer.WrittenSpan.ToArray();
    }

    private static void Call(HttpRequestMessage request, byte[] reply)
    {
        var created = ElastiCacheIntegrationsHttpAdapter.TryCreateAsync(request, "dynamodb", CancellationToken.None);
        Assert.True(created.IsCompletedSuccessfully);
        var frame = ElastiCacheIntegrationsProtocol.EncodeRequest(created.Result!);
        Assert.False(frame.IsEmpty);
        var buffer = new ReadOnlySequence<byte>(reply);
        Assert.True(RespDecoder.TryDecode(ref buffer, out var value));
        var served = Assert.IsType<RequestReply.Served>(ElastiCacheIntegrationsProtocol.ParseRequestReply(value, inBand: false));
        using var response = ElastiCacheIntegrationsHttpAdapter.ToHttpResponse(served.Response, request);
    }

    // The client's share of each call adds to the SDK's and sets how often the workstation GC runs gen0
    // collections, which stall every call in flight.
    [Fact]
    public void ACachedGetItemStaysWithinItsAllocationBudget()
    {
        const long Budget = 8 * 1024;
        var reply = CachedReply();
        var requests = Enumerable.Range(0, 2 * Calls).Select(_ => SdkGetItem()).ToArray();
        for (var i = 0; i < Calls; i++)
        {
            Call(requests[i], reply);
        }

        var before = GC.GetAllocatedBytesForCurrentThread();
        for (var i = Calls; i < 2 * Calls; i++)
        {
            Call(requests[i], reply);
        }

        var perCall = (GC.GetAllocatedBytesForCurrentThread() - before) / Calls;
        foreach (var request in requests)
        {
            request.Dispose();
        }

        Assert.True(perCall <= Budget, $"one cached GetItem allocated {perCall} bytes in the client, over the {Budget} budget");
    }
}
