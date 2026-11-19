using System.Net;
using System.Net.Sockets;
using System.Text;

namespace Amazon.ElastiCache.Integrations.Tests;

public class ReceiveLowWaterTests
{
    private const int KiB = 1024;

    private static CancellationToken Token => TestContext.Current.CancellationToken;

    [Theory]
    [InlineData(0, 1, 1)]
    [InlineData(100 * KiB, 1, 18 * KiB)]
    [InlineData(400 * KiB, 1, 168 * KiB)]
    [InlineData(390 * KiB, 168 * KiB, 168 * KiB)]
    [InlineData(230 * KiB, 168 * KiB, 83 * KiB)]
    [InlineData(80 * KiB, 18 * KiB, 16 * KiB)]
    [InlineData(79 * KiB, 16 * KiB, 1)]
    [InlineData(0, 168 * KiB, 1)]
    [InlineData(16L * KiB * KiB, 1, KiB * KiB)]
    public void TheThresholdStaysBelowWhatIsStillToArrive(long missing, int current, int expected)
    {
        var target = ReceiveLowWater.Target(missing, current);

        Assert.Equal(expected, target);
        Assert.True(target == 1 || target <= missing - ReceiveLowWater.TlsMargin);
    }

    [Fact]
    public async Task ALargeValueSentInSmallSegmentsIsReadWholeInFewerReads()
    {
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        using var client = new Socket(SocketType.Stream, ProtocolType.Tcp) { NoDelay = true };
        await client.ConnectAsync((IPEndPoint)listener.LocalEndpoint, Token);
        using var server = await listener.AcceptSocketAsync(Token);
        server.NoDelay = true;

        var body = new byte[600 * KiB];
        Random.Shared.NextBytes(body);
        byte[] wire = [.. Encoding.ASCII.GetBytes($"${body.Length}\r\n"), .. body, .. "\r\n:7\r\n"u8];
        var chunks = wire.Chunk(3 * KiB).ToArray();
        var send = Task.Run(
            async () =>
            {
                foreach (var chunk in chunks)
                {
                    await server.SendAsync(chunk, Token);
                    await Task.Delay(1, Token);
                }
            },
            Token);

        var lowWater = new ReceiveLowWater(client);
        var stream = new CountingStream(new NetworkStream(client, ownsSocket: false));
        var reader = new ReplyReader(stream, 1 << 20, lowWater);

        Assert.Equal(new RespBlobString(body), await reader.ReadAsync(Token));
        Assert.Equal(new RespInteger(7), await reader.ReadAsync(Token));
        await send;

        Assert.Equal(1, lowWater.Current);
        if (OperatingSystem.IsLinux())
        {
            Assert.InRange(stream.Reads, 1, chunks.Length / 2);
        }
    }

    private sealed class CountingStream(Stream inner) : Stream
    {
        public int Reads { get; private set; }

        public override bool CanRead => true;

        public override bool CanSeek => false;

        public override bool CanWrite => false;

        public override long Length => throw new NotSupportedException();

        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }

        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        {
            Reads++;
            return await inner.ReadAsync(buffer, cancellationToken);
        }

        public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();

        public override void Flush()
        {
        }

        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();

        public override void SetLength(long value) => throw new NotSupportedException();

        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();

        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                inner.Dispose();
            }

            base.Dispose(disposing);
        }
    }
}
