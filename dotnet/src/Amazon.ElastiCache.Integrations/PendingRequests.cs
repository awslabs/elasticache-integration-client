// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

namespace Amazon.ElastiCache.Integrations;

/// <summary>One command waiting for its reply.</summary>
internal sealed class PendingSlot
{
    private readonly TaskCompletionSource<RespValue> _reply = new(TaskCreationOptions.RunContinuationsAsynchronously);

    public PendingSlot(bool deferrable, long sentAt)
    {
        Deferrable = deferrable;
        SentAt = sentAt;
    }

    /// <summary>A <c>+id</c> reply moves this slot to the deferred map.</summary>
    public bool Deferrable { get; }

    /// <summary>The <see cref="TimeProvider"/> timestamp taken when the command was written.</summary>
    public long SentAt { get; }

    public Task<RespValue> Reply => _reply.Task;

    // The fields below are guarded by the owning PendingRequests lock.
    internal bool InFifo = true;
    internal bool Abandoned;
    internal string? RequestId;

    internal void Resolve(RespValue value) => _reply.TrySetResult(value);

    /// <summary>Fails the slot. The fault is marked observed: an abandoned slot has no one awaiting it.</summary>
    internal void Fail(Exception error)
    {
        if (_reply.TrySetException(error))
        {
            _ = _reply.Task.Exception;
        }
    }
}

/// <summary>
/// The commands waiting for replies: a FIFO in wire order, and a map of the requests the cache
/// deferred with <c>+id</c>. Thread safe.
/// </summary>
internal sealed class PendingRequests(int maxPending)
{
    private readonly object _lock = new();
    private readonly Queue<PendingSlot> _fifo = new();
    private readonly Dictionary<string, PendingSlot> _deferred = new(StringComparer.Ordinal);
    private readonly TaskCompletionSource _empty = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private (string Message, Exception? Cause)? _failure;
    private bool _draining;

    public int Count
    {
        get
        {
            lock (_lock)
            {
                return _fifo.Count + _deferred.Count;
            }
        }
    }

    /// <summary>
    /// Adds a slot at the FIFO tail. Null when <c>maxPending</c> slots are in use. After
    /// <see cref="FailAll"/> the slot is returned already failed.
    /// </summary>
    public PendingSlot? TryAdd(bool deferrable, long sentAt)
    {
        var slot = new PendingSlot(deferrable, sentAt);
        lock (_lock)
        {
            if (_failure is { } failure)
            {
                slot.InFifo = false;
                slot.Fail(new ElastiCacheIntegrationsConnectionLostException(failure.Message, failure.Cause));
                return slot;
            }

            if (_fifo.Count + _deferred.Count >= maxPending)
            {
                return null;
            }

            _fifo.Enqueue(slot);
            return slot;
        }
    }

    /// <summary>Gives an in-band reply to the oldest command. An abandoned slot just absorbs it.</summary>
    /// <exception cref="ElastiCacheIntegrationsProtocolException">Nothing is pending.</exception>
    public void ResolveHead(RespValue value)
    {
        PendingSlot? slot;
        lock (_lock)
        {
            if (!_fifo.TryDequeue(out slot))
            {
                throw new ElastiCacheIntegrationsProtocolException("Reply with no request pending.");
            }

            slot.InFifo = false;
            CheckEmpty();
        }

        slot.Resolve(value);
    }

    /// <summary>
    /// Moves the oldest command to the deferred map under <paramref name="requestId"/>. False when
    /// that command cannot be deferred. An abandoned command is dropped instead, so its push is
    /// later ignored as unknown.
    /// </summary>
    /// <exception cref="ElastiCacheIntegrationsProtocolException">Nothing is pending, or the id is already in use.</exception>
    public bool TryDefer(string requestId)
    {
        lock (_lock)
        {
            if (!_fifo.TryPeek(out var head))
            {
                throw new ElastiCacheIntegrationsProtocolException("Deferred reply with no request pending.");
            }

            if (!head.Deferrable)
            {
                return false;
            }

            if (_deferred.ContainsKey(requestId))
            {
                throw new ElastiCacheIntegrationsProtocolException("Duplicate deferred request id.");
            }

            _fifo.Dequeue();
            head.InFifo = false;
            if (head.Abandoned)
            {
                CheckEmpty();
            }
            else
            {
                head.RequestId = requestId;
                _deferred.Add(requestId, head);
            }

            return true;
        }
    }

    /// <summary>Resolves a deferred request. False for an unknown id, such as a push after a timeout.</summary>
    public bool TryResolveDeferred(string requestId, RespValue value)
    {
        PendingSlot? slot;
        lock (_lock)
        {
            if (!_deferred.Remove(requestId, out slot))
            {
                return false;
            }

            CheckEmpty();
        }

        slot.Resolve(value);
        return true;
    }

    /// <summary>
    /// Ends a wait early. A deferred slot leaves the map. A FIFO slot stays, to absorb its reply,
    /// and true is returned: the caller watches it for a stall.
    /// </summary>
    public bool Abandon(PendingSlot slot)
    {
        lock (_lock)
        {
            if (slot.RequestId is { } id)
            {
                // The id may already belong to a later request.
                if (_deferred.TryGetValue(id, out var current) && ReferenceEquals(current, slot))
                {
                    _deferred.Remove(id);
                    CheckEmpty();
                }

                return false;
            }

            if (!slot.InFifo)
            {
                return false;
            }

            slot.Abandoned = true;
            return true;
        }
    }

    /// <summary>True while the slot still waits for its in-band reply.</summary>
    public bool IsInFifo(PendingSlot slot)
    {
        lock (_lock)
        {
            return slot.InFifo;
        }
    }

    /// <summary>Completes once no slot is pending. Call after new commands stop.</summary>
    public Task WhenEmptyAsync()
    {
        lock (_lock)
        {
            _draining = true;
            CheckEmpty();
        }

        return _empty.Task;
    }

    /// <summary>Fails every pending slot, and every later <see cref="TryAdd"/>.</summary>
    public void FailAll(string message, Exception? cause)
    {
        List<PendingSlot> slots;
        lock (_lock)
        {
            _failure ??= (message, cause);
            slots = [.. _fifo, .. _deferred.Values];
            foreach (var slot in slots)
            {
                slot.InFifo = false;
            }

            _fifo.Clear();
            _deferred.Clear();
            _empty.TrySetResult();
        }

        foreach (var slot in slots)
        {
            slot.Fail(new ElastiCacheIntegrationsConnectionLostException(message, cause));
        }
    }

    private void CheckEmpty()
    {
        if (_draining && _fifo.Count == 0 && _deferred.Count == 0)
        {
            _empty.TrySetResult();
        }
    }
}
