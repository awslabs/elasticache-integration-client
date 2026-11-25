// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

//go:build go1.25

package elasticacheintegrations

import (
	"context"
	"errors"
	"slices"
	"sync/atomic"
	"testing"
	"testing/synctest"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"

	"github.com/awslabs/elasticache-integration-client/go/internal/mockcache"
)

// pipeConnectionsSDK is connectionsSDK on a TLS mock cache served over net.Pipe, for synctest
// bubbles.
func pipeConnectionsSDK(t *testing.T, n int, tune func(*config)) (*mockcache.Server, *dynamodb.Client, *Client, *fakeOrigin) {
	t.Helper()
	s, c := pipeMock(t, mockcache.Config{TLS: true})
	s.SetOnRequest(func(mockcache.Command) mockcache.Reply { return servedJSON(200, hitBody) })
	if n != 0 {
		c.connections = n
	}
	if tune != nil {
		tune(c)
	}
	origin := &fakeOrigin{}
	ddb, cache := startSDK(t, c, origin)
	return s, ddb, cache, origin
}

// Start-up opens the default four connections at once: each HELLO takes 400 ms, so start-up
// takes 400 ms, not 1.6 s.
func TestConnectionsDefaultOpenAtOnce(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s, c := pipeMock(t, mockcache.Config{TLS: true})
		s.SetOnRequest(func(mockcache.Command) mockcache.Reply { return servedJSON(200, hitBody) })
		intercept(s, "HELLO", mockcache.After(400*time.Millisecond, mockcache.Hello()))
		c.connectionTimeout = time.Second
		begin := time.Now()
		ddb, cache := startSDK(t, c, &fakeOrigin{})
		if took := time.Since(begin); took != 400*time.Millisecond {
			t.Fatalf("start-up took %v, want 400ms", took)
		}
		getHit(t, ddb)
		if s.Connections() != 4 || len(cache.sessions) != 4 {
			t.Fatalf("connections = %d, sessions = %d; want 4 and 4", s.Connections(), len(cache.sessions))
		}
	})
}

// With every connection down, the picked session's outage decides: fallback to DynamoDB, or
// in strict mode a *CacheUnavailableError and nothing sent to DynamoDB.
func TestConnectionsAllDown(t *testing.T) {
	for _, strict := range []bool{false, true} {
		t.Run(map[bool]string{false: "fallback", true: "strict"}[strict], func(t *testing.T) {
			synctest.Test(t, func(t *testing.T) {
				s, ddb, _, origin := pipeConnectionsSDK(t, 2, func(c *config) {
					c.connectionTimeout = 50 * time.Millisecond
					c.reconnectAttemptTimeout = 50 * time.Millisecond
					if strict {
						c.fallbackUnlimited, c.fallbackDuration = false, 0
					}
				})
				s.Hold()
				defer s.Release()
				s.DropAll()
				out, err := ddb.GetItem(context.Background(), &dynamodb.GetItemInput{TableName: aws.String("Music"), Key: musicKey()})
				if strict {
					if !errors.As(err, new(*CacheUnavailableError)) || origin.count() != 0 {
						t.Fatalf("err = %v, origin calls = %d; want a *CacheUnavailableError and none", err, origin.count())
					}
					return
				}
				if err != nil {
					t.Fatal(err)
				}
				if out.Item != nil || origin.count() == 0 {
					t.Fatalf("item = %v, origin calls = %d; want the origin's empty reply", out.Item, origin.count())
				}
			})
		})
	}
}

// While one connection reconnects, calls never wait for it: the other connection serves them
// at once. The calls start after failedAvoid, so the reconnecting session is not passed over
// for its failed call.
func TestConnectionsNeverWaitOnAReconnectingSession(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s, ddb, cache, origin := pipeConnectionsSDK(t, 2, nil) // ConnectionTimeout 5s: a wait would show
		s.Hold()
		defer s.Release()
		var dropped atomic.Bool
		s.SetOnRequest(func(cmd mockcache.Command) mockcache.Reply {
			if cmd.Conn == 1 && dropped.CompareAndSwap(false, true) {
				return mockcache.Drop()
			}
			return servedJSON(200, hitBody)
		})
		for range 2 {
			getHit(t, ddb)
		}
		if !dropped.Load() {
			t.Fatal("connection 1 got no request")
		}
		time.Sleep(failedAvoid)
		start := time.Now()
		for range 10 {
			getHit(t, ddb)
		}
		if took := time.Since(start); took != 0 {
			t.Fatalf("10 calls took %v, want 0: a call waited for the reconnecting session", took)
		}
		if cache.sessions[0].connected() && cache.sessions[1].connected() {
			t.Fatal("the dropped session reconnected while new connections are held")
		}
		if origin.count() != 0 {
			t.Fatalf("origin calls = %d, want 0", origin.count())
		}
		reqs := s.Requests()
		after := slices.IndexFunc(reqs, func(c mockcache.Command) bool { return c.Conn == 1 }) + 1
		for _, cmd := range reqs[after:] {
			if cmd.Conn != 2 {
				t.Fatalf("a call after the drop went to connection %d, want 2", cmd.Conn)
			}
		}
	})
}

// With every connection down, the fallback window counts from the last drop, as with one
// connection: a session whose own window ran out while another served calls does not decide a
// call that one connection would send to DynamoDB.
func TestConnectionsAllDownFallbackFromLastDrop(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		const window = 300 * time.Millisecond
		s, ddb, cache, _ := pipeConnectionsSDK(t, 3, func(c *config) {
			c.connectionTimeout = 20 * time.Millisecond
			c.reconnectAttemptTimeout = 20 * time.Millisecond
			c.fallbackUnlimited, c.fallbackDuration = false, window
		})
		getHit(t, ddb)
		s.Hold()
		defer s.Release()
		first := cache.sessions[0]
		first.live.Load().drop("test")
		synctest.Wait()
		if first.connected() {
			t.Fatal("session 1 is connected after its drop")
		}
		// The outage starts, and its fallback window ends while the other sessions are up.
		r := getItem("https://dynamodb.us-east-1.amazonaws.com/")
		if o := first.request(context.Background(), r); o.kind != outcomeOrigin {
			t.Fatalf("session 1 at the drop: outcome %v, err %v; want DynamoDB", o.kind, o.err)
		}
		time.Sleep(window + time.Millisecond)
		if o := first.request(context.Background(), r); o.kind == outcomeOrigin {
			t.Fatal("session 1 after its window: DynamoDB, want its window spent")
		}
		for _, ss := range cache.sessions[1:] {
			ss.live.Load().drop("test")
		}
		synctest.Wait()
		for i := range 6 {
			o := cache.request(context.Background(), getItem("https://dynamodb.us-east-1.amazonaws.com/"))
			if o.kind != outcomeOrigin {
				t.Fatalf("call %d: outcome %v, err %v; want DynamoDB inside the fallback window", i, o.kind, o.err)
			}
		}
	})
}
