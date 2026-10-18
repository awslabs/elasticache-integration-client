using System.Buffers;
using System.Text;
using System.Text.Json;

namespace Amazon.Photon.Tests;

public class ConfigProtocolTests
{
    private static RespBlobString Blob(string text) => new(Encoding.UTF8.GetBytes(text));

    private static string Json(PhotonOverrides overrides) => Encoding.UTF8.GetString(PhotonProtocol.SerializeOverrides(overrides));

    [Fact]
    public void SerializesEmptyOverridesWithTheVersionOnly()
    {
        Assert.Equal("{\"version\":\"1.0\"}", Json(new PhotonOverrides()));
    }

    [Fact]
    public void SerializesEveryFieldCompactlyInOrder()
    {
        var overrides = new PhotonOverrides
        {
            ExcludeOperations = ["Scan"],
            ExcludeTables = ["AuditLog"],
            Ttl = 300,
            Nttl = 30,
            PopulateOnWrite = true,
            PopulateFullItem = false,
            Tables = new Dictionary<string, PhotonTableOverrides>
            {
                ["Users"] = new() { Ttl = 600, Nttl = 60, Extra = new Dictionary<string, JsonElement> { ["future"] = JsonDocument.Parse("true").RootElement } },
                ["Sessions"] = new() { Ttl = 60, PopulateOnWrite = false },
            },
        };
        Assert.Equal(
            "{\"version\":\"1.0\",\"exclude_operations\":[\"Scan\"],\"exclude_tables\":[\"AuditLog\"],\"ttl\":300,\"nttl\":30," +
            "\"populate_on_write\":true,\"populate_full_item\":false," +
            "\"tables\":{\"Users\":{\"ttl\":600,\"nttl\":60,\"future\":true},\"Sessions\":{\"ttl\":60,\"populate_on_write\":false}}}",
            Json(overrides));
    }

    [Fact]
    public void SendsExtraFieldsLast()
    {
        var overrides = new PhotonOverrides
        {
            Ttl = 5,
            Extra = new Dictionary<string, JsonElement> { ["b"] = JsonDocument.Parse("2").RootElement, ["a"] = JsonDocument.Parse("[1,{\"x\":null}]").RootElement },
        };
        Assert.Equal("{\"version\":\"1.0\",\"ttl\":5,\"b\":2,\"a\":[1,{\"x\":null}]}", Json(overrides));
    }

    [Fact]
    public void EscapesNonAsciiLikeNode()
    {
        var overrides = new PhotonOverrides { ExcludeTables = ["café", "😀", "<a&b>+'"], Extra = new Dictionary<string, JsonElement> { ["é"] = JsonDocument.Parse("\"\\u00E9\\n\"").RootElement } };
        Assert.Equal(
            "{\"version\":\"1.0\",\"exclude_tables\":[\"caf\\u00e9\",\"\\ud83d\\ude00\",\"<a&b>+'\"],\"\\u00e9\":\"\\u00e9\\n\"}",
            Json(overrides));
    }

    [Fact]
    public void EscapesQuotesAndControlCharacters()
    {
        Assert.Equal("{\"version\":\"1.0\",\"exclude_tables\":[\"a\\\"b\\\\c\\u0001\\t\"]}", Json(new PhotonOverrides { ExcludeTables = ["a\"b\\c\u0001\t"] }));
    }

    [Fact]
    public void KeepsTheCaseOfALiteralBackslashU()
    {
        Assert.Equal("{\"version\":\"1.0\",\"exclude_tables\":[\"a\\\\u00E9\"]}", Json(new PhotonOverrides { ExcludeTables = ["a\\u00E9"] }));
    }

    [Fact]
    public void EscapesDeleteUnlikeNode()
    {
        Assert.Equal("{\"version\":\"1.0\",\"exclude_tables\":[\"\\u007f\"]}", Json(new PhotonOverrides { ExcludeTables = ["\u007f"] }));
    }

    [Fact]
    public void ReplacesALoneSurrogate()
    {
        Assert.Equal("{\"version\":\"1.0\",\"exclude_tables\":[\"\\ufffd\"]}", Json(new PhotonOverrides { ExcludeTables = ["\ud800"] }));
    }

    [Fact]
    public void RefusesAVersionInExtra()
    {
        var overrides = new PhotonOverrides { Extra = new Dictionary<string, JsonElement> { ["version"] = JsonDocument.Parse("\"2.0\"").RootElement } };
        Assert.Throws<PhotonConfigurationException>(() => PhotonProtocol.SerializeOverrides(overrides));
    }

    [Fact]
    public void WritesBothConfigForms()
    {
        var writer = new ArrayBufferWriter<byte>();
        PhotonProtocol.WriteConfig(writer, "{\"exclude_operations\":[\"Scan\"],\"ttl\":300}"u8.ToArray());
        Assert.Equal("*2\r\n$10\r\naws.config\r\n$41\r\n{\"exclude_operations\":[\"Scan\"],\"ttl\":300}\r\n", Encoding.UTF8.GetString(writer.WrittenSpan));

        writer.Clear();
        PhotonProtocol.WriteConfigRefresh(writer);
        Assert.Equal("*1\r\n$10\r\naws.config\r\n", Encoding.UTF8.GetString(writer.WrittenSpan));
    }

    [Fact]
    public void ReadsAResolvedConfigAndKeepsTheRawObject()
    {
        const string json = "{\"version\":\"1.0\",\"refresh_interval\":60,\"ttl\":300,\"nttl\":60," +
            "\"predicates\":[{\"expr\":\"service == 'dynamodb' && operation in ops\",\"vars\":{\"ops\":[\"GetItem\"]}}]}";
        var config = PhotonProtocol.ParseConfigReply(Blob(json), noArgumentForm: false)!;
        Assert.Equal("1.0", config.Version);
        Assert.Equal(TimeSpan.FromSeconds(60), config.RefreshInterval);
        Assert.Equal(json, config.Raw.GetRawText());
        Assert.Equal("GetItem", config.Raw.GetProperty("predicates")[0].GetProperty("vars").GetProperty("ops")[0].GetString());
    }

    [Theory]
    [InlineData("{\"predicates\":[]}", null, null)]
    [InlineData("{\"version\":\"1.7\",\"refresh_interval\":0}", "1.7", null)]
    [InlineData("{\"version\":\"1\",\"refresh_interval\":null}", "1", null)]
    [InlineData("{\"version\":\"2.0\"}", "2.0", null)]
    [InlineData("{\"refresh_interval\":5}", null, 5)]
    [InlineData("{\"refresh_interval\":2147483}", null, 2147483)]
    public void ReadsOptionalFields(string json, string? version, int? seconds)
    {
        var config = PhotonProtocol.ParseConfigReply(Blob(json), noArgumentForm: true)!;
        Assert.Equal(version, config.Version);
        Assert.Equal(seconds is null ? null : TimeSpan.FromSeconds(seconds.Value), config.RefreshInterval);
    }

    [Fact]
    public void ReadsNullAsUnchangedOnlyOnARefresh()
    {
        Assert.Null(PhotonProtocol.ParseConfigReply(RespNull.Instance, noArgumentForm: true));
        Assert.Throws<PhotonProtocolException>(() => PhotonProtocol.ParseConfigReply(RespNull.Instance, noArgumentForm: false));
    }

    [Fact]
    public void RaisesConfigInvalidAsAConfigurationError()
    {
        var e = Assert.Throws<PhotonConfigurationException>(() =>
            PhotonProtocol.ParseConfigReply(new RespError("CONFIG_INVALID", "overrides.version is required"), noArgumentForm: false));
        var cause = Assert.IsType<PhotonServerException>(e.InnerException);
        Assert.Equal(("CONFIG_INVALID", "overrides.version is required"), (cause.Code, cause.ServerMessage));
        Assert.Contains("overrides.version is required", e.Message);
    }

    [Fact]
    public void RaisesOtherErrorsAsServerErrors()
    {
        var e = Assert.Throws<PhotonServerException>(() =>
            PhotonProtocol.ParseConfigReply(new RespError("INTERNAL_ERROR", "aws.config was not processed"), noArgumentForm: true));
        Assert.Equal("INTERNAL_ERROR", e.Code);
    }

    [Theory]
    [InlineData("not json")]
    [InlineData("[1,2,3]")]
    [InlineData("\"text\"")]
    [InlineData("null")]
    [InlineData("")]
    [InlineData("{\"version\":1}")]
    [InlineData("{\"refresh_interval\":-1}")]
    [InlineData("{\"refresh_interval\":1.5}")]
    [InlineData("{\"refresh_interval\":\"60\"}")]
    [InlineData("{\"refresh_interval\":2147484}")]
    [InlineData("{\"refresh_interval\":9999999999}")]
    public void RejectsMalformedConfigJson(string json)
    {
        Assert.Throws<PhotonProtocolException>(() => PhotonProtocol.ParseConfigReply(Blob(json), noArgumentForm: false));
    }

    [Fact]
    public void RejectsInvalidUtf8AndOtherShapes()
    {
        Assert.Throws<PhotonProtocolException>(() => PhotonProtocol.ParseConfigReply(new RespBlobString(new byte[] { 0xFF }), noArgumentForm: false));
        Assert.Throws<PhotonProtocolException>(() => PhotonProtocol.ParseConfigReply(new RespSimpleString("{}"), noArgumentForm: false));
        Assert.Throws<PhotonProtocolException>(() => PhotonProtocol.ParseConfigReply(new RespArray([Blob("version"), Blob("v3")]), noArgumentForm: true));
    }
}
