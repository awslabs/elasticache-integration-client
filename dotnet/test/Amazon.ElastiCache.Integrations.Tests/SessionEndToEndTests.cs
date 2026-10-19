using System.Text;
using Microsoft.Extensions.Time.Testing;

namespace Amazon.ElastiCache.Integrations.Tests;

/// <summary>The session over a real <see cref="ElastiCacheIntegrationsConnection"/> and <see cref="MockCache"/>.</summary>
public class SessionEndToEndTests
{
    private const string ConfigJson = """{"version":"1.0","predicates":[{"expr":"true"}]}""";

    private static CancellationToken Token => TestContext.Current.CancellationToken;

    private static async Task HandshakeAsync(MockCache cache, string token, string config = ConfigJson)
    {
        Assert.Equal(["AUTH", "user", token], await cache.ReadCommandAsync());
        await cache.SendAsync("+OK\r\n");
        Assert.Equal(["HELLO", "3"], await cache.ReadCommandAsync());
        await cache.SendAsync(new RespMap([new(new RespBlobString("proto"u8.ToArray()), new RespInteger(3))]));
        Assert.Equal(["aws.config", """{"version":"1.0","ttl":30}"""], await cache.ReadCommandAsync());
        await cache.SendAsync(new RespBlobString(Encoding.UTF8.GetBytes(config)));
    }

    private static async Task<ElastiCacheIntegrationsOutcome> ServeAsync(ElastiCacheIntegrationsSession session, MockCache cache, string body)
    {
        var request = session.RequestAsync(SessionRig.Request(), Token);
        var command = await cache.ReadCommandAsync();
        Assert.Equal("aws.request", command[0]);
        await cache.SendAsync(MockCache.Served(body));
        return await request;
    }

    [Fact]
    public async Task ServesReconnectsAfterADropAndServesAgain()
    {
        await using var cache = new MockCache();
        var options = new ElastiCacheIntegrationsClientOptions
        {
            Endpoint = cache.Endpoint,
            CacheName = "my-cache",
            UseTls = false,
            Overrides = new ElastiCacheIntegrationsOverrides { Ttl = 30 },
        };

        var accept = cache.AcceptAsync();
        var open = ElastiCacheIntegrationsSession.OpenAsync(options, new ConnectionTests.StaticAuth("user", "token-1", "token-2"), "dynamodb", Token);
        await accept;
        await HandshakeAsync(cache, "token-1");
        await using var session = await open;

        var first = await ServeAsync(session, cache, """{"Item":1}""");
        Assert.Equal("""{"Item":1}""", Encoding.UTF8.GetString(first.Response!.Body.Span));

        accept = cache.AcceptAsync();
        cache.Drop();
        await accept;
        await HandshakeAsync(cache, "token-2");
        for (var i = 0; i < 500 && !session.IsOpen; i++)
        {
            await Task.Delay(10, Token);
        }

        Assert.True(session.IsOpen);
        var second = await ServeAsync(session, cache, """{"Item":2}""");
        Assert.Equal("""{"Item":2}""", Encoding.UTF8.GetString(second.Response!.Body.Span));
    }

    [Fact]
    public async Task AFailedRefreshPastTheTimeoutReconnectsForACurrentConfig()
    {
        const string refreshing = """{"version":"1.0","refresh_interval":60,"predicates":[{"expr":"true"}]}""";
        await using var cache = new MockCache();
        var options = new ElastiCacheIntegrationsClientOptions
        {
            Endpoint = cache.Endpoint,
            CacheName = "my-cache",
            UseTls = false,
            Overrides = new ElastiCacheIntegrationsOverrides { Ttl = 30 },
            CachePolicyTimeout = TimeSpan.FromSeconds(30),
            ConfigTimeout = TimeSpan.FromMinutes(5),
            Reconnect = new ElastiCacheIntegrationsReconnectPolicy { Jitter = 0 },
        };

        var time = new FakeTimeProvider();
        var logger = new ListLogger();
        var auth = new ConnectionTests.StaticAuth("user", "token-1", "token-2");
        var overrides = ElastiCacheIntegrationsProtocol.SerializeOverrides(options.Overrides);
        var accept = cache.AcceptAsync();
        var open = ElastiCacheIntegrationsSession.OpenAsync(
            options,
            async ct => await ElastiCacheIntegrationsConnection.OpenAsync(options, auth, overrides, logger, time, ct),
            "dynamodb",
            logger,
            time,
            new Random(1),
            Token);
        await accept;
        await HandshakeAsync(cache, "token-1", refreshing);
        await using var session = await open;

        var refresh = cache.ReadCommandAsync();
        for (var i = 0; i < 120 && !refresh.IsCompleted; i++)
        {
            time.Advance(TimeSpan.FromSeconds(1));
            await Task.Delay(10, Token);
        }

        Assert.Equal(["aws.config"], await refresh);
        accept = cache.AcceptAsync();
        await cache.SendAsync("-INTERNAL_ERROR aws.config was not processed\r\n");

        await accept;
        await HandshakeAsync(cache, "token-2", refreshing);
        for (var i = 0; i < 500 && !session.IsOpen; i++)
        {
            await Task.Delay(10, Token);
        }

        Assert.True(session.IsOpen);
        Assert.Contains(ElastiCacheIntegrationsEvents.ConfigStale, logger.Ids);
        var served = await ServeAsync(session, cache, """{"Item":3}""");
        Assert.Equal("""{"Item":3}""", Encoding.UTF8.GetString(served.Response!.Body.Span));
    }
}
