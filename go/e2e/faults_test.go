// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package e2e

import (
	"context"
	"fmt"
	"log/slog"
	"strings"
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
)

// failNext answers the next n aws.requests with reply and the rest in band.
func failNext(n *atomic.Int64, reply func() mockcache.Reply) func(mockcache.Command, func() resp.Value) mockcache.Reply {
	return func(_ mockcache.Command, answer func() resp.Value) mockcache.Reply {
		if n.Add(-1) >= 0 {
			return reply()
		}
		return mockcache.Now(answer())
	}
}

func TestHungCacheTimesOutAndReconnects(t *testing.T) {
	t.Parallel()
	h := newHarness(t, withOptions(func(o *elasticacheintegrations.Options) { o.CacheRequestTimeout = 200 * time.Millisecond }))
	seed(t, h.dynamo)
	var n atomic.Int64
	n.Store(1)
	h.proxy.setWrap(failNext(&n, mockcache.Hang))
	ctx := context.Background()
	begin := time.Now()
	_, err := h.getSong(ctx, "One", once)
	errorAs[*elasticacheintegrations.TimeoutError](t, err)
	if decided, retry := retryable(err); !decided || !retry {
		t.Errorf("retryable = %v, %v; want true, true", decided, retry)
	}
	if took := time.Since(begin); took > 2*time.Second {
		t.Errorf("the timeout took %v with a 200 ms CacheRequestTimeout", took)
	}
	eventually(t, "the reconnect", func() bool { return h.cache.Connections() == 2 })
	if out, err := h.getSong(ctx, "One"); err != nil || songOf(out) != "One" {
		t.Fatalf("the call on the new connection = %v, %v", out, err)
	}
	if h.dynamo.fromClient() != 0 {
		t.Errorf("the client sent %d calls to DynamoDB; a timeout must not fall back", h.dynamo.fromClient())
	}
}

func TestServedServerErrorRetriedThroughCache(t *testing.T) {
	t.Parallel()
	served500 := func() mockcache.Reply {
		return mockcache.Now(mockcache.Served(500, map[string]string{"content-type": "application/x-amz-json-1.0"},
			[]byte(`{"__type":"com.amazonaws.dynamodb.v20120810#InternalServerError","message":"retry"}`)))
	}
	ctx := context.Background()
	t.Run("then_served", func(t *testing.T) {
		t.Parallel()
		h := newHarness(t)
		seed(t, h.dynamo)
		var n atomic.Int64
		n.Store(1)
		h.proxy.setWrap(failNext(&n, served500))
		if out, err := h.getSong(ctx, "One"); err != nil || songOf(out) != "One" {
			t.Fatalf("GetItem = %v, %v", out, err)
		}
		if h.requests() != 2 || h.dynamo.fromClient() != 0 {
			t.Errorf("aws.requests %d, client calls %d; want 2 and 0", h.requests(), h.dynamo.fromClient())
		}
	})
	t.Run("always", func(t *testing.T) {
		t.Parallel()
		h := newHarness(t)
		var n atomic.Int64
		n.Store(1 << 30)
		h.proxy.setWrap(failNext(&n, served500))
		_, err := h.getSong(ctx, "One")
		errorAs[*types.InternalServerError](t, err)
		if h.requests() != 3 || h.dynamo.fromClient() != 0 {
			t.Errorf("aws.requests %d, client calls %d; want 3 and 0", h.requests(), h.dynamo.fromClient())
		}
	})
}

func TestCacheErrorCodes(t *testing.T) {
	t.Parallel()
	const (
		rejected = iota // terminal RejectedError, connection kept
		origin          // retryable OriginError, connection kept
		lost            // retryable ConnectionLostError, connection replaced
		toDynamo        // connection replaced, the call goes to DynamoDB from the client
	)
	errorReply := func(code string) func() mockcache.Reply {
		return func() mockcache.Reply { return mockcache.Now(resp.ErrorValue(code, "injected "+code)) }
	}
	for _, tc := range []struct {
		name  string
		reply func() mockcache.Reply
		kind  int
	}{
		{"UNSUPPORTED", errorReply("UNSUPPORTED"), rejected},
		{"PARSE_ERROR", errorReply("PARSE_ERROR"), rejected},
		{"UNIMPLEMENTED", errorReply("UNIMPLEMENTED"), rejected},
		{"REGION_MISMATCH", errorReply("REGION_MISMATCH"), rejected},
		{"ERR", errorReply("ERR"), rejected}, // a code the client does not know
		{"ORIGIN_ERROR", errorReply("ORIGIN_ERROR"), origin},
		{"INTERNAL_ERROR", errorReply("INTERNAL_ERROR"), origin},
		{"MOVED", errorReply("MOVED"), lost},
		{"NOAUTH", errorReply("NOAUTH"), lost},
		{"dropped", mockcache.Drop, lost},
		{"AUTH_ERROR", errorReply("AUTH_ERROR"), toDynamo},
	} {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			h := newHarness(t)
			seed(t, h.dynamo)
			var n atomic.Int64
			h.proxy.setWrap(failNext(&n, tc.reply))
			ctx := context.Background()
			for _, retries := range []bool{false, true} {
				n.Store(1)
				conns, before, clientCalls := h.cache.Connections(), h.requests(), h.dynamo.fromClient()
				var optFns []func(*dynamodb.Options)
				if !retries {
					optFns = append(optFns, once)
				}
				out, err := h.getSong(ctx, "One", optFns...)
				sent := h.requests() - before
				switch {
				case tc.kind == rejected:
					rej := errorAs[*elasticacheintegrations.RejectedError](t, err)
					if rej.Code != tc.name || sent != 1 {
						t.Errorf("code %q, aws.requests %d; want %s and 1", rej.Code, sent, tc.name)
					}
					if decided, retry := retryable(err); !decided || retry {
						t.Errorf("retryable = %v, %v; want true, false", decided, retry)
					}
				case tc.kind == toDynamo:
					if err != nil || songOf(out) != "One" || h.dynamo.fromClient() != clientCalls+1 || sent != 1 {
						t.Errorf("= %v, %v with %d aws.requests; want served by DynamoDB from the client after 1",
							out, err, sent)
					}
				case !retries:
					if tc.kind == origin {
						errorAs[*elasticacheintegrations.OriginError](t, err)
					} else {
						errorAs[*elasticacheintegrations.ConnectionLostError](t, err)
					}
					if decided, retry := retryable(err); !decided || !retry {
						t.Errorf("retryable = %v, %v; want true, true", decided, retry)
					}
				default:
					if err != nil || songOf(out) != "One" || sent != 2 {
						t.Errorf("with retries = %v, %v after %d aws.requests; want served after 2", out, err, sent)
					}
				}
				if tc.kind == lost || tc.kind == toDynamo {
					eventually(t, "the reconnect", func() bool { return h.cache.Connections() == conns+1 })
				} else if h.cache.Connections() != conns {
					t.Errorf("connections %d, want %d: the connection must stay", h.cache.Connections(), conns)
				}
			}
			if tc.kind != toDynamo && h.dynamo.fromClient() != 0 {
				t.Errorf("the client sent %d calls to DynamoDB", h.dynamo.fromClient())
			}
		})
	}
}

func TestSlotExhaustionWaitsThenGoesToOrigin(t *testing.T) {
	t.Parallel()
	const slots = 100
	for _, freed := range []bool{true, false} {
		name := map[bool]string{true: "slot_freed", false: "no_slot"}[freed]
		t.Run(name, func(t *testing.T) {
			t.Parallel()
			h := newHarness(t, withRetries(1),
				withOptions(func(o *elasticacheintegrations.Options) { o.CacheRequestTimeout = 5 * time.Second }))
			seed(t, h.dynamo)
			deferred := make(chan *held, slots)
			h.proxy.setWrap(deferFirst(slots, deferred))
			ctx := context.Background()
			var holders sync.WaitGroup
			holderErrs := make(chan error, slots)
			for range slots {
				holders.Add(1)
				go func() {
					defer holders.Done()
					if out, err := h.getSong(ctx, "One"); err != nil || songOf(out) != "One" {
						holderErrs <- fmt.Errorf("a holder got %v, %v", songOf(out), err)
					}
				}()
			}
			all := make([]*held, 0, slots)
			for range slots {
				all = append(all, <-deferred)
			}
			begin := time.Now()
			done := make(chan error, 1)
			go func() {
				out, err := h.getSong(ctx, "Two")
				if err == nil && songOf(out) != "Two" {
					err = fmt.Errorf("got %v", out)
				}
				done <- err
			}()
			if freed {
				time.Sleep(200 * time.Millisecond)
				select {
				case err := <-done:
					t.Fatalf("call %d returned %v with every slot held", slots+1, err)
				default:
				}
				if h.requests() != slots {
					t.Fatalf("aws.requests = %d with every slot held, want %d", h.requests(), slots)
				}
				all[0].release()
			}
			if err := <-done; err != nil {
				t.Fatalf("call %d = %v", slots+1, err)
			}
			took := time.Since(begin)
			if freed {
				if h.requests() != slots+1 || h.dynamo.fromClient() != 0 {
					t.Errorf("aws.requests %d, client calls %d; want %d and 0", h.requests(), h.dynamo.fromClient(), slots+1)
				}
			} else {
				if h.requests() != slots || h.dynamo.fromClient() != 1 {
					t.Errorf("aws.requests %d, client calls %d; want %d and 1", h.requests(), h.dynamo.fromClient(), slots)
				}
				if took < 3*time.Second {
					t.Errorf("call %d went to DynamoDB after %v; it must wait for a slot first (75%% of 5 s)", slots+1, took)
				}
			}
			if !freed {
				all[0].release()
			}
			for _, d := range all[1:] {
				d.release()
			}
			holders.Wait()
			close(holderErrs)
			for err := range holderErrs {
				t.Error(err)
			}
		})
	}
}

func TestOversizeReplyDropsConnection(t *testing.T) {
	t.Parallel()
	h := newHarness(t)
	seed(t, h.dynamo)
	var n atomic.Int64
	n.Store(1)
	h.proxy.setWrap(failNext(&n, func() mockcache.Reply {
		body := `{"Item":{"Artist":{"S":"Acme"},"Song":{"S":"` + strings.Repeat("x", 17<<20) + `"}}}`
		return mockcache.Now(mockcache.Served(200, map[string]string{"content-type": "application/x-amz-json-1.0"}, []byte(body)))
	}))
	ctx := context.Background()
	_, err := h.getSong(ctx, "One", once)
	errorAs[*elasticacheintegrations.ProtocolError](t, errorAs[*elasticacheintegrations.ConnectionLostError](t, err))
	eventually(t, "the reconnect", func() bool { return h.cache.Connections() == 2 })
	if out, err := h.getSong(ctx, "One"); err != nil || songOf(out) != "One" {
		t.Fatalf("the call on the new connection = %v, %v", out, err)
	}
}

func TestDeeplyNestedReplyDropsConnection(t *testing.T) {
	t.Parallel()
	h := newHarness(t)
	seed(t, h.dynamo)
	var n atomic.Int64
	n.Store(1)
	h.proxy.setWrap(failNext(&n, func() mockcache.Reply {
		return mockcache.Raw([]byte(strings.Repeat("*1\r\n", 101) + ":1\r\n"))
	}))
	ctx := context.Background()
	_, err := h.getSong(ctx, "One", once)
	errorAs[*elasticacheintegrations.ProtocolError](t, errorAs[*elasticacheintegrations.ConnectionLostError](t, err))
	eventually(t, "the reconnect", func() bool { return h.cache.Connections() == 2 })
	if out, err := h.getSong(ctx, "One"); err != nil || songOf(out) != "One" {
		t.Fatalf("the call on the new connection = %v, %v", out, err)
	}
}

func TestBlockedWriteTimesOutAndReconnects(t *testing.T) {
	t.Parallel()
	h := newHarness(t, withRelay(), withOptions(func(o *elasticacheintegrations.Options) {
		o.CacheRequestTimeout = 500 * time.Millisecond
	}))
	seed(t, h.dynamo)
	ctx := context.Background()
	if _, err := h.getSong(ctx, "One"); err != nil {
		t.Fatal(err)
	}
	// 14 MiB is more than the socket buffers on both sides of the relay hold, and under the
	// 16 MiB the client routes.
	h.relay.Pause()
	_, err := h.ddb.PutItem(ctx, &dynamodb.PutItemInput{TableName: aws.String("Music"),
		Item: map[string]types.AttributeValue{"Artist": sv("Big"), "Song": sv("Blob"), "Text": sv(strings.Repeat("x", 14<<20))}}, once)
	h.seen(err)
	// The write deadline and the reply deadline are the same instant, so either may close the
	// connection first; both fail the call with a TimeoutError.
	errorAs[*elasticacheintegrations.TimeoutError](t, err)
	h.relay.Resume()
	eventually(t, "the reconnect", func() bool { return h.cache.Connections() == 2 })
	if out, err := h.getSong(ctx, "One"); err != nil || songOf(out) != "One" {
		t.Fatalf("the call on the new connection = %v, %v", out, err)
	}
	// What the relay held reaches the cache once it resumes, and the cache reads the cut
	// connection to its end. A complete PutItem there would mean the write was not cut.
	eventually(t, "the cache to finish the cut connection", func() bool { return h.cache.Open() == 1 })
	for _, cmd := range h.cache.Requests() {
		if operation(cmd) == "PutItem" {
			t.Fatal("the whole PutItem reached the cache: the write never blocked")
		}
	}
	if h.dynamo.fromClient() != 0 {
		t.Errorf("the client sent %d calls to DynamoDB", h.dynamo.fromClient())
	}
}

// panicHandler is a slog handler that panics on every record and counts them.
type panicHandler struct{ calls *atomic.Int64 }

func (panicHandler) Enabled(context.Context, slog.Level) bool { return true }
func (h panicHandler) Handle(context.Context, slog.Record) error {
	h.calls.Add(1)
	panic("logger failure")
}
func (h panicHandler) WithAttrs([]slog.Attr) slog.Handler { return h }
func (h panicHandler) WithGroup(string) slog.Handler      { return h }

func TestPanickingLoggerDoesNotStopRecovery(t *testing.T) {
	t.Parallel()
	var calls atomic.Int64
	h := newHarness(t, withOptions(func(o *elasticacheintegrations.Options) {
		o.CacheRequestTimeout = 200 * time.Millisecond
		o.Logger = slog.New(panicHandler{&calls})
	}))
	seed(t, h.dynamo)
	var n atomic.Int64
	n.Store(1)
	h.proxy.setWrap(failNext(&n, mockcache.Hang))
	ctx := context.Background()
	_, err := h.getSong(ctx, "One", once)
	errorAs[*elasticacheintegrations.TimeoutError](t, err)
	eventually(t, "the reconnect", func() bool { return h.cache.Connections() == 2 })
	if out, err := h.getSong(ctx, "One"); err != nil || songOf(out) != "One" {
		t.Fatalf("the call on the new connection = %v, %v", out, err)
	}
	if calls.Load() == 0 {
		t.Fatal("the client never called the logger, so its panics were not tested")
	}
}
