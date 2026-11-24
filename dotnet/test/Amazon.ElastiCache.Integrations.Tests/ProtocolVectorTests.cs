// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using System.Buffers;
using System.Text;
using System.Text.Json;

namespace Amazon.ElastiCache.Integrations.Tests;

/// <summary>The cross-client wire vectors in <c>shared/protocol-vectors.json</c>. Python's output defines each expected value.</summary>
[Trait("Category", "alignment")]
public class ProtocolVectorTests
{
    private static readonly JsonElement Vectors = SharedFixtures.Load("protocol-vectors.json");

    public static TheoryData<string> EncodeVectors => SharedFixtures.Names(Vectors, "encode");

    public static TheoryData<string> DecodeVectors => SharedFixtures.Names(Vectors, "decode");

    [Theory]
    [MemberData(nameof(EncodeVectors))]
    public void Encodes(string name) =>
        AssertEncodes(SharedFixtures.Find(Vectors, "encode", name));

    [Theory]
    [MemberData(nameof(DecodeVectors))]
    public void Decodes(string name) =>
        AssertDecodes(SharedFixtures.Find(Vectors, "decode", name));

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
            if (vector.TryGetProperty("response", out var response))
            {
                Assert.Equal("body,headers,status", SharedFixtures.Keys(response));
            }

            if (vector.TryGetProperty("config", out var config))
            {
                Assert.Equal("raw,refresh_interval,version", SharedFixtures.Keys(config));
            }
        }

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
            // The typed path a caller uses: modeled names bind to properties, the rest to Extra.
            var raw = vector.GetProperty("overrides");
            var overrides = raw.Deserialize<ElastiCacheIntegrationsOverrides>()!;
            OptionsValidator.Validate(new ElastiCacheIntegrationsClientOptions { Endpoint = "h:1", CacheName = "c", Region = "us-east-1", Overrides = overrides });
            var payload = ElastiCacheIntegrationsProtocol.SerializeOverrides(overrides);
            ElastiCacheIntegrationsProtocol.WriteConfig(frame, payload);
            AssertConfigFramesMatch(vector.GetProperty("frame").GetString()!, frame.WrittenMemory);
            return;
        }

        Assert.Equal(vector.GetProperty("frame").GetString(), Encoding.UTF8.GetString(frame.WrittenSpan));
    }

    private static void AssertConfigFramesMatch(string expectedWire, ReadOnlyMemory<byte> actualWire)
    {
        static RespArray Decode(ReadOnlyMemory<byte> wire)
        {
            var buffer = new ReadOnlySequence<byte>(wire);
            Assert.True(RespDecoder.TryDecode(ref buffer, out var value));
            Assert.True(buffer.IsEmpty);
            return Assert.IsType<RespArray>(value);
        }

        var expected = Decode(Encoding.UTF8.GetBytes(expectedWire));
        var actual = Decode(actualWire);
        Assert.Equal(2, actual.Items.Count);
        Assert.Equal(expected.Items[0], actual.Items[0]);
        using var expectedJson = JsonDocument.Parse(Assert.IsType<RespBlobString>(expected.Items[1]).Value);
        using var actualJson = JsonDocument.Parse(Assert.IsType<RespBlobString>(actual.Items[1]).Value);
        // JSON object key order has no effect on cache options.
        Assert.True(SharedFixtures.JsonEquals(expectedJson.RootElement, actualJson.RootElement));
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
