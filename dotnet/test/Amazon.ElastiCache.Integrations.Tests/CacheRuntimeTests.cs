// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using Amazon.Runtime;

namespace Amazon.ElastiCache.Integrations.Tests;

public class CacheRuntimeTests
{
    private const string Config = """{"version":"1.0","refresh_interval":60}""";
    private static readonly ElastiCacheIntegrationsRequest Request = new(
        "POST", "https://dynamodb.us-east-1.amazonaws.com/", new Dictionary<string, string>
        {
            ["X-Amz-Target"] = "DynamoDB_20120810.GetItem",
            ["Authorization"] = "origin-only",
            ["X-Amz-Security-Token"] = "origin-only",
        }, "{}"u8.ToArray(), "DynamoDB", "GetItem");

    [Theory(Timeout = 15000)]
    [InlineData(true)]
    [InlineData(false)]
    public async Task SendsRequestsAndHandlesInBandAndDeferredReplies(bool resp3)
    {
        var cancellationToken = TestContext.Current.CancellationToken;
        using var root = TestCertificate.Create();
        await using var server = new MockCache(root);
        var startup = OpenAsync(server, root, cancellationToken);
        await HandshakeAsync(server, resp3: resp3);
        await using var cache = await startup;
        Assert.Equal("1.0", cache.CurrentConfig!.Value.GetProperty("version").GetString());

        var first = cache.SendAsync(Request, cancellationToken);
        var request = await server.ReadCommandAsync();
        Assert.Equal(["aws.request", "POST", Request.Url, "x-amz-target:DynamoDB_20120810.GetItem", "{}", "service=dynamodb&op=GetItem"], request);
        if (resp3)
        {
            await server.SendAsync("+first\r\n");
        }

        var second = cache.SendAsync(Request, cancellationToken);
        Assert.Equal(request, await server.ReadCommandAsync());
        await server.SendAsync(MockCache.Served(resp3 ? "second" : "first"));
        if (resp3)
        {
            Assert.Equal("second", Encoding.UTF8.GetString((await second).Body.Span));
            Assert.False(first.IsCompleted);
            await server.SendAsync(MockCache.Push("first", MockCache.Served("first")));
        }
        else
        {
            await server.SendAsync(MockCache.Served("second"));
        }

        Assert.Equal("first", Encoding.UTF8.GetString((await first).Body.Span));
        Assert.Equal("second", Encoding.UTF8.GetString((await second).Body.Span));
    }

    [Theory(Timeout = 15000)]
    [InlineData(false)]
    [InlineData(true)]
    public async Task RejectsCertificateBeforeReadingCredentials(bool wrongName)
    {
        var cancellationToken = TestContext.Current.CancellationToken;
        using var root = TestCertificate.Create();
        await using var server = new MockCache(root);
        var credentials = new RotatingCredentials();
        var options = Options(server, wrongName ? root : null, tlsName: wrongName ? "wrong.example" : null);
        var accept = server.AcceptAsync();
        var error = await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionException>(
            () => ElastiCacheIntegrationsCache.OpenAsync(options, credentials, cancellationToken));
        Assert.IsType<AuthenticationException>(error.InnerException);
        await Record.ExceptionAsync(() => accept);
        Assert.Equal(0, credentials.Reads);
    }

    [Fact(Timeout = 15000)]
    public async Task AuthRejectionRedactsEchoedTokenAndSkipsConfig()
    {
        var cancellationToken = TestContext.Current.CancellationToken;
        using var root = TestCertificate.Create();
        await using var server = new MockCache(root);
        var startup = OpenAsync(server, root, cancellationToken);
        await server.AcceptAsync();
        var auth = await server.ReadCommandAsync();
        await server.SendAsync($"-ERR {auth[2]}\r\n");
        var error = await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionException>(() => startup);
        Assert.Equal("AUTH_ERROR", Assert.IsType<ElastiCacheIntegrationsServerException>(error.InnerException).Code);
        Assert.DoesNotContain("X-Amz-", error.ToString());
        await server.WaitForCloseAsync();
    }

    [Fact(Timeout = 15000)]
    public async Task RefreshesCredentialsAndConfigAndRetainsConfigOnNull()
    {
        var cancellationToken = TestContext.Current.CancellationToken;
        using var root = TestCertificate.Create();
        await using var server = new MockCache(root);
        var credentials = new RotatingCredentials();
        var startup = ElastiCacheIntegrationsCache.OpenAsync(Options(server, root, reauthSeconds: 1), credentials, cancellationToken);
        var initial = await HandshakeAsync(server, config: """{"version":"1.0","refresh_interval":1}""");
        Assert.Contains("X-Amz-Credential=TEST1%2F", initial);
        await using var cache = await startup;

        var rotated = false;
        var refreshed = 0;
        while (!rotated || refreshed < 2)
        {
            var command = await server.ReadCommandAsync();
            if (command[0] == "AUTH")
            {
                Assert.DoesNotContain("X-Amz-Credential=TEST1%2F", command[2]);
                Assert.Contains("X-Amz-Security-Token=session", command[2]);
                rotated = true;
                await server.SendAsync("+OK\r\n");
            }
            else
            {
                Assert.Equal(["aws.config"], command);
                await server.SendAsync(refreshed++ == 0 ? MockCache.Bulk("""
                    {"version":"1.1","refresh_interval":1,"marker":true}
                    """) : "_\r\n");
            }
        }

        Assert.True(cache.CurrentConfig!.Value.GetProperty("marker").GetBoolean());
    }

    [Fact(Timeout = 15000)]
    public async Task ConnectionLossFailsSentRequestAndReconnectsForTheNextRequest()
    {
        var cancellationToken = TestContext.Current.CancellationToken;
        using var root = TestCertificate.Create();
        await using var server = new MockCache(root);
        var startup = OpenAsync(server, root, cancellationToken);
        await HandshakeAsync(server);
        await using var cache = await startup;

        var first = cache.SendAsync(Request, cancellationToken);
        Assert.Equal("aws.request", (await server.ReadCommandAsync())[0]);
        server.Drop();
        await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionLostException>(() => first);
        await HandshakeAsync(server);

        var next = cache.SendAsync(Request, cancellationToken);
        Assert.Equal("aws.request", (await server.ReadCommandAsync())[0]);
        await server.SendAsync(MockCache.Served("fresh", 202));
        Assert.Equal(202, (await next).StatusCode);
        await cache.DisposeAsync();
        await server.WaitForCloseAsync();
    }

    [Fact(Timeout = 15000)]
    public async Task RejectsMalformedRepliesAndBoundsPendingRequestsAndFrameSize()
    {
        var cancellationToken = TestContext.Current.CancellationToken;
        using var root = TestCertificate.Create();
        await using var server = new MockCache(root);
        var startup = ElastiCacheIntegrationsCache.OpenAsync(Options(server, root, maxPending: 1, maxResponseSize: 128), new RotatingCredentials(), cancellationToken);
        await HandshakeAsync(server);
        await using var cache = await startup;

        var malformed = cache.SendAsync(Request, cancellationToken);
        Assert.Equal("aws.request", (await server.ReadCommandAsync())[0]);
        await Assert.ThrowsAsync<ElastiCacheIntegrationsPendingLimitException>(() => cache.SendAsync(Request, cancellationToken));
        await server.SendAsync(":123\r\n");
        await Assert.ThrowsAsync<ElastiCacheIntegrationsProtocolException>(() => malformed);

        var oversized = cache.SendAsync(Request, cancellationToken);
        Assert.Equal("aws.request", (await server.ReadCommandAsync())[0]);
        await server.SendAsync(MockCache.Served(new string('x', 128)));
        var error = await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionLostException>(() => oversized);
        Assert.Contains("MaxResponseSize", Assert.IsType<ElastiCacheIntegrationsProtocolException>(error.InnerException).Message);
    }

    [Fact(Timeout = 15000)]
    public async Task InBandTimeoutClosesTheWireAndDoesNotReplay()
    {
        var cancellationToken = TestContext.Current.CancellationToken;
        using var root = TestCertificate.Create();
        await using var server = new MockCache(root);
        var startup = ElastiCacheIntegrationsCache.OpenAsync(Options(server, root, requestTimeoutMs: 500), new RotatingCredentials(), cancellationToken);
        await HandshakeAsync(server);
        await using var cache = await startup;
        var timedOut = cache.SendAsync(Request, cancellationToken);
        Assert.Equal("aws.request", (await server.ReadCommandAsync())[0]);
        await Assert.ThrowsAsync<ElastiCacheIntegrationsTimeoutException>(() => timedOut);
        await server.WaitForCloseAsync();
        await HandshakeAsync(server);
        await cache.DisposeAsync();
        await server.WaitForCloseAsync();
    }

    [Fact(Timeout = 15000)]
    public async Task DeferredTimeoutReleasesCapacityAndLatePushDoesNotAffectNextRequest()
    {
        var cancellationToken = TestContext.Current.CancellationToken;
        using var root = TestCertificate.Create();
        await using var server = new MockCache(root);
        var startup = ElastiCacheIntegrationsCache.OpenAsync(Options(server, root, maxPending: 1, requestTimeoutMs: 750), new RotatingCredentials(), cancellationToken);
        await HandshakeAsync(server);
        await using var cache = await startup;
        var timedOut = cache.SendAsync(Request, cancellationToken);
        Assert.Equal("aws.request", (await server.ReadCommandAsync())[0]);
        await server.SendAsync("+deferred\r\n");
        await Assert.ThrowsAsync<ElastiCacheIntegrationsTimeoutException>(() => timedOut);

        var next = cache.SendAsync(Request, cancellationToken);
        Assert.Equal("aws.request", (await server.ReadCommandAsync())[0]);
        await server.SendAsync(MockCache.Push("deferred", MockCache.Served("late")));
        await server.SendAsync(MockCache.Served("next", 201));
        Assert.Equal(201, (await next).StatusCode);
    }

    [Theory(Timeout = 15000)]
    [InlineData(true)]
    [InlineData(false)]
    public async Task DisposeStopsAdmissionAndEitherDrainsOrAbortsPendingReplies(bool drain)
    {
        var cancellationToken = TestContext.Current.CancellationToken;
        using var root = TestCertificate.Create();
        await using var server = new MockCache(root);
        var startup = OpenAsync(server, root, cancellationToken);
        await HandshakeAsync(server);
        await using var cache = await startup;
        var pending = cache.SendAsync(Request, cancellationToken);
        Assert.Equal("aws.request", (await server.ReadCommandAsync())[0]);

        if (!drain)
        {
            cache.Dispose();
        }

        var close = cache.DisposeAsync().AsTask();
        await Assert.ThrowsAsync<ObjectDisposedException>(() => cache.SendAsync(Request, cancellationToken));
        if (drain)
        {
            Assert.False(close.IsCompleted);
            await server.SendAsync(MockCache.Served("done"));
            Assert.Equal(200, (await pending).StatusCode);
        }
        else
        {
            await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionLostException>(() => pending);
        }

        await close;
        await server.WaitForCloseAsync();
        Assert.Null(cache.CurrentConfig);
    }

    private static Task<ElastiCacheIntegrationsCache> OpenAsync(MockCache server, X509Certificate2 root, CancellationToken cancellationToken) =>
        ElastiCacheIntegrationsCache.OpenAsync(Options(server, root), new RotatingCredentials(), cancellationToken);

    private static ElastiCacheIntegrationsClientOptions Options(MockCache server, X509Certificate2? root, string? tlsName = null,
        int maxPending = 100, int maxResponseSize = 4096, int requestTimeoutMs = 3000, int reauthSeconds = 1800) => new()
        {
            Endpoint = server.Endpoint,
            CacheName = "test-cache",
            Region = "us-east-1",
            CaCertificates = root is null ? null : [root],
            TlsServerName = tlsName,
            MaxPending = maxPending,
            MaxResponseSize = maxResponseSize,
            RequestTimeoutMs = requestTimeoutMs,
            ConfigTimeoutMs = 3000,
            ConnectTimeoutMs = 3000,
            ReconnectWaitMs = 3000,
            ReauthIntervalSeconds = reauthSeconds,
            Reconnect = new() { ConnectAttempts = 1 },
        };

    private static async Task<string> HandshakeAsync(MockCache server, bool resp3 = true, string config = Config)
    {
        await server.AcceptAsync();
        var auth = await server.ReadCommandAsync();
        Assert.Equal(3, auth.Length);
        Assert.Equal("AUTH", auth[0]);
        Assert.Equal("default.iam-user", auth[1]);
        Assert.StartsWith("test-cache/?Action=connect&User=default.iam-user&ResourceType=ServerlessCache", auth[2]);
        await server.SendAsync("+OK\r\n");
        Assert.Equal(["HELLO", "3"], await server.ReadCommandAsync());
        await server.SendAsync(resp3 ? "%1\r\n+proto\r\n:3\r\n" : "-NOPROTO RESP2 only\r\n");
        Assert.Equal(["aws.config", """{"version":"1.0"}"""], await server.ReadCommandAsync());
        await server.SendAsync(MockCache.Bulk(config));
        return auth[2];
    }

    private sealed class RotatingCredentials : AWSCredentials
    {
        private int _reads;

        public int Reads => Volatile.Read(ref _reads);

        public override ImmutableCredentials GetCredentials() => new($"TEST{Interlocked.Increment(ref _reads)}", "test-signing-key", "session");

        public override Task<ImmutableCredentials> GetCredentialsAsync() => Task.FromResult(GetCredentials());
    }
}
