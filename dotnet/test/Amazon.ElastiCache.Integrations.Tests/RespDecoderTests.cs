// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using System.Buffers;
using System.Numerics;
using System.Text;

namespace Amazon.ElastiCache.Integrations.Tests;

public class RespDecoderTests
{
    private static byte[] Bytes(string s) => Encoding.UTF8.GetBytes(s);

    private static RespBlobString Blob(string s) => new(Bytes(s));

    private static RespValue DecodeAll(ReadOnlySequence<byte> buffer)
    {
        Assert.True(RespDecoder.TryDecode(ref buffer, out var value));
        Assert.True(buffer.IsEmpty);
        return value;
    }

    private static RespValue DecodeAll(byte[] wire) => DecodeAll(new ReadOnlySequence<byte>(wire));

    /// <summary>Every proper prefix is incomplete and consumes nothing; the whole wire decodes.</summary>
    private static RespValue DecodeByteAtATime(byte[] wire)
    {
        for (var n = 0; n < wire.Length; n++)
        {
            var partial = Segments(wire.AsMemory(0, n), 1);
            Assert.False(RespDecoder.TryDecode(ref partial, out var none));
            Assert.Null(none);
            Assert.Equal(n, partial.Length);
        }

        return DecodeAll(Segments(wire, 1));
    }

    /// <summary>A sequence of <paramref name="size"/>-byte segments, so lines and bodies span segments.</summary>
    private static ReadOnlySequence<byte> Segments(ReadOnlyMemory<byte> bytes, int size)
    {
        if (bytes.IsEmpty)
        {
            return ReadOnlySequence<byte>.Empty;
        }

        var first = new Segment(bytes[..Math.Min(size, bytes.Length)], 0);
        var last = first;
        for (var i = size; i < bytes.Length; i += size)
        {
            last = last.Append(bytes[i..Math.Min(i + size, bytes.Length)]);
        }

        return new ReadOnlySequence<byte>(first, 0, last, last.Memory.Length);
    }

    public static TheoryData<string, object> Values => new()
    {
        { "+OK\r\n", new RespSimpleString("OK") },
        { "+\r\n", new RespSimpleString("") },
        { "-UNSUPPORTED op Foo\r\n", new RespError("UNSUPPORTED", "op Foo") },
        { "-AUTH_ERROR\r\n", new RespError("AUTH_ERROR", "") },
        { "!21\r\nSYNTAX invalid syntax\r\n", new RespError("SYNTAX", "invalid syntax", IsBlob: true) },
        { "!8\r\nERR a\r\nb\r\n", new RespError("ERR", "a\r\nb", IsBlob: true) },
        { ":-42\r\n", new RespInteger(-42) },
        { ":9223372036854775807\r\n", new RespInteger(long.MaxValue) },
        { "(-3492890328409238509324850943850943825024385\r\n", new RespBigNumber(BigInteger.Parse("-3492890328409238509324850943850943825024385")) },
        { ",1.5\r\n", new RespDouble(1.5) },
        { ",1E+300\r\n", new RespDouble(1e300) },
        { ",inf\r\n", new RespDouble(double.PositiveInfinity) },
        { ",-inf\r\n", new RespDouble(double.NegativeInfinity) },
        { ",nan\r\n", new RespDouble(double.NaN) },
        { "#t\r\n", new RespBoolean(true) },
        { "#f\r\n", new RespBoolean(false) },
        { "_\r\n", RespNull.Instance },
        { "$5\r\nhe\r\no\r\n", Blob("he\r\no") },
        { "$0\r\n\r\n", Blob("") },
        { "=15\r\ntxt:Some string\r\n", new RespVerbatimString("txt", "Some string"u8.ToArray()) },
        { "*2\r\n:1\r\n*0\r\n", new RespArray([new RespInteger(1), new RespArray([])]) },
        { "~2\r\n+a\r\n+b\r\n", new RespSet([new RespSimpleString("a"), new RespSimpleString("b")]) },
        { ">2\r\n$12\r\naws.response\r\n+id\r\n", new RespPush([Blob("aws.response"), new RespSimpleString("id")]) },
        { "%1\r\n+k\r\n%0\r\n", new RespMap([new(new RespSimpleString("k"), new RespMap([]))]) },
    };

    [Theory]
    [MemberData(nameof(Values))]
    public void DecodesEveryTypeByteAtATime(string wire, object expected)
    {
        Assert.Equal(expected, DecodeByteAtATime(Bytes(wire)));
    }

    public static TheoryData<string, object> Resp2AndLenient => new()
    {
        { "$-1\r\n", RespNull.Instance },
        { "*-1\r\n", RespNull.Instance },
        { ":+7\r\n", new RespInteger(7) },
        { ",+inf\r\n", new RespDouble(double.PositiveInfinity) },
        { ",-nan\r\n", new RespDouble(double.NaN) },
        { ",-1.5e-3\r\n", new RespDouble(-0.0015) },
        { "-ERR\r\n", new RespError("ERR", "") },
        { "- lone\r\n", new RespError("", "lone") },
        { ">0\r\n", new RespPush([]) },
        { "|1\r\n+ttl\r\n:3600\r\n+OK\r\n", new RespSimpleString("OK") },
        { "*2\r\n|1\r\n+a\r\n+b\r\n:1\r\n:2\r\n", new RespArray([new RespInteger(1), new RespInteger(2)]) },
    };

    [Theory]
    [MemberData(nameof(Resp2AndLenient))]
    public void DecodesResp2NullsAndLenientForms(string wire, object expected)
    {
        Assert.Equal(expected, DecodeByteAtATime(Bytes(wire)));
    }

    [Fact]
    public void DecodesBinaryBodies()
    {
        byte[] wire = [.. "$4\r\n"u8, 0, 13, 10, 255, .. "\r\n"u8];
        Assert.Equal(new RespBlobString(new byte[] { 0, 13, 10, 255 }), DecodeByteAtATime(wire));
    }

    [Fact]
    public void LeavesTheNextValueInTheBuffer()
    {
        var buffer = new ReadOnlySequence<byte>(Bytes("+a\r\n:1\r\n$2\r\nx"));
        Assert.True(RespDecoder.TryDecode(ref buffer, out var first));
        Assert.True(RespDecoder.TryDecode(ref buffer, out var second));
        Assert.False(RespDecoder.TryDecode(ref buffer, out _));
        Assert.Equal(new RespSimpleString("a"), first);
        Assert.Equal(new RespInteger(1), second);
        Assert.Equal("$2\r\nx", Encoding.UTF8.GetString(buffer.ToArray()));
    }

    public static TheoryData<string> Malformed => new()
    {
        "?3\r\n",
        "X",
        "\r\n",
        ":12a\r\n",
        ":\r\n",
        ":99999999999999999999\r\n",
        ",1.2.3x\r\n",
        ",Infinity\r\n",
        "(12.5\r\n",
        "(-\r\n",
        "#x\r\n",
        "#\r\n",
        "_x\r\n",
        "$-2\r\n",
        "$?\r\n",
        "!-1\r\n",
        "$3\r\nabcde",
        "=3\r\ntxt\r\n",
        "=5\r\ntxt-x\r\n",
        "*-2\r\n",
        "~-1\r\n",
        ">-1\r\n",
        "%-1\r\n",
        "*2147483648\r\n",
        "%1073741824\r\n",
        "*2\r\n:1\r\n?\r\n",
        "+a\nb\r\n",
        "-E x\ry\r\n",
        "=7\r\na\nb:xyz\r\n",
        "=7\r\na:b:xyz\r\n",
        "(" + new string('1', RespDecoder.MaxBigNumberLength + 1) + "\r\n",
    };

    [Theory]
    [MemberData(nameof(Malformed))]
    public void RejectsMalformedInput(string wire)
    {
        var buffer = new ReadOnlySequence<byte>(Bytes(wire));
        Assert.Throws<ElastiCacheIntegrationsProtocolException>(() => RespDecoder.TryDecode(ref buffer, out _));
    }

    [Fact]
    public void RejectsInvalidUtf8InASimpleString()
    {
        var buffer = new ReadOnlySequence<byte>([(byte)'+', 0xC3, 0x28, 13, 10]);
        var e = Assert.Throws<ElastiCacheIntegrationsProtocolException>(() => RespDecoder.TryDecode(ref buffer, out _));
        Assert.IsType<DecoderFallbackException>(e.InnerException);
    }

    [Fact]
    public void ErrorTextIsDecodedLeniently()
    {
        var value = DecodeAll(new byte[] { (byte)'-', (byte)'E', (byte)' ', 0xFF, 13, 10 });
        Assert.Equal(new RespError("E", "\ufffd"), value);
    }

    [Fact]
    public void AcceptsTheLongestBigNumberAllowed()
    {
        var digits = new string('9', RespDecoder.MaxBigNumberLength);
        Assert.Equal(new RespBigNumber(System.Numerics.BigInteger.Parse(digits)), DecodeAll(Bytes($"({digits}\r\n")));
    }

    [Fact]
    public void LimitsNesting()
    {
        string Nested(int depth) => string.Concat(Enumerable.Repeat("*1\r\n", depth)) + ":1\r\n";

        Assert.IsType<RespArray>(DecodeAll(Bytes(Nested(RespDecoder.MaxDepth))));
        var buffer = new ReadOnlySequence<byte>(Bytes(Nested(RespDecoder.MaxDepth + 1)));
        Assert.Throws<ElastiCacheIntegrationsProtocolException>(() => RespDecoder.TryDecode(ref buffer, out _));
    }

    [Fact]
    public void AHugeDeclaredLengthWaitsForBytes()
    {
        var buffer = new ReadOnlySequence<byte>(Bytes("*1000000\r\n:1\r\n"));
        Assert.False(RespDecoder.TryDecode(ref buffer, out _));
        buffer = new ReadOnlySequence<byte>(Bytes("$2147483591\r\nabc"));
        Assert.False(RespDecoder.TryDecode(ref buffer, out _));
    }

    public static TheoryData<string, object> ProtocolShapes => new()
    {
        {
            "*3\r\n:200\r\n$29\r\ncontent-type:application/json\r\n$10\r\n{\"Item\":1}\r\n",
            new RespArray([new RespInteger(200), Blob("content-type:application/json"), Blob("{\"Item\":1}")])
        },
        { "+req-7\r\n", new RespSimpleString("req-7") },
        { "-UNSUPPORTED op ListTables\r\n", new RespError("UNSUPPORTED", "op ListTables") },
        { "-CONFIG_INVALID ttl must be positive\r\n", new RespError("CONFIG_INVALID", "ttl must be positive") },
        { "_\r\n", RespNull.Instance },
        { "$23\r\n{\"refresh_interval\":60}\r\n", Blob("{\"refresh_interval\":60}") },
        {
            ">3\r\n$12\r\naws.response\r\n$5\r\nreq-7\r\n*3\r\n:404\r\n$0\r\n\r\n$2\r\n{}\r\n",
            new RespPush([Blob("aws.response"), Blob("req-7"), new RespArray([new RespInteger(404), Blob(""), Blob("{}")])])
        },
        {
            "%3\r\n$6\r\nserver\r\n$5\r\ncache\r\n$5\r\nproto\r\n:3\r\n$7\r\nmodules\r\n*0\r\n",
            new RespMap([new(Blob("server"), Blob("cache")), new(Blob("proto"), new RespInteger(3)), new(Blob("modules"), new RespArray([]))])
        },
    };

    [Theory]
    [MemberData(nameof(ProtocolShapes))]
    public void DecodesTheShapesTheCacheSends(string wire, object expected)
    {
        Assert.Equal(expected, DecodeByteAtATime(Bytes(wire)));
    }

    [Fact]
    public void DecodesALargeBodyInAPushInChunks()
    {
        var body = new byte[400 * 1024 + 3];
        Random.Shared.NextBytes(body);
        byte[] wire =
        [
            .. ">3\r\n$12\r\naws.response\r\n$1\r\n1\r\n*3\r\n:200\r\n$0\r\n\r\n$"u8,
            .. Bytes(body.Length.ToString()), .. "\r\n"u8, .. body, .. "\r\n"u8,
        ];

        for (var n = 0; n < wire.Length; n += 4093)
        {
            var partial = Segments(wire.AsMemory(0, n), 4096);
            Assert.False(RespDecoder.TryDecode(ref partial, out _));
        }

        var push = Assert.IsType<RespPush>(DecodeAll(Segments(wire, 4096)));
        var reply = Assert.IsType<RespArray>(push.Items[2]);
        Assert.Equal(body, Assert.IsType<RespBlobString>(reply.Items[2]).Value.ToArray());
    }

    private sealed class Segment : ReadOnlySequenceSegment<byte>
    {
        public Segment(ReadOnlyMemory<byte> memory, long runningIndex)
        {
            Memory = memory;
            RunningIndex = runningIndex;
        }

        public Segment Append(ReadOnlyMemory<byte> memory)
        {
            var next = new Segment(memory, RunningIndex + Memory.Length);
            Next = next;
            return next;
        }
    }
}
