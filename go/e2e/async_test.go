// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package e2e

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"

	"github.com/awslabs/elasticache-integration-client/go/elasticacheintegrations"
	"github.com/awslabs/elasticache-integration-client/go/internal/mockcache"
	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
	"github.com/awslabs/elasticache-integration-client/go/internal/testutil"
)

// held is a deferred reply: the cache sent +<id>, and the push carries value once released.
type held struct {
	value resp.Value
	push  chan resp.Value
}

func (d *held) release() { d.push <- d.value }

// deferFirst defers the first n aws.requests; later ones are answered in band. held receives
// each deferred reply in arrival order.
func deferFirst(n int, held chan<- *held) func(mockcache.Command, func() resp.Value) mockcache.Reply {
	var count atomic.Int64
	return func(_ mockcache.Command, answer func() resp.Value) mockcache.Reply {
		if count.Add(1) > int64(n) {
			return mockcache.Now(answer())
		}
		d := newHeld(answer())
		held <- d
		return mockcache.DeferredFrom(d.push)
	}
}

func newHeld(v resp.Value) *held { return &held{value: v, push: make(chan resp.Value, 1)} }

// eventLatePushDropped is the client's log event for a push whose request is no longer pending.
const eventLatePushDropped = 1203

// logged reports whether the client logged a record with event id ev.
func (h *harness) logged(ev int64) bool {
	for _, r := range h.logs.Records() {
		if v, ok := testutil.Attr(r, "event"); ok && v.Kind() == slog.KindInt64 && v.Int64() == ev {
			return true
		}
	}
	return false
}

// getSong reads Acme/<song>.
func (h *harness) getSong(ctx context.Context, song string, optFns ...func(*dynamodb.Options)) (*dynamodb.GetItemOutput, error) {
	out, err := h.ddb.GetItem(ctx, &dynamodb.GetItemInput{TableName: aws.String("Music"), Key: songKey("Acme", song)}, optFns...)
	return out, h.seen(err)
}

// songOf is the Song of a GetItem output, or "" without an item.
func songOf(out *dynamodb.GetItemOutput) string {
	if out == nil || out.Item == nil {
		return ""
	}
	if v, ok := out.Item["Song"].(*types.AttributeValueMemberS); ok {
		return v.Value
	}
	return ""
}

func TestDeferredReplyDoesNotBlockLaterCalls(t *testing.T) {
	t.Parallel()
	h := newHarness(t)
	seed(t, h.dynamo)
	deferred := make(chan *held, 1)
	h.proxy.setWrap(deferFirst(1, deferred))
	ctx := context.Background()
	done := make(chan string, 1)
	go func() {
		out, err := h.getSong(ctx, "One")
		if err != nil {
			done <- "error: " + err.Error()
			return
		}
		done <- songOf(out)
	}()
	first := <-deferred
	out, err := h.getSong(ctx, "Two")
	if err != nil || songOf(out) != "Two" {
		t.Fatalf("the call behind the deferred one = %v, %v", out, err)
	}
	select {
	case got := <-done:
		t.Fatalf("the deferred call returned %q before its push", got)
	default:
	}
	first.release()
	if got := <-done; got != "One" {
		t.Fatalf("the deferred call got %q, want One", got)
	}
	if h.requests() != 2 || h.cache.Connections() != 1 || h.dynamo.fromClient() != 0 {
		t.Errorf("aws.requests %d, connections %d, client calls %d; want 2, 1, 0",
			h.requests(), h.cache.Connections(), h.dynamo.fromClient())
	}
}

func TestOutOfOrderPushesReachTheirCallers(t *testing.T) {
	t.Parallel()
	const n = 64
	h := newHarness(t)
	for i := range n {
		h.dynamo.put(t, "Music", item{"Artist": sj("Acme"), "Song": sj(fmt.Sprintf("S%02d", i))})
	}
	deferred := make(chan *held, n)
	h.proxy.setWrap(deferFirst(n, deferred))
	ctx := context.Background()
	var wg sync.WaitGroup
	errs := make(chan error, n)
	for i := range n {
		wg.Add(1)
		go func() {
			defer wg.Done()
			want := fmt.Sprintf("S%02d", i)
			out, err := h.getSong(ctx, want)
			if err != nil || songOf(out) != want {
				errs <- fmt.Errorf("caller %s got %v, %v", want, songOf(out), err)
			}
		}()
	}
	all := make([]*held, 0, n)
	for range n {
		all = append(all, <-deferred)
	}
	for i := n - 1; i >= 0; i-- {
		all[i].release()
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Error(err)
	}
	// One request per caller: a retry would be answered in band, not by its push.
	if h.requests() != n || h.cache.Connections() != 1 || h.dynamo.fromClient() != 0 {
		t.Errorf("aws.requests %d, connections %d, client calls %d; want %d, 1, 0",
			h.requests(), h.cache.Connections(), h.dynamo.fromClient(), n)
	}
}

func TestDeferredTimeoutRetriesThroughCache(t *testing.T) {
	t.Parallel()
	h := newHarness(t, withOptions(func(o *elasticacheintegrations.Options) { o.CacheRequestTimeout = 300 * time.Millisecond }))
	seed(t, h.dynamo)
	deferred := make(chan *held, 1)
	h.proxy.setWrap(deferFirst(1, deferred))
	ctx := context.Background()
	out, err := h.getSong(ctx, "One")
	if err != nil || songOf(out) != "One" {
		t.Fatalf("GetItem = %v, %v; want the retry served", out, err)
	}
	if h.requests() != 2 {
		t.Fatalf("aws.requests = %d, want 2 (the timed-out attempt and its retry)", h.requests())
	}
	(<-deferred).release() // a late push for an id that is no longer pending
	eventually(t, "the client to drop the late push", func() bool { return h.logged(eventLatePushDropped) })
	if out, err := h.getSong(ctx, "Two"); err != nil || songOf(out) != "Two" {
		t.Fatalf("the call after the late push = %v, %v", out, err)
	}
	if h.cache.Connections() != 1 || h.dynamo.fromClient() != 0 {
		t.Errorf("connections %d, client calls %d; want 1 and 0", h.cache.Connections(), h.dynamo.fromClient())
	}
}

func TestPushedErrorFailsOnlyItsCall(t *testing.T) {
	t.Parallel()
	h := newHarness(t)
	seed(t, h.dynamo)
	const id = "9223372036854775807"
	var first atomic.Bool
	h.proxy.setWrap(func(_ mockcache.Command, answer func() resp.Value) mockcache.Reply {
		if first.CompareAndSwap(false, true) {
			return mockcache.Raw([]byte("+" + id + "\r\n"))
		}
		return mockcache.Now(answer())
	})
	ctx := context.Background()
	done := make(chan error, 1)
	go func() {
		_, err := h.getSong(ctx, "One", once)
		done <- err
	}()
	eventually(t, "the first request", func() bool { return h.requests() == 1 })
	if out, err := h.getSong(ctx, "Two"); err != nil || songOf(out) != "Two" {
		t.Fatalf("the parallel call = %v, %v", out, err)
	}
	if err := h.cache.Push(mockcache.ResponsePush(id, resp.ErrorValue("ORIGIN_ERROR", "DynamoDB timed out"))); err != nil {
		t.Fatal(err)
	}
	err := <-done
	errorAs[*elasticacheintegrations.OriginError](t, err)
	if decided, retry := retryable(err); !decided || !retry {
		t.Errorf("retryable = %v, %v; want true, true", decided, retry)
	}
	if out, err := h.getSong(ctx, "Three"); err != nil || songOf(out) != "Three" {
		t.Fatalf("the call after the pushed error = %v, %v", out, err)
	}
	if h.cache.Connections() != 1 {
		t.Errorf("connections = %d; a pushed error must keep the connection", h.cache.Connections())
	}
}

func TestCancelledCallLeavesConnectionUsable(t *testing.T) {
	t.Parallel()
	for _, tc := range []struct {
		name  string
		reply func(v resp.Value) mockcache.Reply
	}{
		{"in_order", func(v resp.Value) mockcache.Reply { return mockcache.After(300*time.Millisecond, v) }},
		{"deferred", func(resp.Value) mockcache.Reply { return mockcache.DeferredFrom(make(chan resp.Value)) }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			h := newHarness(t)
			seed(t, h.dynamo)
			var first atomic.Bool
			h.proxy.setWrap(func(_ mockcache.Command, answer func() resp.Value) mockcache.Reply {
				if first.CompareAndSwap(false, true) {
					return tc.reply(answer())
				}
				return mockcache.Now(answer())
			})
			ctx, cancel := context.WithCancel(context.Background())
			done := make(chan error, 1)
			go func() {
				_, err := h.getSong(ctx, "One")
				done <- err
			}()
			eventually(t, "the cache to hold the request", func() bool { return h.requests() == 1 })
			cancel()
			select {
			case err := <-done:
				if !errors.Is(err, context.Canceled) {
					t.Fatalf("the cancelled call = %v, want context.Canceled", err)
				}
			case <-time.After(200 * time.Millisecond):
				t.Fatal("the cancelled call did not return at once")
			}
			if out, err := h.getSong(context.Background(), "Two"); err != nil || songOf(out) != "Two" {
				t.Fatalf("the call after the cancelled one = %v, %v", out, err)
			}
			if h.requests() != 2 || h.cache.Connections() != 1 || h.dynamo.fromClient() != 0 {
				t.Errorf("aws.requests %d, connections %d, client calls %d; want 2, 1, 0",
					h.requests(), h.cache.Connections(), h.dynamo.fromClient())
			}
		})
	}
}

func TestBadPushDropsConnection(t *testing.T) {
	t.Parallel()
	for _, tc := range []struct {
		name string
		push resp.Value
	}{
		{"unknown_kind", resp.PushValue(resp.BulkStringValue("invalidate"), resp.ArrayValue(resp.BulkStringValue("k")))},
		{"malformed", resp.PushValue(resp.BulkStringValue("aws.response"), resp.BulkStringValue("1"))},
	} {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			h := newHarness(t)
			seed(t, h.dynamo)
			deferred := make(chan *held, 2)
			h.proxy.setWrap(deferFirst(2, deferred))
			ctx := context.Background()
			for _, retries := range []bool{false, true} {
				conns := h.cache.Connections()
				done := make(chan error, 1)
				go func() {
					var optFns []func(*dynamodb.Options)
					if !retries {
						optFns = append(optFns, once)
					}
					out, err := h.getSong(ctx, "One", optFns...)
					if err == nil && songOf(out) != "One" {
						err = fmt.Errorf("got %v", out)
					}
					done <- err
				}()
				<-deferred
				if err := h.cache.Push(tc.push); err != nil {
					t.Fatal(err)
				}
				err := <-done
				if retries {
					if err != nil {
						t.Fatalf("with retries: %v, want the retry served", err)
					}
				} else {
					errorAs[*elasticacheintegrations.ProtocolError](t, errorAs[*elasticacheintegrations.ConnectionLostError](t, err))
					if decided, retry := retryable(err); !decided || !retry {
						t.Errorf("retryable = %v, %v; want true, true", decided, retry)
					}
				}
				eventually(t, "the reconnect", func() bool { return h.cache.Connections() == conns+1 })
			}
			if out, err := h.getSong(ctx, "Two"); err != nil || songOf(out) != "Two" {
				t.Fatalf("the call on the new connection = %v, %v", out, err)
			}
		})
	}
}
