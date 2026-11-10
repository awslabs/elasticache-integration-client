using System.Net;
using System.Text;

namespace Amazon.ElastiCache.Integrations.Tests;

public class HttpAdapterTests
{
    private const string Url = "https://dynamodb.us-east-1.amazonaws.com/";

    private static CancellationToken Token => TestContext.Current.CancellationToken;

    private static HttpRequestMessage SignedRequest(HttpContent? content = null, string? target = "DynamoDB_20120810.GetItem")
    {
        var request = new HttpRequestMessage(HttpMethod.Post, Url)
        {
            Content = content ?? new ReadOnlyMemoryContent("""{"TableName":"t"}"""u8.ToArray()),
        };
        if (target is not null)
        {
            request.Headers.TryAddWithoutValidation("X-Amz-Target", target);
        }

        request.Headers.TryAddWithoutValidation("Authorization", "AWS4-HMAC-SHA256 Credential=secret");
        request.Headers.TryAddWithoutValidation("X-Amz-Date", "20261017T000000Z");
        request.Headers.TryAddWithoutValidation("X-Amz-Security-Token", "session-token");
        request.Headers.TryAddWithoutValidation("X-Amz-Api-Version", "2012-08-10");
        request.Headers.TryAddWithoutValidation("X-Multi", ["a", "b"]);
        request.Content!.Headers.TryAddWithoutValidation("Content-Type", "application/x-amz-json-1.0");
        return request;
    }

    [Fact]
    public async Task BuildsTheRequestWithoutSigV4Headers()
    {
        using var request = SignedRequest();

        var cacheRequest = await ElastiCacheIntegrationsHttpAdapter.TryCreateAsync(request, "DynamoDB", Token);

        Assert.NotNull(cacheRequest);
        Assert.Equal("POST", cacheRequest.Method);
        Assert.Equal(Url, cacheRequest.Url);
        Assert.Equal("""{"TableName":"t"}""", Encoding.UTF8.GetString(cacheRequest.Body.Span));
        Assert.Equal("dynamodb", cacheRequest.Metadata.Service);
        Assert.Equal("GetItem", cacheRequest.Metadata.Operation);
        Assert.Equal(
            new Dictionary<string, string>
            {
                ["x-amz-target"] = "DynamoDB_20120810.GetItem",
                ["x-amz-api-version"] = "2012-08-10",
                ["x-multi"] = "a, b",
                ["content-type"] = "application/x-amz-json-1.0",
                ["content-length"] = "17",
                ["host"] = "dynamodb.us-east-1.amazonaws.com",
            },
            cacheRequest.Headers);

        // The origin path still gets the signed request and its body.
        Assert.True(request.Headers.Contains("Authorization"));
        Assert.Equal("""{"TableName":"t"}""", await request.Content!.ReadAsStringAsync(Token));
    }

    [Fact]
    public async Task TakesTheHostFromTheUriWhenTheSdkSetNone()
    {
        using var request = SignedRequest();
        request.RequestUri = new Uri("http://localhost:8000/");

        var cacheRequest = await ElastiCacheIntegrationsHttpAdapter.TryCreateAsync(request, "dynamodb", Token);

        Assert.Equal("localhost:8000", cacheRequest!.Headers["host"]);
    }

    [Fact]
    public async Task KeepsTheHostTheSdkSet()
    {
        using var request = SignedRequest();
        request.Headers.Host = "custom.example:8000";

        var cacheRequest = await ElastiCacheIntegrationsHttpAdapter.TryCreateAsync(request, "dynamodb", Token);

        Assert.Equal("custom.example:8000", cacheRequest!.Headers["host"]);
    }

    [Fact]
    public async Task AcceptsByteArrayContentAndNoContent()
    {
        using var bytes = SignedRequest(new ByteArrayContent("{}"u8.ToArray()));
        Assert.Equal("{}", Encoding.UTF8.GetString((await ElastiCacheIntegrationsHttpAdapter.TryCreateAsync(bytes, "dynamodb", Token))!.Body.Span));

        using var empty = SignedRequest();
        empty.Content = null;
        Assert.True((await ElastiCacheIntegrationsHttpAdapter.TryCreateAsync(empty, "dynamodb", Token))!.Body.IsEmpty);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("DynamoDB_20120810.")]
    [InlineData("")]
    public async Task NoOperationGoesToTheOrigin(string? target)
    {
        using var request = SignedRequest(target: target);

        Assert.Null(await ElastiCacheIntegrationsHttpAdapter.TryCreateAsync(request, "dynamodb", Token));
    }

    [Fact]
    public async Task TwoTargetsGoToTheOrigin()
    {
        using var request = SignedRequest();
        request.Headers.TryAddWithoutValidation("X-Amz-Target", "DynamoDB_20120810.PutItem");

        Assert.Null(await ElastiCacheIntegrationsHttpAdapter.TryCreateAsync(request, "dynamodb", Token));
    }

    [Fact]
    public async Task AStreamingBodyGoesToTheOrigin()
    {
        using var request = SignedRequest(new StreamContent(new MemoryStream("{}"u8.ToArray())));

        Assert.Null(await ElastiCacheIntegrationsHttpAdapter.TryCreateAsync(request, "dynamodb", Token));
    }

    [Fact]
    public async Task BuildsTheResponse()
    {
        using var request = SignedRequest();
        var headers = new Dictionary<string, string>
        {
            ["content-type"] = "application/x-amz-json-1.0",
            ["x-amzn-requestid"] = "rid-1",
            ["x-amz-crc32"] = "123",
            ["content-length"] = "999",
            ["transfer-encoding"] = "chunked",
        };

        using var response = ElastiCacheIntegrationsHttpAdapter.ToHttpResponse(new ElastiCacheIntegrationsResponse(400, headers, """{"__type":"x"}"""u8.ToArray()), request);

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        Assert.Same(request, response.RequestMessage);
        Assert.Equal("rid-1", Assert.Single(response.Headers.GetValues("x-amzn-RequestId")));
        Assert.Equal("123", Assert.Single(response.Headers.GetValues("x-amz-crc32")));
        Assert.Equal("application/x-amz-json-1.0", response.Content.Headers.ContentType!.MediaType);
        Assert.Equal(14, response.Content.Headers.ContentLength);
        Assert.Null(response.Headers.TransferEncodingChunked);
        Assert.Equal(["x-amzn-requestid", "x-amz-crc32"], response.Headers.Select(h => h.Key));
        Assert.Equal(["Content-Type", "Content-Length"], response.Content.Headers.Select(h => h.Key));
        Assert.Equal("""{"__type":"x"}""", await response.Content.ReadAsStringAsync(Token));
    }
}
