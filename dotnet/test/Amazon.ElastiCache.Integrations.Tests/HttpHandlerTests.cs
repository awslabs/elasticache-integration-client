using System.Net;
using System.Text;
using Amazon.DynamoDBv2;
using Amazon.DynamoDBv2.Model;
using Amazon.Runtime;

namespace Amazon.ElastiCache.Integrations.Tests;

/// <summary>A real <see cref="AmazonDynamoDBClient"/> over the client's HTTP client factory.</summary>
public class HttpHandlerTests
{
    private const string ItemBody = """{"Item":{"pk":{"S":"a"}},"ConsumedCapacity":{"TableName":"t","CapacityUnits":0.5}}""";

    private static CancellationToken Token => TestContext.Current.CancellationToken;

    private static ElastiCacheIntegrationsResponse Cached(string body, int status = 200) =>
        new(status, new Dictionary<string, string> { ["content-type"] = "application/x-amz-json-1.0" }, Encoding.UTF8.GetBytes(body));

    private static AmazonDynamoDBClient Client(ElastiCacheIntegrationsSend send, RecordingOrigin origin, Action? onSyncCall = null) =>
        new(
            new BasicAWSCredentials("AKID", "SECRET"),
            new AmazonDynamoDBConfig
            {
                RegionEndpoint = RegionEndpoint.USEast1,
                MaxErrorRetry = 2,
                HttpClientFactory = new ElastiCacheIntegrationsHttpClientFactory(send, "dynamodb", origin, onSyncCall ?? (() => { })),
            });

    private static Task<GetItemResponse> GetItemAsync(AmazonDynamoDBClient client) =>
        client.GetItemAsync(
            new GetItemRequest { TableName = "t", Key = new() { ["pk"] = new AttributeValue { S = "a" } }, ReturnConsumedCapacity = ReturnConsumedCapacity.TOTAL },
            Token);

    [Fact]
    public async Task ServedResponseIsUnmarshalledByTheSdk()
    {
        ElastiCacheIntegrationsRequest? sent = null;
        var origin = new RecordingOrigin();
        using var client = Client((r, _) => { sent = r; return Task.FromResult(ElastiCacheIntegrationsOutcome.Served(Cached(ItemBody))); }, origin);

        var response = await GetItemAsync(client);

        Assert.Equal("a", response.Item["pk"].S);
        Assert.Equal(0.5, response.ConsumedCapacity.CapacityUnits);
        Assert.Equal("GetItem", sent!.Metadata.Operation);
        Assert.False(sent.Headers.ContainsKey("authorization"));
        Assert.Equal(0, origin.Calls);
    }

    [Fact]
    public async Task OriginOutcomeSendsTheSignedRequestToTheOrigin()
    {
        var origin = new RecordingOrigin(ItemBody);
        using var client = Client((_, _) => Task.FromResult(ElastiCacheIntegrationsOutcome.Origin), origin);

        var response = await GetItemAsync(client);

        Assert.Equal("a", response.Item["pk"].S);
        Assert.Equal(1, origin.Calls);
        Assert.True(origin.LastSigned);
        Assert.Equal("""{"Key":{"pk":{"S":"a"}},"ReturnConsumedCapacity":"TOTAL","TableName":"t"}""", origin.LastBody);
    }

    [Fact]
    public async Task RetryableIsRetriedByTheSdkUntilItsLimit()
    {
        var sends = 0;
        var error = new ElastiCacheIntegrationsTimeoutException("slow");
        using var client = Client((_, _) => { Interlocked.Increment(ref sends); return Task.FromResult(ElastiCacheIntegrationsOutcome.Retryable(error)); }, new RecordingOrigin());

        var thrown = await Assert.ThrowsAsync<HttpRequestException>(() => GetItemAsync(client));

        Assert.Same(error, thrown.InnerException);
        Assert.Equal(3, sends);
    }

    [Fact]
    public async Task RetryableThenServedSucceeds()
    {
        var sends = 0;
        using var client = Client(
            (_, _) => Task.FromResult(Interlocked.Increment(ref sends) == 1
                ? ElastiCacheIntegrationsOutcome.Retryable(new ElastiCacheIntegrationsConnectionLostException("drop"))
                : ElastiCacheIntegrationsOutcome.Served(Cached(ItemBody))),
            new RecordingOrigin());

        Assert.Equal("a", (await GetItemAsync(client)).Item["pk"].S);
        Assert.Equal(2, sends);
    }

    [Theory]
    [InlineData("unavailable")]
    [InlineData("disposed")]
    [InlineData("server")]
    public async Task TerminalErrorSurfacesUnwrappedWithoutRetry(string kind)
    {
        Exception error = kind switch
        {
            "unavailable" => new ElastiCacheIntegrationsCacheUnavailableException("down"),
            "disposed" => new ObjectDisposedException("ElastiCacheIntegrationsSession"),
            _ => new ElastiCacheIntegrationsServerException("UNSUPPORTED", "not cached"),
        };
        var sends = 0;
        var origin = new RecordingOrigin();
        using var client = Client((_, _) => { Interlocked.Increment(ref sends); return Task.FromResult(ElastiCacheIntegrationsOutcome.Terminal(error)); }, origin);

        Assert.Same(error, await Assert.ThrowsAnyAsync<Exception>(() => GetItemAsync(client)));
        Assert.Equal(1, sends);
        Assert.Equal(0, origin.Calls);
    }

    [Fact]
    public async Task AThrowingSendSurfacesUnwrappedWithoutRetry()
    {
        var sends = 0;
        using var client = Client((_, _) => { Interlocked.Increment(ref sends); throw new ObjectDisposedException("ElastiCacheIntegrationsSession"); }, new RecordingOrigin());

        await Assert.ThrowsAsync<ObjectDisposedException>(() => GetItemAsync(client));
        Assert.Equal(1, sends);
    }

    [Fact]
    public async Task CallerCancellationIsNotRetried()
    {
        var sends = 0;
        var origin = new RecordingOrigin();
        using var cancel = CancellationTokenSource.CreateLinkedTokenSource(Token);
        using var client = Client(
            async (_, ct) =>
            {
                Interlocked.Increment(ref sends);
                await cancel.CancelAsync();
                await Task.Delay(Timeout.Infinite, ct);
                return ElastiCacheIntegrationsOutcome.Origin;
            },
            origin);

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() =>
            client.GetItemAsync(new GetItemRequest { TableName = "t", Key = new() { ["pk"] = new AttributeValue { S = "a" } } }, cancel.Token));
        Assert.Equal(1, sends);
        Assert.Equal(0, origin.Calls);
    }

    [Fact]
    public async Task TheSdkTimeoutCutsACacheWaitLikeASlowOrigin()
    {
        var origin = new RecordingOrigin();
        var config = new AmazonDynamoDBConfig
        {
            RegionEndpoint = RegionEndpoint.USEast1,
            MaxErrorRetry = 0,
            Timeout = TimeSpan.FromMilliseconds(200),
            HttpClientFactory = new ElastiCacheIntegrationsHttpClientFactory(
                async (_, ct) => { await Task.Delay(Timeout.Infinite, ct); return ElastiCacheIntegrationsOutcome.Origin; }, "dynamodb", origin, () => { }),
        };
        using var client = new AmazonDynamoDBClient(new BasicAWSCredentials("AKID", "SECRET"), config);

        // The SDK unwraps HttpClient's timeout to TimeoutException, as for a slow origin.
        await Assert.ThrowsAsync<TimeoutException>(() => GetItemAsync(client));
        Assert.Equal(0, origin.Calls);
    }

    [Fact]
    public async Task CachedDynamoDBErrorSurfacesAsTheSdkException()
    {
        const string body = """{"__type":"com.amazonaws.dynamodb.v20120810#ResourceNotFoundException","message":"no table"}""";
        using var client = Client((_, _) => Task.FromResult(ElastiCacheIntegrationsOutcome.Served(Cached(body, 400))), new RecordingOrigin());

        var thrown = await Assert.ThrowsAsync<ResourceNotFoundException>(() => GetItemAsync(client));

        Assert.Equal("no table", thrown.Message);
    }

    [Fact]
    public async Task UnroutableRequestGoesToTheOrigin()
    {
        var origin = new RecordingOrigin();
        var factory = new ElastiCacheIntegrationsHttpClientFactory((_, _) => throw new InvalidOperationException("not routed"), "dynamodb", origin, () => { });
        using var http = factory.CreateHttpClient(new AmazonDynamoDBConfig());
        using var request = new HttpRequestMessage(HttpMethod.Post, "https://dynamodb.us-east-1.amazonaws.com/") { Content = new StringContent("{}") };

        using var response = await http.SendAsync(request, Token);

        Assert.Equal(1, origin.Calls);
    }

    [Fact]
    public void SyncSendGoesToTheOriginAndWarnsOnce()
    {
        var origin = new RecordingOrigin();
        var logger = new ListLogger();
        var flag = new OnceFlag();
        using var handler = new ElastiCacheIntegrationsHttpHandler((_, _) => throw new InvalidOperationException("async path"), "dynamodb", origin, disposeOrigin: false,
            () => ElastiCacheIntegrationsLog.WarnSyncCallToOrigin(logger, flag));
        using var invoker = new HttpMessageInvoker(handler);

        for (var i = 0; i < 2; i++)
        {
            using var request = new HttpRequestMessage(HttpMethod.Post, "https://dynamodb.us-east-1.amazonaws.com/");
            request.Headers.TryAddWithoutValidation("X-Amz-Target", "DynamoDB_20120810.GetItem");
            using var response = invoker.Send(request, Token);
        }

        Assert.Equal(2, origin.Calls);
        Assert.Equal([ElastiCacheIntegrationsEvents.SyncCallToOrigin], logger.Ids);
    }

    [Fact]
    public async Task ACallersOriginOutlivesEveryHttpClientAndTheSdkClient()
    {
        var origin = new RecordingOrigin(ItemBody);
        var factory = new ElastiCacheIntegrationsHttpClientFactory((_, _) => Task.FromResult(ElastiCacheIntegrationsOutcome.Origin), "dynamodb", origin, () => { });
        var config = new AmazonDynamoDBConfig { RegionEndpoint = RegionEndpoint.USEast1, HttpClientCacheSize = 2, HttpClientFactory = factory };

        factory.CreateHttpClient(config).Dispose();
        using (var client = new AmazonDynamoDBClient(new BasicAWSCredentials("AKID", "SECRET"), config))
        {
            await GetItemAsync(client);
        }

        Assert.False(origin.Disposed);
        Assert.Equal(1, origin.Calls);
    }

    [Fact]
    public void AnOwnedOriginIsDisposedWithItsHttpClient()
    {
        var origin = new RecordingOrigin();

        new HttpClient(new ElastiCacheIntegrationsHttpHandler((_, _) => Task.FromResult(ElastiCacheIntegrationsOutcome.Origin), "dynamodb", origin, disposeOrigin: true, () => { })).Dispose();

        Assert.True(origin.Disposed);
    }

    [Fact]
    public void TheSdkTimeoutIsCopiedToTheHttpClient()
    {
        var factory = new ElastiCacheIntegrationsHttpClientFactory((_, _) => Task.FromResult(ElastiCacheIntegrationsOutcome.Origin), "dynamodb", null, () => { });

        using var client = factory.CreateHttpClient(new AmazonDynamoDBConfig { Timeout = TimeSpan.FromSeconds(7) });

        Assert.Equal(TimeSpan.FromSeconds(7), client.Timeout);
    }

    [Fact]
    public void TheDefaultOriginCopiesTheSdkSettings()
    {
        var config = new AmazonDynamoDBConfig
        {
            MaxConnectionsPerServer = 7,
            ConnectTimeout = TimeSpan.FromSeconds(3),
            AllowAutoRedirect = false,
            ProxyHost = "proxy.local",
            ProxyPort = 8080,
            ProxyCredentials = new NetworkCredential("u", "p"),
        };

        using var origin = ElastiCacheIntegrationsHttpClientFactory.CreateOrigin(config);

        Assert.Equal(7, origin.MaxConnectionsPerServer);
        Assert.Equal(TimeSpan.FromSeconds(3), origin.ConnectTimeout);
        Assert.False(origin.AllowAutoRedirect);
        Assert.Equal(DecompressionMethods.None, origin.AutomaticDecompression);
        Assert.True(origin.EnableMultipleHttp2Connections);
        Assert.Equal(new Uri("http://proxy.local:8080/"), origin.Proxy!.GetProxy(new Uri("https://dynamodb.us-east-1.amazonaws.com/")));
        Assert.Same(config.ProxyCredentials, origin.Proxy.Credentials);
    }

    /// <summary>A stand-in for DynamoDB that records what reached it.</summary>
    internal sealed class RecordingOrigin(string body = "{}") : HttpMessageHandler
    {
        private int _calls;

        public int Calls => Volatile.Read(ref _calls);

        public bool LastSigned { get; private set; }

        public bool Disposed { get; private set; }

        public string? LastBody { get; private set; }

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) =>
            Task.FromResult(Send(request, cancellationToken));

        protected override HttpResponseMessage Send(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            ObjectDisposedException.ThrowIf(Disposed, this);
            Interlocked.Increment(ref _calls);
            LastSigned = request.Headers.Contains("Authorization");
            LastBody = request.Content?.ReadAsStringAsync(cancellationToken).GetAwaiter().GetResult();

            var content = new StringContent(body);
            content.Headers.ContentType = new("application/x-amz-json-1.0");
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = content, RequestMessage = request };
        }

        protected override void Dispose(bool disposing)
        {
            Disposed = true;
            base.Dispose(disposing);
        }
    }
}
