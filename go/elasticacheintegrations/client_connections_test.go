// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"context"
	"errors"
	"slices"
	"sync/atomic"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"

	"github.com/awslabs/elasticache-integration-client/go/internal/mockcache"
	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
)

const hitBody = `{"Item":{"Artist":{"S":"Acme"}}}`

// connectionsSDK starts a client with n connections, or the default for 0, on a TLS mock cache
// that serves hitBody.
func connectionsSDK(t *testing.T, n int, tune func(*config)) (*mockcache.Server, *dynamodb.Client, *Client, *fakeOrigin) {
	t.Helper()
	s, addr := sdkMock(t)
	s.SetOnRequest(func(mockcache.Command) mockcache.Reply { return servedJSON(200, hitBody) })
	c := sdkConfig(t, s, addr)
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

func getHit(t *testing.T, ddb *dynamodb.Client) {
	t.Helper()
	out, err := ddb.GetItem(context.Background(), &dynamodb.GetItemInput{TableName: aws.String("Music"), Key: musicKey()})
	if err != nil {
		t.Fatal(err)
	}
	if got := attrS(t, out.Item, "Artist"); got != "Acme" {
		t.Fatalf("Artist = %q", got)
	}
}

// perConn counts the commands named name on each connection.
func perConn(s *mockcache.Server, name string) map[int]int {
	m := map[int]int{}
	for _, cmd := range s.Commands() {
		if cmd.Name() == name {
			m[cmd.Conn]++
		}
	}
	return m
}

func TestConnectionsOpensEachAndSpreadsCalls(t *testing.T) {
	s, ddb, cache, origin := connectionsSDK(t, 4, nil)
	if got := s.Connections(); got != 4 {
		t.Fatalf("configured connections = %d, want 4", got)
	}
	if hello := perConn(s, "HELLO"); len(hello) != 4 {
		t.Fatalf("HELLO per connection = %v", hello)
	}
	if len(cache.sessions) != 4 {
		t.Fatalf("sessions = %d", len(cache.sessions))
	}
	for range 8 {
		getHit(t, ddb)
	}
	got := perConn(s, "AWS.REQUEST")
	if len(got) != 4 {
		t.Fatalf("requests per connection = %v, want 2 on each of 4", got)
	}
	for conn, n := range got {
		if n != 2 {
			t.Fatalf("requests per connection = %v (connection %d), want 2 each", got, conn)
		}
	}
	if origin.count() != 0 {
		t.Fatalf("origin calls = %d", origin.count())
	}
}

// A call goes to the connection with the fewest calls in flight: while one call waits on a
// connection, the next calls all go to the other.
func TestConnectionsPickFewestInFlight(t *testing.T) {
	s, ddb, _, origin := connectionsSDK(t, 2, nil)
	held := make(chan resp.Value, 1)
	var heldConn atomic.Int64
	heldConn.Store(-1)
	s.SetOnRequest(func(cmd mockcache.Command) mockcache.Reply {
		if heldConn.CompareAndSwap(-1, int64(cmd.Conn)) {
			return mockcache.DeferredFrom(held)
		}
		return servedJSON(200, hitBody)
	})
	done := make(chan struct{})
	go func() { defer close(done); getHit(t, ddb) }()
	waitFor(t, "the first call to be in flight", func() bool { return heldConn.Load() >= 0 })
	for range 4 {
		getHit(t, ddb)
	}
	got := perConn(s, "AWS.REQUEST")
	if got[int(heldConn.Load())] != 1 || len(got) != 2 {
		t.Fatalf("requests per connection = %v, want 1 on the held connection %d", got, heldConn.Load())
	}
	held <- mockcache.Served(200, map[string]string{"content-type": "application/x-amz-json-1.0", "x-amz-crc32": crc(hitBody)}, []byte(hitBody))
	<-done
	if origin.count() != 0 {
		t.Fatalf("origin calls = %d", origin.count())
	}
}

// A connection that fails every call at once has the fewest calls in flight. Calls must not
// all go to it: after a call fails there, the next ones go to the other connection, and the
// SDK's retry of the failed call succeeds there.
func TestConnectionsAvoidConnectionThatJustFailed(t *testing.T) {
	s, ddb, cache, origin := connectionsSDK(t, 2, nil)
	held := make(chan resp.Value, 1)
	var heldConn atomic.Int64
	heldConn.Store(-1)
	s.SetOnRequest(func(cmd mockcache.Command) mockcache.Reply {
		if heldConn.CompareAndSwap(-1, int64(cmd.Conn)) {
			return mockcache.DeferredFrom(held)
		}
		if int64(cmd.Conn) != heldConn.Load() {
			return mockcache.Now(errorReply("INTERNAL_ERROR", "broken"))
		}
		return servedJSON(200, hitBody)
	})
	done := make(chan struct{})
	go func() { defer close(done); getHit(t, ddb) }()
	waitFor(t, "the first call to be in flight", func() bool { return heldConn.Load() >= 0 })
	h := ddb.Options().HTTPClient.(*httpClient)
	failures := 0
	for range 6 {
		res, err := h.Do(getItemRequest(t))
		if err != nil {
			failures++
			continue
		}
		_ = res.Body.Close()
	}
	got := perConn(s, "AWS.REQUEST")
	if failures != 1 || got[int(heldConn.Load())] != 6 {
		t.Fatalf("failures = %d, requests per connection = %v, want 1 failure and 5 more on the held connection %d",
			failures, got, heldConn.Load())
	}
	getHit(t, ddb)
	for _, ss := range cache.sessions {
		if at := ss.failedAt.Load(); at != 0 {
			ss.failedAt.Store(at - int64(failedAvoid)) // the failure is older than failedAvoid
		}
	}
	if _, err := h.Do(getItemRequest(t)); err == nil {
		t.Fatal("a call after failedAvoid did not go back to the idle connection")
	}
	held <- mockcache.Served(200, map[string]string{"content-type": "application/x-amz-json-1.0", "x-amz-crc32": crc(hitBody)}, []byte(hitBody))
	<-done
	if origin.count() != 0 {
		t.Fatalf("origin calls = %d", origin.count())
	}
	for i, ss := range cache.sessions {
		if n := ss.inflight.Load(); n != 0 {
			t.Errorf("session %d has %d calls in flight after every call returned", i, n)
		}
	}
}

// A call whose context ends while it waits on a connection no longer counts there.
func TestConnectionsInFlightCountsOnlyLiveCalls(t *testing.T) {
	s, ddb, cache, _ := connectionsSDK(t, 2, nil)
	s.SetOnRequest(func(mockcache.Command) mockcache.Reply { return mockcache.Hang() })
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() {
		_, err := ddb.GetItem(ctx, &dynamodb.GetItemInput{TableName: aws.String("Music"), Key: musicKey()})
		done <- err
	}()
	waitFor(t, "the call to be in flight", func() bool { return len(perConn(s, "AWS.REQUEST")) == 1 })
	cancel()
	if err := <-done; !errors.Is(err, context.Canceled) {
		t.Fatalf("GetItem = %v, want context.Canceled", err)
	}
	for i, ss := range cache.sessions {
		if n := ss.inflight.Load(); n != 0 {
			t.Errorf("session %d has %d calls in flight after the call returned", i, n)
		}
	}
}

func TestConnectionsDefaultIsFour(t *testing.T) {
	s, ddb, cache, _ := connectionsSDK(t, 0, nil)
	getHit(t, ddb)
	if s.Connections() != 4 || len(cache.sessions) != 4 {
		t.Fatalf("connections = %d, sessions = %d", s.Connections(), len(cache.sessions))
	}
	for _, name := range []string{"HELLO", "AWS.CONFIG"} {
		if got := perConn(s, name); len(got) != 4 {
			t.Fatalf("%s per connection = %v, want one on each of 4", name, got)
		}
	}
}

// Start-up opens the default four connections at once: each HELLO takes 400 ms, so four in a
// row would take longer than the 1 s ConnectionTimeout.
func TestConnectionsDefaultOpenAtOnce(t *testing.T) {
	s, addr := sdkMock(t)
	s.SetOnRequest(func(mockcache.Command) mockcache.Reply { return servedJSON(200, hitBody) })
	intercept(s, "HELLO", mockcache.After(400*time.Millisecond, mockcache.Hello()))
	c := sdkConfig(t, s, addr)
	c.connectionTimeout = time.Second
	begin := time.Now()
	ddb, cache := startSDK(t, c, &fakeOrigin{})
	if took := time.Since(begin); took >= c.connectionTimeout {
		t.Fatalf("start-up took %v, want under ConnectionTimeout", took)
	}
	getHit(t, ddb)
	if s.Connections() != 4 || len(cache.sessions) != 4 {
		t.Fatalf("connections = %d, sessions = %d", s.Connections(), len(cache.sessions))
	}
}

func TestConnectionsReauthEach(t *testing.T) {
	s, _, _, _ := connectionsSDK(t, 3, func(c *config) { c.reauthInterval = 20 * time.Millisecond })
	waitFor(t, "a re-AUTH on every connection", func() bool {
		auth := perConn(s, "AUTH")
		return len(auth) == 3
	})
}

// One connection drops; calls keep being served by the others while it reconnects, and the
// call in flight on it is retried on another.
func TestConnectionsDropOneKeepsServing(t *testing.T) {
	s, ddb, cache, origin := connectionsSDK(t, 3, nil)
	var dropped atomic.Bool
	s.SetOnRequest(func(cmd mockcache.Command) mockcache.Reply {
		if cmd.Conn == 2 && dropped.CompareAndSwap(false, true) {
			return mockcache.Drop()
		}
		return servedJSON(200, hitBody)
	})
	for range 12 {
		getHit(t, ddb)
	}
	if !dropped.Load() {
		t.Fatal("connection 2 got no request")
	}
	if origin.count() != 0 {
		t.Fatalf("origin calls = %d, want 0", origin.count())
	}
	waitFor(t, "the dropped connection to reconnect", func() bool { return s.Connections() == 4 })
	waitFor(t, "every session to be connected", func() bool {
		for _, ss := range cache.sessions {
			if !ss.connected() {
				return false
			}
		}
		return true
	})
}

// With every connection down, the picked session's outage decides: fallback to DynamoDB, or
// in strict mode a *CacheUnavailableError and nothing sent to DynamoDB.
func TestConnectionsAllDown(t *testing.T) {
	for _, strict := range []bool{false, true} {
		t.Run(map[bool]string{false: "fallback", true: "strict"}[strict], func(t *testing.T) {
			s, ddb, _, origin := connectionsSDK(t, 2, func(c *config) {
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
	}
}

func TestConnectionsCloseClosesAll(t *testing.T) {
	s, ddb, cache, origin := connectionsSDK(t, 4, nil)
	getHit(t, ddb)
	if err := cache.Close(); err != nil {
		t.Fatal(err)
	}
	for i, ss := range cache.sessions {
		if !ss.closed() {
			t.Fatalf("session %d not closed", i)
		}
	}
	waitFor(t, "the mock to see every connection end", func() bool { return s.Open() == 0 })
	if _, err := ddb.GetItem(context.Background(), &dynamodb.GetItemInput{TableName: aws.String("Music"), Key: musicKey()}); err != nil {
		t.Fatal(err)
	}
	if origin.count() != 1 {
		t.Fatalf("origin calls after Close = %d, want 1", origin.count())
	}
}

// A connection that cannot come up fails start-up and leaves nothing open.
func TestConnectionsStartFailureClosesAll(t *testing.T) {
	s, addr := sdkMock(t)
	var hellos atomic.Int32
	s.SetIntercept(func(cmd mockcache.Command) *mockcache.Reply {
		if cmd.Name() == "HELLO" && hellos.Add(1) > 2 {
			r := mockcache.Drop()
			return &r
		}
		return nil
	})
	c := sdkConfig(t, s, addr)
	c.connections = 4
	c.connectionTimeout = 200 * time.Millisecond
	ddb, cache, err := start(context.Background(), c, func(fns ...func(*dynamodb.Options)) *dynamodb.Client {
		return dynamodb.NewFromConfig(awsConfig(), fns...)
	}, []func(*dynamodb.Options){func(o *dynamodb.Options) { o.HTTPClient = &fakeOrigin{} }})
	if err == nil || ddb != nil || cache != nil {
		t.Fatalf("start = %v, %v, %v; want an error", ddb, cache, err)
	}
	var ce *ConnectionError
	if !errors.As(err, &ce) {
		t.Fatalf("err = %v, want *ConnectionError", err)
	}
	waitFor(t, "every connection to close", func() bool { return s.Open() == 0 })
}

// While one connection reconnects, calls never wait for it: the other connection serves them.
func TestConnectionsNeverWaitOnAReconnectingSession(t *testing.T) {
	s, ddb, cache, origin := connectionsSDK(t, 2, nil) // ConnectionTimeout 5s: a wait would show
	s.Hold()
	defer s.Release()
	var dropped atomic.Bool
	s.SetOnRequest(func(cmd mockcache.Command) mockcache.Reply {
		if cmd.Conn == 1 && dropped.CompareAndSwap(false, true) {
			return mockcache.Drop()
		}
		return servedJSON(200, hitBody)
	})
	start := time.Now()
	for range 10 {
		getHit(t, ddb)
	}
	if !dropped.Load() {
		t.Fatal("connection 1 got no request")
	}
	if took := time.Since(start); took > 2*time.Second {
		t.Fatalf("10 calls took %v: a call waited for the reconnecting session", took)
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
			t.Fatalf("a call after the drop went to connection %d", cmd.Conn)
		}
	}
}

// A session that loses its connection between the pick and the call does not make the call
// wait: tryRequest reports it, and another session serves it.
func TestTryRequestDoesNotWait(t *testing.T) {
	s, ddb, cache, _ := connectionsSDK(t, 2, nil)
	getHit(t, ddb)
	s.Hold()
	defer s.Release()
	ss := cache.sessions[0]
	ss.live.Load().drop("test")
	waitFor(t, "session 1 to see the drop", func() bool { return !ss.connected() })
	r := getItem("https://dynamodb.us-east-1.amazonaws.com/")
	if _, ok := ss.tryRequest(context.Background(), r, false); ok {
		t.Fatal("tryRequest without a connection returned an outcome")
	}
}

// With every connection down, the fallback window counts from the last drop, as with one
// connection: a session whose own window ran out while another served calls does not decide a
// call that one connection would send to DynamoDB.
func TestConnectionsAllDownFallbackFromLastDrop(t *testing.T) {
	s, ddb, cache, _ := connectionsSDK(t, 3, func(c *config) {
		c.connectionTimeout = 20 * time.Millisecond
		c.reconnectAttemptTimeout = 20 * time.Millisecond
		c.fallbackUnlimited, c.fallbackDuration = false, 300*time.Millisecond
	})
	getHit(t, ddb)
	s.Hold()
	defer s.Release()
	first := cache.sessions[0]
	first.live.Load().drop("test")
	waitFor(t, "session 1 to see the drop", func() bool { return !first.connected() })
	// The outage starts, and its fallback window ends while the other sessions are up.
	first.request(context.Background(), getItem("https://dynamodb.us-east-1.amazonaws.com/"))
	time.Sleep(400 * time.Millisecond)
	for _, ss := range cache.sessions[1:] {
		ss.live.Load().drop("test")
	}
	for i := range 6 {
		o := cache.request(context.Background(), getItem("https://dynamodb.us-east-1.amazonaws.com/"))
		if o.kind != outcomeOrigin {
			t.Fatalf("call %d: outcome %v, err %v; want DynamoDB inside the fallback window", i, o.kind, o.err)
		}
	}
}
