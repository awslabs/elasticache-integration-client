using System.Buffers;
using System.Text;
using System.Text.Json;

namespace Amazon.ElastiCache.Integrations.Tests;

public class ConfigProtocolTests
{
    private static RespBlobString Blob(string text) => new(Encoding.UTF8.GetBytes(text));

    private static string Json(ElastiCacheIntegrationsOverrides overrides) => Encoding.UTF8.GetString(ElastiCacheIntegrationsProtocol.SerializeOverrides(overrides));

    [Fact]
    public void SerializesEmptyOverridesWithTheVersionOnly()
    {
        Assert.Equal("{\"version\":\"1.0\"}", Json(new ElastiCacheIntegrationsOverrides()));
    }

    [Fact]
    public void SerializesEveryFieldCompactlyInOrder()
    {
        var overrides = new ElastiCacheIntegrationsOverrides
        {
            CachedTables = ["Users", "Sessions"],
            CachedOperations = ["GetItem"],
            ItemTtl = 300,
            ItemNegativeTtl = 30,
            ItemWriteBehavior = ItemWriteBehavior.UpdateCached,
            TableOverrides = new Dictionary<string, ElastiCacheIntegrationsTableOverrides>
            {
                ["Users"] = new() { ItemTtl = 600, Extra = new Dictionary<string, JsonElement> { ["future"] = JsonDocument.Parse("true").RootElement } },
                ["Sessions"] = new() { QueryTtl = 60 },
            },
        };
        Assert.Equal(
            "{\"version\":\"1.0\",\"cached_tables\":[\"Users\",\"Sessions\"],\"cached_operations\":[\"GetItem\"],\"item_ttl\":300,\"item_negative_ttl\":30," +
            "\"item_write_behavior\":\"UPDATE_CACHED\"," +
            "\"table_overrides\":{\"Users\":{\"item_ttl\":600,\"future\":true},\"Sessions\":{\"query_ttl\":60}}}",
            Json(overrides));
    }

    [Fact]
    public void SendsExtraFieldsLast()
    {
        var overrides = new ElastiCacheIntegrationsOverrides
        {
            ItemTtl = 5,
            Extra = new Dictionary<string, JsonElement> { ["b"] = JsonDocument.Parse("2").RootElement, ["a"] = JsonDocument.Parse("[1,{\"x\":null}]").RootElement },
        };
        Assert.Equal("{\"version\":\"1.0\",\"item_ttl\":5,\"b\":2,\"a\":[1,{\"x\":null}]}", Json(overrides));
    }

    [Fact]
    public void EscapesNonAsciiLikeNode()
    {
        var overrides = new ElastiCacheIntegrationsOverrides { CachedTables = ["café", "😀", "<a&b>+'"], Extra = new Dictionary<string, JsonElement> { ["é"] = JsonDocument.Parse("\"\\u00E9\\n\"").RootElement } };
        Assert.Equal(
            "{\"version\":\"1.0\",\"cached_tables\":[\"caf\\u00e9\",\"\\ud83d\\ude00\",\"<a&b>+'\"],\"\\u00e9\":\"\\u00e9\\n\"}",
            Json(overrides));
    }

    [Fact]
    public void EscapesQuotesAndControlCharacters()
    {
        Assert.Equal("{\"version\":\"1.0\",\"cached_tables\":[\"a\\\"b\\\\c\\u0001\\t\"]}", Json(new ElastiCacheIntegrationsOverrides { CachedTables = ["a\"b\\c\u0001\t"] }));
    }

    [Fact]
    public void KeepsTheCaseOfALiteralBackslashU()
    {
        Assert.Equal("{\"version\":\"1.0\",\"cached_tables\":[\"a\\\\u00E9\"]}", Json(new ElastiCacheIntegrationsOverrides { CachedTables = ["a\\u00E9"] }));
    }

    [Fact]
    public void EscapesDeleteUnlikeNode()
    {
        Assert.Equal("{\"version\":\"1.0\",\"cached_tables\":[\"\\u007f\"]}", Json(new ElastiCacheIntegrationsOverrides { CachedTables = ["\u007f"] }));
    }

    [Fact]
    public void ReplacesALoneSurrogate()
    {
        Assert.Equal("{\"version\":\"1.0\",\"cached_tables\":[\"\\ufffd\"]}", Json(new ElastiCacheIntegrationsOverrides { CachedTables = ["\ud800"] }));
    }

    [Fact]
    public void RefusesAVersionInExtra()
    {
        var overrides = new ElastiCacheIntegrationsOverrides { Extra = new Dictionary<string, JsonElement> { ["version"] = JsonDocument.Parse("\"2.0\"").RootElement } };
        Assert.Throws<ElastiCacheIntegrationsConfigurationException>(() => ElastiCacheIntegrationsProtocol.SerializeOverrides(overrides));
    }

    [Fact]
    public void WritesBothConfigForms()
    {
        var writer = new ArrayBufferWriter<byte>();
        ElastiCacheIntegrationsProtocol.WriteConfig(writer, "{\"cached_operations\":[\"Scan\"],\"item_ttl\":300}"u8.ToArray());
        Assert.Equal("*2\r\n$10\r\naws.config\r\n$45\r\n{\"cached_operations\":[\"Scan\"],\"item_ttl\":300}\r\n", Encoding.UTF8.GetString(writer.WrittenSpan));

        writer.Clear();
        ElastiCacheIntegrationsProtocol.WriteConfigRefresh(writer);
        Assert.Equal("*1\r\n$10\r\naws.config\r\n", Encoding.UTF8.GetString(writer.WrittenSpan));
    }

    [Fact]
    public void ReadsAResolvedConfigAndKeepsTheRawObject()
    {
        const string json = "{\"version\":\"1.0\",\"refresh_interval\":60,\"item_ttl\":300," +
            "\"predicates\":[{\"expr\":\"service == 'dynamodb' && operation in ops\",\"vars\":{\"ops\":[\"GetItem\"]}}]}";
        var config = ElastiCacheIntegrationsProtocol.ParseConfigReply(Blob(json), noArgumentForm: false)!;
        Assert.Equal("1.0", config.Version);
        Assert.Equal(TimeSpan.FromSeconds(60), config.RefreshInterval);
        Assert.Equal(json, config.Raw.GetRawText());
        Assert.Equal("GetItem", config.Raw.GetProperty("predicates")[0].GetProperty("vars").GetProperty("ops")[0].GetString());
    }

    [Theory]
    [InlineData("{\"refresh_interval\":60}", null, 60)]
    [InlineData("{\"version\":null,\"refresh_interval\":60}", null, 60)]
    [InlineData("{\"version\":\"1.7\",\"refresh_interval\":1}", "1.7", 1)]
    [InlineData("{\"version\":\"2.0\",\"refresh_interval\":5}", "2.0", 5)]
    [InlineData("{\"refresh_interval\":2147483}", null, 2147483)]
    [InlineData("{\"refresh_interval\":2147484}", null, 2147483)]
    [InlineData("{\"refresh_interval\":9999999999}", null, 2147483)]
    public void ReadsTheVersionAndRefreshInterval(string json, string? version, int seconds)
    {
        var config = ElastiCacheIntegrationsProtocol.ParseConfigReply(Blob(json), noArgumentForm: true)!;
        Assert.Equal(version, config.Version);
        Assert.Equal(TimeSpan.FromSeconds(seconds), config.RefreshInterval);
    }

    [Fact]
    public void ReadsNullAsUnchangedOnlyOnARefresh()
    {
        Assert.Null(ElastiCacheIntegrationsProtocol.ParseConfigReply(RespNull.Instance, noArgumentForm: true));
        Assert.Throws<ElastiCacheIntegrationsProtocolException>(() => ElastiCacheIntegrationsProtocol.ParseConfigReply(RespNull.Instance, noArgumentForm: false));
    }

    [Fact]
    public void RaisesConfigInvalidAsAConfigurationError()
    {
        var e = Assert.Throws<ElastiCacheIntegrationsConfigurationException>(() =>
            ElastiCacheIntegrationsProtocol.ParseConfigReply(new RespError("CONFIG_INVALID", "overrides.version is required"), noArgumentForm: false));
        var cause = Assert.IsType<ElastiCacheIntegrationsServerException>(e.InnerException);
        Assert.Equal(("CONFIG_INVALID", "overrides.version is required"), (cause.Code, cause.ServerMessage));
        Assert.Contains("overrides.version is required", e.Message);
    }

    [Fact]
    public void RaisesOtherErrorsAsServerErrors()
    {
        var e = Assert.Throws<ElastiCacheIntegrationsServerException>(() =>
            ElastiCacheIntegrationsProtocol.ParseConfigReply(new RespError("INTERNAL_ERROR", "aws.config was not processed"), noArgumentForm: true));
        Assert.Equal("INTERNAL_ERROR", e.Code);
    }

    [Theory]
    [InlineData("not json")]
    [InlineData("[1,2,3]")]
    [InlineData("\"text\"")]
    [InlineData("null")]
    [InlineData("")]
    [InlineData("{\"version\":1,\"refresh_interval\":60}")]
    [InlineData("{\"predicates\":[]}")]
    [InlineData("{\"refresh_interval\":null}")]
    [InlineData("{\"refresh_interval\":0}")]
    [InlineData("{\"refresh_interval\":60.0}")]
    [InlineData("{\"refresh_interval\":-1}")]
    [InlineData("{\"refresh_interval\":1.5}")]
    [InlineData("{\"refresh_interval\":\"60\"}")]
    public void RejectsMalformedConfigJson(string json)
    {
        Assert.Throws<ElastiCacheIntegrationsProtocolException>(() => ElastiCacheIntegrationsProtocol.ParseConfigReply(Blob(json), noArgumentForm: false));
    }

    [Fact]
    public void RejectsInvalidUtf8AndOtherShapes()
    {
        Assert.Throws<ElastiCacheIntegrationsProtocolException>(() => ElastiCacheIntegrationsProtocol.ParseConfigReply(new RespBlobString(new byte[] { 0xFF }), noArgumentForm: false));
        Assert.Throws<ElastiCacheIntegrationsProtocolException>(() => ElastiCacheIntegrationsProtocol.ParseConfigReply(new RespSimpleString("{}"), noArgumentForm: false));
        Assert.Throws<ElastiCacheIntegrationsProtocolException>(() => ElastiCacheIntegrationsProtocol.ParseConfigReply(new RespArray([Blob("version"), Blob("v3")]), noArgumentForm: true));
    }
}
