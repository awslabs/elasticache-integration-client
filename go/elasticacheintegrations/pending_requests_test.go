// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"context"
	"errors"
	"math/rand/v2"
	"sync"
	"testing"
	"time"

	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
)

func budget(d time.Duration) *admission { return &admission{until: time.Now().Add(d)} }

func (l *slotLimiter) state() (free, waiting int) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.free, l.waiters.Len()
}

func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(time.Millisecond)
	}
}

func TestSlotLimiterHandsSlotsInArrivalOrder(t *testing.T) {
	l := newSlotLimiter(1)
	if err := l.acquire(context.Background(), budget(time.Second)); err != nil {
		t.Fatal(err)
	}
	order := make(chan int, 5)
	var wg sync.WaitGroup
	for i := range 5 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if err := l.acquire(context.Background(), budget(5*time.Second)); err != nil {
				t.Error(err)
				return
			}
			order <- i
			l.release()
		}()
		waitFor(t, "waiter to queue", func() bool { _, w := l.state(); return w == i+1 })
	}
	l.release()
	wg.Wait()
	close(order)
	want := 0
	for got := range order {
		if got != want {
			t.Fatalf("waiter %d got a slot before waiter %d", got, want)
		}
		want++
	}
	if free, waiting := l.state(); free != 1 || waiting != 0 {
		t.Fatalf("free %d, waiting %d", free, waiting)
	}
}

func TestSlotLimiterBoundedWait(t *testing.T) {
	l := newSlotLimiter(0)
	start := time.Now()
	a := budget(20 * time.Millisecond)
	defer a.stop()
	err := l.acquire(context.Background(), a)
	var ns *RequestNotSentError
	if !errors.As(err, &ns) || time.Since(start) < 20*time.Millisecond {
		t.Fatalf("got %v after %v", err, time.Since(start))
	}
	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		waitFor(t, "waiter", func() bool { _, w := l.state(); return w == 1 })
		cancel()
	}()
	if err := l.acquire(ctx, budget(time.Minute)); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancelled wait: %v", err)
	}
	if _, waiting := l.state(); waiting != 0 {
		t.Fatalf("%d waiters left", waiting)
	}
	// A budget already spent still takes a free slot.
	l.release()
	if err := l.acquire(context.Background(), budget(-time.Second)); err != nil {
		t.Fatalf("free slot with a spent budget: %v", err)
	}
}

func TestSlotLimiterCloseWakesWaiters(t *testing.T) {
	l := newSlotLimiter(0)
	errs := make(chan error, 3)
	for range 3 {
		go func() { errs <- l.acquire(context.Background(), budget(time.Minute)) }()
	}
	waitFor(t, "waiters", func() bool { _, w := l.state(); return w == 3 })
	l.close()
	for range 3 {
		var lost *ConnectionLostError
		if err := <-errs; !errors.As(err, &lost) {
			t.Fatalf("woken waiter: %v", err)
		}
	}
	var lost *ConnectionLostError
	if err := l.acquire(context.Background(), budget(time.Second)); !errors.As(err, &lost) {
		t.Fatalf("acquire after close: %v", err)
	}
	l.release() // no-op after close
	l.close()
}

func TestPendingSlotsSurviveCancelStorm(t *testing.T) {
	const slots, waiters = 100, 1000
	l := newSlotLimiter(slots)
	for range slots {
		if err := l.acquire(context.Background(), budget(time.Second)); err != nil {
			t.Fatal(err)
		}
	}
	var wg sync.WaitGroup
	for i := range waiters {
		wg.Add(1)
		go func() {
			defer wg.Done()
			ctx, cancel := context.WithTimeout(context.Background(), time.Duration(rand.IntN(20))*time.Millisecond)
			defer cancel()
			wait := time.Duration(rand.IntN(30)) * time.Millisecond
			if i%3 == 0 {
				ctx = context.Background()
			}
			if l.acquire(ctx, budget(wait)) == nil {
				time.Sleep(time.Duration(rand.IntN(200)) * time.Microsecond)
				l.release()
			}
		}()
	}
	for range slots {
		time.Sleep(50 * time.Microsecond)
		l.release()
	}
	wg.Wait()
	if free, waiting := l.state(); free != slots || waiting != 0 {
		t.Fatalf("after the storm: free %d of %d, %d waiters", free, slots, waiting)
	}
}

// track registers an entry the way the connection does: a slot first.
func track(t *testing.T, p *pendingRequests, deferrable bool, deadline time.Time) *pendingEntry {
	t.Helper()
	if err := p.slots.acquire(context.Background(), budget(time.Second)); err != nil {
		t.Fatal(err)
	}
	e, err := p.register(deferrable, deadline)
	if err != nil {
		p.slots.release()
		t.Fatal(err)
	}
	return e
}

func newTestPending(n int) *pendingRequests { return newPendingRequests(n, func() {}) }

func finishedWith(t *testing.T, e *pendingEntry) (resp.Value, error) {
	t.Helper()
	select {
	case <-e.done:
		return e.value, e.err
	default:
		t.Fatal("entry not finished")
		return resp.Value{}, nil
	}
}

func later() time.Time { return time.Now().Add(time.Hour) }

func TestPendingFIFO(t *testing.T) {
	p := newTestPending(10)
	a, b := track(t, p, true, later()), track(t, p, false, later())
	if !p.resolveHead(resp.IntValue(1)) || !p.resolveHead(resp.IntValue(2)) {
		t.Fatal("resolveHead returned false")
	}
	if v, err := finishedWith(t, a); err != nil || v.Int != 1 {
		t.Fatalf("a: %v %v", v, err)
	}
	if v, _ := finishedWith(t, b); v.Int != 2 {
		t.Fatalf("b: %v", v)
	}
	if p.resolveHead(resp.IntValue(3)) {
		t.Fatal("resolved with nothing pending")
	}
	if free, _ := p.slots.state(); free != 10 || p.pending() != 0 {
		t.Fatalf("free %d, pending %d", free, p.pending())
	}
}

func TestPendingFIFOCompacts(t *testing.T) {
	p := newTestPending(200)
	for i := range 1000 {
		track(t, p, false, later())
		if i%2 == 1 && !p.resolveHead(resp.IntValue(int64(i))) {
			t.Fatal("resolveHead")
		}
		if i%2 == 1 && !p.resolveHead(resp.IntValue(int64(i))) {
			t.Fatal("resolveHead")
		}
	}
	if p.pending() != 0 || cap(p.fifo) > 64 {
		t.Fatalf("pending %d, cap %d", p.pending(), cap(p.fifo))
	}
}

func TestPendingDefer(t *testing.T) {
	p := newTestPending(10)
	if _, err := p.deferHead("1"); !errors.As(err, new(*ProtocolError)) {
		t.Fatalf("defer with nothing pending: %v", err)
	}
	cmd := track(t, p, false, later())
	if moved, err := p.deferHead("1"); moved || err != nil {
		t.Fatalf("non-deferrable head: %v %v", moved, err)
	}
	p.resolveHead(resp.SimpleValue("1")) // the id is the command's reply
	if v, _ := finishedWith(t, cmd); string(v.Bytes) != "1" {
		t.Fatalf("command reply %v", v)
	}

	a, b := track(t, p, true, later()), track(t, p, true, later())
	if moved, err := p.deferHead("7"); !moved || err != nil {
		t.Fatalf("defer a: %v %v", moved, err)
	}
	if _, err := p.deferHead("7"); !errors.As(err, new(*ProtocolError)) {
		t.Fatalf("duplicate id: %v", err)
	}
	if moved, _ := p.deferHead("8"); !moved {
		t.Fatal("defer b")
	}
	if p.resolveDeferred("9", resp.IntValue(9)) {
		t.Fatal("resolved an unknown id")
	}
	if !p.resolveDeferred("8", resp.IntValue(8)) || !p.resolveDeferred("7", resp.IntValue(7)) {
		t.Fatal("resolveDeferred")
	}
	if va, _ := finishedWith(t, a); va.Int != 7 {
		t.Fatalf("a: %v", va)
	}
	if vb, _ := finishedWith(t, b); vb.Int != 8 {
		t.Fatalf("b: %v", vb)
	}
	if p.resolveDeferred("7", resp.IntValue(7)) {
		t.Fatal("resolved an id twice")
	}
	if free, _ := p.slots.state(); free != 10 {
		t.Fatalf("free %d", free)
	}
}

func TestPendingAbandon(t *testing.T) {
	p := newTestPending(3)
	inOrder := track(t, p, true, later())
	if !p.abandon(inOrder) {
		t.Fatal("abandon in-order")
	}
	if free, _ := p.slots.state(); free != 2 || p.pending() != 1 {
		t.Fatalf("an abandoned in-order entry left the FIFO: free %d, pending %d", free, p.pending())
	}
	// Its +<id> removes it; the push for that id is then dropped.
	if moved, err := p.deferHead("1"); !moved || err != nil {
		t.Fatalf("defer an abandoned head: %v %v", moved, err)
	}
	if p.resolveDeferred("1", resp.IntValue(1)) || p.pending() != 0 {
		t.Fatal("an abandoned head was deferred")
	}

	deferred := track(t, p, true, later())
	p.deferHead("2")
	if !p.abandon(deferred) || p.pending() != 0 {
		t.Fatal("abandon deferred")
	}
	if p.resolveDeferred("2", resp.IntValue(2)) {
		t.Fatal("a push resolved an abandoned entry")
	}

	resolved := track(t, p, false, later())
	abandoned := track(t, p, false, later())
	p.resolveHead(resp.IntValue(1))
	if p.abandon(resolved) {
		t.Fatal("abandon of a finished entry reported true")
	}
	p.abandon(abandoned)
	p.resolveHead(resp.IntValue(2)) // the reply of the abandoned entry is discarded
	if free, _ := p.slots.state(); free != 3 || p.pending() != 0 {
		t.Fatalf("free %d, pending %d", free, p.pending())
	}
}

func TestPendingExpire(t *testing.T) {
	p := newTestPending(5)
	now := time.Now()
	timeout := func() error { return &TimeoutError{Msg: "late"} }
	deferredLate := track(t, p, true, now.Add(time.Second))
	p.deferHead("1")
	deferredOK := track(t, p, true, now.Add(3*time.Second))
	p.deferHead("2")
	inOrder := track(t, p, false, now.Add(2*time.Second))

	if fatal := p.expire(now.Add(time.Second), timeout); fatal != nil {
		t.Fatalf("fatal %v at 1s", fatal)
	}
	if _, err := finishedWith(t, deferredLate); !errors.As(err, new(*TimeoutError)) {
		t.Fatalf("late deferred: %v", err)
	}
	if p.armedAt != now.Add(2*time.Second) {
		t.Fatalf("armed at %v, want the in-order deadline", p.armedAt.Sub(now))
	}
	if p.resolveDeferred("1", resp.IntValue(1)) {
		t.Fatal("a late push resolved a timed-out entry")
	}
	if fatal := p.expire(now.Add(2*time.Second), timeout); fatal != inOrder {
		t.Fatalf("fatal %v at 2s, want the in-order entry", fatal)
	}
	select {
	case <-deferredOK.done:
		t.Fatal("an entry inside its deadline finished")
	default:
	}
	if free, _ := p.slots.state(); free != 3 {
		t.Fatalf("free %d", free)
	}
}

func TestPendingFailAll(t *testing.T) {
	p := newTestPending(5)
	a := track(t, p, true, later())
	b := track(t, p, true, later())
	p.deferHead("1")
	c := track(t, p, true, later())
	lost := &ConnectionLostError{Msg: "gone"}
	timeout := &TimeoutError{Msg: "late"}
	p.failAll(lost, b, timeout)
	if _, err := finishedWith(t, a); err != lost {
		t.Fatalf("a: %v", err)
	}
	if _, err := finishedWith(t, b); err != timeout {
		t.Fatalf("b: %v", err)
	}
	if _, err := finishedWith(t, c); err != lost {
		t.Fatalf("c: %v", err)
	}
	if _, err := p.register(true, later()); !errors.As(err, new(*ConnectionLostError)) {
		t.Fatalf("register after failAll: %v", err)
	}
	if err := p.slots.acquire(context.Background(), budget(time.Second)); !errors.As(err, new(*ConnectionLostError)) {
		t.Fatalf("acquire after failAll: %v", err)
	}
	if p.resolveHead(resp.IntValue(1)) || p.resolveDeferred("1", resp.IntValue(1)) || p.expire(time.Now(), nil) != nil {
		t.Fatal("a closed tracker resolved something")
	}
	p.failAll(lost, nil, nil)
}

func TestPendingTimerFiresAtEarliestDeadline(t *testing.T) {
	fired := make(chan time.Time, 4)
	p := newPendingRequests(5, func() { fired <- time.Now() })
	start := time.Now()
	track(t, p, false, start.Add(time.Hour))
	track(t, p, false, start.Add(30*time.Millisecond)) // lowers the timer
	select {
	case at := <-fired:
		if at.Sub(start) < 30*time.Millisecond {
			t.Fatalf("fired after %v", at.Sub(start))
		}
	case <-time.After(5 * time.Second):
		t.Fatal("the timer did not fire")
	}
	p.failAll(&ConnectionLostError{}, nil, nil)
}

// A deferred entry past its deadline times out alone even when an in-order entry is late in
// the same scan, so the connection's close does not turn its timeout into a lost connection.
func TestPendingExpireFailsLateDeferredBeforeFatal(t *testing.T) {
	p := newTestPending(5)
	now := time.Now()
	deferred := track(t, p, true, now.Add(time.Second))
	p.deferHead("1")
	inOrder := track(t, p, false, now.Add(time.Second))
	timeout := &TimeoutError{Msg: "late"}
	if fatal := p.expire(now.Add(time.Second), func() error { return timeout }); fatal != inOrder {
		t.Fatalf("fatal %v, want the in-order entry", fatal)
	}
	p.failAll(&ConnectionLostError{Msg: "gone"}, inOrder, timeout)
	if _, err := finishedWith(t, deferred); err != timeout {
		t.Fatalf("late deferred entry: %v, want its timeout", err)
	}
}
