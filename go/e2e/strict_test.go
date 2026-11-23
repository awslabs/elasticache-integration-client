// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package e2e

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"

	"github.com/awslabs/elasticache-integration-client/go/elasticacheintegrations"
	"github.com/awslabs/elasticache-integration-client/go/internal/mockcache"
	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
)

// TestStrictNeverReachesOrigin is the design's named regression test end to end: with
// FallbackDuration 0 no routed call reaches DynamoDB from the client, whatever happens to the
// cache, under load, and at and after Close.
func TestStrictNeverReachesOrigin(t *testing.T) {
	t.Parallel()
	for _, tc := range []struct {
		name  string
		setup []func(*harnessConfig)
		run   func(t *testing.T, h *harness)
	}{
		{"outage", []func(*harnessConfig){withConnectionTimeout(300 * time.Millisecond)}, strictOutage},
		{"lost_in_flight", nil, strictLostInFlight},
		{"auth_error", []func(*harnessConfig){withRetries(1)}, strictAuthError},
		{"slots_full", []func(*harnessConfig){withRetries(1), withRequestTimeout(5 * time.Second)}, strictSlotsFull},
		{"load_during_outage", []func(*harnessConfig){withConnectionTimeout(200 * time.Millisecond)}, strictLoad},
		{"close", []func(*harnessConfig){withRetries(1)}, strictClose},
		{"close_during_outage", []func(*harnessConfig){withConnectionTimeout(5 * time.Second)}, strictCloseDuringOutage},
		{"close_waiting_for_slot", []func(*harnessConfig){withRequestTimeout(5 * time.Second)}, strictCloseWaitingForSlot},
	} {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			setup := append([]func(*harnessConfig){withRelay(), withOptions(func(o *elasticacheintegrations.Options) {
				o.FallbackDuration = aws.Duration(0)
			})}, tc.setup...)
			h := newHarness(t, setup...)
			seed(t, h.dynamo)
			if _, err := h.getSong(context.Background(), "One"); err != nil {
				t.Fatal(err)
			}
			tc.run(t, h)
			if n := h.dynamo.fromClient(); n != 0 {
				t.Fatalf("the client sent %d calls to DynamoDB in strict mode", n)
			}
		})
	}
}

func withConnectionTimeout(d time.Duration) func(*harnessConfig) {
	return withOptions(func(o *elasticacheintegrations.Options) { o.ConnectionTimeout = d })
}

func withRequestTimeout(d time.Duration) func(*harnessConfig) {
	return withOptions(func(o *elasticacheintegrations.Options) { o.CacheRequestTimeout = d })
}

// wantUnavailable checks err is a retryable CacheUnavailableError.
func wantUnavailable(t *testing.T, err error) {
	t.Helper()
	errorAs[*elasticacheintegrations.CacheUnavailableError](t, err)
	if decided, retry := retryable(err); !decided || !retry {
		t.Errorf("retryable = %v, %v; want true, true", decided, retry)
	}
}

// servedAgain waits until a call is served by the cache.
func servedAgain(t *testing.T, h *harness) {
	t.Helper()
	routed := h.requests()
	eventually(t, "the cache to serve again", func() bool {
		out, err := h.getSong(context.Background(), "Two", once)
		return err == nil && songOf(out) == "Two" && h.requests() > routed
	})
}

func strictOutage(t *testing.T, h *harness) {
	h.outage()
	for range 3 {
		_, err := h.getSong(context.Background(), "Two")
		wantUnavailable(t, err)
	}
	h.restore()
	servedAgain(t, h)
}

// strictLostInFlight: a call in flight when the connection drops is retried by the SDK, and
// the retry waits for the reconnect and is served by the cache.
func strictLostInFlight(t *testing.T, h *harness) {
	deferred := make(chan *held, 1)
	h.proxy.setWrap(deferFirst(1, deferred))
	done := make(chan error, 1)
	go func() {
		out, err := h.getSong(context.Background(), "Two")
		if err == nil && songOf(out) != "Two" {
			err = errUnexpected(out)
		}
		done <- err
	}()
	<-deferred
	h.relay.Disconnect()
	if err := <-done; err != nil {
		t.Fatalf("the call in flight at the drop = %v; want its retry served by the cache", err)
	}
	if h.requests() != 3 || h.cache.Connections() != 2 {
		t.Errorf("aws.requests %d, connections %d; want 3 and 2", h.requests(), h.cache.Connections())
	}
}

func strictAuthError(t *testing.T, h *harness) {
	var n atomic.Int64
	n.Store(1)
	h.proxy.setWrap(failNext(&n, func() mockcache.Reply {
		return mockcache.Now(resp.ErrorValue("AUTH_ERROR", "no FAS for this connection"))
	}))
	_, err := h.getSong(context.Background(), "Two")
	wantUnavailable(t, err)
	eventually(t, "the reconnect", func() bool { return h.cache.Connections() == 2 })
	servedAgain(t, h)
}

func strictSlotsFull(t *testing.T, h *harness) {
	const slots = 100
	deferred := make(chan *held, slots)
	h.proxy.setWrap(deferFirst(slots, deferred))
	var holders sync.WaitGroup
	for range slots {
		holders.Add(1)
		go func() {
			defer holders.Done()
			_, _ = h.getSong(context.Background(), "One")
		}()
	}
	all := make([]*held, 0, slots)
	for range slots {
		all = append(all, <-deferred)
	}
	_, err := h.getSong(context.Background(), "Two")
	wantUnavailable(t, err)
	for _, d := range all {
		d.release()
	}
	holders.Wait()
	servedAgain(t, h)
}

// strictLoad runs 32 goroutines through an outage and the recovery after it.
func strictLoad(t *testing.T, h *harness) {
	var wg sync.WaitGroup
	stop := make(chan struct{})
	defer func() { // also when a check below fails the test
		close(stop)
		wg.Wait()
	}()
	var served, unavailable atomic.Int64
	for range 32 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for {
				select {
				case <-stop:
					return
				default:
				}
				out, err := h.getSong(context.Background(), "Two", once)
				var ce *elasticacheintegrations.CacheUnavailableError
				var le *elasticacheintegrations.ConnectionLostError
				switch {
				case err == nil && songOf(out) == "Two":
					served.Add(1)
				case errors.As(err, &ce), errors.As(err, &le):
					unavailable.Add(1)
				default:
					t.Errorf("a call under load = %v, %v", out, err)
					return
				}
				time.Sleep(5 * time.Millisecond)
			}
		}()
	}
	time.Sleep(200 * time.Millisecond)
	h.outage()
	eventually(t, "calls to fail during the outage", func() bool { return unavailable.Load() >= 32 })
	h.restore()
	before := served.Load()
	eventually(t, "calls to be served after the outage", func() bool { return served.Load() > before+32 })
}

// strictClose: a call in flight at Close fails and is not retried; later calls fail too.
func strictClose(t *testing.T, h *harness) {
	deferred := make(chan *held, 1)
	h.proxy.setWrap(deferFirst(1, deferred))
	done := make(chan error, 1)
	go func() {
		_, err := h.getSong(context.Background(), "Two")
		done <- err
	}()
	<-deferred
	if err := h.client.Close(); err != nil {
		t.Fatal(err)
	}
	err := <-done
	errorAs[*elasticacheintegrations.ConnectionLostError](t, err)
	if decided, retry := retryable(err); !decided || retry {
		t.Errorf("in flight at Close: retryable = %v, %v; want true, false", decided, retry)
	}
	for _, op := range []string{"routed", "not routed"} {
		var err error
		if op == "routed" {
			_, err = h.getSong(context.Background(), "Three")
		} else {
			_, err = h.ddb.ListTables(context.Background(), nil)
			h.seen(err)
		}
		errorAs[*elasticacheintegrations.CacheUnavailableError](t, err)
		if decided, retry := retryable(err); !decided || retry {
			t.Errorf("%s call after Close: retryable = %v, %v; want true, false", op, decided, retry)
		}
	}
}

// closedWhileWaiting closes the client while call waits, checks Close wakes it with a terminal
// error, and returns that error.
func closedWhileWaiting(t *testing.T, h *harness, call func() error) error {
	t.Helper()
	done := make(chan error, 1)
	go func() { done <- call() }()
	time.Sleep(100 * time.Millisecond)
	select {
	case err := <-done:
		t.Fatalf("the call returned %v before Close; want it still waiting", err)
	default:
	}
	closed := time.Now()
	if err := h.client.Close(); err != nil {
		t.Fatal(err)
	}
	err := <-done
	if took := time.Since(closed); took > time.Second {
		t.Errorf("the waiting call returned %v after Close; Close must wake it", took)
	}
	if decided, retry := retryable(err); !decided || retry {
		t.Errorf("a call waiting at Close = %v: retryable = %v, %v; want true, false", err, decided, retry)
	}
	return err
}

// strictCloseDuringOutage: a call waiting for the reconnect when Close comes fails and is not
// retried, with SDK retries on.
func strictCloseDuringOutage(t *testing.T, h *harness) {
	h.outage()
	err := closedWhileWaiting(t, h, func() error {
		_, err := h.getSong(context.Background(), "Two")
		return err
	})
	errorAs[*elasticacheintegrations.CacheUnavailableError](t, err)
}

// strictCloseWaitingForSlot: with every slot held, a call waiting for one when Close comes
// fails and is not retried, and so do the calls holding the slots, with SDK retries on.
func strictCloseWaitingForSlot(t *testing.T, h *harness) {
	const slots = 100
	deferred := make(chan *held, slots)
	h.proxy.setWrap(deferFirst(slots, deferred))
	results := make(chan error, slots)
	for range slots {
		go func() {
			_, err := h.getSong(context.Background(), "One")
			results <- err
		}()
	}
	for range slots {
		<-deferred
	}
	err := closedWhileWaiting(t, h, func() error {
		_, err := h.getSong(context.Background(), "Two")
		return err
	})
	// A call still waiting for a slot fails as lost; one that reached the client only after
	// Close fails as unavailable. Both are terminal.
	if !errors.As(err, new(*elasticacheintegrations.ConnectionLostError)) &&
		!errors.As(err, new(*elasticacheintegrations.CacheUnavailableError)) {
		t.Errorf("a call waiting for a slot at Close = %v; want *ConnectionLostError", err)
	}
	for range slots {
		err := <-results
		errorAs[*elasticacheintegrations.ConnectionLostError](t, err)
		if decided, retry := retryable(err); !decided || retry {
			t.Errorf("a call holding a slot at Close: retryable = %v, %v; want true, false", decided, retry)
		}
	}
}
