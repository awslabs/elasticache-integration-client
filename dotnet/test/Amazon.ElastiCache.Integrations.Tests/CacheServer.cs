using System.Buffers;
using System.Collections.Concurrent;
using System.Globalization;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Logging;

namespace Amazon.ElastiCache.Integrations.Tests;

/// <summary>What the mock cache does with one command.</summary>
internal abstract record MockReply
{
    public static implicit operator MockReply(RespValue value) => new Now(value);

    /// <summary>Replies <c>+id</c>, then at once its <c>aws.response</c> push. RESP3 only.</summary>
    public static MockReply Later(RespValue reply) => new Deferred(Task.FromResult(reply));

    /// <summary>Replies in band.</summary>
    public sealed record Now(RespValue Value) : MockReply;

    /// <summary>Replies <c>+id</c> now, then the <c>aws.response</c> push once <paramref name="Reply"/> completes; a faulted task fails the server. RESP3 only.</summary>
    public sealed record Deferred(Task<RespValue> Reply) : MockReply;

    /// <summary>Replies to nothing more on this connection, which stays open. Pushes already pending still go out.</summary>
    public sealed record Hang : MockReply;

    /// <summary>Writes <paramref name="Before"/>, if any, then closes the connection.</summary>
    public sealed record Drop(RespValue? Before = null) : MockReply;
}

/// <summary>
/// An in-process cache on a loopback port. It serves any number of connections, plaintext
/// or TLS, RESP3 or RESP2 (it refuses <c>HELLO 3</c>), and checks commands the way the proxy
/// does. <see cref="Handle"/> is the command contract and does no I/O. A client that breaks the
/// handshake order or sends a frame that is not a command fails the server. A test waits on the
/// client (for example <see cref="ElastiCacheIntegrationsHandle.IsOpen"/>), not on this server, for a connection
/// to be usable. A failure other than a lost connection is rethrown by <see cref="DisposeAsync"/>.
/// </summary>
internal sealed class CacheServer : IAsyncDisposable
{
    /// <summary>A config that routes the ten operations the proxy advertises.</summary>
    public const string RouteTenOperations =
        """{"version":"1.0","refresh_interval":60,"predicates":[{"expr":"operation in ['GetItem','Query','Scan','PutItem','UpdateItem','DeleteItem','BatchGetItem','BatchWriteItem','TransactGetItems','TransactWriteItems']"}]}""";

    private static readonly ElastiCacheIntegrationsClientOptions Defaults = new() { Endpoint = "-", CacheName = "-" };

    private static readonly RespValue HelloReply = new RespMap([new(new RespBlobString("proto"u8.ToArray()), new RespInteger(3))]);

    private readonly TcpListener _listener = new(IPAddress.Loopback, 0);
    private readonly X509Certificate2? _certificate;
    private readonly CancellationTokenSource _stop = CancellationTokenSource.CreateLinkedTokenSource(TestContext.Current.CancellationToken);
    private readonly ConcurrentDictionary<Socket, byte> _sockets = new();
    private readonly ConcurrentBag<Task> _served = [];
    private readonly Task _loop;
    private int _connections;
    private long _requestIds;
    private Exception? _failure;
    private volatile string _configJson;
    private TaskCompletionSource _accepting = Completed();

    public CacheServer(string configJson = RouteTenOperations, X509Certificate2? certificate = null, bool resp3 = true)
    {
        _configJson = configJson;
        _certificate = certificate;
        Resp3 = resp3;
        _listener.Start();
        _loop = AcceptLoopAsync();
    }

    /// <summary>The reply to <c>aws.config</c> with overrides. A change applies to later handshakes.</summary>
    public string ConfigJson
    {
        get => _configJson;
        set => _configJson = value;
    }

    /// <summary>False: <c>HELLO 3</c> is refused and every reply is in band.</summary>
    public bool Resp3 { get; }

    public string Endpoint => $"127.0.0.1:{((IPEndPoint)_listener.LocalEndpoint).Port}";

    /// <summary>The reply to one valid <c>aws.request</c>, given the whole command.</summary>
    public Func<string[], MockReply> OnRequest { get; set; } = _ => MockCache.Served("{}");

    /// <summary>A fault for any command: a non-null reply replaces the normal one, and the command is only in <see cref="Commands"/>.</summary>
    public Func<string[], MockReply?> Intercept { get; set; } = _ => null;

    /// <summary>The reply to the no-argument <c>aws.config</c>: a config, or null for unchanged.</summary>
    public Func<string?> OnRefresh { get; set; } = () => null;

    /// <summary>Every command received, name first, in arrival order.</summary>
    public ConcurrentQueue<string[]> Commands { get; } = new();

    /// <summary>Every valid <c>aws.request</c>, name first.</summary>
    public ConcurrentQueue<string[]> Requests { get; } = new();

    /// <summary>The overrides of every accepted <c>aws.config</c>.</summary>
    public ConcurrentQueue<string> Overrides { get; } = new();

    /// <summary>Connections that got their first config.</summary>
    public int Connections => Volatile.Read(ref _connections);

    public ElastiCacheIntegrationsClientOptions Options(
        HttpMessageHandler? origin = null,
        TimeSpan? fallbackDuration = null,
        TimeSpan? requestTimeout = null,
        ILogger? logger = null,
        int? maxResponseSize = null,
        TimeSpan? configTimeout = null,
        IElastiCacheIntegrationsAuthProvider? auth = null,
        TimeSpan? headStallTimeout = null)
    {
        return new()
        {
            Endpoint = Endpoint,
            CacheName = "my-cache",
            UseTls = _certificate is not null,
            CaCertificates = _certificate is null ? null : [_certificate],
            AuthProvider = auth ?? new ConnectionTests.StaticAuth("user", "token"),
            OriginHandler = origin ?? new HttpHandlerTests.RecordingOrigin(),
            ReconnectWait = TimeSpan.FromMilliseconds(10),
            FallbackDuration = fallbackDuration ?? Timeout.InfiniteTimeSpan,
            RequestTimeout = requestTimeout ?? TimeSpan.FromSeconds(10),
            LoggerFactory = logger is null ? null : new SingleLoggerFactory(logger),
            MaxResponseSize = maxResponseSize ?? Defaults.MaxResponseSize,
            ConfigTimeout = configTimeout ?? Defaults.ConfigTimeout,
            HeadStallTimeout = headStallTimeout ?? Defaults.HeadStallTimeout,
            Reconnect = new ElastiCacheIntegrationsReconnectPolicy { BaseDelay = TimeSpan.FromMilliseconds(10), MaxDelay = TimeSpan.FromMilliseconds(50) },
        };
    }

    /// <summary>Leaves connections accepted from now on unanswered until <see cref="Release"/>.</summary>
    public void Hold()
    {
        var current = Volatile.Read(ref _accepting);
        if (current.Task.IsCompleted)
        {
            Interlocked.CompareExchange(ref _accepting, new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously), current);
        }
    }

    public void Release() => Volatile.Read(ref _accepting).TrySetResult();

    /// <summary>Closes every open connection; the client sees end of stream.</summary>
    public void Drop()
    {
        foreach (var socket in _sockets.Keys)
        {
            socket.Dispose();
        }
    }

    /// <summary>The command contract: records <paramref name="command"/> and picks the reply.</summary>
    public MockReply Handle(string[] command)
    {
        Commands.Enqueue(command);
        if (Intercept(command) is { } fault)
        {
            return fault;
        }

        return command[0].ToUpperInvariant() switch
        {
            "AUTH" => new RespSimpleString("OK"),
            "HELLO" => Resp3 ? HelloReply : new RespError("ERR", "unknown command 'HELLO'"),
            "AWS.CONFIG" => Config(command),
            "AWS.REQUEST" => Request(command),
            _ => new RespError("ERR", $"unknown command '{command[0]}'"),
        };
    }

    public async ValueTask DisposeAsync()
    {
        await _stop.CancelAsync();
        _listener.Stop();
        Drop();
        await _loop;

        // A serve task can add a push while the server stops.
        while (_served.Any(task => !task.IsCompleted))
        {
            await Task.WhenAll(_served.ToArray());
        }

        _listener.Dispose();
        _stop.Dispose();
        if (_failure is not null)
        {
            throw new InvalidOperationException("The cache server failed.", _failure);
        }
    }

    private MockReply Config(string[] command)
    {
        if (command.Length == 1)
        {
            return OnRefresh() is { } json ? Blob(json) : RespNull.Instance;
        }

        if (command.Length != 2 || !IsVersionedObject(command[1]))
        {
            return new RespError("CONFIG_INVALID", "overrides must be one JSON object with version 1.0");
        }

        Overrides.Enqueue(command[1]);
        return Blob(ConfigJson);
    }

    private MockReply Request(string[] command)
    {
        // method, url, headers, body, metadata; the metadata names the service and operation.
        if (command.Length != 6 || !HasServiceAndOperation(command[5]))
        {
            return new RespError("PARSE_ERROR", "failed to parse aws.request");
        }

        Requests.Enqueue(command);
        return OnRequest(command);
    }

    private static bool IsVersionedObject(string json)
    {
        try
        {
            using var document = JsonDocument.Parse(json);
            return document.RootElement.ValueKind == JsonValueKind.Object
                && document.RootElement.TryGetProperty("version", out var version)
                && version.ValueKind == JsonValueKind.String
                && version.GetString() == "1.0";
        }
        catch (JsonException)
        {
            return false;
        }
    }

    private static bool HasServiceAndOperation(string metadata)
    {
        var keys = metadata.Split('&').Select(pair => pair.Split('=', 2)).Where(kv => kv is [_, { Length: > 0 }]).Select(kv => kv[0]).ToHashSet();
        return keys.Contains("service") && keys.Contains("op");
    }

    private static RespBlobString Blob(string text) => new(Encoding.UTF8.GetBytes(text));

    private static TaskCompletionSource Completed()
    {
        var done = new TaskCompletionSource();
        done.SetResult();
        return done;
    }

    private async Task AcceptLoopAsync()
    {
        while (true)
        {
            Socket socket;
            try
            {
                socket = await _listener.AcceptSocketAsync(_stop.Token);
            }
            catch (Exception e) when (e is OperationCanceledException or ObjectDisposedException || _stop.IsCancellationRequested)
            {
                return;
            }
            catch (SocketException)
            {
                // A client that reset before the accept finished.
                continue;
            }

            var gate = Volatile.Read(ref _accepting).Task;
            _sockets[socket] = 0;
            _served.Add(Task.Run(() => ServeAsync(socket, gate)));
        }
    }

    private async Task ServeAsync(Socket socket, Task gate)
    {
        var connection = new Connection(new NetworkStream(socket, ownsSocket: true));
        var handshake = new Handshake();
        try
        {
            await gate.WaitAsync(_stop.Token);
            if (_certificate is not null)
            {
                await connection.StartTlsAsync(_certificate, _stop.Token);
            }

            var reader = new ReplyReader(connection.Stream, int.MaxValue);
            var hung = false;
            while (true)
            {
                var command = ToCommand(await reader.ReadAsync(_stop.Token));
                handshake.Check(command);
                if (hung)
                {
                    Commands.Enqueue(command);
                    continue;
                }

                var reply = Handle(command);
                hung = reply is MockReply.Hang;
                if (handshake.Observe(command, reply))
                {
                    Interlocked.Increment(ref _connections);
                }

                if (!await ReplyAsync(connection, reply))
                {
                    return;
                }
            }
        }
        catch (Exception e) when (e is ElastiCacheIntegrationsConnectionLostException or IOException or SocketException or ObjectDisposedException
            or OperationCanceledException or AuthenticationException)
        {
            // The connection is gone.
        }
        catch (Exception e)
        {
            Interlocked.CompareExchange(ref _failure, e, null);
        }
        finally
        {
            _sockets.TryRemove(socket, out _);
            await connection.Stream.DisposeAsync();
        }
    }

    /// <summary>Sends <paramref name="reply"/>; false means close the connection.</summary>
    private async Task<bool> ReplyAsync(Connection connection, MockReply reply)
    {
        switch (reply)
        {
            case MockReply.Now now:
                await connection.WriteAsync(Encode(now.Value), _stop.Token);
                return true;
            case MockReply.Deferred deferred when Resp3:
                var id = Interlocked.Increment(ref _requestIds).ToString(CultureInfo.InvariantCulture);
                await connection.WriteAsync(Encode(new RespSimpleString(id)), _stop.Token);
                _served.Add(PushAsync(connection, id, deferred.Reply));
                return true;
            case MockReply.Hang:
                return true;
            case MockReply.Drop drop:
                if (drop.Before is { } before)
                {
                    await connection.WriteAsync(Encode(before), _stop.Token);
                }

                return false;
            default:
                throw new InvalidOperationException($"Cannot send {reply} in {(Resp3 ? "RESP3" : "RESP2")}.");
        }
    }

    private async Task PushAsync(Connection connection, string id, Task<RespValue> reply)
    {
        RespValue value;
        try
        {
            value = await reply.WaitAsync(_stop.Token);
        }
        catch (OperationCanceledException) when (_stop.IsCancellationRequested)
        {
            return;
        }
        catch (Exception e)
        {
            Interlocked.CompareExchange(ref _failure, e, null);
            return;
        }

        try
        {
            await connection.WriteAsync(Encode(MockCache.Push(id, value)), _stop.Token);
        }
        catch (Exception e) when (e is IOException or SocketException or ObjectDisposedException or OperationCanceledException)
        {
            // The connection or the server is gone.
        }
        catch (Exception e)
        {
            Interlocked.CompareExchange(ref _failure, e, null);
        }
    }

    private byte[] Encode(RespValue value)
    {
        if (!Resp3 && value is RespNull)
        {
            return "$-1\r\n"u8.ToArray();
        }

        var buffer = new ArrayBufferWriter<byte>();
        RespEncoder.WriteValue(buffer, value);
        return buffer.WrittenSpan.ToArray();
    }

    /// <summary>A client command is an array of blob strings; anything else is a client bug.</summary>
    private static string[] ToCommand(RespValue value) =>
        value is RespArray { Items.Count: > 0 } array && array.Items.All(item => item is RespBlobString)
            ? [.. array.Items.Select(item => Encoding.UTF8.GetString(((RespBlobString)item).Value.Span))]
            : throw new InvalidDataException($"The client sent {value}, not a command.");

    /// <summary>The order the client must follow: <c>AUTH</c>, <c>HELLO 3</c>, then <c>aws.config</c> with overrides before anything else.</summary>
    private sealed class Handshake
    {
        private bool _authenticated;
        private bool _configured;

        public void Check(string[] command)
        {
            var inOrder = command[0].ToUpperInvariant() switch
            {
                "AUTH" => true,
                "HELLO" => _authenticated && command is [_, "3"],
                "AWS.CONFIG" => _authenticated && (command.Length > 1 || _configured),
                _ => _configured,
            };
            if (!inOrder)
            {
                throw new InvalidDataException($"The client sent {command[0]} out of order.");
            }
        }

        /// <summary>Tracks the replies the client saw. True when the connection gets its first config.</summary>
        public bool Observe(string[] command, MockReply reply)
        {
            var value = (reply as MockReply.Now)?.Value;
            switch (command[0].ToUpperInvariant())
            {
                case "AUTH" when value is RespSimpleString { Value: "OK" }:
                    _authenticated = true;
                    return false;
                case "AWS.CONFIG" when command.Length == 2 && value is RespBlobString && !_configured:
                    _configured = true;
                    return true;
                default:
                    return false;
            }
        }
    }

    /// <summary>One accepted connection. Writes are serialized, so replies never interleave.</summary>
    private sealed class Connection(Stream stream)
    {
        private readonly SemaphoreSlim _write = new(1, 1);

        public Stream Stream { get; private set; } = stream;

        public async Task StartTlsAsync(X509Certificate2 certificate, CancellationToken cancellationToken)
        {
            var tls = new SslStream(Stream);
            Stream = tls;
            await tls.AuthenticateAsServerAsync(new SslServerAuthenticationOptions { ServerCertificate = certificate }, cancellationToken);
        }

        public async Task WriteAsync(byte[] bytes, CancellationToken cancellationToken)
        {
            await _write.WaitAsync(cancellationToken);
            try
            {
                await Stream.WriteAsync(bytes, cancellationToken);
                await Stream.FlushAsync(cancellationToken);
            }
            finally
            {
                _write.Release();
            }
        }
    }
}

/// <summary>Hands every category the same logger.</summary>
internal sealed class SingleLoggerFactory(ILogger logger) : ILoggerFactory
{
    public ILogger CreateLogger(string categoryName) => logger;

    public void AddProvider(ILoggerProvider provider)
    {
    }

    public void Dispose()
    {
    }
}
