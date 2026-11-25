// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"context"
	"errors"
	"fmt"
	"sync/atomic"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"

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

// getHit fails the test unless one GetItem returns the hit item. Call it only from the test
// goroutine; another goroutine uses hitErr.
func getHit(t *testing.T, ddb *dynamodb.Client) {
	t.Helper()
	if err := hitErr(ddb); err != nil {
		t.Fatal(err)
	}
}

// hitErr makes one GetItem and reports an error unless it returns the hit item.
func hitErr(ddb *dynamodb.Client) error {
	out, err := ddb.GetItem(context.Background(), &dynamodb.GetItemInput{TableName: aws.String("Music"), Key: musicKey()})
	if err != nil {
		return err
	}
	if v, ok := out.Item["Artist"].(*types.AttributeValueMemberS); !ok || v.Value != "Acme" {
		return fmt.Errorf("GetItem Artist = %#v, want \"Acme\"", out.Item["Artist"])
	}
	return nil
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
	done := make(chan error, 1)
	go func() { done <- hitErr(ddb) }()
	waitFor(t, "the first call to be in flight", func() bool { return heldConn.Load() >= 0 })
	for range 4 {
		getHit(t, ddb)
	}
	got := perConn(s, "AWS.REQUEST")
	if got[int(heldConn.Load())] != 1 || len(got) != 2 {
		t.Fatalf("requests per connection = %v, want 1 on the held connection %d", got, heldConn.Load())
	}
	held <- mockcache.Served(200, map[string]string{"content-type": "application/x-amz-json-1.0", "x-amz-crc32": crc(hitBody)}, []byte(hitBody))
	if err := <-done; err != nil {
		t.Fatalf("the held call: %v", err)
	}
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
	done := make(chan error, 1)
	go func() { done <- hitErr(ddb) }()
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
	if err := <-done; err != nil {
		t.Fatalf("the held call: %v", err)
	}
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
