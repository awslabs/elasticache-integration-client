// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using System.Buffers;
using System.Text;

namespace Amazon.ElastiCache.Integrations.Tests;

public class RequestProtocolTests
{
    private static readonly RequestMetadata GetItem = new("dynamodb", "GetItem");

    private static RespBlobString Blob(string text) => new(Encoding.UTF8.GetBytes(text));

    private static string Frame(ElastiCacheIntegrationsRequest request)
    {
        var writer = new ArrayBufferWriter<byte>();
        ElastiCacheIntegrationsProtocol.WriteRequest(writer, request);
        return Encoding.UTF8.GetString(writer.WrittenSpan);
    }

    private static ElastiCacheIntegrationsRequest Request(Dictionary<string, string> headers, string body) =>
        new("POST", "https://dynamodb.us-east-1.amazonaws.com/", headers, Encoding.UTF8.GetBytes(body), GetItem);

    [Fact]
    public void WritesTheNodeVector()
    {
        var request = Request(new() { ["content-type"] = "application/x-amz-json-1.0" }, "{}");
        Assert.Equal(
            "*6\r\n$11\r\naws.request\r\n$4\r\nPOST\r\n$41\r\nhttps://dynamodb.us-east-1.amazonaws.com/\r\n" +
            "$39\r\ncontent-type:application/x-amz-json-1.0\r\n$2\r\n{}\r\n$27\r\nservice=dynamodb&op=GetItem\r\n",
            Frame(request));
    }

    [Fact]
    public void WritesTheProtocolDocExample()
    {
        var request = Request(
            new()
            {
                ["X-Amz-Target"] = "DynamoDB_20120810.GetItem",
                ["Content-Type"] = "application/x-amz-json-1.0",
                ["Authorization"] = "AWS4-HMAC-SHA256 Credential=secret",
                ["X-Amz-Date"] = "20261017T090000Z",
            },
            "{\"TableName\":\"Users\",\"Key\":{\"userId\":{\"S\":\"abc123\"}}}");
        Assert.Equal(
            "*6\r\n$11\r\naws.request\r\n$4\r\nPOST\r\n$41\r\nhttps://dynamodb.us-east-1.amazonaws.com/\r\n" +
            "$78\r\ncontent-type:application/x-amz-json-1.0\nx-amz-target:DynamoDB_20120810.GetItem\r\n" +
            "$53\r\n{\"TableName\":\"Users\",\"Key\":{\"userId\":{\"S\":\"abc123\"}}}\r\n$27\r\nservice=dynamodb&op=GetItem\r\n",
            Frame(request));
    }

    [Fact]
    public void WritesBinaryBodiesAndEmptyHeaders()
    {
        var request = new ElastiCacheIntegrationsRequest("POST", "u", new Dictionary<string, string>(), new byte[] { 0, 13, 10, 255 }, GetItem);
        var writer = new ArrayBufferWriter<byte>();
        ElastiCacheIntegrationsProtocol.WriteRequest(writer, request);
        byte[] expected =
            [.. "*6\r\n$11\r\naws.request\r\n$4\r\nPOST\r\n$1\r\nu\r\n$0\r\n\r\n$4\r\n"u8, 0, 13, 10, 255, .. "\r\n$27\r\nservice=dynamodb&op=GetItem\r\n"u8];
        Assert.Equal(expected, writer.WrittenSpan.ToArray());
    }

    [Fact]
    public void WritesNothingForInvalidText()
    {
        var request = Request(new() { ["x"] = "\ud800" }, "{}");
        var writer = new ArrayBufferWriter<byte>();
        Assert.ThrowsAny<ArgumentException>(() => ElastiCacheIntegrationsProtocol.WriteRequest(writer, request));
        Assert.Equal(0, writer.WrittenCount);
    }

    [Fact]
    public void NormalizesHeadersLikeNode()
    {
        var a = new Dictionary<string, string>
        {
            ["X-Amz-Target"] = " DynamoDB_20120810.GetItem ",
            ["Content-Type"] = "application/json",
            ["Authorization"] = "a",
            ["x-amz-security-token"] = "t",
            ["X-Amz-Request-Id"] = "r",
            ["amz-sdk-request"] = "attempt=1",
        };
        var b = new Dictionary<string, string>
        {
            ["content-type"] = "application/json",
            ["x-amz-target"] = "DynamoDB_20120810.GetItem",
            ["X-Amz-Date"] = "d",
        };
        Assert.Equal("content-type:application/json\nx-amz-target:DynamoDB_20120810.GetItem", ElastiCacheIntegrationsProtocol.NormalizeHeaders(a));
        Assert.Equal(ElastiCacheIntegrationsProtocol.NormalizeHeaders(a), ElastiCacheIntegrationsProtocol.NormalizeHeaders(b));
    }

    [Fact]
    public void KeepsContentSha256AndSortsDuplicateNamesByValue()
    {
        Assert.Equal("x-amz-content-sha256:hash", ElastiCacheIntegrationsProtocol.NormalizeHeaders(new Dictionary<string, string> { ["x-amz-content-sha256"] = "hash" }));
        Assert.Equal("x-key:a\nx-key:z", ElastiCacheIntegrationsProtocol.NormalizeHeaders(new Dictionary<string, string> { ["X-Key"] = "z", [" x-key "] = "a" }));
    }

    [Fact]
    public void SortsDuplicateHeaderValuesByUnicodeCodePoint() =>
        Assert.Equal(
            "x-key:\ue000\nx-key:\U00010000",
            ElastiCacheIntegrationsProtocol.NormalizeHeaders(
                new Dictionary<string, string>(StringComparer.Ordinal)
                {
                    ["X-Key"] = "\U00010000",
                    ["x-key"] = "\ue000",
                }));

    [Fact]
    public void NormalizesToEmpty()
    {
        Assert.Equal("", ElastiCacheIntegrationsProtocol.NormalizeHeaders(new Dictionary<string, string>()));
        Assert.Equal("", ElastiCacheIntegrationsProtocol.NormalizeHeaders(new Dictionary<string, string> { ["Authorization"] = "x", ["X-Amz-Date"] = "y" }));
    }

    [Fact]
    public void FormatsMetadataWithALowercaseService()
    {
        Assert.Equal("service=dynamodb&op=Query", ElastiCacheIntegrationsProtocol.FormatMetadata(new RequestMetadata("DynamoDB", "Query")));
    }

    [Fact]
    public void ReadsAServedReply()
    {
        var reply = ElastiCacheIntegrationsProtocol.ParseRequestReply(new RespArray([new RespInteger(200), Blob("content-type:application/json"), Blob("{\"k\":1}")]));
        var response = Assert.IsType<RequestReply.Served>(reply).Response;
        Assert.Equal(200, response.StatusCode);
        Assert.Equal(new Dictionary<string, string> { ["content-type"] = "application/json" }, response.Headers);
        Assert.Equal("{\"k\":1}", Encoding.UTF8.GetString(response.Body.Span));
    }

    [Fact]
    public void ReadsEmptyHeadersAndBody()
    {
        var response = Assert.IsType<RequestReply.Served>(ElastiCacheIntegrationsProtocol.ParseRequestReply(new RespArray([new RespInteger(204), Blob(""), Blob("")]))).Response;
        Assert.Empty(response.Headers);
        Assert.True(response.Body.IsEmpty);
    }

    [Fact]
    public void ParsesResponseHeaderLines()
    {
        var response = Assert.IsType<RequestReply.Served>(ElastiCacheIntegrationsProtocol.ParseRequestReply(
            new RespArray([new RespInteger(400), Blob(" content-type : application/json \ngarbage\n\nx:1\nx: 2\nurl:a:b"), Blob("")]))).Response;
        Assert.Equal(new Dictionary<string, string> { ["content-type"] = "application/json", ["x"] = "2", ["url"] = "a:b" }, response.Headers);
    }

    [Theory]
    [InlineData("200", 200)]
    [InlineData(" 404 ", 404)]
    [InlineData("+100", 100)]
    public void ReadsADecimalStatusString(string status, int expected)
    {
        var blob = ElastiCacheIntegrationsProtocol.ParseRequestReply(new RespArray([Blob(status), Blob(""), Blob("")]));
        var simple = ElastiCacheIntegrationsProtocol.ParseRequestReply(new RespArray([new RespSimpleString(status), Blob(""), Blob("")]));
        Assert.Equal(expected, Assert.IsType<RequestReply.Served>(blob).Response.StatusCode);
        Assert.Equal(expected, Assert.IsType<RequestReply.Served>(simple).Response.StatusCode);
    }

    [Fact]
    public void ReadsAServedReplyInsideAPush()
    {
        var reply = ElastiCacheIntegrationsProtocol.ParseRequestReply(new RespArray([new RespInteger(200), Blob("a:b"), Blob("{}")]), inBand: false);
        Assert.Equal(200, Assert.IsType<RequestReply.Served>(reply).Response.StatusCode);
    }

    private static readonly Dictionary<string, RespValue> MalformedReplyValues = new()
    {
        ["null"] = RespNull.Instance,
        ["bulk"] = Blob("not-an-array"),
        ["OK"] = new RespSimpleString("OK"),
        ["empty id"] = new RespSimpleString(""),
        ["two items"] = new RespArray([new RespInteger(200), Blob("")]),
        ["four items"] = new RespArray([new RespInteger(200), Blob(""), Blob(""), Blob("")]),
        ["set"] = new RespSet([new RespInteger(200), Blob(""), Blob("")]),
        ["status 99"] = new RespArray([new RespInteger(99), Blob(""), Blob("")]),
        ["status 600"] = new RespArray([new RespInteger(600), Blob(""), Blob("")]),
        ["huge status"] = new RespArray([new RespInteger(long.MaxValue), Blob(""), Blob("")]),
        ["text status"] = new RespArray([Blob("not-a-number"), Blob(""), Blob("")]),
        ["decimal status"] = new RespArray([Blob("200.0"), Blob(""), Blob("")]),
        ["hex status"] = new RespArray([Blob("0xC8"), Blob(""), Blob("")]),
        ["NUL status"] = new RespArray([Blob("200\0"), Blob(""), Blob("")]),
        ["sign only status"] = new RespArray([Blob("+"), Blob(""), Blob("")]),
        ["status 0"] = new RespArray([Blob("0"), Blob(""), Blob("")]),
        ["status -1"] = new RespArray([Blob("-1"), Blob(""), Blob("")]),
        ["status 1000"] = new RespArray([new RespInteger(1000), Blob(""), Blob("")]),
        ["double status"] = new RespArray([new RespDouble(200), Blob(""), Blob("")]),
        ["integer headers"] = new RespArray([new RespInteger(200), new RespInteger(1), Blob("")]),
        ["null body"] = new RespArray([new RespInteger(200), Blob(""), RespNull.Instance]),
        ["bad UTF-8 headers"] = new RespArray([new RespInteger(200), new RespBlobString(new byte[] { 0xFF, 0x3A, 0x78 }), Blob("")]),
    };

    public static TheoryData<string> MalformedReplies => [.. MalformedReplyValues.Keys];

    [Theory]
    [MemberData(nameof(MalformedReplies))]
    public void RejectsMalformedReplies(string name)
    {
        Assert.Throws<ElastiCacheIntegrationsProtocolException>(() => ElastiCacheIntegrationsProtocol.ParseRequestReply(MalformedReplyValues[name]));
    }

    [Fact]
    public void ReadsADeferredReply()
    {
        Assert.Equal(new RequestReply.Deferred("12"), ElastiCacheIntegrationsProtocol.ParseRequestReply(new RespSimpleString("12")));
    }

    [Fact]
    public void RejectsADeferredReplyInsideAPush()
    {
        Assert.Throws<ElastiCacheIntegrationsProtocolException>(() => ElastiCacheIntegrationsProtocol.ParseRequestReply(new RespSimpleString("12"), inBand: false));
    }

    [Fact]
    public void ReadsAnErrorReply()
    {
        var reply = ElastiCacheIntegrationsProtocol.ParseRequestReply(new RespError("UNSUPPORTED", "service=s3"), inBand: false);
        var error = Assert.IsType<RequestReply.Rejected>(reply).Error;
        Assert.Equal(("UNSUPPORTED", "service=s3"), (error.Code, error.ServerMessage));
    }

    [Fact]
    public void SplitsAResponsePush()
    {
        RespValue inner = new RespError("AUTH_ERROR", "no FAS credentials were vended for the caller");
        var (id, reply) = ElastiCacheIntegrationsProtocol.ParseResponsePush(new RespPush([Blob("aws.response"), Blob("12"), inner]));
        Assert.Equal("12", id);
        Assert.Same(inner, reply);
    }

    [Fact]
    public void AcceptsSimpleStringPushItems()
    {
        var (id, _) = ElastiCacheIntegrationsProtocol.ParseResponsePush(new RespPush([new RespSimpleString("aws.response"), new RespSimpleString("7"), RespNull.Instance]));
        Assert.Equal("7", id);
    }

    private static readonly Dictionary<string, RespPush> MalformedPushValues = new()
    {
        ["two items"] = new RespPush([Blob("aws.response"), Blob("1")]),
        ["four items"] = new RespPush([Blob("aws.response"), Blob("1"), Blob(""), Blob("")]),
        ["old kind"] = new RespPush([Blob("photon.response"), Blob("1"), Blob("")]),
        ["pubsub kind"] = new RespPush([Blob("message"), Blob("1"), Blob("")]),
        ["integer kind"] = new RespPush([new RespInteger(1), Blob("1"), Blob("")]),
        ["integer id"] = new RespPush([Blob("aws.response"), new RespInteger(1), Blob("")]),
        ["empty id"] = new RespPush([Blob("aws.response"), Blob(""), Blob("")]),
        ["no items"] = new RespPush([]),
        ["foreign two items"] = new RespPush([Blob("invalidate"), Blob("k")]),
        ["nested push"] = new RespPush([Blob("aws.response"), Blob("1"), new RespPush([Blob("aws.response"), Blob("2"), Blob("")])]),
        ["bad UTF-8 id"] = new RespPush([Blob("aws.response"), new RespBlobString(new byte[] { 0xFF }), Blob("")]),
    };

    public static TheoryData<string> MalformedPushes => [.. MalformedPushValues.Keys];

    [Theory]
    [MemberData(nameof(MalformedPushes))]
    public void RejectsMalformedPushes(string name)
    {
        Assert.Throws<ElastiCacheIntegrationsProtocolException>(() => ElastiCacheIntegrationsProtocol.ParseResponsePush(MalformedPushValues[name]));
    }
}
