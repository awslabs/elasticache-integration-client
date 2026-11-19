namespace Amazon.ElastiCache.Integrations.Tests;

public class PendingRequestsTests
{
    private static readonly RespValue Ok = new RespSimpleString("OK");

    private static PendingSlot Add(PendingRequests pending, bool deferrable)
    {
        Assert.True(pending.TryReserve());
        return pending.Add(deferrable, 0);
    }

    [Fact]
    public async Task InBandRepliesResolveInOrder()
    {
        var pending = new PendingRequests(10);
        var first = Add(pending, false);
        var second = Add(pending, false);

        pending.ResolveHead(new RespInteger(1));
        pending.ResolveHead(new RespInteger(2));

        Assert.Equal(new RespInteger(1), await first.Reply);
        Assert.Equal(new RespInteger(2), await second.Reply);
        Assert.Equal(0, pending.Count);
    }

    [Fact]
    public void ReplyWithNothingPendingIsAProtocolError()
    {
        var pending = new PendingRequests(10);

        Assert.Throws<ElastiCacheIntegrationsProtocolException>(() => pending.ResolveHead(Ok));
        Assert.Throws<ElastiCacheIntegrationsProtocolException>(() => pending.TryDefer("a"));
    }

    [Fact]
    public void AnInBandReplyWithOnlyDeferredRequestsPendingIsAProtocolError()
    {
        var pending = new PendingRequests(10);
        Add(pending, true);
        pending.TryDefer("id");

        Assert.Throws<ElastiCacheIntegrationsProtocolException>(() => pending.ResolveHead(Ok));
    }

    [Fact]
    public async Task DeferredRepliesResolveByIdInAnyOrder()
    {
        var pending = new PendingRequests(10);
        var a = Add(pending, true);
        var b = Add(pending, true);

        Assert.True(pending.TryDefer("id-a"));
        Assert.True(pending.TryDefer("id-b"));
        Assert.Equal(2, pending.Count);
        Assert.True(pending.TryResolveDeferred("id-b", new RespInteger(2)));
        Assert.True(pending.TryResolveDeferred("id-a", new RespInteger(1)));

        Assert.Equal(new RespInteger(1), await a.Reply);
        Assert.Equal(new RespInteger(2), await b.Reply);
        Assert.False(pending.TryResolveDeferred("id-a", Ok));
    }

    [Fact]
    public async Task ANonDeferrableHeadTakesASimpleStringInBand()
    {
        var pending = new PendingRequests(10);
        var auth = Add(pending, false);

        Assert.False(pending.TryDefer("QUEUED"));
        pending.ResolveHead(new RespSimpleString("QUEUED"));

        Assert.Equal(new RespSimpleString("QUEUED"), await auth.Reply);
    }

    [Fact]
    public void DuplicateDeferredIdIsAProtocolError()
    {
        var pending = new PendingRequests(10);
        Add(pending, true);
        Add(pending, true);
        pending.TryDefer("id");

        Assert.Throws<ElastiCacheIntegrationsProtocolException>(() => pending.TryDefer("id"));
    }

    [Fact]
    public void LimitCountsFifoAndDeferredSlots()
    {
        var pending = new PendingRequests(2);
        Add(pending, true);
        pending.TryDefer("id");
        Add(pending, true);

        Assert.False(pending.TryReserve());
        pending.TryResolveDeferred("id", Ok);
        Assert.True(pending.TryReserve());
    }

    [Fact]
    public async Task AWaiterGetsTheSlotOfEachWayASlotLeaves()
    {
        var pending = new PendingRequests(1);

        async Task Freed(Action leave)
        {
            var waiter = pending.ReserveAsync(TestContext.Current.CancellationToken);
            Assert.False(waiter.IsCompleted);
            leave();
            await waiter.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken);
        }

        // Each step's waiter holds the reservation the next Add uses.
        Add(pending, false);
        await Freed(() => pending.ResolveHead(Ok));
        pending.Add(deferrable: true, 0);
        pending.TryDefer("a");
        await Freed(() => pending.TryResolveDeferred("a", Ok));
        var deferred = pending.Add(deferrable: true, 0);
        pending.TryDefer("b");
        await Freed(() => pending.Abandon(deferred));
        var dropped = pending.Add(deferrable: true, 0);
        pending.Abandon(dropped);
        await Freed(() => pending.TryDefer("c"));
        pending.Add(deferrable: false, 0);
        await Freed(() => pending.FailAll("lost", null));

        // After FailAll an added slot fails at once and frees its reservation.
        pending.Add(deferrable: false, 0);
        Assert.True(pending.TryReserve());
    }

    [Fact]
    public void AnAbandonedFifoSlotKeepsItsSlotUntilItsReplyArrives()
    {
        var pending = new PendingRequests(1);
        var stale = Add(pending, false);
        pending.Abandon(stale);

        Assert.False(pending.TryReserve());
        pending.ResolveHead(Ok);
        Assert.True(pending.TryReserve());
    }

    [Fact]
    public async Task AnAbandonedFifoSlotStaysAndAbsorbsItsReply()
    {
        var pending = new PendingRequests(10);
        var stale = Add(pending, false);
        var next = Add(pending, false);

        Assert.True(pending.Abandon(stale));
        Assert.True(pending.IsInFifo(stale));
        pending.ResolveHead(new RespInteger(1));
        pending.ResolveHead(new RespInteger(2));

        Assert.False(pending.IsInFifo(stale));
        Assert.Equal(new RespInteger(2), await next.Reply);
    }

    [Fact]
    public void AnAbandonedHeadThatIsDeferredIsDroppedAndItsPushIsUnknown()
    {
        var pending = new PendingRequests(10);
        var stale = Add(pending, true);
        pending.Abandon(stale);

        Assert.True(pending.TryDefer("id"));
        Assert.Equal(0, pending.Count);
        Assert.False(pending.TryResolveDeferred("id", Ok));
    }

    [Fact]
    public void AbandoningADeferredSlotRemovesItFromTheMap()
    {
        var pending = new PendingRequests(10);
        var slot = Add(pending, true);
        pending.TryDefer("id");

        Assert.False(pending.Abandon(slot));
        Assert.Equal(0, pending.Count);
        Assert.False(pending.TryResolveDeferred("id", Ok));
        Assert.False(pending.Abandon(slot));
    }

    [Fact]
    public async Task AbandoningAResolvedSlotLeavesAReusedIdAlone()
    {
        var pending = new PendingRequests(10);
        var first = Add(pending, true);
        pending.TryDefer("id");
        pending.TryResolveDeferred("id", new RespInteger(1));
        var second = Add(pending, true);
        pending.TryDefer("id");

        Assert.False(pending.Abandon(first));
        Assert.True(pending.TryResolveDeferred("id", new RespInteger(2)));
        Assert.Equal(new RespInteger(2), await second.Reply);
    }

    [Fact]
    public void AbandoningASlotAnsweredInBandDoesNothing()
    {
        var pending = new PendingRequests(10);
        var slot = Add(pending, false);
        pending.ResolveHead(Ok);

        Assert.False(pending.Abandon(slot));
    }

    [Fact]
    public void AFailedSlotNobodyAwaitsRaisesNoUnobservedException()
    {
        var message = $"lost-{Guid.NewGuid()}";
        var unobserved = 0;
        void OnUnobserved(object? sender, UnobservedTaskExceptionEventArgs e)
        {
            if (e.Exception.InnerExceptions.Any(x => x.Message == message))
            {
                Interlocked.Increment(ref unobserved);
            }
        }

        TaskScheduler.UnobservedTaskException += OnUnobserved;
        try
        {
            FailAnAbandonedSlot(message);
            GC.Collect();
            GC.WaitForPendingFinalizers();
            GC.Collect();
        }
        finally
        {
            TaskScheduler.UnobservedTaskException -= OnUnobserved;
        }

        Assert.Equal(0, unobserved);
    }

    [System.Runtime.CompilerServices.MethodImpl(System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]
    private static void FailAnAbandonedSlot(string message)
    {
        var pending = new PendingRequests(10);
        pending.Abandon(Add(pending, false));
        pending.FailAll(message, null);
    }

    [Fact]
    public async Task FailAllFailsEverySlotAndEveryLaterOne()
    {
        var pending = new PendingRequests(10);
        var deferred = Add(pending, true);
        pending.TryDefer("id");
        var second = Add(pending, false);
        var cause = new IOException("reset");

        pending.FailAll("lost", cause);

        foreach (var slot in new[] { deferred, second, Add(pending, false) })
        {
            var error = await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionLostException>(() => slot.Reply);
            Assert.Equal("lost", error.Message);
            Assert.Same(cause, error.InnerException);
        }

        Assert.Equal(0, pending.Count);
    }

    [Fact]
    public async Task WhenEmptyCompletesOnceTheLastSlotLeaves()
    {
        var pending = new PendingRequests(10);
        Add(pending, true);
        Add(pending, false);
        pending.TryDefer("id");

        var empty = pending.WhenEmptyAsync();
        Assert.False(empty.IsCompleted);
        pending.ResolveHead(Ok);
        Assert.False(empty.IsCompleted);
        pending.TryResolveDeferred("id", Ok);

        await empty.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken);
        Assert.True(new PendingRequests(1).WhenEmptyAsync().IsCompleted);
    }
}
