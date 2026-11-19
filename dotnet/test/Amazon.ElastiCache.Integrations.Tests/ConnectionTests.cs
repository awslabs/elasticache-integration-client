// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using System.Text;
using Microsoft.Extensions.Time.Testing;

namespace Amazon.ElastiCache.Integrations.Tests;

public class ConnectionTests
{
    private static readonly TimeSpan RequestTimeout = TimeSpan.FromMilliseconds(100);
    private static readonly TimeSpan ConfigTimeout = TimeSpan.FromSeconds(2);
    private static readonly TimeSpan HeadStall = TimeSpan.FromSeconds(10);

    private static CancellationToken Token => TestContext.Current.CancellationToken;

    private static ElastiCacheIntegrationsRequest Request(string body) => new(
        "POST",
        "https://dynamodb.us-east-1.amazonaws.com/",
        new Dictionary<string, string> { ["x-amz-target"] = "DynamoDB_20120810.GetItem" },
        Encoding.UTF8.GetBytes(body),
        new RequestMetadata("dynamodb", "GetItem"));

    private static string BodyOf(RequestReply reply) =>
        Encoding.UTF8.GetString(Assert.IsType<RequestReply.Served>(reply).Response.Body.Span);

    private static async Task<string> ReadBodyAsync(MockCache cache)
    {
        var command = await cache.ReadCommandAsync();
        Assert.Equal("aws.request", command[0]);
        return command[4];
    }

    private static async Task ClosedAsync(ElastiCacheIntegrationsConnection connection) =>
        await connection.Closed.WaitAsync(TimeSpan.FromSeconds(5), Token);

    [Fact]
    public async Task PipelinedRepliesResolveInWireOrder()
    {
        await using var rig = await Rig.StartAsync(resp3: false);
        var requests = Enumerable.Range(0, 3).Select(i => rig.Connection.RequestAsync(Request($"r{i}"), Token)).ToArray();

        for (var i = 0; i < 3; i++)
        {
            await rig.Cache.SendAsync(MockCache.Served(await ReadBodyAsync(rig.Cache)));
        }

        Assert.Equal(["r0", "r1", "r2"], (await Task.WhenAll(requests)).Select(BodyOf));
    }

    [Fact]
    public async Task DeferredRepliesResolveByIdOutOfOrder()
    {
        await using var rig = await Rig.StartAsync();
        var a = rig.Connection.RequestAsync(Request("a"), Token);
        var b = rig.Connection.RequestAsync(Request("b"), Token);
        var bodies = new[] { await ReadBodyAsync(rig.Cache), await ReadBodyAsync(rig.Cache) };

        await rig.Cache.SendAsync($"+id-{bodies[0]}\r\n+id-{bodies[1]}\r\n");
        await rig.Cache.SendAsync(MockCache.Push("id-b", MockCache.Served("b")));
        Assert.Equal("b", BodyOf(await b));
        await rig.Cache.SendAsync(MockCache.Push("id-a", MockCache.Served("a")));

        Assert.Equal("a", BodyOf(await a));
        Assert.Equal(0, rig.Connection.PendingCount);
    }

    [Fact]
    public async Task AReplySentOneByteAtATimeIsReadWhole()
    {
        await using var rig = await Rig.StartAsync();
        var request = rig.Connection.RequestAsync(Request("slow"), Token);
        await ReadBodyAsync(rig.Cache);

        foreach (var b in "*3\r\n:200\r\n$0\r\n\r\n$4\r\nslow\r\n"u8.ToArray())
        {
            await rig.Cache.SendAsync([b]);
        }

        Assert.Equal("slow", BodyOf(await request));
    }

    [Fact]
    public async Task AnOversizedReplyKillsTheConnection()
    {
        await using var rig = await Rig.StartAsync(maxResponseSize: 64);
        var request = rig.Connection.RequestAsync(Request("big"), Token);
        await ReadBodyAsync(rig.Cache);

        await rig.Cache.SendAsync(MockCache.Served(new string('x', 100)));

        await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionLostException>(() => request);
        var cause = await rig.Connection.Closed;
        Assert.IsType<ElastiCacheIntegrationsProtocolException>(cause.InnerException);
        Assert.False(rig.Connection.IsOpen);
    }

    [Fact]
    public async Task ATimedOutRequestAbandonsOnlyItsSlot()
    {
        await using var rig = await Rig.StartAsync(resp3: false);
        var late = rig.Connection.RequestAsync(Request("late"), Token);
        await ReadBodyAsync(rig.Cache);

        rig.Time.Advance(RequestTimeout);
        await Assert.ThrowsAsync<ElastiCacheIntegrationsTimeoutException>(() => late);
        var next = rig.Connection.RequestAsync(Request("next"), Token);
        await ReadBodyAsync(rig.Cache);
        await rig.Cache.SendAsync(MockCache.Served("late"));
        await rig.Cache.SendAsync(MockCache.Served("next"));

        Assert.Equal("next", BodyOf(await next));
        Assert.True(rig.Connection.IsOpen);

        rig.Time.Advance(HeadStall);
        Assert.True(rig.Connection.IsOpen);
    }

    [Fact]
    public async Task ARequestThatWaitedForTheWriteLockStillGetsTheFullTimeout()
    {
        GatedStream? gated = null;
        await using var rig = await Rig.StartAsync(resp3: false, wrap: s => gated = new GatedStream(s));
        var first = rig.Connection.RequestAsync(Request("first"), Token);
        await gated!.Blocked.WaitAsync(Token);
        var second = rig.Connection.RequestAsync(Request("second"), Token);

        rig.Time.Advance(RequestTimeout * 0.9);
        gated.Open();
        Assert.Equal(["first", "second"], [await ReadBodyAsync(rig.Cache), await ReadBodyAsync(rig.Cache)]);
        await rig.Cache.SendAsync(MockCache.Served("first"));
        Assert.Equal("first", BodyOf(await first));
        rig.Time.Advance(RequestTimeout * 0.5);
        await rig.Cache.SendAsync(MockCache.Served("second"));

        Assert.Equal("second", BodyOf(await second));
    }

    [Fact]
    public async Task ADefaultRequestWaitsOutTheProxysDynamoDBCall()
    {
        var timeout = new ElastiCacheIntegrationsClientOptions { Endpoint = "-", CacheName = "-" }.RequestTimeout;
        await using var rig = await Rig.StartAsync(resp3: false, requestTimeout: timeout);
        var slow = rig.Connection.RequestAsync(Request("slow"), Token);
        await ReadBodyAsync(rig.Cache);

        rig.Time.Advance(TimeSpan.FromSeconds(5) - TimeSpan.FromMilliseconds(1));
        await rig.Cache.SendAsync(MockCache.Served("slow"));
        Assert.Equal("slow", BodyOf(await slow));

        var unanswered = rig.Connection.RequestAsync(Request("unanswered"), Token);
        await ReadBodyAsync(rig.Cache);
        rig.Time.Advance(timeout);
        await Assert.ThrowsAsync<ElastiCacheIntegrationsTimeoutException>(() => unanswered);
    }

    [Fact]
    public void TheHeadStallLimitDefaultsTo10Seconds()
    {
        Assert.Equal(HeadStall, new ElastiCacheIntegrationsClientOptions { Endpoint = "-", CacheName = "-" }.HeadStallTimeout);
    }

    [Fact]
    public async Task AnUnansweredHeadKillsTheConnectionAfterTheStallLimit()
    {
        await using var rig = await Rig.StartAsync(resp3: false);
        var stuck = rig.Connection.RequestAsync(Request("stuck"), Token);
        await ReadBodyAsync(rig.Cache);
        rig.Time.Advance(RequestTimeout);
        await Assert.ThrowsAsync<ElastiCacheIntegrationsTimeoutException>(() => stuck);
        var behind = rig.Connection.RequestAsync(Request("behind"), Token);
        await ReadBodyAsync(rig.Cache);

        rig.Time.Advance(HeadStall - RequestTimeout - TimeSpan.FromMilliseconds(1));
        Assert.True(rig.Connection.IsOpen);
        rig.Time.Advance(TimeSpan.FromMilliseconds(1));

        await ClosedAsync(rig.Connection);
        Assert.Equal("No reply from the cache within 10000 ms.", (await rig.Connection.Closed).Message);
        await Assert.ThrowsAsync<ElastiCacheIntegrationsTimeoutException>(() => behind);
        Assert.Contains(ElastiCacheIntegrationsEvents.HeadStalled, rig.Logger.Ids);
    }

    [Fact]
    public async Task ASlowInBandReplyUnderTheStallLimitKeepsTheConnection()
    {
        await using var rig = await Rig.StartAsync(resp3: false);
        var slow = rig.Connection.RequestAsync(Request("slow"), Token);
        await ReadBodyAsync(rig.Cache);
        rig.Time.Advance(RequestTimeout);
        await Assert.ThrowsAsync<ElastiCacheIntegrationsTimeoutException>(() => slow);

        // Past ConfigTimeout, just under the stall limit.
        rig.Time.Advance(HeadStall - RequestTimeout - TimeSpan.FromMilliseconds(1));
        var behind = rig.Connection.RequestAsync(Request("behind"), Token);
        await ReadBodyAsync(rig.Cache);
        await rig.Cache.SendAsync(MockCache.Served("slow"));
        await rig.Cache.SendAsync(MockCache.Served("behind"));

        Assert.Equal("behind", BodyOf(await behind));
        rig.Time.Advance(HeadStall);
        Assert.True(rig.Connection.IsOpen);
        Assert.DoesNotContain(ElastiCacheIntegrationsEvents.HeadStalled, rig.Logger.Ids);
    }

    [Fact]
    public async Task AReauthThatTimesOutKeepsTheConnectionUntilTheStallLimit()
    {
        await using var rig = await Rig.StartAsync(resp3: false);
        var reauth = rig.Connection.ReauthenticateAsync(Token);
        Assert.Equal("AUTH", (await rig.Cache.ReadCommandAsync())[0]);
        rig.Time.Advance(ConfigTimeout);
        await Assert.ThrowsAsync<ElastiCacheIntegrationsTimeoutException>(() => reauth);
        Assert.True(rig.Connection.IsOpen);

        var next = rig.Connection.RequestAsync(Request("next"), Token);
        await ReadBodyAsync(rig.Cache);
        await rig.Cache.SendAsync("+OK\r\n");
        await rig.Cache.SendAsync(MockCache.Served("next"));

        Assert.Equal("next", BodyOf(await next));
        rig.Time.Advance(HeadStall);
        Assert.True(rig.Connection.IsOpen);
    }

    [Fact]
    public async Task ADeferredRequestThatTimesOutIsDroppedAndItsLatePushIgnored()
    {
        await using var rig = await Rig.StartAsync();
        var deferred = rig.Connection.RequestAsync(Request("a"), Token);
        var inBand = rig.Connection.RequestAsync(Request("b"), Token);
        await ReadBodyAsync(rig.Cache);
        await ReadBodyAsync(rig.Cache);
        await rig.Cache.SendAsync("+id-a\r\n");
        await rig.Cache.SendAsync(MockCache.Served("b"));
        Assert.Equal("b", BodyOf(await inBand));

        rig.Time.Advance(RequestTimeout);
        await Assert.ThrowsAsync<ElastiCacheIntegrationsTimeoutException>(() => deferred);
        Assert.Equal(0, rig.Connection.PendingCount);
        await rig.Cache.SendAsync(MockCache.Push("id-a", MockCache.Served("a")));
        var next = rig.Connection.RequestAsync(Request("c"), Token);
        await ReadBodyAsync(rig.Cache);
        await rig.Cache.SendAsync(MockCache.Served("c"));

        Assert.Equal("c", BodyOf(await next));
        Assert.Contains(ElastiCacheIntegrationsEvents.LatePushDropped, rig.Logger.Ids);
        rig.Time.Advance(HeadStall);
        Assert.True(rig.Connection.IsOpen);
    }

    [Fact]
    public async Task CallerCancellationAbandonsTheSlot()
    {
        await using var rig = await Rig.StartAsync(resp3: false);
        using var cancel = CancellationTokenSource.CreateLinkedTokenSource(Token);
        var cancelled = rig.Connection.RequestAsync(Request("a"), cancel.Token);
        await ReadBodyAsync(rig.Cache);

        await cancel.CancelAsync();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => cancelled);
        var next = rig.Connection.RequestAsync(Request("b"), Token);
        await ReadBodyAsync(rig.Cache);
        await rig.Cache.SendAsync(MockCache.Served("a"));
        await rig.Cache.SendAsync(MockCache.Served("b"));

        Assert.Equal("b", BodyOf(await next));
    }

    [Fact]
    public async Task AServerDropFailsPendingAndLaterRequests()
    {
        await using var rig = await Rig.StartAsync();
        var pending = rig.Connection.RequestAsync(Request("a"), Token);
        await ReadBodyAsync(rig.Cache);

        rig.Cache.Drop();

        await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionLostException>(() => pending);
        await ClosedAsync(rig.Connection);
        await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionLostException>(() => rig.Connection.RequestAsync(Request("b"), Token));
        Assert.Contains(ElastiCacheIntegrationsEvents.ConnectionLost, rig.Logger.Ids);
    }

    [Theory]
    [InlineData("?\r\n")]
    [InlineData(":1\r\n")]
    [InlineData(">2\r\n$10\r\ninvalidate\r\n$1\r\nk\r\n")]
    public async Task ACorruptStreamAStrayReplyOrAnUnknownPushKillsTheConnection(string wire)
    {
        await using var rig = await Rig.StartAsync();

        await rig.Cache.SendAsync(wire);

        var cause = await rig.Connection.Closed.WaitAsync(TimeSpan.FromSeconds(5), Token);
        Assert.IsType<ElastiCacheIntegrationsProtocolException>(cause.InnerException);
    }

    [Theory]
    [InlineData(true, "+id\r\n>3\r\n$12\r\naws.response\r\n$2\r\nid\r\n:5\r\n")]
    [InlineData(false, "+id\r\n")]
    public async Task AReplyOfTheWrongShapeFailsOnlyThatRequest(bool resp3, string wire)
    {
        await using var rig = await Rig.StartAsync(resp3);
        var request = rig.Connection.RequestAsync(Request("a"), Token);
        await ReadBodyAsync(rig.Cache);

        await rig.Cache.SendAsync(wire);

        await Assert.ThrowsAsync<ElastiCacheIntegrationsProtocolException>(() => request);
        var next = rig.Connection.RequestAsync(Request("b"), Token);
        await ReadBodyAsync(rig.Cache);
        await rig.Cache.SendAsync(MockCache.Served("b"));
        Assert.Equal("b", BodyOf(await next));
    }

    [Fact]
    public async Task MaxPendingRefusesWithoutWriting()
    {
        await using var rig = await Rig.StartAsync(maxPending: 1);
        var first = rig.Connection.RequestAsync(Request("a"), Token);
        await ReadBodyAsync(rig.Cache);

        await Assert.ThrowsAsync<ElastiCacheIntegrationsPendingLimitException>(() => rig.Connection.RequestAsync(Request("refused"), Token));
        await rig.Cache.SendAsync(MockCache.Served("a"));
        await first;
        var next = rig.Connection.RequestAsync(Request("b"), Token);

        Assert.Equal("b", await ReadBodyAsync(rig.Cache));
        await rig.Cache.SendAsync(MockCache.Served("b"));
        await next;
    }

    [Theory]
    [InlineData("-MOVED 3999 127.0.0.1:6381\r\n", "MOVED")]
    [InlineData("-NOAUTH Authentication required.\r\n", "NOAUTH")]
    public async Task AConnectionDropCodeIsReturnedAndKillsTheConnection(string wire, string code)
    {
        await using var rig = await Rig.StartAsync();
        var request = rig.Connection.RequestAsync(Request("a"), Token);
        await ReadBodyAsync(rig.Cache);

        await rig.Cache.SendAsync(wire);

        Assert.Equal(code, Assert.IsType<RequestReply.Rejected>(await request).Error.Code);
        await ClosedAsync(rig.Connection);
    }

    [Fact]
    public async Task MovedAfterTheCacheAcceptedARequestFailsItWithTheConnection()
    {
        await using var rig = await Rig.StartAsync();
        var request = rig.Connection.RequestAsync(Request("a"), Token);
        await ReadBodyAsync(rig.Cache);
        await rig.Cache.SendAsync("+id-a\r\n");

        await rig.Cache.SendAsync(MockCache.Push("id-a", new RespError("MOVED", "3999 127.0.0.1:6381")));

        var error = await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionLostException>(() => request);
        Assert.Equal("MOVED", Assert.IsType<ElastiCacheIntegrationsServerException>(error.InnerException).Code);
        await ClosedAsync(rig.Connection);
    }

    [Fact]
    public async Task NoAuthAfterTheCacheAcceptedARequestIsItsReplyAndKillsTheConnection()
    {
        await using var rig = await Rig.StartAsync();
        var request = rig.Connection.RequestAsync(Request("a"), Token);
        await ReadBodyAsync(rig.Cache);
        await rig.Cache.SendAsync("+id-a\r\n");

        await rig.Cache.SendAsync(MockCache.Push("id-a", new RespError("NOAUTH", "Authentication required.")));

        Assert.Equal("NOAUTH", Assert.IsType<RequestReply.Rejected>(await request).Error.Code);
        await ClosedAsync(rig.Connection);
    }

    [Fact]
    public async Task MovedForARequestThatTimedOutStillKillsTheConnection()
    {
        await using var rig = await Rig.StartAsync(resp3: false);
        var request = rig.Connection.RequestAsync(Request("a"), Token);
        await ReadBodyAsync(rig.Cache);
        rig.Time.Advance(RequestTimeout);
        await Assert.ThrowsAsync<ElastiCacheIntegrationsTimeoutException>(() => request);

        await rig.Cache.SendAsync("-MOVED 3999 127.0.0.1:6381\r\n");

        var cause = await rig.Connection.Closed.WaitAsync(TimeSpan.FromSeconds(5), Token);
        Assert.Equal("MOVED", Assert.IsType<ElastiCacheIntegrationsServerException>(cause.InnerException).Code);
    }

    [Fact]
    public async Task MovedInAPushForARequestThatTimedOutStillKillsTheConnection()
    {
        await using var rig = await Rig.StartAsync();
        var deferred = rig.Connection.RequestAsync(Request("a"), Token);
        var inBand = rig.Connection.RequestAsync(Request("b"), Token);
        await ReadBodyAsync(rig.Cache);
        await ReadBodyAsync(rig.Cache);
        await rig.Cache.SendAsync("+id-a\r\n");
        await rig.Cache.SendAsync(MockCache.Served("b"));
        await inBand;
        rig.Time.Advance(RequestTimeout);
        await Assert.ThrowsAsync<ElastiCacheIntegrationsTimeoutException>(() => deferred);

        await rig.Cache.SendAsync(MockCache.Push("id-a", new RespError("MOVED", "3999 127.0.0.1:6381")));

        var cause = await rig.Connection.Closed.WaitAsync(TimeSpan.FromSeconds(5), Token);
        Assert.Equal("MOVED", Assert.IsType<ElastiCacheIntegrationsServerException>(cause.InnerException).Code);
    }

    [Fact]
    public async Task ADropWhileDisposeAsyncDrainsIsLogged()
    {
        await using var rig = await Rig.StartAsync();
        var inFlight = rig.Connection.RequestAsync(Request("a"), Token);
        await ReadBodyAsync(rig.Cache);
        var dispose = rig.Connection.DisposeAsync().AsTask();

        rig.Cache.Drop();

        await dispose.WaitAsync(TimeSpan.FromSeconds(5), Token);
        await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionLostException>(() => inFlight);
        Assert.Contains(ElastiCacheIntegrationsEvents.ConnectionLost, rig.Logger.Ids);
    }

    [Fact]
    public async Task AnOtherErrorCodeKeepsTheConnection()
    {
        await using var rig = await Rig.StartAsync();
        var request = rig.Connection.RequestAsync(Request("a"), Token);
        await ReadBodyAsync(rig.Cache);

        await rig.Cache.SendAsync("-UNSUPPORTED Scan is not cached\r\n");

        Assert.Equal("UNSUPPORTED", Assert.IsType<RequestReply.Rejected>(await request).Error.Code);
        Assert.True(rig.Connection.IsOpen);
    }

    [Fact]
    public async Task DisposeAsyncWaitsForRequestsInFlight()
    {
        await using var rig = await Rig.StartAsync(resp3: false);
        var inFlight = rig.Connection.RequestAsync(Request("a"), Token);
        await ReadBodyAsync(rig.Cache);

        var dispose = rig.Connection.DisposeAsync().AsTask();
        Assert.False(rig.Connection.IsOpen);
        await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionLostException>(() => rig.Connection.RequestAsync(Request("b"), Token));
        Assert.False(dispose.IsCompleted);
        await rig.Cache.SendAsync(MockCache.Served("a"));

        Assert.Equal("a", BodyOf(await inFlight));
        await dispose.WaitAsync(TimeSpan.FromSeconds(5), Token);
        await ClosedAsync(rig.Connection);
    }

    [Fact]
    public async Task DisposeAsyncStopsWaitingAfterConfigTimeout()
    {
        await using var rig = await Rig.StartAsync(requestTimeout: TimeSpan.FromMinutes(1));
        var unanswered = rig.Connection.RequestAsync(Request("a"), Token);
        await ReadBodyAsync(rig.Cache);

        var dispose = rig.Connection.DisposeAsync().AsTask();
        rig.Time.Advance(ConfigTimeout);

        await dispose.WaitAsync(TimeSpan.FromSeconds(5), Token);
        await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionLostException>(() => unanswered);
    }

    [Fact]
    public async Task DisposeAsyncUnderLoadServesEveryRequestInFlight()
    {
        await using var rig = await Rig.StartAsync();
        var requests = Enumerable.Range(0, 50).Select(i => Task.Run(() => rig.Connection.RequestAsync(Request($"r{i}"), Token))).ToArray();
        var bodies = new List<string>();
        for (var i = 0; i < requests.Length; i++)
        {
            bodies.Add(await ReadBodyAsync(rig.Cache));
        }

        var dispose = rig.Connection.DisposeAsync().AsTask();
        for (var i = 0; i < bodies.Count; i++)
        {
            // Half in band, half deferred.
            await rig.Cache.SendAsync(i % 2 == 0 ? MockCache.Served(bodies[i]) : new RespSimpleString($"id{i}"));
        }

        for (var i = 1; i < bodies.Count; i += 2)
        {
            await rig.Cache.SendAsync(MockCache.Push($"id{i}", MockCache.Served(bodies[i])));
        }

        await dispose.WaitAsync(TimeSpan.FromSeconds(5), Token);
        var replies = await Task.WhenAll(requests);
        Assert.Equal(Enumerable.Range(0, 50).Select(i => $"r{i}").Order(), replies.Select(BodyOf).Order());
    }

    [Fact]
    public async Task DisposeUnderLoadFailsEveryRequestInFlightAtOnce()
    {
        await using var rig = await Rig.StartAsync();
        var requests = Enumerable.Range(0, 50).Select(i => Task.Run(() => rig.Connection.RequestAsync(Request($"r{i}"), Token))).ToArray();
        for (var i = 0; i < requests.Length; i++)
        {
            await ReadBodyAsync(rig.Cache);
        }

        rig.Connection.Dispose();

        foreach (var request in requests)
        {
            await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionLostException>(() => request);
        }

        rig.Connection.Dispose();
        await rig.Connection.DisposeAsync();
        Assert.Equal("The connection was closed.", (await rig.Connection.Closed).Message);
        Assert.DoesNotContain(ElastiCacheIntegrationsEvents.ConnectionLost, rig.Logger.Ids);
    }

    internal sealed class Rig : IAsyncDisposable
    {
        private Rig(MockCache cache, ElastiCacheIntegrationsConnection connection, FakeTimeProvider time, ListLogger logger)
        {
            Cache = cache;
            Connection = connection;
            Time = time;
            Logger = logger;
        }

        public MockCache Cache { get; }

        public ElastiCacheIntegrationsConnection Connection { get; }

        public FakeTimeProvider Time { get; }

        public ListLogger Logger { get; }

        public static ElastiCacheIntegrationsClientOptions Options(MockCache cache, int maxPending = 100, int maxResponseSize = 1 << 20, TimeSpan? requestTimeout = null) => new()
        {
            Endpoint = cache.Endpoint,
            CacheName = "my-cache",
            UseTls = false,
            RequestTimeout = requestTimeout ?? RequestTimeout,
            ConfigTimeout = ConfigTimeout,
            MaxPending = maxPending,
            MaxResponseSize = maxResponseSize,
        };

        public static async Task<Rig> StartAsync(
            bool resp3 = true, int maxPending = 100, int maxResponseSize = 1 << 20, TimeSpan? requestTimeout = null,
            Func<Stream, Stream>? wrap = null)
        {
            var cache = new MockCache();
            var accept = cache.AcceptAsync();
            var options = Options(cache, maxPending, maxResponseSize, requestTimeout);
            var time = new FakeTimeProvider();
            var logger = new ListLogger();
            var stream = await Transport.ConnectAsync(options, logger, time, TestContext.Current.CancellationToken);
            await accept;
            stream = wrap?.Invoke(stream) ?? stream;
            var connection = new ElastiCacheIntegrationsConnection(stream, options, new StaticAuth("user", "token"), logger, time) { Resp3 = resp3 };
            connection.Start();
            return new Rig(cache, connection, time, logger);
        }

        public async ValueTask DisposeAsync()
        {
            Connection.Dispose();
            await Cache.DisposeAsync();
        }
    }

    /// <summary>Hands out the given tokens in turn, then the last one again.</summary>
    internal sealed class StaticAuth(string userId, params string[] tokens) : IElastiCacheIntegrationsAuthProvider
    {
        private int _next;

        public ValueTask<(string UserId, string Token)> GetAuthAsync(CancellationToken cancellationToken) =>
            ValueTask.FromResult((userId, tokens[Math.Min(Interlocked.Increment(ref _next) - 1, tokens.Length - 1)]));
    }
}

/// <summary>Holds every write until <see cref="Open"/>; reads pass through.</summary>
internal sealed class GatedStream(Stream inner) : Stream
{
    private readonly TaskCompletionSource _blocked = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private readonly TaskCompletionSource _open = new(TaskCreationOptions.RunContinuationsAsynchronously);

    public Task Blocked => _blocked.Task;

    public override bool CanRead => true;

    public override bool CanSeek => false;

    public override bool CanWrite => true;

    public override long Length => throw new NotSupportedException();

    public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }

    public void Open() => _open.TrySetResult();

    public override async ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken cancellationToken = default)
    {
        _blocked.TrySetResult();
        await _open.Task.WaitAsync(cancellationToken).ConfigureAwait(false);
        await inner.WriteAsync(buffer, cancellationToken).ConfigureAwait(false);
    }

    public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default) =>
        inner.ReadAsync(buffer, cancellationToken);

    public override Task FlushAsync(CancellationToken cancellationToken) => inner.FlushAsync(cancellationToken);

    public override void Flush() => inner.Flush();

    public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();

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
