// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

//go:build go1.25

package elasticacheintegrations

import (
	"context"
	"errors"
	"testing"
	"testing/synctest"
	"time"
)

// A slot handed over at the instant the waiter's budget ends is never lost, whichever of the
// two the waiter sees first.
func TestSlotHandoffRacingGiveUp(t *testing.T) {
	for range 200 {
		synctest.Test(t, func(t *testing.T) {
			l := newSlotLimiter(1)
			if err := l.acquire(context.Background(), budget(time.Second)); err != nil {
				t.Fatal(err)
			}
			done := make(chan error, 1)
			go func() { done <- l.acquire(context.Background(), budget(time.Second)) }()
			synctest.Wait()
			time.Sleep(time.Second)
			l.release()
			if err := <-done; err == nil {
				l.release()
			} else if !errors.As(err, new(*RequestNotSentError)) {
				t.Fatalf("waiter: %v", err)
			}
			if free, waiting := l.state(); free != 1 || waiting != 0 {
				t.Fatalf("free %d, waiting %d", free, waiting)
			}
		})
	}
}

// The connection's deadline check runs at each deadline: a deferred entry times out alone,
// and the timer is re-armed for the in-order entry, which is then reported.
func TestPendingTimerRearms(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		var p *pendingRequests
		fatal := make(chan *pendingEntry, 1)
		p = newPendingRequests(5, func() {
			if e := p.expire(time.Now(), func() error { return &TimeoutError{} }); e != nil {
				fatal <- e
			}
		})
		start := time.Now()
		deferred := track(t, p, true, start.Add(time.Second))
		p.deferHead("1")
		inOrder := track(t, p, false, start.Add(3*time.Second))

		time.Sleep(time.Second)
		synctest.Wait()
		if _, err := finishedWith(t, deferred); !errors.As(err, new(*TimeoutError)) {
			t.Fatalf("deferred at 1s: %v", err)
		}
		select {
		case <-fatal:
			t.Fatal("in-order entry reported before its deadline")
		default:
		}
		time.Sleep(2 * time.Second)
		synctest.Wait()
		if got := <-fatal; got != inOrder || time.Since(start) != 3*time.Second {
			t.Fatalf("fatal %v after %v", got, time.Since(start))
		}
		p.failAll(&ConnectionLostError{}, inOrder, &TimeoutError{})
	})
}

// A register lowers a timer that expire re-armed for a later deadline, and failAll stops the
// timer, so nothing runs past the tracker's close.
func TestPendingTimerLoweredAfterRearmAndStoppedByFailAll(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		var p *pendingRequests
		var fired []time.Duration
		start := time.Now()
		p = newPendingRequests(5, func() {
			fired = append(fired, time.Since(start))
			p.expire(time.Now(), func() error { return &TimeoutError{} })
		})
		a := track(t, p, true, start.Add(time.Second))
		p.deferHead("1")
		track(t, p, true, start.Add(10*time.Second))
		p.deferHead("2")
		time.Sleep(time.Second)
		synctest.Wait()
		if _, err := finishedWith(t, a); !errors.As(err, new(*TimeoutError)) {
			t.Fatalf("first deferred at 1s: %v", err)
		}
		track(t, p, false, start.Add(2*time.Second)) // earlier than the re-armed 10s
		time.Sleep(time.Second)
		synctest.Wait()
		p.failAll(&ConnectionLostError{}, nil, nil)
		time.Sleep(time.Minute)
		synctest.Wait()
		if len(fired) != 2 || fired[0] != time.Second || fired[1] != 2*time.Second {
			t.Fatalf("timer fired at %v, want [1s 2s]", fired)
		}
	})
}
