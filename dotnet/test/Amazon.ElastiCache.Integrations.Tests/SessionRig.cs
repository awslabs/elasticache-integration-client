using System.Collections.Concurrent;
using System.Text;
using System.Text.Json;
using System.Threading.Channels;
using Microsoft.Extensions.Time.Testing;

namespace Amazon.ElastiCache.Integrations.Tests;

/// <summary>A connection the test drives: it records what the session calls and drops on demand.</summary>
internal sealed class FakeConnection(ResolvedConfig? config) : ICacheConnection
{
    public static readonly ElastiCacheIntegrationsResponse Ok = new(200, new Dictionary<string, string>(), "{}"u8.ToArray());

    private readonly TaskCompletionSource<Exception> _closed = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private int _requests;
    private int _reauths;
    private int _refreshes;

    private volatile bool _dead;

    public bool IsOpen => !_dead && !_closed.Task.IsCompleted;

    public Task<Exception> Closed => _closed.Task;

    public ResolvedConfig? Config { get; } = config;

    public bool Disposed { get; private set; }

    public int Requests => Volatile.Read(ref _requests);

    public int Reauths => Volatile.Read(ref _reauths);

    public int Refreshes => Volatile.Read(ref _refreshes);

    public Func<ElastiCacheIntegrationsRequest, CancellationToken, Task<RequestReply>> OnRequest { get; set; } =
        (_, _) => Task.FromResult<RequestReply>(new RequestReply.Served(Ok));

    public Func<CancellationToken, Task> OnReauth { get; set; } = _ => Task.CompletedTask;

    public Func<CancellationToken, Task<ResolvedConfig?>> OnRefresh { get; set; } = _ => Task.FromResult<ResolvedConfig?>(null);

    public Task<RequestReply> RequestAsync(ElastiCacheIntegrationsRequest request, CancellationToken cancellationToken)
    {
        Interlocked.Increment(ref _requests);
        return IsOpen ? OnRequest(request, cancellationToken) : Task.FromException<RequestReply>(new ElastiCacheIntegrationsConnectionLostException("dead"));
    }

    public Task ReauthenticateAsync(CancellationToken cancellationToken)
    {
        Interlocked.Increment(ref _reauths);
        return OnReauth(cancellationToken);
    }

    public Task<ResolvedConfig?> RefreshConfigAsync(CancellationToken cancellationToken)
    {
        Interlocked.Increment(ref _refreshes);
        return OnRefresh(cancellationToken);
    }

    public void Drop() => _closed.TrySetResult(new ElastiCacheIntegrationsConnectionLostException("dropped"));

    /// <summary>Not open, but <see cref="Closed"/> has not completed yet.</summary>
    public void MarkDead() => _dead = true;

    public void Dispose()
    {
        Disposed = true;
        Drop();
    }

    public ValueTask DisposeAsync()
    {
        Dispose();
        return ValueTask.CompletedTask;
    }
}

/// <summary>Bring-up attempts the test answers one by one.</summary>
internal sealed class FakeConnector
{
    private readonly Channel<Attempt> _attempts = Channel.CreateUnbounded<Attempt>();

    private int _count;

    public int Count => Volatile.Read(ref _count);

    /// <summary>False makes attempts ignore cancellation, as a factory that finishes a handshake it started would.</summary>
    public bool HonorsCancellation { get; init; } = true;

    public Task<ICacheConnection> ConnectAsync(CancellationToken cancellationToken)
    {
        Interlocked.Increment(ref _count);
        var attempt = new Attempt();
        _attempts.Writer.TryWrite(attempt);
        return HonorsCancellation ? attempt.Result.Task.WaitAsync(cancellationToken) : attempt.Result.Task;
    }

    /// <summary>The next attempt. Advances <paramref name="time"/> in steps, so a backoff delay armed later still fires.</summary>
    public async Task<Attempt> NextAsync(FakeTimeProvider? time = null, TimeSpan? step = null)
    {
        for (var i = 0; i < 500; i++)
        {
            if (_attempts.Reader.TryRead(out var attempt))
            {
                return attempt;
            }

            time?.Advance(step ?? TimeSpan.FromMilliseconds(100));
            await Task.Delay(10, TestContext.Current.CancellationToken);
        }

        throw new TimeoutException("no bring-up attempt was made");
    }

    internal sealed class Attempt
    {
        public TaskCompletionSource<ICacheConnection> Result { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);

        /// <summary>Lands a connection; <paramref name="configure"/> runs before the session can see it.</summary>
        public FakeConnection Succeed(ResolvedConfig? config = null, Action<FakeConnection>? configure = null)
        {
            var connection = new FakeConnection(config ?? SessionRig.Config());
            configure?.Invoke(connection);
            Result.SetResult(connection);
            return connection;
        }

        public void Fail(Exception error) => Result.SetException(error);
    }
}

/// <summary>A fake clock that records the due time of every timer created on it.</summary>
internal sealed class RecordingTimeProvider : FakeTimeProvider
{
    private readonly ConcurrentQueue<TimeSpan> _dueTimes = new();

    /// <summary>Timers created so far whose due time matches <paramref name="match"/>.</summary>
    public int Armed(Func<TimeSpan, bool> match) => _dueTimes.Count(match);

    public override ITimer CreateTimer(TimerCallback callback, object? state, TimeSpan dueTime, TimeSpan period)
    {
        var timer = base.CreateTimer(callback, state, dueTime, period);
        _dueTimes.Enqueue(dueTime);
        return timer;
    }
}

internal sealed class SessionRig : IAsyncDisposable
{
    public const string RouteAll = """[{"expr":"true"}]""";

    private SessionRig(ElastiCacheIntegrationsSession session, FakeConnector connector, FakeTimeProvider time, ListLogger logger, FakeConnection connection)
    {
        Session = session;
        Connector = connector;
        Time = time;
        Logger = logger;
        Connection = connection;
    }

    public ElastiCacheIntegrationsSession Session { get; }

    public FakeConnector Connector { get; }

    public FakeTimeProvider Time { get; }

    public ListLogger Logger { get; }

    /// <summary>The connection from bring-up.</summary>
    public FakeConnection Connection { get; }

    public static ElastiCacheIntegrationsClientOptions Options(TimeSpan? fallback = null, TimeSpan? reconnectWait = null, TimeSpan? reauth = null, TimeSpan? cachePolicyTimeout = null, ElastiCacheIntegrationsReconnectPolicy? reconnect = null) => new()
    {
        Endpoint = "localhost:6379",
        CacheName = "my-cache",
        FallbackDuration = fallback ?? Timeout.InfiniteTimeSpan,
        ReconnectWait = reconnectWait ?? TimeSpan.FromMilliseconds(100),
        ReauthInterval = reauth ?? TimeSpan.FromMinutes(30),
        CachePolicyTimeout = cachePolicyTimeout ?? TimeSpan.FromHours(1),
        Reconnect = reconnect ?? new ElastiCacheIntegrationsReconnectPolicy { Jitter = 0 },
    };

    /// <summary>A config; the default interval is one day, so a test sees a refresh only when it asks for one.</summary>
    public static ResolvedConfig Config(string predicates = RouteAll, int refreshSeconds = 86400) =>
        new("1.0", TimeSpan.FromSeconds(refreshSeconds), JsonDocument.Parse($$"""{"version":"1.0","refresh_interval":{{refreshSeconds}},"predicates":{{predicates}}}""").RootElement);

    public static Task<ElastiCacheIntegrationsSession> OpenAsync(ElastiCacheIntegrationsClientOptions options, FakeConnector connector, FakeTimeProvider time, ListLogger logger) =>
        ElastiCacheIntegrationsSession.OpenAsync(options, connector.ConnectAsync, "DynamoDB", logger, time, new Random(1), TestContext.Current.CancellationToken);

    public static async Task<SessionRig> StartAsync(ElastiCacheIntegrationsClientOptions? options = null, ResolvedConfig? config = null, FakeTimeProvider? time = null)
    {
        var connector = new FakeConnector();
        time ??= new FakeTimeProvider();
        var logger = new ListLogger();
        var open = OpenAsync(options ?? Options(), connector, time, logger);
        var connection = (await connector.NextAsync()).Succeed(config);
        return new SessionRig(await open, connector, time, logger, connection);
    }

    public static ElastiCacheIntegrationsRequest Request(string operation = "GetItem") => new(
        "POST",
        "https://dynamodb.us-east-1.amazonaws.com/",
        new Dictionary<string, string> { ["x-amz-target"] = $"DynamoDB_20120810.{operation}" },
        Encoding.UTF8.GetBytes("""{"TableName":"t"}"""),
        new RequestMetadata("dynamodb", operation));

    public Task<ElastiCacheIntegrationsOutcome> SendAsync(string operation = "GetItem") =>
        Session.RequestAsync(Request(operation), TestContext.Current.CancellationToken);

    /// <summary>Waits until <paramref name="condition"/> holds, advancing the clock by <paramref name="step"/> between checks.</summary>
    public async Task EventuallyAsync(Func<bool> condition, TimeSpan? step = null)
    {
        for (var i = 0; i < 500 && !condition(); i++)
        {
            if (step is { } s)
            {
                Time.Advance(s);
            }

            await Task.Delay(10, TestContext.Current.CancellationToken);
        }

        Assert.True(condition(), "the condition never held");
    }

    public async ValueTask DisposeAsync() => await Session.DisposeAsync();
}
