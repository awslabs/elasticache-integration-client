// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using System.Buffers;
using System.Text;

namespace Amazon.ElastiCache.Integrations.Tests;

public class RespEncoderTests
{
    private static string Command(params RespArg[] args)
    {
        var writer = new ArrayBufferWriter<byte>();
        RespEncoder.WriteCommand(writer, args);
        return Encoding.UTF8.GetString(writer.WrittenSpan);
    }

    [Fact]
    public void WritesTheProtocolDocExample()
    {
        Assert.Equal(
            "*2\r\n$10\r\naws.config\r\n$41\r\n{\"exclude_operations\":[\"Scan\"],\"ttl\":300}\r\n",
            Command("aws.config", "{\"exclude_operations\":[\"Scan\"],\"ttl\":300}"));
    }

    [Fact]
    public void WritesTheNoArgumentForm()
    {
        Assert.Equal("*1\r\n$10\r\naws.config\r\n", Command("aws.config"));
    }

    [Fact]
    public void MixesTextAndBytes()
    {
        ReadOnlyMemory<byte> body = new byte[] { 0, 13, 10, 255 };
        var writer = new ArrayBufferWriter<byte>();
        RespEncoder.WriteCommand(writer, "aws.request", "é", body, Array.Empty<byte>());
        byte[] expected = [.. "*4\r\n$11\r\naws.request\r\n$2\r\n"u8, 0xC3, 0xA9, .. "\r\n$4\r\n"u8, 0, 13, 10, 255, .. "\r\n$0\r\n\r\n"u8];
        Assert.Equal(expected, writer.WrittenSpan.ToArray());
    }

    [Fact]
    public void WritesASliceWithoutItsNeighbours()
    {
        var bytes = "xxabcxx"u8.ToArray();
        Assert.Equal("*2\r\n$1\r\nc\r\n$3\r\nabc\r\n", Command("c", bytes.AsMemory(2, 3)));
    }

    [Fact]
    public void WritesALargeBodyThroughSmallBuffers()
    {
        var body = new byte[400 * 1024];
        Random.Shared.NextBytes(body);
        var writer = new SmallSpanWriter();
        RespEncoder.WriteCommand(writer, "aws.request", body);
        byte[] expected = [.. "*2\r\n$11\r\naws.request\r\n$409600\r\n"u8, .. body, .. "\r\n"u8];
        Assert.Equal(expected, writer.Written.ToArray());
    }

    [Fact]
    public void ManyArgumentsUseTheHeap()
    {
        var args = Enumerable.Range(0, 20).Select(i => (RespArg)i.ToString()).ToArray();
        Assert.StartsWith("*20\r\n$1\r\n0\r\n", Command(args));
    }

    [Fact]
    public void InvalidTextWritesNothing()
    {
        var writer = new ArrayBufferWriter<byte>();
        Assert.ThrowsAny<ArgumentException>(() => RespEncoder.WriteCommand(writer, "ok", "\ud800"));
        Assert.Equal(0, writer.WrittenCount);
    }

    [Fact]
    public void RejectsMisuse()
    {
        var writer = new ArrayBufferWriter<byte>();
        Assert.Throws<ArgumentException>(() => RespEncoder.WriteCommand(writer, ReadOnlySpan<RespArg>.Empty));
        Assert.Throws<ArgumentNullException>(() => RespEncoder.WriteCommand(null!, "x"));
        Assert.Throws<ArgumentNullException>(() => RespEncoder.WriteCommand(writer, (RespArg[])null!));
        Assert.Throws<ArgumentNullException>(() => (RespArg)(string)null!);
        Assert.Throws<ArgumentNullException>(() => (RespArg)(byte[])null!);
    }

    private static void AssertSame(RespValue a, RespValue b)
    {
        Assert.Equal(a, b);
        Assert.Equal(a.GetHashCode(), b.GetHashCode());
    }

    [Fact]
    public void ScalarsCompareByValue()
    {
        AssertSame(new RespSimpleString("a"), new RespSimpleString("a"));
        Assert.NotEqual(new RespSimpleString("a"), new RespSimpleString("b"));
        AssertSame(new RespError("E", "m"), new RespError("E", "m"));
        Assert.NotEqual(new RespError("E", "m"), new RespError("F", "m"));
        Assert.NotEqual(new RespError("E", "m"), new RespError("E", "n"));
        Assert.NotEqual(new RespError("E", "m"), new RespError("E", "m", IsBlob: true));
        AssertSame(new RespBigNumber(12), new RespBigNumber(12));
        Assert.NotEqual(new RespBigNumber(12), new RespBigNumber(13));
        AssertSame(new RespBoolean(true), new RespBoolean(true));
        Assert.NotEqual(new RespBoolean(true), new RespBoolean(false));
        Assert.NotEqual<RespValue>(new RespInteger(1), new RespBigNumber(1));
    }

    [Fact]
    public void ValuesCompareByContent()
    {
        AssertSame(new RespBlobString("ab"u8.ToArray()), new RespBlobString("xab"u8.ToArray().AsMemory(1)));
        Assert.NotEqual(new RespBlobString("ab"u8.ToArray()), new RespBlobString("ac"u8.ToArray()));
        Assert.Equal(new RespVerbatimString("txt", "a"u8.ToArray()), new RespVerbatimString("txt", "a"u8.ToArray()));
        Assert.NotEqual(new RespVerbatimString("txt", "a"u8.ToArray()), new RespVerbatimString("mkd", "a"u8.ToArray()));
        Assert.Equal(new RespArray([new RespInteger(1)]), new RespArray([new RespInteger(1)]));
        AssertSame(new RespPush([new RespInteger(1)]), new RespPush([new RespInteger(1)]));
        Assert.NotEqual<RespValue>(new RespArray([new RespInteger(1)]), new RespSet([new RespInteger(1)]));
        Assert.NotEqual<RespValue>(new RespArray([new RespInteger(1)]), new RespPush([new RespInteger(1)]));
        Assert.NotEqual(new RespArray([new RespInteger(1)]), new RespArray([new RespInteger(2)]));
        Assert.Equal(new RespDouble(double.NaN), new RespDouble(double.NaN));
        Assert.Same(RespNull.Instance, RespNull.Instance);

        var map = new RespMap([new(new RespSimpleString("k"), new RespBoolean(true))]);
        AssertSame(map, new RespMap([new(new RespSimpleString("k"), new RespBoolean(true))]));
        Assert.NotEqual(map, new RespMap([new(new RespSimpleString("j"), new RespBoolean(true))]));
        Assert.NotEqual(map, new RespMap([new(new RespSimpleString("k"), new RespBoolean(false))]));
        Assert.NotEqual(map, new RespMap([]));
    }

    /// <summary>Hands out 7-byte spans, so every write must loop.</summary>
    private sealed class SmallSpanWriter : IBufferWriter<byte>
    {
        private readonly ArrayBufferWriter<byte> _inner = new();

        public ReadOnlySpan<byte> Written => _inner.WrittenSpan;

        public void Advance(int count) => _inner.Advance(count);

        public Memory<byte> GetMemory(int sizeHint = 0) => _inner.GetMemory(Math.Max(sizeHint, 7))[..Math.Max(sizeHint, 7)];

        public Span<byte> GetSpan(int sizeHint = 0) => _inner.GetSpan(Math.Max(sizeHint, 7))[..Math.Max(sizeHint, 7)];
    }

    [Fact]
    public void ACommandIsWrittenIntoOneBufferSizedForTheWholeFrame()
    {
        var writer = new ArrayBufferWriter<byte>();
        var body = new byte[400_000];

        RespEncoder.WriteCommand(writer, "aws.request", "POST", body);

        Assert.InRange(writer.Capacity - writer.WrittenCount, 0, 1024);
    }
}
