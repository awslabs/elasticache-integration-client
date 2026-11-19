// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using System.Text;

namespace Amazon.ElastiCache.Integrations.Tests;

public class ReplyReaderTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    private static byte[] Bytes(string text) => Encoding.ASCII.GetBytes(text);

    [Fact]
    public async Task AValueSplitIntoSingleBytesIsReadWhole()
    {
        var wire = Bytes("*3\r\n:200\r\n$2\r\nh1\r\n$4\r\nbody\r\n");
        var reader = new ReplyReader(new ChunkStream([.. wire.Select(b => new[] { b })]), 1024);

        var value = await reader.ReadAsync(Token);

        Assert.Equal(new RespArray([new RespInteger(200), new RespBlobString(Bytes("h1")), new RespBlobString(Bytes("body"))]), value);
    }

    [Fact]
    public async Task SeveralValuesInOneReadAreReturnedOneByOne()
    {
        var stream = new ChunkStream([Bytes("+a\r\n:1\r\n$1\r\n")]);
        var reader = new ReplyReader(stream, 1024);

        Assert.Equal(new RespSimpleString("a"), await reader.ReadAsync(Token));
        Assert.Equal(new RespInteger(1), await reader.ReadAsync(Token));
        Assert.Equal(1, stream.Reads);
        await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionLostException>(() => reader.ReadAsync(Token).AsTask());
    }

    [Fact]
    public async Task EndOfStreamIsConnectionLost()
    {
        var reader = new ReplyReader(new ChunkStream([]), 1024);

        await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionLostException>(() => reader.ReadAsync(Token).AsTask());
    }

    [Fact]
    public async Task CorruptBytesAreAProtocolError()
    {
        var reader = new ReplyReader(new ChunkStream([Bytes("?\r\n")]), 1024);

        await Assert.ThrowsAsync<ElastiCacheIntegrationsProtocolException>(() => reader.ReadAsync(Token).AsTask());
    }

    [Fact]
    public async Task AValueOfExactlyTheLimitIsAccepted()
    {
        var wire = Bytes("$5\r\nhello\r\n");
        var reader = new ReplyReader(new ChunkStream([wire]), wire.Length);

        Assert.Equal(new RespBlobString(Bytes("hello")), await reader.ReadAsync(Token));
    }

    [Fact]
    public async Task ACompleteValueOverTheLimitIsRejected()
    {
        var wire = Bytes("$5\r\nhello\r\n");
        var reader = new ReplyReader(new ChunkStream([wire]), wire.Length - 1);

        var error = await Assert.ThrowsAsync<ElastiCacheIntegrationsProtocolException>(() => reader.ReadAsync(Token).AsTask());
        Assert.Contains("MaxResponseSize", error.Message);
    }

    [Fact]
    public async Task AnIncompleteValueOverTheLimitIsRejectedBeforeTheRestArrives()
    {
        var stream = new ChunkStream([Bytes("$100\r\n"), new byte[20], new byte[200]]);
        var reader = new ReplyReader(stream, 16);

        await Assert.ThrowsAsync<ElastiCacheIntegrationsProtocolException>(() => reader.ReadAsync(Token).AsTask());
        Assert.Equal(2, stream.Reads);
    }

    [Fact]
    public async Task TheLimitAppliesPerValueNotPerRead()
    {
        var reader = new ReplyReader(new ChunkStream([Bytes("$3\r\nabc\r\n$3\r\ndef\r\n$3\r\nghi\r\n")]), 9);

        foreach (var expected in new[] { "abc", "def", "ghi" })
        {
            Assert.Equal(new RespBlobString(Bytes(expected)), await reader.ReadAsync(Token));
        }
    }

    [Fact]
    public async Task AValueLargerThanTheBufferGrowsItAndLaterValuesStillRead()
    {
        var body = new byte[ReplyReader.ReadSize * 5 + 7];
        Random.Shared.NextBytes(body);
        var wire = Bytes($"${body.Length}\r\n").Concat(body).Concat(Bytes("\r\n:7\r\n")).ToArray();
        var chunks = wire.Chunk(ReplyReader.ReadSize / 3).ToArray();
        var reader = new ReplyReader(new ChunkStream(chunks), 1 << 20);

        Assert.Equal(new RespBlobString(body), await reader.ReadAsync(Token));
        Assert.Equal(new RespInteger(7), await reader.ReadAsync(Token));
    }

    [Fact]
    public async Task APartialValueAtTheBufferEndIsCompactedAndAGrownBufferShrinksOnceEmpty()
    {
        static byte[] Blob(int size, byte fill) => [.. Bytes($"${size}\r\n"), .. Enumerable.Repeat(fill, size), .. Bytes("\r\n")];
        var a = Blob(ReplyReader.ReadSize - ReplyReader.MinRead, 1);
        var b = Blob(100, 2);
        var c = Blob(ReplyReader.ReadSize * 3, 3);
        var stream = new ChunkStream([[.. a, .. b[..10]], b[10..], .. c.Chunk(ReplyReader.ReadSize / 2), Bytes(":7\r\n")]);
        var reader = new ReplyReader(stream, 1 << 20);

        Assert.Equal(new RespBlobString(Enumerable.Repeat((byte)1, ReplyReader.ReadSize - ReplyReader.MinRead).ToArray()), await reader.ReadAsync(Token));
        Assert.Equal(new RespBlobString(Enumerable.Repeat((byte)2, 100).ToArray()), await reader.ReadAsync(Token));
        Assert.Equal(ReplyReader.ReadSize, reader.BufferLength);
        Assert.Equal(new RespBlobString(Enumerable.Repeat((byte)3, ReplyReader.ReadSize * 3).ToArray()), await reader.ReadAsync(Token));
        Assert.True(reader.BufferLength > ReplyReader.ReadSize);
        Assert.Equal(new RespInteger(7), await reader.ReadAsync(Token));
        Assert.Equal(ReplyReader.ReadSize, reader.BufferLength);
    }

    [Fact]
    public async Task TheBufferNeverGrowsPastTheLimitPlusOneRead()
    {
        var body = new byte[ReplyReader.ReadSize * 4];
        var wire = Bytes($"${body.Length}\r\n").Concat(body).Concat(Bytes("\r\n")).ToArray();
        var limit = wire.Length;
        var reader = new ReplyReader(new ChunkStream(wire.Chunk(1000).ToArray()), limit);

        await reader.ReadAsync(Token);

        Assert.True(reader.BufferLength <= limit + ReplyReader.ReadSize);
    }

    /// <summary>Returns one chunk per read, then end of stream.</summary>
    internal sealed class ChunkStream(IReadOnlyList<byte[]> chunks) : Stream
    {
        private int _next;
        private int _offset;

        public int Reads { get; private set; }

        public override bool CanRead => true;

        public override bool CanSeek => false;

        public override bool CanWrite => false;

        public override long Length => throw new NotSupportedException();

        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }

        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        {
            Reads++;
            if (_next == chunks.Count)
            {
                return ValueTask.FromResult(0);
            }

            var chunk = chunks[_next].AsSpan(_offset);
            var n = Math.Min(chunk.Length, buffer.Length);
            chunk[..n].CopyTo(buffer.Span);
            _offset += n;
            if (_offset == chunks[_next].Length)
            {
                _next++;
                _offset = 0;
            }

            return ValueTask.FromResult(n);
        }

        public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();

        public override void Flush()
        {
        }

        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();

        public override void SetLength(long value) => throw new NotSupportedException();

        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }
}
