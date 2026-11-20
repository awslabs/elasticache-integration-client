// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using System.Net;
using System.Text;
using Amazon;
using Amazon.DynamoDBv2;
using Amazon.DynamoDBv2.Model;
using Amazon.Runtime;

namespace Amazon.ElastiCache.Integrations.Tests;

public sealed class IntegrationFoundationTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    [Fact]
    public async Task InstalledInterceptorCanServeAnSdkRequest()
    {
        var origin = new RecordingOrigin("origin");
        var interceptor = new RecordingInterceptor(_ => Response("intercepted"));
        var config = Config();
        await using var handle = ElastiCacheIntegrationsClient.Install(config, interceptor, origin);
        using var client = new AmazonDynamoDBClient(new AnonymousAWSCredentials(), config);

        var response = await GetAsync(client);

        Assert.Equal("intercepted", response.Item["pk"].S);
        Assert.Equal(1, interceptor.Calls);
        Assert.Equal(0, origin.Calls);
        Assert.IsType<ElastiCacheIntegrationsHttpClientFactory>(config.HttpClientFactory);
    }

    [Fact]
    public async Task PassThroughUsesTheSameSignedRequestObject()
    {
        var origin = new RecordingOrigin("origin");
        var interceptor = new RecordingInterceptor(_ => null);
        var config = Config();
        await using var handle = ElastiCacheIntegrationsClient.Install(config, interceptor, origin);
        using var client = new AmazonDynamoDBClient(new AnonymousAWSCredentials(), config);

        var response = await GetAsync(client);

        Assert.Equal("origin", response.Item["pk"].S);
        Assert.Same(interceptor.LastRequest, origin.LastRequest);
        Assert.Equal(HttpMethod.Post, origin.LastRequest!.Method);
        Assert.Equal("DynamoDB_20120810.GetItem", Assert.Single(origin.LastRequest.Headers.GetValues("X-Amz-Target")));
    }

    [Fact]
    public void ExistingSdkFactoryIsRejectedWithoutReplacement()
    {
        var original = new StubFactory();
        var config = Config();
        config.HttpClientFactory = original;
        var interceptor = new RecordingInterceptor(_ => null);

        var exception = Assert.Throws<ElastiCacheIntegrationsConfigurationException>(
            () => ElastiCacheIntegrationsClient.Install(config, interceptor));

        Assert.Contains("already has an HttpClientFactory", exception.Message);
        Assert.Same(original, config.HttpClientFactory);
        Assert.Equal(1, interceptor.DisposeCalls);
    }

    [Fact]
    public void OneHandleCanAttachToMultipleDynamoDbConfigs()
    {
        var first = Config();
        var second = Config();
        using var handle = ElastiCacheIntegrationsClient.Install(
            first,
            new RecordingInterceptor(_ => null),
            new RecordingOrigin("origin"));

        handle.Attach(second);

        Assert.IsType<ElastiCacheIntegrationsHttpClientFactory>(first.HttpClientFactory);
        Assert.IsType<ElastiCacheIntegrationsHttpClientFactory>(second.HttpClientFactory);
        Assert.Throws<ElastiCacheIntegrationsConfigurationException>(() => handle.Attach(first));
    }

    [Fact]
    public void OwnedClientDisposesItsInterceptorExactlyOnce()
    {
        var interceptor = new RecordingInterceptor(_ => null);
        var client = ElastiCacheIntegrationsClient.CreateDynamoDBClient(
            new AnonymousAWSCredentials(),
            Config(),
            interceptor,
            new RecordingOrigin("origin"));

        client.Dispose();
        client.Dispose();

        Assert.True(client.Handle.IsDisposed);
        Assert.Equal(1, interceptor.DisposeCalls);
    }

    [Fact]
    public async Task ThrowingInterceptorDisposeStillDisposesTheSdkClient()
    {
        var interceptor = new ThrowingDisposeInterceptor();
        var client = ElastiCacheIntegrationsClient.CreateDynamoDBClient(
            new AnonymousAWSCredentials(),
            Config(),
            interceptor,
            new RecordingOrigin("origin"));

        var exception = Assert.Throws<InvalidOperationException>(client.Dispose);

        Assert.Equal("interceptor dispose failed", exception.Message);
        Assert.Equal(1, interceptor.DisposeCalls);
        await Assert.ThrowsAsync<ObjectDisposedException>(() => GetAsync(client));
    }

    [Fact]
    public async Task AsyncDisposalDrainsAnActiveInterception()
    {
        var interceptor = new BlockingInterceptor();
        var client = ElastiCacheIntegrationsClient.CreateDynamoDBClient(
            new AnonymousAWSCredentials(),
            Config(),
            interceptor,
            new RecordingOrigin("origin"));
        var request = GetAsync(client);
        await interceptor.Started.Task.WaitAsync(Token);

        var disposal = client.DisposeAsync().AsTask();
        Assert.False(disposal.IsCompleted);

        interceptor.Release.TrySetResult(Response("intercepted"));
        Assert.Equal("intercepted", (await request).Item["pk"].S);
        await disposal;

        Assert.Equal(1, interceptor.DisposeAsyncCalls);
        Assert.Equal(0, interceptor.DisposeCalls);
    }

    [Fact]
    public async Task ADisposedManualHandlePassesNewCallsThroughToOrigin()
    {
        var origin = new RecordingOrigin("origin");
        var interceptor = new RecordingInterceptor(_ => Response("intercepted"));
        var config = Config();
        var handle = ElastiCacheIntegrationsClient.Install(config, interceptor, origin);
        using var client = new AmazonDynamoDBClient(new AnonymousAWSCredentials(), config);

        handle.Dispose();
        var response = await GetAsync(client);

        Assert.Equal("origin", response.Item["pk"].S);
        Assert.Equal(0, interceptor.Calls);
        Assert.Equal(1, origin.Calls);
        Assert.Throws<ObjectDisposedException>(() => handle.Attach(Config()));
    }

    [Fact]
    public async Task ConcurrentSdkCallsShareOneThreadSafeInstallation()
    {
        var interceptor = new ConcurrentInterceptor();
        await using var client = ElastiCacheIntegrationsClient.CreateDynamoDBClient(
            new AnonymousAWSCredentials(),
            Config(),
            interceptor,
            new RecordingOrigin("origin"));

        var calls = Enumerable.Range(0, 64).Select(_ => GetAsync(client));
        var responses = await Task.WhenAll(calls);

        Assert.All(responses, response => Assert.Equal("intercepted", response.Item["pk"].S));
        Assert.Equal(64, interceptor.Calls);
        Assert.True(interceptor.MaximumConcurrentCalls > 1);
    }

    [Fact]
    public async Task CallerOwnedOriginHandlerIsNotDisposed()
    {
        var origin = new RecordingOrigin("origin");
        var config = Config();
        var handle = ElastiCacheIntegrationsClient.Install(
            config,
            new RecordingInterceptor(_ => null),
            origin);
        using (var client = new AmazonDynamoDBClient(new AnonymousAWSCredentials(), config))
        {
            await GetAsync(client);
        }

        await handle.DisposeAsync();

        Assert.False(origin.Disposed);
    }

    [Fact]
    public void SynchronousTransportPreservesTheOriginPath()
    {
        var origin = new RecordingOrigin("origin");
        var interceptor = new RecordingInterceptor(_ => Response("intercepted"));
        var config = Config();
        using var handle = ElastiCacheIntegrationsClient.Install(config, interceptor, origin);
        var factory = Assert.IsType<ElastiCacheIntegrationsHttpClientFactory>(config.HttpClientFactory);
        using var http = factory.CreateHttpClient(config);
        using var request = new HttpRequestMessage(
            HttpMethod.Post,
            "https://dynamodb.us-east-1.amazonaws.com/");

        using var response = http.Send(request, Token);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal(0, interceptor.Calls);
        Assert.Equal(1, origin.Calls);
        Assert.Same(request, origin.LastRequest);
    }

    private static AmazonDynamoDBConfig Config() => new()
    {
        RegionEndpoint = RegionEndpoint.USEast1,
        MaxErrorRetry = 0,
    };

    private static Task<GetItemResponse> GetAsync(IAmazonDynamoDB client) =>
        client.GetItemAsync(
            new GetItemRequest
            {
                TableName = "table",
                Key = new Dictionary<string, AttributeValue>
                {
                    ["pk"] = new() { S = "key" },
                },
            },
            Token);

    private static HttpResponseMessage Response(string value) => new(HttpStatusCode.OK)
    {
        Content = new StringContent(
            "{\"Item\":{\"pk\":{\"S\":\"" + value + "\"}}}",
            Encoding.UTF8,
            "application/x-amz-json-1.0"),
    };

    private sealed class RecordingInterceptor(
        Func<HttpRequestMessage, HttpResponseMessage?> response) : IElastiCacheIntegrationsRequestInterceptor
    {
        public int Calls { get; private set; }

        public int DisposeCalls { get; private set; }

        public HttpRequestMessage? LastRequest { get; private set; }

        public ValueTask<HttpResponseMessage?> InterceptAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            Calls++;
            LastRequest = request;
            return ValueTask.FromResult(response(request));
        }

        public void Dispose() => DisposeCalls++;

        public ValueTask DisposeAsync()
        {
            DisposeCalls++;
            return ValueTask.CompletedTask;
        }
    }

    private sealed class BlockingInterceptor : IElastiCacheIntegrationsRequestInterceptor
    {
        public TaskCompletionSource Started { get; } =
            new(TaskCreationOptions.RunContinuationsAsynchronously);

        public TaskCompletionSource<HttpResponseMessage?> Release { get; } =
            new(TaskCreationOptions.RunContinuationsAsynchronously);

        public int DisposeCalls { get; private set; }

        public int DisposeAsyncCalls { get; private set; }

        public async ValueTask<HttpResponseMessage?> InterceptAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            Started.TrySetResult();
            return await Release.Task.WaitAsync(cancellationToken);
        }

        public void Dispose() => DisposeCalls++;

        public ValueTask DisposeAsync()
        {
            DisposeAsyncCalls++;
            return ValueTask.CompletedTask;
        }
    }

    private sealed class ConcurrentInterceptor : IElastiCacheIntegrationsRequestInterceptor
    {
        private int _active;
        private int _calls;
        private int _maximum;

        public int Calls => _calls;

        public int MaximumConcurrentCalls => _maximum;

        public async ValueTask<HttpResponseMessage?> InterceptAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            Interlocked.Increment(ref _calls);
            var active = Interlocked.Increment(ref _active);
            SetMaximum(active);
            await Task.Yield();
            Interlocked.Decrement(ref _active);
            return Response("intercepted");
        }

        public void Dispose()
        {
        }

        public ValueTask DisposeAsync() => ValueTask.CompletedTask;

        private void SetMaximum(int active)
        {
            var maximum = Volatile.Read(ref _maximum);
            while (active > maximum)
            {
                var observed = Interlocked.CompareExchange(ref _maximum, active, maximum);
                if (observed == maximum)
                {
                    return;
                }

                maximum = observed;
            }
        }
    }

    private sealed class ThrowingDisposeInterceptor : IElastiCacheIntegrationsRequestInterceptor
    {
        public int DisposeCalls { get; private set; }

        public ValueTask<HttpResponseMessage?> InterceptAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken) => ValueTask.FromResult<HttpResponseMessage?>(null);

        public void Dispose()
        {
            DisposeCalls++;
            throw new InvalidOperationException("interceptor dispose failed");
        }

        public ValueTask DisposeAsync() => ValueTask.CompletedTask;
    }

    private sealed class RecordingOrigin(string responseValue) : HttpMessageHandler
    {
        public int Calls { get; private set; }

        public bool Disposed { get; private set; }

        public HttpRequestMessage? LastRequest { get; private set; }

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            return Task.FromResult(Record(request));
        }

        protected override HttpResponseMessage Send(
            HttpRequestMessage request,
            CancellationToken cancellationToken) => Record(request);

        protected override void Dispose(bool disposing)
        {
            Disposed = true;
            base.Dispose(disposing);
        }

        private HttpResponseMessage Record(HttpRequestMessage request)
        {
            Calls++;
            LastRequest = request;
            return Response(responseValue);
        }
    }

    private sealed class StubFactory : HttpClientFactory
    {
        public override HttpClient CreateHttpClient(IClientConfig clientConfig) => new();
    }
}
