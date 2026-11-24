// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package e2e

import (
	"context"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"

	"github.com/awslabs/elasticache-integration-client/go/elasticacheintegrations"
	"github.com/awslabs/elasticache-integration-client/go/internal/mockcache"
	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
)

// outage cuts the cache off: open connections are reset and new ones refused.
func (h *harness) outage() {
	h.relay.SetMode(mockcache.Refuse)
	h.relay.Disconnect()
}

// restore lets connections through again.
func (h *harness) restore() { h.relay.SetMode(mockcache.Forward) }

func TestReconnectAfterDropUsesRefreshedToken(t *testing.T) {
	t.Parallel()
	creds := &rotating{}
	h := newHarness(t, withRelay(), withCredentials(creds), withOptions(func(o *elasticacheintegrations.Options) {
		o.IAMTokenRefreshDelay = time.Second
		o.CachePolicyOverrides = map[string]any{"item_ttl": 5}
	}))
	seed(t, h.dynamo)
	ctx := context.Background()
	if _, err := h.getSong(ctx, "One"); err != nil {
		t.Fatal(err)
	}
	rotated := creds.next()
	// The token manager signs again IAMTokenRefreshDelay after it installed a token, so two
	// more credential calls mean a token signed with the rotated key was installed.
	calls := creds.calls.Load()
	eventually(t, "the background token refresh", func() bool { return creds.calls.Load() >= calls+2 })
	h.relay.Disconnect()
	eventually(t, "the reconnect", func() bool { return h.cache.Connections() == 2 })
	tokens := h.hellos()
	if len(tokens) != 2 || keyID(tokens[0]) != "AKIDROTATING0" || keyID(tokens[1]) != rotated {
		t.Fatalf("HELLO tokens signed by %v, want AKIDROTATING0 then %s", keyIDs(tokens), rotated)
	}
	if got := h.cache.Overrides(); len(got) != 2 || got[0] != got[1] {
		t.Errorf("overrides %q; the reconnect must send the same overrides", got)
	}
	if out, err := h.getSong(ctx, "Two"); err != nil || songOf(out) != "Two" {
		t.Fatalf("the call on the new connection = %v, %v", out, err)
	}
	if h.relay.Accepted() != 2 || h.dynamo.fromClient() != 0 {
		t.Errorf("accepted %d, client calls %d; want 2 and 0", h.relay.Accepted(), h.dynamo.fromClient())
	}
}

func keyIDs(tokens []string) []string {
	out := make([]string, len(tokens))
	for i, tok := range tokens {
		out[i] = keyID(tok)
	}
	return out
}

func TestDropRightAfterReplyReconnects(t *testing.T) {
	t.Parallel()
	h := newHarness(t)
	seed(t, h.dynamo)
	var n atomic.Int64
	n.Store(1)
	h.server.setWrap(func(_ mockcache.Command, answer func() resp.Value) mockcache.Reply {
		if n.Add(-1) >= 0 {
			return mockcache.DropAfter(answer())
		}
		return mockcache.Now(answer())
	})
	ctx := context.Background()
	if out, err := h.getSong(ctx, "One", once); err != nil || songOf(out) != "One" {
		t.Fatalf("the call answered before the drop = %v, %v", out, err)
	}
	eventually(t, "the reconnect", func() bool { return h.cache.Connections() == 2 })
	if out, err := h.getSong(ctx, "Two", once); err != nil || songOf(out) != "Two" {
		t.Fatalf("the call on the new connection = %v, %v", out, err)
	}
	if h.requests() != 2 || h.dynamo.fromClient() != 0 {
		t.Errorf("aws.requests %d, client calls %d; want 2 and 0", h.requests(), h.dynamo.fromClient())
	}
}

func TestDropDuringHandshakeIsRetried(t *testing.T) {
	t.Parallel()
	var dropped atomic.Bool
	h := newHarness(t, withCache(func(s *mockcache.Server) {
		s.SetIntercept(func(cmd mockcache.Command) *mockcache.Reply {
			if cmd.Name() == "AWS.CONFIG" && len(cmd.Args) == 2 && dropped.CompareAndSwap(false, true) {
				r := mockcache.Drop()
				return &r
			}
			return nil
		})
	}))
	seed(t, h.dynamo)
	if n := len(h.hellos()); n != 2 || h.cache.Connections() != 1 {
		t.Fatalf("HELLO %d times, %d configured connections; want 2 and 1", n, h.cache.Connections())
	}
	if h.getOne(t).Item == nil || h.requests() != 1 {
		t.Fatal("not served after the retried handshake")
	}
}

func TestFallbackDuringOutageThenCacheAgain(t *testing.T) {
	t.Parallel()
	h := newHarness(t, withRelay(), withOptions(func(o *elasticacheintegrations.Options) { o.ConnectionTimeout = time.Second }))
	seed(t, h.dynamo)
	ctx := context.Background()
	if _, err := h.getSong(ctx, "One"); err != nil {
		t.Fatal(err)
	}
	cut := time.Now()
	h.outage()
	routed := h.requests()
	// SDK retries stay on: the first call may be written before the client sees the reset, and
	// then it fails as lost and is retried.
	for i := range 5 {
		begin := time.Now()
		out, err := h.getSong(ctx, "Two")
		took := time.Since(begin)
		if err != nil || songOf(out) != "Two" {
			t.Fatalf("call %d during the outage = %v, %v; want DynamoDB's answer", i, out, err)
		}
		if i == 0 && time.Since(cut) < time.Second {
			t.Errorf("the first call went to DynamoDB %v into the outage; want it to wait the 1 s ConnectionTimeout",
				time.Since(cut))
		}
		if i > 0 && took > 500*time.Millisecond {
			t.Errorf("call %d during the outage took %v; only the first may wait for the reconnect", i, took)
		}
	}
	if h.requests() != routed || h.dynamo.fromClient() != 5 {
		t.Fatalf("during the outage: aws.requests %d (was %d), client calls %d; want unchanged and 5",
			h.requests(), routed, h.dynamo.fromClient())
	}
	h.restore()
	eventually(t, "the cache to serve again", func() bool {
		if _, err := h.getSong(ctx, "Three", once); err != nil {
			t.Fatal(err)
		}
		return h.requests() > routed
	})
}

func TestFallbackWindowEnds(t *testing.T) {
	t.Parallel()
	h := newHarness(t, withRelay(), withOptions(func(o *elasticacheintegrations.Options) {
		o.ConnectionTimeout = time.Second
		o.FallbackDuration = aws.Duration(time.Second)
	}))
	seed(t, h.dynamo)
	ctx := context.Background()
	if _, err := h.getSong(ctx, "One"); err != nil {
		t.Fatal(err)
	}
	begin := time.Now()
	h.outage()
	var fallbacks int
	var last error
	for time.Since(begin) < 5*time.Second {
		_, err := h.getSong(ctx, "Two") // retries on, as above
		if err != nil {
			last = err
			break
		}
		fallbacks++
		time.Sleep(20 * time.Millisecond)
	}
	if last == nil {
		t.Fatal("calls still go to DynamoDB 5 s into an outage with a 1 s wait and a 1 s fallback window")
	}
	errorAs[*elasticacheintegrations.CacheUnavailableError](t, last)
	if decided, retry := retryable(last); !decided || !retry {
		t.Errorf("retryable = %v, %v; want true, true", decided, retry)
	}
	if took := time.Since(begin); fallbacks == 0 || took < 2*time.Second {
		t.Errorf("%d calls went to DynamoDB in %v before the window ended; want some, over at least 2 s", fallbacks, took)
	}
	if h.dynamo.fromClient() != fallbacks {
		t.Errorf("client calls %d, want %d", h.dynamo.fromClient(), fallbacks)
	}
	h.restore()
	routed := h.requests()
	eventually(t, "the cache to serve again", func() bool {
		_, err := h.getSong(ctx, "Three", once)
		return err == nil && h.requests() > routed
	})
	// The next outage has a wait and a window of its own.
	cut := time.Now()
	h.outage()
	if out, err := h.getSong(ctx, "Two"); err != nil || songOf(out) != "Two" {
		t.Fatalf("the first call of a second outage = %v, %v; want DynamoDB's answer", out, err)
	}
	if took := time.Since(cut); took < time.Second {
		t.Errorf("the first call of a second outage went to DynamoDB after %v; want the 1 s wait", took)
	}
	if h.dynamo.fromClient() != fallbacks+1 {
		t.Errorf("client calls %d, want %d", h.dynamo.fromClient(), fallbacks+1)
	}
}

// TestCloseWithRequestsInFlight closes the client with calls in flight: each fails with a
// *ConnectionLostError that is not retried, SDK retries on or off, as the Python client does
// for a request failed by close(). A call after Close goes to DynamoDB.
func TestCloseWithRequestsInFlight(t *testing.T) {
	t.Parallel()
	const inFlight = 16
	for _, tc := range []struct {
		name    string
		closers int
		retries bool
	}{
		{"one_closer", 1, false},
		{"32_closers", 32, false},
		{"sdk_retries_on", 1, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			h := newHarness(t)
			seed(t, h.dynamo)
			deferred := make(chan *held, inFlight)
			h.server.setWrap(deferFirst(inFlight, deferred))
			ctx := context.Background()
			results := make(chan error, inFlight)
			for range inFlight {
				go func() {
					var optFns []func(*dynamodb.Options)
					if !tc.retries {
						optFns = append(optFns, once)
					}
					out, err := h.getSong(ctx, "One", optFns...)
					if err == nil && songOf(out) != "One" {
						err = errUnexpected(out)
					}
					results <- err
				}()
			}
			for range inFlight {
				<-deferred
			}
			begin := time.Now()
			var closers sync.WaitGroup
			for range tc.closers {
				closers.Add(1)
				go func() {
					defer closers.Done()
					if err := h.client.Close(); err != nil {
						t.Errorf("Close: %v", err)
					}
				}()
			}
			closers.Wait()
			if took := time.Since(begin); took > time.Second {
				t.Errorf("Close took %v with calls in flight; it must not wait for the cache", took)
			}
			for range inFlight {
				err := <-results
				errorAs[*elasticacheintegrations.ConnectionLostError](t, err)
				if decided, retry := retryable(err); !decided || retry {
					t.Errorf("retryable = %v, %v; want true, false", decided, retry)
				}
			}
			const want = 0
			if h.dynamo.fromClient() != want {
				t.Errorf("client calls %d, want %d", h.dynamo.fromClient(), want)
			}
			routed := h.requests()
			if out, err := h.getSong(ctx, "Two"); err != nil || songOf(out) != "Two" {
				t.Fatalf("a call after Close = %v, %v; want DynamoDB's answer", out, err)
			}
			if h.requests() != routed || h.dynamo.fromClient() != want+1 {
				t.Errorf("after Close: aws.requests %d (was %d), client calls %d; want unchanged and %d",
					h.requests(), routed, h.dynamo.fromClient(), want+1)
			}
		})
	}
}
