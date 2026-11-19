using System.Text.Json;

namespace Amazon.ElastiCache.Integrations.Tests;

public class OptionsTests
{
    private static ElastiCacheIntegrationsClientOptions Valid() => new() { Endpoint = "cache.example.com:6379", CacheName = "mycache" };

    [Fact]
    public void DefaultsMatchTheDesign()
    {
        var options = Valid();

        Assert.Equal("default.iam-user", options.UserId);
        Assert.True(options.UseTls);
        Assert.False(options.InsecureSkipVerify);
        Assert.Equal(TimeSpan.FromSeconds(1), options.ConnectTimeout);
        Assert.Equal(TimeSpan.FromSeconds(5), options.RequestTimeout);
        Assert.Equal(TimeSpan.FromSeconds(2), options.ConfigTimeout);
        Assert.Equal(TimeSpan.FromMinutes(30), options.ReauthInterval);
        Assert.Equal(TimeSpan.FromMilliseconds(100), options.ReconnectWait);
        Assert.Equal(Timeout.InfiniteTimeSpan, options.FallbackDuration);
        Assert.Equal(TimeSpan.FromHours(1), options.CachePolicyTimeout);
        Assert.Equal(100, options.MaxPending);
        Assert.Equal(16 * 1024 * 1024, options.MaxResponseSize);
        Assert.Equal(3, options.Reconnect.ConnectAttempts);
        Assert.Equal(TimeSpan.FromSeconds(5), options.Reconnect.AttemptTimeout);
        Assert.Equal(TimeSpan.FromMilliseconds(100), options.Reconnect.BaseDelay);
        Assert.Equal(TimeSpan.FromMilliseconds(3200), options.Reconnect.MaxDelay);
        Assert.Equal(0.2, options.Reconnect.Jitter);
        OptionsValidator.Validate(options);
    }

    [Fact]
    public void ZeroFallbackAndZeroReconnectWaitAreValid()
    {
        OptionsValidator.Validate(new ElastiCacheIntegrationsClientOptions { Endpoint = "h:1", CacheName = "c", FallbackDuration = TimeSpan.Zero });
        OptionsValidator.Validate(new ElastiCacheIntegrationsClientOptions { Endpoint = "h:1", CacheName = "c", ReconnectWait = TimeSpan.Zero });
    }

    [Theory]
    [InlineData("cache.example.com:6379", "cache.example.com", 6379)]
    [InlineData("[::1]:6380", "::1", 6380)]
    [InlineData("::1:6380", "::1", 6380)]
    [InlineData("h:+1", "h", 1)]
    [InlineData("h:65535", "h", 65535)]
    [InlineData("h: 6379", "h", 6379)]
    public void ParsesEndpoints(string endpoint, string host, int port)
    {
        Assert.Equal((host, port), OptionsValidator.ParseEndpoint(endpoint));
    }

    [Theory]
    [InlineData("")]
    [InlineData("host")]
    [InlineData(":6379")]
    [InlineData("host:")]
    [InlineData("[]:6379")]
    [InlineData("host:0")]
    [InlineData("host:65536")]
    [InlineData("host:63x")]
    [InlineData("host:-1")]
    [InlineData(" :6379")]
    [InlineData("[ ]:6379")]
    [InlineData(null)]
    public void RejectsBadEndpoints(string? endpoint)
    {
        var error = Assert.Throws<ElastiCacheIntegrationsConfigurationException>(
            () => OptionsValidator.Validate(new ElastiCacheIntegrationsClientOptions { Endpoint = endpoint!, CacheName = "c" }));
        Assert.StartsWith("ElastiCacheIntegrationsClientOptions.Endpoint ", error.Message);
    }

    public static TheoryData<string, Func<ElastiCacheIntegrationsClientOptions>> BadOptions => new()
    {
        { "CacheName", () => new() { Endpoint = "h:1", CacheName = " " } },
        { "UserId", () => new() { Endpoint = "h:1", CacheName = "c", UserId = "" } },
        { "Region", () => new() { Endpoint = "h:1", CacheName = "c", Region = "" } },
        { "TlsServerName", () => new() { Endpoint = "h:1", CacheName = "c", TlsServerName = " " } },
        { "UseTls", () => new() { Endpoint = "h:1", CacheName = "c", UseTls = false, InsecureSkipVerify = true } },
        { "UseTls", () => new() { Endpoint = "h:1", CacheName = "c", UseTls = false, TlsServerName = "n" } },
        { "UseTls", () => new() { Endpoint = "h:1", CacheName = "c", UseTls = false, CaCertificates = [] } },
        { "Overrides", () => new() { Endpoint = "h:1", CacheName = "c", Overrides = null! } },
        { "ConnectTimeout", () => new() { Endpoint = "h:1", CacheName = "c", ConnectTimeout = TimeSpan.Zero } },
        { "RequestTimeout", () => new() { Endpoint = "h:1", CacheName = "c", RequestTimeout = Timeout.InfiniteTimeSpan } },
        { "ConfigTimeout", () => new() { Endpoint = "h:1", CacheName = "c", ConfigTimeout = TimeSpan.FromDays(30) } },
        { "ReauthInterval", () => new() { Endpoint = "h:1", CacheName = "c", ReauthInterval = TimeSpan.FromSeconds(-1) } },
        { "ReauthInterval", () => new() { Endpoint = "h:1", CacheName = "c", ReauthInterval = TimeSpan.FromHours(12) } },
        { "RequestTimeout", () => new() { Endpoint = "h:1", CacheName = "c", RequestTimeout = TimeSpan.FromTicks(5000) } },
        { "Overrides.CachedOperations", () => WithOverrides(new() { CachedOperations = ["Scan", ""] }) },
        { "Overrides.CachedTables", () => WithOverrides(new() { CachedTables = [null!] }) },
        { "Overrides.ItemTtl", () => WithOverrides(new() { ItemTtl = -1 }) },
        { "Overrides.ItemWriteBehavior", () => WithOverrides(new() { ItemWriteBehavior = (ItemWriteBehavior)7 }) },
        { "Overrides.Extra[\"item_ttl\"]", () => WithOverrides(new() { Extra = Extra("item_ttl", "1") }) },
        { "Overrides.Extra[\"table_overrides\"]", () => WithOverrides(new() { Extra = Extra("table_overrides", "{}") }) },
        { "Overrides.Extra[\"version\"]", () => WithOverrides(new() { Extra = Extra("version", "\"1.0\"") }) },
        { "Overrides.Extra[\"future\"]", () => WithOverrides(new() { Extra = new Dictionary<string, JsonElement> { ["future"] = default } }) },
        { "Overrides.TableOverrides key", () => WithOverrides(new() { TableOverrides = new Dictionary<string, ElastiCacheIntegrationsTableOverrides> { [" "] = new() } }) },
        { "Overrides.TableOverrides[\"Users\"]", () => WithOverrides(new() { TableOverrides = new Dictionary<string, ElastiCacheIntegrationsTableOverrides> { ["Users"] = null! } }) },
        { "Overrides.TableOverrides[\"Users\"].ScanTtl", () => WithOverrides(new() { TableOverrides = new Dictionary<string, ElastiCacheIntegrationsTableOverrides> { ["Users"] = new() { ScanTtl = -5 } } }) },
        { "Overrides.TableOverrides[\"Users\"].Extra[\"query_ttl\"]", () => WithOverrides(new() { TableOverrides = new Dictionary<string, ElastiCacheIntegrationsTableOverrides> { ["Users"] = new() { Extra = Extra("query_ttl", "1") } } }) },
        { "ReconnectWait", () => new() { Endpoint = "h:1", CacheName = "c", ReconnectWait = TimeSpan.FromSeconds(-1) } },
        { "FallbackDuration", () => new() { Endpoint = "h:1", CacheName = "c", FallbackDuration = TimeSpan.FromSeconds(-2) } },
        { "CachePolicyTimeout", () => new() { Endpoint = "h:1", CacheName = "c", CachePolicyTimeout = TimeSpan.Zero } },
        { "CachePolicyTimeout", () => new() { Endpoint = "h:1", CacheName = "c", CachePolicyTimeout = TimeSpan.FromSeconds(-5) } },
        { "CachePolicyTimeout", () => new() { Endpoint = "h:1", CacheName = "c", CachePolicyTimeout = Timeout.InfiniteTimeSpan } },
        { "MaxPending", () => new() { Endpoint = "h:1", CacheName = "c", MaxPending = 0 } },
        { "MaxResponseSize", () => new() { Endpoint = "h:1", CacheName = "c", MaxResponseSize = 0 } },
        { "Reconnect", () => new() { Endpoint = "h:1", CacheName = "c", Reconnect = null! } },
        { "Reconnect.ConnectAttempts", () => WithReconnect(new() { ConnectAttempts = 0 }) },
        { "Reconnect.AttemptTimeout", () => WithReconnect(new() { AttemptTimeout = TimeSpan.Zero }) },
        { "Reconnect.BaseDelay", () => WithReconnect(new() { BaseDelay = TimeSpan.Zero }) },
        { "Reconnect.MaxDelay", () => WithReconnect(new() { MaxDelay = TimeSpan.FromMilliseconds(50) }) },
        { "Reconnect.Jitter", () => WithReconnect(new() { Jitter = 1 }) },
        { "Reconnect.Jitter", () => WithReconnect(new() { Jitter = double.NaN }) },
    };

    [Theory]
    [MemberData(nameof(BadOptions))]
    public void RejectsBadOptionsByName(string name, Func<ElastiCacheIntegrationsClientOptions> make)
    {
        var error = Assert.Throws<ElastiCacheIntegrationsConfigurationException>(() => OptionsValidator.Validate(make()));
        Assert.StartsWith($"ElastiCacheIntegrationsClientOptions.{name} ", error.Message);
    }

    [Fact]
    public void EmptyOverridesSerializeToAnEmptyObject()
    {
        Assert.Equal("{}", JsonSerializer.Serialize(new ElastiCacheIntegrationsOverrides()));
        Assert.Equal("{}", JsonSerializer.Serialize(new ElastiCacheIntegrationsTableOverrides()));
    }

    [Fact]
    public void OverridesUseTheProtocolNames()
    {
        var overrides = new ElastiCacheIntegrationsOverrides
        {
            CachedTables = ["Users"],
            CachedOperations = ["GetItem"],
            ItemTtl = 300,
            ItemNegativeTtl = 0,
            QueryTtl = 60,
            ScanTtl = 30,
            ItemWriteBehavior = ItemWriteBehavior.UpdateCachedAndStoreFullItem,
            TableOverrides = new Dictionary<string, ElastiCacheIntegrationsTableOverrides>
            {
                ["Users"] = new() { ItemTtl = 600, ItemWriteBehavior = ItemWriteBehavior.InvalidateOnly, Extra = Extra("t", "1") },
            },
            Extra = Extra("future", "true"),
        };

        Assert.Equal(
            """{"cached_tables":["Users"],"cached_operations":["GetItem"],"item_ttl":300,"item_negative_ttl":0,"query_ttl":60,"scan_ttl":30,"item_write_behavior":"UPDATE_CACHED_AND_STORE_FULL_ITEM","table_overrides":{"Users":{"item_ttl":600,"item_write_behavior":"INVALIDATE_ONLY","t":1}},"future":true}""",
            JsonSerializer.Serialize(overrides));
        OptionsValidator.Validate(new ElastiCacheIntegrationsClientOptions { Endpoint = "h:1", CacheName = "c", Overrides = overrides });
    }

    [Theory]
    [InlineData(ItemWriteBehavior.InvalidateOnly, "INVALIDATE_ONLY")]
    [InlineData(ItemWriteBehavior.UpdateCached, "UPDATE_CACHED")]
    [InlineData(ItemWriteBehavior.UpdateCachedAndStoreFullItem, "UPDATE_CACHED_AND_STORE_FULL_ITEM")]
    public void WriteBehaviorsUseTheProxyLabels(ItemWriteBehavior behavior, string label) =>
        Assert.Equal($"{{\"item_write_behavior\":\"{label}\"}}", JsonSerializer.Serialize(new ElastiCacheIntegrationsTableOverrides { ItemWriteBehavior = behavior }));

    /// <summary>The example in the README, and in the proxy's config.rs test_the_documented_example_resolves_as_written.</summary>
    [Fact]
    public void TheReadmeExampleSerializesToTheProxysDocumentedExample()
    {
        var overrides = new ElastiCacheIntegrationsOverrides
        {
            CachedTables = ["Users", "Sessions"],
            CachedOperations = ["GetItem", "Query"],
            ItemTtl = 500,
            ItemNegativeTtl = 400,
            QueryTtl = 300,
            ScanTtl = 200,
            ItemWriteBehavior = ItemWriteBehavior.UpdateCached,
            TableOverrides = new Dictionary<string, ElastiCacheIntegrationsTableOverrides>
            {
                ["Users"] = new() { ItemTtl = 600 },
                ["Sessions"] = new() { QueryTtl = 90, ItemWriteBehavior = ItemWriteBehavior.InvalidateOnly },
                ["Foo"] = new() { QueryTtl = 90 },
            },
        };
        const string proxyExample = """
            {
                "cached_tables": ["Users", "Sessions"],
                "cached_operations": ["GetItem", "Query"],
                "item_ttl": 500,
                "item_negative_ttl": 400,
                "query_ttl": 300,
                "scan_ttl": 200,
                "item_write_behavior": "UPDATE_CACHED",
                "table_overrides": {
                    "Users": { "item_ttl": 600 },
                    "Sessions": { "query_ttl": 90, "item_write_behavior": "INVALIDATE_ONLY" },
                    "Foo": { "query_ttl": 90 }
                }
            }
            """;

        using var expected = JsonDocument.Parse(proxyExample);
        Assert.Equal(JsonSerializer.Serialize(expected.RootElement), JsonSerializer.Serialize(overrides));
    }

    private static Dictionary<string, JsonElement> Extra(string key, string json)
    {
        using var document = JsonDocument.Parse(json);
        return new() { [key] = document.RootElement.Clone() };
    }

    private static ElastiCacheIntegrationsClientOptions WithOverrides(ElastiCacheIntegrationsOverrides overrides) =>
        new() { Endpoint = "h:1", CacheName = "c", Overrides = overrides };

    private static ElastiCacheIntegrationsClientOptions WithReconnect(ElastiCacheIntegrationsReconnectPolicy reconnect) =>
        new() { Endpoint = "h:1", CacheName = "c", Reconnect = reconnect };
}
