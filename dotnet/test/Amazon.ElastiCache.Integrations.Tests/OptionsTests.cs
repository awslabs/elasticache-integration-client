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
        Assert.Equal(TimeSpan.FromMilliseconds(100), options.RequestTimeout);
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
        { "Overrides.ExcludeOperations", () => WithOverrides(new() { ExcludeOperations = ["Scan", ""] }) },
        { "Overrides.ExcludeTables", () => WithOverrides(new() { ExcludeTables = [null!] }) },
        { "Overrides.Extra[\"ttl\"]", () => WithOverrides(new() { Extra = Extra("ttl", "1") }) },
        { "Overrides.Extra[\"version\"]", () => WithOverrides(new() { Extra = Extra("version", "\"1.0\"") }) },
        { "Overrides.Extra[\"future\"]", () => WithOverrides(new() { Extra = new Dictionary<string, JsonElement> { ["future"] = default } }) },
        { "Overrides.Tables key", () => WithOverrides(new() { Tables = new Dictionary<string, ElastiCacheIntegrationsTableOverrides> { [" "] = new() } }) },
        { "Overrides.Tables[\"Users\"]", () => WithOverrides(new() { Tables = new Dictionary<string, ElastiCacheIntegrationsTableOverrides> { ["Users"] = null! } }) },
        { "Overrides.Tables[\"Users\"].Extra[\"nttl\"]", () => WithOverrides(new() { Tables = new Dictionary<string, ElastiCacheIntegrationsTableOverrides> { ["Users"] = new() { Extra = Extra("nttl", "1") } } }) },
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
            ExcludeOperations = ["Scan"],
            ExcludeTables = ["AuditLog"],
            Ttl = 300,
            Nttl = 30,
            PopulateOnWrite = true,
            PopulateFullItem = false,
            Tables = new Dictionary<string, ElastiCacheIntegrationsTableOverrides>
            {
                ["Users"] = new() { Ttl = 600, Nttl = 60, PopulateOnWrite = false, PopulateFullItem = true, Extra = Extra("t", "1") },
            },
            Extra = Extra("future", "true"),
        };

        Assert.Equal(
            """{"exclude_operations":["Scan"],"exclude_tables":["AuditLog"],"ttl":300,"nttl":30,"populate_on_write":true,"populate_full_item":false,"tables":{"Users":{"ttl":600,"nttl":60,"populate_on_write":false,"populate_full_item":true,"t":1}},"future":true}""",
            JsonSerializer.Serialize(overrides));
        OptionsValidator.Validate(new ElastiCacheIntegrationsClientOptions { Endpoint = "h:1", CacheName = "c", Overrides = overrides });
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
