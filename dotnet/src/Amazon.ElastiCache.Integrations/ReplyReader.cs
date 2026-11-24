// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using System.Buffers;

namespace Amazon.ElastiCache.Integrations;

/// <summary>
/// Reads whole RESP values from a stream. A value may arrive split across any number of reads;
/// bytes after it stay buffered for the next call. Not thread safe: one reader task owns it.
/// </summary>
internal sealed class ReplyReader(Stream stream, int maxResponseSize)
{
    /// <summary>The buffer size between large values, below the large object heap threshold.</summary>
    internal const int ReadSize = 64 * 1024;

    /// <summary>The fewest free bytes a read is given before the buffer is compacted or grown.</summary>
    internal const int MinRead = 4 * 1024;

    private byte[] _buffer = new byte[ReadSize];
    private int _start;
    private int _end;

    internal int BufferLength => _buffer.Length;

    /// <exception cref="ElastiCacheIntegrationsConnectionLostException">The cache closed the stream.</exception>
    /// <exception cref="ElastiCacheIntegrationsProtocolException">A corrupt value, or one larger than the limit.</exception>
    public async ValueTask<RespValue> ReadAsync(CancellationToken cancellationToken)
    {
        while (true)
        {
            if (_end > _start)
            {
                var buffered = new ReadOnlySequence<byte>(_buffer, _start, _end - _start);
                var rest = buffered;
                if (RespDecoder.TryDecode(ref rest, out var value))
                {
                    CheckSize(buffered.Length - rest.Length);
                    _start = _end - (int)rest.Length;
                    return value;
                }

                // The decoder has no size limit, so an incomplete value is bounded here.
                CheckSize(buffered.Length);
            }

            MakeRoom();
            var available = (int)Math.Min(_buffer.Length - _end, (long)maxResponseSize - (_end - _start) + 1);
            var read = await stream.ReadAsync(_buffer.AsMemory(_end, available), cancellationToken).ConfigureAwait(false);
            if (read == 0)
            {
                throw new ElastiCacheIntegrationsConnectionLostException("The cache closed the connection.");
            }

            _end += read;
        }
    }

    private void CheckSize(long size)
    {
        if (size > maxResponseSize)
        {
            throw TooLarge();
        }
    }

    private ElastiCacheIntegrationsProtocolException TooLarge() => new($"Reply is larger than MaxResponseSize ({maxResponseSize} bytes).");

    /// <summary>
    /// Leaves free bytes after the buffered ones: compacts first, then grows, up to
    /// <c>maxResponseSize</c> plus one read. A grown buffer goes back to <see cref="ReadSize"/> once empty.
    /// </summary>
    private void MakeRoom()
    {
        var length = _end - _start;
        if (length == 0)
        {
            if (_buffer.Length > ReadSize)
            {
                _buffer = new byte[ReadSize];
            }

            _start = _end = 0;
            return;
        }

        if (_buffer.Length - _end >= MinRead)
        {
            return;
        }

        var target = _buffer;
        if (length + MinRead > _buffer.Length)
        {
            var size = Math.Min(Math.Max(2L * _buffer.Length, (long)length + ReadSize), (long)maxResponseSize + ReadSize);
            target = new byte[(int)Math.Min(size, Array.MaxLength)];
            if (target.Length <= length)
            {
                throw TooLarge();
            }
        }

        Buffer.BlockCopy(_buffer, _start, target, 0, length);
        _buffer = target;
        _start = 0;
        _end = length;
    }
}
