// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

namespace Amazon.ElastiCache.Integrations.Tests;

public class PendingRequestsTests
{
    private static readonly RespValue Ok = new RespSimpleString("OK");

    [Fact]
    public async Task InBandRepliesResolveInOrder()
    {
        var pending = new PendingRequests(10);
        var first = pending.TryAdd(deferrable: false, 0)!;
        var second = pending.TryAdd(deferrable: false, 0)!;

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
        pending.TryAdd(deferrable: true, 0);
        pending.TryDefer("id");

        Assert.Throws<ElastiCacheIntegrationsProtocolException>(() => pending.ResolveHead(Ok));
    }

    [Fact]
    public async Task DeferredRepliesResolveByIdInAnyOrder()
    {
        var pending = new PendingRequests(10);
        var a = pending.TryAdd(deferrable: true, 0)!;
        var b = pending.TryAdd(deferrable: true, 0)!;

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
        var auth = pending.TryAdd(deferrable: false, 0)!;

        Assert.False(pending.TryDefer("QUEUED"));
        pending.ResolveHead(new RespSimpleString("QUEUED"));

        Assert.Equal(new RespSimpleString("QUEUED"), await auth.Reply);
    }

    [Fact]
    public void DuplicateDeferredIdIsAProtocolError()
    {
        var pending = new PendingRequests(10);
        pending.TryAdd(deferrable: true, 0);
        pending.TryAdd(deferrable: true, 0);
        pending.TryDefer("id");

        Assert.Throws<ElastiCacheIntegrationsProtocolException>(() => pending.TryDefer("id"));
    }

    [Fact]
    public void LimitCountsFifoAndDeferredSlots()
    {
        var pending = new PendingRequests(2);
        pending.TryAdd(deferrable: true, 0);
        pending.TryDefer("id");
        pending.TryAdd(deferrable: true, 0);

        Assert.Null(pending.TryAdd(deferrable: true, 0));
        pending.TryResolveDeferred("id", Ok);
        Assert.NotNull(pending.TryAdd(deferrable: true, 0));
    }

    [Fact]
    public async Task AnAbandonedFifoSlotStaysAndAbsorbsItsReply()
    {
        var pending = new PendingRequests(10);
        var stale = pending.TryAdd(deferrable: false, 0)!;
        var next = pending.TryAdd(deferrable: false, 0)!;

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
        var stale = pending.TryAdd(deferrable: true, 0)!;
        pending.Abandon(stale);

        Assert.True(pending.TryDefer("id"));
        Assert.Equal(0, pending.Count);
        Assert.False(pending.TryResolveDeferred("id", Ok));
    }

    [Fact]
    public void AbandoningADeferredSlotRemovesItFromTheMap()
    {
        var pending = new PendingRequests(10);
        var slot = pending.TryAdd(deferrable: true, 0)!;
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
        var first = pending.TryAdd(deferrable: true, 0)!;
        pending.TryDefer("id");
        pending.TryResolveDeferred("id", new RespInteger(1));
        var second = pending.TryAdd(deferrable: true, 0)!;
        pending.TryDefer("id");

        Assert.False(pending.Abandon(first));
        Assert.True(pending.TryResolveDeferred("id", new RespInteger(2)));
        Assert.Equal(new RespInteger(2), await second.Reply);
    }

    [Fact]
    public void AbandoningASlotAnsweredInBandDoesNothing()
    {
        var pending = new PendingRequests(10);
        var slot = pending.TryAdd(deferrable: false, 0)!;
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
        pending.Abandon(pending.TryAdd(deferrable: false, 0)!);
        pending.FailAll(message, null);
    }

    [Fact]
    public async Task FailAllFailsEverySlotAndEveryLaterOne()
    {
        var pending = new PendingRequests(10);
        var deferred = pending.TryAdd(deferrable: true, 0)!;
        pending.TryDefer("id");
        var second = pending.TryAdd(deferrable: false, 0)!;
        var cause = new IOException("reset");

        pending.FailAll("lost", cause);

        foreach (var slot in new[] { deferred, second, pending.TryAdd(deferrable: false, 0)! })
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
        pending.TryAdd(deferrable: true, 0);
        pending.TryAdd(deferrable: false, 0);
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
