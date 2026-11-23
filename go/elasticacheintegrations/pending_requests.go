// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"container/list"
	"context"
	"sync"
	"time"

	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
)

func errConnectionClosed() error { return &ConnectionLostError{Msg: "the connection is closed"} }

func errNoSlot() error {
	return &RequestNotSentError{Msg: "no pending slot was free within the request budget"}
}

// admission is the part of a request's budget it may spend before it is sent: the waits for a
// pending slot and for the write turn. Its timer is made only when a wait blocks.
type admission struct {
	until time.Time
	timer *time.Timer
}

func (a *admission) expired() <-chan time.Time {
	if a.timer == nil {
		a.timer = time.NewTimer(time.Until(a.until))
	}
	return a.timer.C
}

func (a *admission) stop() {
	if a.timer != nil {
		a.timer.Stop()
	}
}

// slotLimiter bounds the requests awaiting a reply on one connection. Waiters get slots in
// arrival order.
type slotLimiter struct {
	mu      sync.Mutex
	free    int
	closed  bool
	waiters list.List // *slotWaiter, oldest first
}

type slotWaiter struct {
	ready   chan struct{} // closed when a slot is handed over or the limiter closes
	granted bool
}

func newSlotLimiter(n int) *slotLimiter { return &slotLimiter{free: n} }

// acquire takes a slot, waiting until a's budget ends. A waiter that gives up after a slot was
// handed to it passes the slot on.
func (l *slotLimiter) acquire(ctx context.Context, a *admission) error {
	l.mu.Lock()
	if l.closed {
		l.mu.Unlock()
		return errConnectionClosed()
	}
	if l.free > 0 && l.waiters.Len() == 0 {
		l.free--
		l.mu.Unlock()
		return nil
	}
	w := &slotWaiter{ready: make(chan struct{})}
	el := l.waiters.PushBack(w)
	l.mu.Unlock()

	var err error
	select {
	case <-w.ready:
	case <-a.expired():
		err = errNoSlot()
	case <-ctx.Done():
		err = ctx.Err()
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	switch {
	case w.granted && err == nil:
		return nil
	case w.granted:
		l.releaseLocked()
	case l.closed:
		return errConnectionClosed()
	default:
		l.waiters.Remove(el)
	}
	return err
}

func (l *slotLimiter) release() {
	l.mu.Lock()
	l.releaseLocked()
	l.mu.Unlock()
}

func (l *slotLimiter) releaseLocked() {
	if l.closed {
		return
	}
	if front := l.waiters.Front(); front != nil {
		w := l.waiters.Remove(front).(*slotWaiter)
		w.granted = true
		close(w.ready)
		return
	}
	l.free++
}

// close wakes every waiter; they and later callers get *ConnectionLostError.
func (l *slotLimiter) close() {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.closed {
		return
	}
	l.closed = true
	for el := l.waiters.Front(); el != nil; el = el.Next() {
		close(el.Value.(*slotWaiter).ready)
	}
	l.waiters.Init()
}

type entryPlace uint8

const (
	inFIFO entryPlace = iota
	inDeferred
	finished
)

// pendingEntry is one request awaiting its reply. It holds one slot until it is finished.
type pendingEntry struct {
	done       chan struct{} // closed once value or err is set
	value      resp.Value
	err        error
	deferrable bool // aws.request may be answered with +<id> and a push

	// Guarded by pendingRequests.mu.
	deadline  time.Time // zero while the request is being written
	where     entryPlace
	abandoned bool   // the caller stopped waiting; the reply is discarded
	id        string // when deferred
}

// pendingRequests tracks the requests of one connection: a FIFO in wire order and the deferred
// entries by id. One timer, armed for the earliest deadline, enforces every entry's deadline.
type pendingRequests struct {
	slots   *slotLimiter
	onTimer func() // the connection's deadline check

	mu       sync.Mutex
	fifo     []*pendingEntry // fifo[head:] awaits in-order replies, oldest first
	head     int
	deferred map[string]*pendingEntry
	closed   bool
	timer    *time.Timer
	armedAt  time.Time // zero when not armed
}

func newPendingRequests(maxPending int, onTimer func()) *pendingRequests {
	return &pendingRequests{
		slots:    newSlotLimiter(maxPending),
		onTimer:  onTimer,
		deferred: map[string]*pendingEntry{},
	}
}

// register adds an entry at the FIFO tail; the caller's slot belongs to it from now on. It
// must be called under the write turn, just before the frame is written. The entry has no
// reply deadline until setDeadline: the write has its own.
func (p *pendingRequests) register(deferrable bool) (*pendingEntry, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.closed {
		return nil, errConnectionClosed()
	}
	e := &pendingEntry{done: make(chan struct{}), deferrable: deferrable}
	p.fifo = append(p.fifo, e)
	return e, nil
}

// resolveHead completes the oldest FIFO entry with v. It reports false when none is pending.
// After failAll it discards v: the connection is closing.
func (p *pendingRequests) resolveHead(v resp.Value) bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.closed {
		return true
	}
	e := p.popLocked()
	if e == nil {
		return false
	}
	p.finishLocked(e, v, nil)
	return true
}

// deferHead moves the oldest FIFO entry to the deferred map under id, as Java's
// moveNextToAsyncResponses. It reports false when that entry may not be deferred, so the
// value resolves it instead. A head whose caller left is removed and its push will be dropped.
func (p *pendingRequests) deferHead(id string) (bool, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.closed {
		return true, nil // discarded: the connection is closing
	}
	if p.head == len(p.fifo) {
		return false, &ProtocolError{Msg: "a deferred request id arrived with nothing pending"}
	}
	if !p.fifo[p.head].deferrable {
		return false, nil
	}
	if _, dup := p.deferred[id]; dup {
		return false, &ProtocolError{Msg: "a deferred request id arrived that is already pending"}
	}
	e := p.popLocked()
	if e.abandoned {
		p.finishLocked(e, resp.Value{}, nil)
		return true, nil
	}
	e.where, e.id = inDeferred, id
	p.deferred[id] = e
	return true, nil
}

// resolveDeferred completes the deferred entry id with v. It reports false for an id that is
// not pending: timed out, abandoned, or never seen.
func (p *pendingRequests) resolveDeferred(id string, v resp.Value) bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	e, ok := p.deferred[id]
	if !ok {
		return false
	}
	delete(p.deferred, id)
	p.finishLocked(e, v, nil)
	return true
}

// abandon records that e's caller stopped waiting. A deferred entry leaves at once and frees
// its slot; a FIFO entry stays until its reply or its deadline, so the stream stays in step.
// It reports false when e had already finished.
func (p *pendingRequests) abandon(e *pendingEntry) bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	switch e.where {
	case finished:
		return false
	case inDeferred:
		delete(p.deferred, e.id)
		p.finishLocked(e, resp.Value{}, nil)
	default:
		e.abandoned = true
	}
	return true
}

// setDeadline gives e, once written, its reply deadline.
func (p *pendingRequests) setDeadline(e *pendingEntry, at time.Time) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if e.where != finished && !p.closed {
		e.deadline = at
		p.armLocked(at)
	}
}

// expire fails each deferred entry past its deadline with timeoutErr() and frees its slot. For
// a FIFO entry past its deadline it calls dying(lost()), then fails that entry with timeoutErr()
// and every other entry with the error dying returns, as failAll does, all in one critical
// section, so no reply can resolve it in between; expire then returns it, and the connection
// must close. Otherwise it arms the timer for the next deadline. A nil dying fails them with
// lost().
func (p *pendingRequests) expire(now time.Time, timeoutErr, lost func() error, dying func(error) error) *pendingEntry {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.closed {
		return nil
	}
	p.armedAt = time.Time{}
	var next time.Time
	for id, e := range p.deferred {
		if e.deadline.IsZero() {
			continue // still being written
		}
		if !e.deadline.After(now) {
			delete(p.deferred, id)
			p.finishLocked(e, resp.Value{}, timeoutErr())
			continue
		}
		next = earlier(next, e.deadline)
	}
	for _, e := range p.fifo[p.head:] {
		if e.deadline.IsZero() {
			continue
		}
		if !e.deadline.After(now) {
			err := lost()
			if dying != nil {
				err = dying(err)
			}
			p.failAllLocked(err, e, timeoutErr())
			return e
		}
		next = earlier(next, e.deadline)
	}
	if !next.IsZero() {
		p.armLocked(next)
	}
	return nil
}

// failAll finishes every entry with lost, and timedOut with timeoutErr, and refuses later
// registrations and slot waits.
func (p *pendingRequests) failAll(lost error, timedOut *pendingEntry, timeoutErr error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.failAllLocked(lost, timedOut, timeoutErr)
}

func (p *pendingRequests) failAllLocked(lost error, timedOut *pendingEntry, timeoutErr error) {
	if p.closed {
		return
	}
	p.closed = true
	p.slots.close()
	fail := func(e *pendingEntry) {
		err := lost
		if e == timedOut {
			err = timeoutErr
		}
		p.finishLocked(e, resp.Value{}, err)
	}
	for _, e := range p.fifo[p.head:] {
		fail(e)
	}
	for _, e := range p.deferred {
		fail(e)
	}
	p.fifo, p.head, p.deferred = nil, 0, map[string]*pendingEntry{}
	if p.timer != nil {
		p.timer.Stop()
	}
}

// pending counts the entries awaiting a reply, in the FIFO and deferred.
func (p *pendingRequests) pending() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return len(p.fifo) - p.head + len(p.deferred)
}

func (p *pendingRequests) popLocked() *pendingEntry {
	if p.head == len(p.fifo) {
		return nil
	}
	e := p.fifo[p.head]
	p.fifo[p.head] = nil
	p.head++
	switch {
	case p.head == len(p.fifo):
		p.fifo, p.head = p.fifo[:0], 0
	case p.head >= 32 && 2*p.head >= len(p.fifo):
		n := copy(p.fifo, p.fifo[p.head:])
		clear(p.fifo[n:])
		p.fifo, p.head = p.fifo[:n], 0
	}
	return e
}

// finishLocked sets e's result once and frees its slot.
func (p *pendingRequests) finishLocked(e *pendingEntry, v resp.Value, err error) {
	if e.where == finished {
		return
	}
	e.where = finished
	e.value, e.err = v, err
	close(e.done)
	p.slots.release()
}

// armLocked makes the timer fire by at.
func (p *pendingRequests) armLocked(at time.Time) {
	if !p.armedAt.IsZero() && !at.Before(p.armedAt) {
		return
	}
	p.armedAt = at
	if p.timer == nil {
		p.timer = time.AfterFunc(time.Until(at), p.onTimer)
		return
	}
	p.timer.Reset(time.Until(at))
}

func laterOf(a, b time.Time) time.Time {
	if b.After(a) {
		return b
	}
	return a
}

func earlier(a, b time.Time) time.Time {
	if a.IsZero() || b.Before(a) {
		return b
	}
	return a
}
