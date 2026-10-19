using System.Buffers;
using System.Net.Sockets;
using System.Text;

namespace Amazon.ElastiCache.Integrations.Tests;

/// <summary>The command contract and wire behaviour of <see cref="CacheServer"/>.</summary>
public class CacheServerTests
{
    private const string Metadata = "service=dynamodb&op=GetItem";

    private static CancellationToken Token => TestContext.Current.CancellationToken;

    private static string[] Request(string metadata = Metadata) => ["aws.request", "POST", "https://x/", "h:v", "{}", metadata];

    private static void AssertError(string code, MockReply reply) =>
        Assert.Equal(code, Assert.IsType<RespError>(Assert.IsType<MockReply.Now>(reply).Value).Code);

    [Theory]
    [InlineData("""{"version":"1.0"}""", "extra")]
    [InlineData("not-json", null)]
    [InlineData("""["a-list"]""", null)]
    [InlineData("{}", null)]
    [InlineData("""{"version":"2.0"}""", null)]
    public async Task ConfigWithBadArgumentsIsConfigInvalid(string overrides, string? extra)
    {
        await using var server = new CacheServer();
        string[] command = extra is null ? ["aws.config", overrides] : ["aws.config", overrides, extra];

        AssertError("CONFIG_INVALID", server.Handle(command));
        Assert.Empty(server.Overrides);
    }

    [Fact]
    public async Task OverridesAreRecordedInOrder()
    {
        await using var server = new CacheServer();

        server.Handle(["aws.config", """{"version":"1.0","item_ttl":300}"""]);
        server.Handle(["aws.config", """{"version":"1.0"}"""]);

        Assert.Equal(["""{"version":"1.0","item_ttl":300}""", """{"version":"1.0"}"""], server.Overrides);
    }

    [Fact]
    public async Task ARequestWithoutExactlyFiveArgumentsIsAParseError()
    {
        await using var server = new CacheServer();

        AssertError("PARSE_ERROR", server.Handle(["aws.request", "POST"]));
        AssertError("PARSE_ERROR", server.Handle([.. Request(), "TTL", "300"]));
        Assert.Empty(server.Requests);
    }

    [Fact]
    public async Task ARequestWithBadMetadataIsAParseErrorAndNotRecorded()
    {
        await using var server = new CacheServer();

        AssertError("PARSE_ERROR", server.Handle(Request("garbage")));
        AssertError("PARSE_ERROR", server.Handle(Request("service=dynamodb")));
        Assert.Empty(server.Requests);
    }

    [Fact]
    public async Task AValidRequestIsRecordedAndAnswered()
    {
        await using var server = new CacheServer { OnRequest = _ => MockCache.Served("{}", 201) };

        var reply = Assert.IsType<RespArray>(Assert.IsType<MockReply.Now>(server.Handle(Request("service=dynamodb&op=GetItem&future=1"))).Value);

        Assert.Equal(new RespInteger(201), reply.Items[0]);
        Assert.Equal("service=dynamodb&op=GetItem&future=1", Assert.Single(server.Requests)[5]);
    }

    [Fact]
    public async Task InterceptReplacesAnyReply()
    {
        await using var server = new CacheServer { Intercept = c => c[0] == "AUTH" ? new MockReply.Now(new RespError("WRONGPASS", "no")) : null };

        AssertError("WRONGPASS", server.Handle(["AUTH", "user", "token"]));
        Assert.IsType<MockReply.Now>(server.Handle(["HELLO", "3"]));
        Assert.Equal(2, server.Commands.Count);
    }

    [Fact]
    public async Task Resp3AnswersHelloAndAnUnchangedRefreshWithNull()
    {
        await using var server = new CacheServer();
        await using var client = await RawClient.ConnectAsync(server, handshake: false);

        Assert.Equal(new RespSimpleString("OK"), await client.CallAsync("AUTH", "user", "token"));
        Assert.IsType<RespMap>(await client.CallAsync("HELLO", "3"));
        Assert.Equal(server.ConfigJson, Text(await client.CallAsync("aws.config", """{"version":"1.0"}""")));
        Assert.Equal("_\r\n", await client.CallRawAsync("aws.config"));
        server.OnRefresh = () => """{"version":"1.0"}""";
        Assert.Equal("""{"version":"1.0"}""", Text(await client.CallAsync("aws.config")));
        Assert.Equal(1, server.Connections);
    }

    [Fact]
    public async Task Resp2RefusesHelloAndSendsTheResp2Null()
    {
        await using var server = new CacheServer(resp3: false);
        await using var client = await RawClient.ConnectAsync(server, handshake: false);

        await client.CallAsync("AUTH", "user", "token");
        Assert.Equal("ERR", Assert.IsType<RespError>(await client.CallAsync("HELLO", "3")).Code);
        await client.CallAsync("aws.config", """{"version":"1.0"}""");
        Assert.Equal("$-1\r\n", await client.CallRawAsync("aws.config"));
    }

    [Fact]
    public async Task ADeferredReplyIsTheIdThenItsPush()
    {
        var reply = new TaskCompletionSource<RespValue>();
        await using var server = new CacheServer { OnRequest = _ => new MockReply.Deferred(reply.Task) };
        await using var client = await RawClient.ConnectAsync(server);

        Assert.Equal(new RespSimpleString("1"), await client.CallAsync(Request()));
        reply.SetResult(MockCache.Served("body"));

        Assert.Equal(MockCache.Push("1", MockCache.Served("body")), await client.ReadAsync());
    }

    [Fact]
    public async Task LaterSendsThePushRightAfterTheId()
    {
        await using var server = new CacheServer { OnRequest = _ => MockReply.Later(MockCache.Served("body")) };
        await using var client = await RawClient.ConnectAsync(server);

        Assert.Equal(new RespSimpleString("1"), await client.CallAsync(Request()));
        Assert.Equal(MockCache.Push("1", MockCache.Served("body")), await client.ReadAsync());
    }

    [Fact]
    public async Task HangStopsReplyingButKeepsRecording()
    {
        await using var server = new CacheServer { OnRequest = _ => new MockReply.Hang() };
        var client = await RawClient.ConnectAsync(server);
        Task<RespValue> read;
        try
        {
            await client.SendAsync(Request());
            await client.SendAsync("AUTH", "user", "token");
            read = client.ReadAsync();
            for (var i = 0; i < 500 && server.Commands.Count(c => c[0] == "AUTH") < 2; i++)
            {
                await Task.Delay(10, Token);
            }

            Assert.Equal(2, server.Commands.Count(c => c[0] == "AUTH"));
            Assert.False(read.IsCompleted);
        }
        finally
        {
            await client.DisposeAsync();
        }

        await Assert.ThrowsAnyAsync<Exception>(() => read);
    }

    [Fact]
    public async Task DropClosesAfterItsReply()
    {
        await using var server = new CacheServer { OnRequest = _ => new MockReply.Drop(MockCache.Served("last")) };
        await using var client = await RawClient.ConnectAsync(server);

        Assert.Equal(MockCache.Served("last"), await client.CallAsync(Request()));
        await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionLostException>(() => client.ReadAsync());
    }

    [Theory]
    [InlineData(":5\r\n", typeof(InvalidDataException))]
    [InlineData("*1\r\n:5\r\n", typeof(InvalidDataException))]
    [InlineData("*1\r\n$4\r\nAUTHxx", typeof(ElastiCacheIntegrationsProtocolException))]
    [InlineData("*2\r\n$5\r\nHELLO\r\n$1\r\n3\r\n", typeof(InvalidDataException))]
    public async Task ABrokenFrameOrHandshakeFailsTheServer(string frame, Type cause)
    {
        var server = new CacheServer();
        try
        {
            await using var client = await RawClient.ConnectAsync(server, handshake: false);
            await client.SendRawAsync(frame);
            await Assert.ThrowsAnyAsync<Exception>(() => client.ReadAsync());
        }
        finally
        {
            var thrown = await Assert.ThrowsAsync<InvalidOperationException>(() => server.DisposeAsync().AsTask());
            Assert.IsType(cause, thrown.InnerException);
        }
    }

    private static string Text(RespValue value) => Encoding.UTF8.GetString(Assert.IsType<RespBlobString>(value).Value.Span);

    /// <summary>A bare RESP client for the server's wire behaviour.</summary>
    private sealed class RawClient(TcpClient tcp) : IAsyncDisposable
    {
        private readonly NetworkStream _stream = tcp.GetStream();
        private ReplyReader? _reader;

        private ReplyReader Reader => _reader ??= new ReplyReader(_stream, int.MaxValue);

        /// <summary>Connects and, unless <paramref name="handshake"/> is false, sends <c>AUTH</c>, <c>HELLO 3</c> and the overrides.</summary>
        public static async Task<RawClient> ConnectAsync(CacheServer server, bool handshake = true)
        {
            var tcp = new TcpClient();
            var endpoint = server.Endpoint.Split(':');
            await tcp.ConnectAsync(endpoint[0], int.Parse(endpoint[1], System.Globalization.CultureInfo.InvariantCulture), Token);
            var client = new RawClient(tcp);
            if (handshake)
            {
                await client.CallAsync("AUTH", "user", "token");
                await client.CallAsync("HELLO", "3");
                await client.CallAsync("aws.config", """{"version":"1.0"}""");
            }

            return client;
        }

        public async Task SendAsync(params string[] command)
        {
            var frame = new ArrayBufferWriter<byte>();
            RespEncoder.WriteCommand(frame, [.. command.Select(arg => (RespArg)arg)]);
            await _stream.WriteAsync(frame.WrittenMemory, Token);
        }

        public Task SendRawAsync(string raw) => _stream.WriteAsync(Encoding.UTF8.GetBytes(raw), Token).AsTask();

        public Task<RespValue> ReadAsync() => Reader.ReadAsync(Token).AsTask();

        public async Task<RespValue> CallAsync(params string[] command)
        {
            await SendAsync(command);
            return await ReadAsync();
        }

        /// <summary>Sends a command and reads the raw bytes of a one-line reply.</summary>
        public async Task<string> CallRawAsync(params string[] command)
        {
            await SendAsync(command);
            var reply = new StringBuilder();
            var buffer = new byte[1];
            while (!reply.ToString().EndsWith("\r\n", StringComparison.Ordinal))
            {
                await _stream.ReadExactlyAsync(buffer, Token);
                reply.Append((char)buffer[0]);
            }

            return reply.ToString();
        }

        public ValueTask DisposeAsync()
        {
            tcp.Dispose();
            return ValueTask.CompletedTask;
        }
    }
}
