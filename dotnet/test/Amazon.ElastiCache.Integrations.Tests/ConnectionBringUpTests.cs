// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using System.Text;
using Microsoft.Extensions.Time.Testing;

namespace Amazon.ElastiCache.Integrations.Tests;

public class ConnectionBringUpTests
{
    private const string ConfigJson = """{"version":"1.0","refresh_interval":60,"predicates":[]}""";
    private static readonly byte[] Overrides = Encoding.ASCII.GetBytes("""{"version":"1.0","item_ttl":30}""");
    private static readonly RespValue HelloReply =
        new RespMap([new(new RespBlobString("proto"u8.ToArray()), new RespInteger(3))]);

    private static CancellationToken Token => TestContext.Current.CancellationToken;

    private static ElastiCacheIntegrationsClientOptions Options(MockCache cache) => ConnectionTests.Rig.Options(cache);

    private static Task<ElastiCacheIntegrationsConnection> OpenAsync(
        ElastiCacheIntegrationsClientOptions options, IElastiCacheIntegrationsAuthProvider? auth = null, TimeProvider? time = null, ListLogger? logger = null) =>
        ElastiCacheIntegrationsConnection.OpenAsync(
            options, auth ?? new ConnectionTests.StaticAuth("my-user", "token-1"), Overrides, logger ?? new ListLogger(), time ?? new FakeTimeProvider(), Token);

    /// <summary>Answers AUTH, HELLO and aws.config, checking what the client sent.</summary>
    private static async Task HandshakeAsync(MockCache cache, RespValue? hello = null, string config = ConfigJson)
    {
        Assert.Equal(["AUTH", "my-user", "token-1"], await cache.ReadCommandAsync());
        await cache.SendAsync("+OK\r\n");
        Assert.Equal(["HELLO", "3"], await cache.ReadCommandAsync());
        await cache.SendAsync(hello ?? HelloReply);
        Assert.Equal(["aws.config", Encoding.ASCII.GetString(Overrides)], await cache.ReadCommandAsync());
        await cache.SendAsync(new RespBlobString(Encoding.UTF8.GetBytes(config)));
    }

    [Fact]
    public async Task BringsUpOverTlsWithResp3AndTheResolvedConfig()
    {
        using var certificate = TestCertificate.Create();
        await using var cache = new MockCache(certificate);
        var accept = cache.AcceptAsync();
        var options = new ElastiCacheIntegrationsClientOptions { Endpoint = $"localhost:{cache.Port}", CacheName = "my-cache", CaCertificates = [certificate] };

        var open = OpenAsync(options);
        await accept;
        await HandshakeAsync(cache);
        using var connection = await open;

        Assert.True(connection.Resp3);
        Assert.True(connection.IsOpen);
        Assert.Equal("1.0", connection.Config!.Version);
        Assert.Equal(TimeSpan.FromSeconds(60), connection.Config.RefreshInterval);
    }

    [Fact]
    public async Task ARefusedHelloKeepsResp2()
    {
        await using var cache = new MockCache();
        var accept = cache.AcceptAsync();
        var logger = new ListLogger();

        var open = OpenAsync(Options(cache), logger: logger);
        await accept;
        await HandshakeAsync(cache, hello: new RespError("NOPROTO", "unsupported protocol version"));
        using var connection = await open;

        Assert.False(connection.Resp3);
        Assert.Contains(ElastiCacheIntegrationsEvents.Resp2Fallback, logger.Ids);
    }

    [Fact]
    public async Task ARejectedAuthFailsBringUpWithoutTheToken()
    {
        await using var cache = new MockCache();
        var accept = cache.AcceptAsync();
        var logger = new ListLogger();

        var open = OpenAsync(Options(cache), logger: logger);
        await accept;
        await cache.ReadCommandAsync();
        await cache.SendAsync("-WRONGPASS invalid username-password pair or user is disabled.\r\n");

        var error = await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionException>(() => open);
        Assert.Contains("WRONGPASS", error.Message);
        Assert.DoesNotContain("token-1", error.ToString());
        Assert.DoesNotContain(logger.Messages, m => m.Contains("token-1"));
        await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionLostException>(() => cache.ReadCommandAsync());
    }

    [Fact]
    public async Task RejectedOverridesAreAConfigurationError()
    {
        await using var cache = new MockCache();
        var accept = cache.AcceptAsync();

        var open = OpenAsync(Options(cache));
        await accept;
        await cache.ReadCommandAsync();
        await cache.SendAsync("+OK\r\n");
        await cache.ReadCommandAsync();
        await cache.SendAsync(HelloReply);
        await cache.ReadCommandAsync();
        await cache.SendAsync("-CONFIG_INVALID ttl must be positive\r\n");

        await Assert.ThrowsAsync<ElastiCacheIntegrationsConfigurationException>(() => open);
    }

    [Fact]
    public async Task ABringUpStepThatTimesOutIsAConnectionError()
    {
        await using var cache = new MockCache();
        var accept = cache.AcceptAsync();
        var time = new FakeTimeProvider();

        var open = OpenAsync(Options(cache), time: time);
        await accept;
        await cache.ReadCommandAsync();
        time.Advance(TimeSpan.FromSeconds(2));

        var error = await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionException>(() => open);
        Assert.IsType<ElastiCacheIntegrationsTimeoutException>(error.InnerException);
        Assert.StartsWith("AUTH failed during bring-up", error.Message);
    }

    [Fact]
    public async Task AFailingAuthProviderIsAConnectionError()
    {
        await using var cache = new MockCache();
        var accept = cache.AcceptAsync();

        var open = OpenAsync(Options(cache), new FailingAuth());
        await accept;

        var error = await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionException>(() => open);
        Assert.StartsWith("AUTH failed during bring-up", error.Message);
        Assert.IsType<InvalidOperationException>(error.InnerException!.InnerException);
    }

    [Fact]
    public async Task AnUnexpectedHelloReplyFailsBringUp()
    {
        await using var cache = new MockCache();
        var accept = cache.AcceptAsync();

        var open = OpenAsync(Options(cache));
        await accept;
        await cache.ReadCommandAsync();
        await cache.SendAsync("+OK\r\n");
        await cache.ReadCommandAsync();
        await cache.SendAsync("+OK\r\n");

        var error = await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionException>(() => open);
        Assert.StartsWith("HELLO failed during bring-up", error.Message);
    }

    [Theory]
    [InlineData("-MOVED 3999 127.0.0.1:6381")]
    [InlineData("-LOADING the dataset is loading")]
    [InlineData("-BUSY a script is running")]
    [InlineData("-INTERNAL_ERROR transient")]
    public async Task AHelloErrorOtherThanARefusalFailsBringUpInsteadOfFallingBack(string reply)
    {
        await using var cache = new MockCache();
        var accept = cache.AcceptAsync();
        var logger = new ListLogger();

        var open = OpenAsync(Options(cache), logger: logger);
        await accept;
        await cache.ReadCommandAsync();
        await cache.SendAsync("+OK\r\n");
        await cache.ReadCommandAsync();
        await cache.SendAsync(reply + "\r\n");

        // A fallback would send aws.config next; the attempt closes the connection instead.
        await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionLostException>(() => cache.ReadCommandAsync());
        var error = await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionException>(() => open);
        Assert.StartsWith("HELLO failed during bring-up", error.Message);
        Assert.Equal(reply[1..reply.IndexOf(' ')], Assert.IsType<ElastiCacheIntegrationsServerException>(error.InnerException).Code);
        Assert.DoesNotContain(ElastiCacheIntegrationsEvents.Resp2Fallback, logger.Ids);
    }

    [Fact]
    public async Task CallerCancellationDuringBringUpPassesThrough()
    {
        await using var cache = new MockCache();
        var accept = cache.AcceptAsync();
        using var cancel = CancellationTokenSource.CreateLinkedTokenSource(Token);

        var open = ElastiCacheIntegrationsConnection.OpenAsync(
            Options(cache), new ConnectionTests.StaticAuth("my-user", "token-1"), Overrides, new ListLogger(), new FakeTimeProvider(), cancel.Token);
        await accept;
        await cache.ReadCommandAsync();
        await cancel.CancelAsync();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => open);
    }

    [Fact]
    public async Task AProviderCancellationTheCallerDidNotAskForIsAConnectionError()
    {
        await using var cache = new MockCache();
        var accept = cache.AcceptAsync();

        var open = OpenAsync(Options(cache), new FailingAuth(new TaskCanceledException("credential fetch timed out")));
        await accept;

        var error = await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionException>(() => open);
        Assert.StartsWith("AUTH failed during bring-up", error.Message);
        Assert.IsType<TaskCanceledException>(error.InnerException!.InnerException);
    }

    [Fact]
    public async Task ReauthenticateSendsAFreshToken()
    {
        await using var rig = await OpenRigAsync(new ConnectionTests.StaticAuth("my-user", "token-1", "token-2"));

        var reauth = rig.Connection.ReauthenticateAsync(Token);
        Assert.Equal(["AUTH", "my-user", "token-2"], await rig.Cache.ReadCommandAsync());
        await rig.Cache.SendAsync("+OK\r\n");

        await reauth;
    }

    [Fact]
    public async Task ARejectedReauthKeepsTheConnectionServing()
    {
        await using var rig = await OpenRigAsync();

        var reauth = rig.Connection.ReauthenticateAsync(Token);
        await rig.Cache.ReadCommandAsync();
        await rig.Cache.SendAsync("-WRONGPASS invalid username-password pair or user is disabled.\r\n");

        Assert.Equal("WRONGPASS", (await Assert.ThrowsAsync<ElastiCacheIntegrationsServerException>(() => reauth)).Code);
        Assert.True(rig.Connection.IsOpen);
        var request = rig.Connection.RequestAsync(ConnectionTestsRequest(), Token);
        await rig.Cache.ReadCommandAsync();
        await rig.Cache.SendAsync(MockCache.Served("ok"));
        Assert.IsType<RequestReply.Served>(await request);
    }

    [Fact]
    public async Task RefreshKeepsTheConfigOnNullAndReplacesItOnANewOne()
    {
        await using var rig = await OpenRigAsync();
        var first = rig.Connection.Config;

        var unchanged = rig.Connection.RefreshConfigAsync(Token);
        Assert.Equal(["aws.config"], await rig.Cache.ReadCommandAsync());
        await rig.Cache.SendAsync("_\r\n");
        Assert.Null(await unchanged);
        Assert.Same(first, rig.Connection.Config);

        var changed = rig.Connection.RefreshConfigAsync(Token);
        await rig.Cache.ReadCommandAsync();
        await rig.Cache.SendAsync(new RespBlobString("""{"version":"1.1","refresh_interval":60}"""u8.ToArray()));
        var config = await changed;
        Assert.Equal("1.1", config!.Version);
        Assert.Same(config, rig.Connection.Config);
    }

    [Fact]
    public async Task ARefreshErrorKeepsTheConnectionAndTheConfig()
    {
        await using var rig = await OpenRigAsync();
        var first = rig.Connection.Config;

        var refresh = rig.Connection.RefreshConfigAsync(Token);
        await rig.Cache.ReadCommandAsync();
        await rig.Cache.SendAsync("-INTERNAL_ERROR try again\r\n");

        await Assert.ThrowsAsync<ElastiCacheIntegrationsServerException>(() => refresh);
        Assert.Same(first, rig.Connection.Config);
        Assert.True(rig.Connection.IsOpen);
    }

    private static ElastiCacheIntegrationsRequest ConnectionTestsRequest() => new(
        "POST",
        "https://dynamodb.us-east-1.amazonaws.com/",
        new Dictionary<string, string>(),
        "{}"u8.ToArray(),
        new RequestMetadata("dynamodb", "GetItem"));

    private static async Task<OpenRig> OpenRigAsync(IElastiCacheIntegrationsAuthProvider? auth = null)
    {
        var cache = new MockCache();
        try
        {
            var accept = cache.AcceptAsync();
            var open = OpenAsync(Options(cache), auth);
            await accept;
            await HandshakeAsync(cache);
            return new OpenRig(cache, await open);
        }
        catch
        {
            await cache.DisposeAsync();
            throw;
        }
    }

    private sealed record OpenRig(MockCache Cache, ElastiCacheIntegrationsConnection Connection) : IAsyncDisposable
    {
        public async ValueTask DisposeAsync()
        {
            Connection.Dispose();
            await Cache.DisposeAsync();
        }
    }

    private sealed class FailingAuth(Exception? error = null) : IElastiCacheIntegrationsAuthProvider
    {
        public ValueTask<(string UserId, string Token)> GetAuthAsync(CancellationToken cancellationToken) =>
            throw error ?? new InvalidOperationException("no credentials");
    }
}
