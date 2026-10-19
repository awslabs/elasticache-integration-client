using System.Buffers;
using System.Text;
using System.Text.Json;

namespace Amazon.ElastiCache.Integrations.Tests;

/// <summary>The cross-client wire vectors in <c>shared/protocol-vectors.json</c>. Python's output defines each expected value.</summary>
[Trait("Category", "alignment")]
public class ProtocolVectorTests
{
    private static readonly JsonElement Vectors = SharedFixtures.Load("protocol-vectors.json");

    /// <summary>Vectors this client does not match yet, each with a fragment of the failure message it gives today.</summary>
    private static readonly Dictionary<string, string> KnownGaps = new();

    public static TheoryData<string> EncodeVectors => SharedFixtures.Names(Vectors, "encode");

    public static TheoryData<string> DecodeVectors => SharedFixtures.Names(Vectors, "decode");

    [Theory]
    [MemberData(nameof(EncodeVectors))]
    public void Encodes(string name) =>
        SharedFixtures.Check(KnownGaps, name, () => AssertEncodes(SharedFixtures.Find(Vectors, "encode", name)));

    [Theory]
    [MemberData(nameof(DecodeVectors))]
    public void Decodes(string name) =>
        SharedFixtures.Check(KnownGaps, name, () => AssertDecodes(SharedFixtures.Find(Vectors, "decode", name)));

    // A field this runner does not read would be skipped silently, so the shapes are pinned.
    [Fact]
    public void UsesOnlyKnownFields()
    {
        Assert.Equal("decode,description,encode", SharedFixtures.Keys(Vectors));
        foreach (var vector in Vectors.GetProperty("encode").EnumerateArray())
        {
            Assert.Contains(SharedFixtures.Keys(vector), new[] { "frame,name,request", "frame,name,overrides" });
            if (vector.TryGetProperty("request", out var request))
            {
                Assert.Equal("body,headers,metadata,method,url", SharedFixtures.Keys(request));
                Assert.Equal("operation,service", SharedFixtures.Keys(request.GetProperty("metadata")));
            }
        }

        foreach (var vector in Vectors.GetProperty("decode").EnumerateArray())
        {
            Assert.Contains(SharedFixtures.Keys(vector), new[]
            {
                "command,name,reply,response", "command,config,name,reply", "command,error,name,reply", "code,command,error,name,reply",
            });
        }

        Assert.All(KnownGaps.Keys, name => Assert.Contains(Vectors.GetProperty("encode").EnumerateArray().Concat(Vectors.GetProperty("decode").EnumerateArray()),
            v => v.GetProperty("name").GetString() == name));
    }

    private static void AssertEncodes(JsonElement vector)
    {
        var frame = new ArrayBufferWriter<byte>();
        if (vector.TryGetProperty("request", out var request))
        {
            // A dictionary keeps names that only differ in case, as the vectors need.
            var headers = request.GetProperty("headers").EnumerateObject().ToDictionary(h => h.Name, h => h.Value.GetString()!, StringComparer.Ordinal);
            var metadata = request.GetProperty("metadata");
            ElastiCacheIntegrationsProtocol.WriteRequest(frame, new ElastiCacheIntegrationsRequest(
                request.GetProperty("method").GetString()!,
                request.GetProperty("url").GetString()!,
                headers,
                Encoding.UTF8.GetBytes(request.GetProperty("body").GetString()!),
                new RequestMetadata(metadata.GetProperty("service").GetString()!, metadata.GetProperty("operation").GetString()!)));
        }
        else if (vector.GetProperty("overrides") is { ValueKind: JsonValueKind.Null })
        {
            ElastiCacheIntegrationsProtocol.WriteConfigRefresh(frame);
        }
        else
        {
            var overrides = new ElastiCacheIntegrationsOverrides
            {
                Extra = vector.GetProperty("overrides").EnumerateObject().ToDictionary(p => p.Name, p => p.Value.Clone()),
            };
            ElastiCacheIntegrationsProtocol.WriteConfig(frame, ElastiCacheIntegrationsProtocol.SerializeOverrides(overrides));
        }

        Assert.Equal(vector.GetProperty("frame").GetString(), Encoding.UTF8.GetString(frame.WrittenSpan));
    }

    private static void AssertDecodes(JsonElement vector)
    {
        var buffer = new ReadOnlySequence<byte>(Encoding.UTF8.GetBytes(vector.GetProperty("reply").GetString()!));
        Assert.True(RespDecoder.TryDecode(ref buffer, out var reply));
        Assert.True(buffer.IsEmpty, "the reply was not consumed whole");
        var isRequest = vector.GetProperty("command").GetString() == ElastiCacheIntegrationsProtocol.RequestCommand;

        if (vector.TryGetProperty("error", out var error))
        {
            var thrown = Record.Exception(() =>
            {
                var result = isRequest ? ElastiCacheIntegrationsProtocol.ParseRequestReply(reply) : (object?)ElastiCacheIntegrationsProtocol.ParseConfigReply(reply, noArgumentForm: false);
                if (result is RequestReply.Rejected rejected)
                {
                    throw rejected.Error;
                }
            });
            Assert.IsAssignableFrom<ElastiCacheIntegrationsException>(thrown);
            switch (error.GetString())
            {
                case "protocol":
                    Assert.IsType<ElastiCacheIntegrationsProtocolException>(thrown);
                    break;
                case "rejected":
                    Assert.IsType<ElastiCacheIntegrationsServerException>(thrown);
                    break;
                case "overrides_rejected":
                    Assert.IsType<ElastiCacheIntegrationsConfigurationException>(thrown);
                    break;
                case "error":
                    break;
                default:
                    Assert.Fail($"unknown error kind {error}");
                    break;
            }

            if (vector.TryGetProperty("code", out var code))
            {
                Assert.Equal(code.GetString(), Assert.IsType<ElastiCacheIntegrationsServerException>(thrown).Code);
            }
        }
        else if (vector.TryGetProperty("response", out var expected))
        {
            var response = Assert.IsType<RequestReply.Served>(ElastiCacheIntegrationsProtocol.ParseRequestReply(reply)).Response;
            Assert.Equal(expected.GetProperty("status").GetInt32(), response.StatusCode);
            Assert.Equal<IReadOnlyDictionary<string, string>>(
                expected.GetProperty("headers").EnumerateObject().ToDictionary(h => h.Name, h => h.Value.GetString()!),
                response.Headers);
            Assert.Equal(expected.GetProperty("body").GetString(), Encoding.UTF8.GetString(response.Body.Span));
        }
        else
        {
            var expectedConfig = vector.GetProperty("config");
            var config = ElastiCacheIntegrationsProtocol.ParseConfigReply(reply, noArgumentForm: false)!;
            Assert.Equal(expectedConfig.GetProperty("version") is { ValueKind: JsonValueKind.String } version ? version.GetString() : null, config.Version);
            Assert.Equal(TimeSpan.FromSeconds(expectedConfig.GetProperty("refresh_interval").GetInt64()), config.RefreshInterval);
            Assert.True(SharedFixtures.JsonEquals(expectedConfig.GetProperty("raw"), config.Raw), $"raw config {config.Raw.GetRawText()}");
        }
    }
}
