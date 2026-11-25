// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"log/slog"
	"math/rand/v2"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/awslabs/elasticache-integration-client/go/internal/mockcache"
	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
	"github.com/awslabs/elasticache-integration-client/go/internal/testutil"
)

func getItem(url string) *request {
	return &request{
		method:   "POST",
		url:      url,
		headers:  map[string]string{"Content-Type": "application/x-amz-json-1.0", "X-Amz-Target": "DynamoDB_20120810.GetItem"},
		body:     []byte(`{"TableName":"t"}`),
		metadata: newRequestMetadata("dynamodb", "GetItem"),
	}
}

func TestRequestRoundTrip(t *testing.T) {
	s, addr := startMock(t, mockcache.Config{TLS: true})
	s.SetOnRequest(func(cmd mockcache.Command) mockcache.Reply {
		body := mockcache.Served(200, map[string]string{"x-amz-crc32": "1"}, []byte(cmd.Arg(2)))
		switch rand.IntN(3) {
		case 0:
			return mockcache.Now(body)
		case 1:
			return mockcache.Deferred(body)
		default:
			return mockcache.DeferredAfter(time.Duration(rand.IntN(3))*time.Millisecond, body)
		}
	})
	conn, _, lost, err := openMock(t, s, mockConfig(t, s, addr))
	if err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	for g := range 20 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := range 25 {
				url := fmt.Sprintf("https://dynamodb.us-east-1.amazonaws.com/%d/%d", g, i)
				reply, err := conn.request(context.Background(), getItem(url))
				if err != nil {
					t.Error(err)
					return
				}
				if reply.failed || reply.response.statusCode != 200 || string(reply.response.body) != url ||
					reply.response.headers.Get("X-Amz-Crc32") != "1" {
					t.Errorf("%s: %+v", url, reply)
					return
				}
			}
		}()
	}
	wg.Wait()
	if conn.pending.pending() != 0 || !conn.alive() || lost.count() != 0 {
		t.Fatalf("pending %d, alive %v", conn.pending.pending(), conn.alive())
	}
	if free, _ := conn.pending.slots.state(); free != 100 {
		t.Fatalf("free slots %d", free)
	}
	r := s.Requests()[0]
	if r.Arg(1) != "POST" || r.Arg(3) != "content-type:application/x-amz-json-1.0\nx-amz-target:DynamoDB_20120810.GetItem" ||
		r.Arg(4) != `{"TableName":"t"}` || r.Arg(5) != "service=dynamodb&op=GetItem" {
		t.Fatalf("request %q", r.Args)
	}
}

func TestRequestReplies(t *testing.T) {
	s, addr := startMock(t, mockcache.Config{})
	s.SetOnRequest(func(cmd mockcache.Command) mockcache.Reply {
		switch cmd.Arg(2) {
		case "origin":
			return mockcache.Deferred(resp.ErrorValue("ORIGIN_ERROR", "throttled"))
		case "shape":
			return mockcache.Now(resp.IntValue(1))
		}
		return mockcache.Now(resp.ErrorValue("UNSUPPORTED", "Scan with Select"))
	})
	conn, _, _, err := openMock(t, s, mockConfig(t, s, addr))
	if err != nil {
		t.Fatal(err)
	}
	reply, err := conn.request(context.Background(), getItem("origin"))
	if err != nil || !reply.failed || reply.server.kind() != serverOrigin {
		t.Fatalf("origin: %+v %v", reply, err)
	}
	reply, err = conn.request(context.Background(), getItem("rejected"))
	if err != nil || !reply.failed || reply.server.code != "UNSUPPORTED" {
		t.Fatalf("rejected: %+v %v", reply, err)
	}
	if _, err := conn.request(context.Background(), getItem("shape")); !errors.As(err, new(*ProtocolError)) {
		t.Fatalf("wrong shape: %v", err)
	}
	if !conn.alive() {
		t.Fatal("a wrong-shaped reply closed the connection")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	before := len(s.Requests())
	if _, err := conn.request(ctx, getItem("x")); !errors.Is(err, context.Canceled) || len(s.Requests()) != before {
		t.Fatalf("done ctx: %v", err)
	}
}

func TestReauth(t *testing.T) {
	s, addr := startMock(t, mockcache.Config{})
	var reply resp.Value
	var mu sync.Mutex
	s.SetOnAuth(func(user, token string) resp.Value {
		mu.Lock()
		defer mu.Unlock()
		if reply.Kind == resp.KindInvalid {
			return mockcache.OK()
		}
		return reply
	})
	set := func(v resp.Value) { mu.Lock(); reply = v; mu.Unlock() }
	conn, _, lost, err := openMock(t, s, mockConfig(t, s, addr))
	if err != nil {
		t.Fatal(err)
	}
	creds := authCredentials{user: "default.iam-user", token: newSecret("NEW-" + testToken)}
	if err := conn.reauth(context.Background(), creds); err != nil {
		t.Fatalf("+OK: %v", err)
	}
	cmds := s.Commands()
	if last := cmds[len(cmds)-1]; last.Name() != "AUTH" || last.Arg(1) != "default.iam-user" || last.Arg(2) != "NEW-"+testToken {
		t.Fatalf("AUTH %q", last.Args)
	}
	set(resp.ErrorValue("WRONGPASS", "invalid"))
	var ae *AuthError
	if err := conn.reauth(context.Background(), creds); !errors.As(err, &ae) || ae.Code != "WRONGPASS" || !conn.alive() {
		t.Fatalf("WRONGPASS: %v, alive %v", err, conn.alive())
	}
	set(resp.ErrorValue("NEW-"+testToken, ""))
	if err := conn.reauth(context.Background(), creds); !errors.As(err, &ae) || ae.Code != "(unrecognized error code)" || !conn.alive() {
		t.Fatalf("token as the code: %v, alive %v", err, conn.alive())
	}
	testutil.AssertNoSecrets(t, ae.Error(), testutil.TokenSecrets("NEW-"+testToken)...)
	set(resp.IntValue(1))
	if err := conn.reauth(context.Background(), creds); !errors.As(err, &ae) || ae.Code != "" {
		t.Fatalf(":1: %v", err)
	}
	if err := lost.wait(t); err == nil || conn.alive() {
		t.Fatal("an unexpected AUTH reply kept the connection")
	}
}

func TestRefreshConfig(t *testing.T) {
	s, addr := startMock(t, mockcache.Config{})
	conn, _, _, err := openMock(t, s, mockConfig(t, s, addr))
	if err != nil {
		t.Fatal(err)
	}
	if rc, err := conn.refreshConfig(context.Background()); rc != nil || err != nil {
		t.Fatalf("unchanged: %v %v", rc, err)
	}
	s.SetOnRefresh(func() mockcache.Reply {
		return mockcache.Now(resp.BulkStringValue(`{"version":"2","refresh_interval":30}`))
	})
	if rc, err := conn.refreshConfig(context.Background()); err != nil || rc.version != "2" || rc.refreshInterval != 30*time.Second {
		t.Fatalf("new config: %v %v", rc, err)
	}
	s.SetOnRefresh(func() mockcache.Reply { return mockcache.Now(resp.ErrorValue("ERR", "busy")) })
	if _, err := conn.refreshConfig(context.Background()); !errors.As(err, new(*RejectedError)) {
		t.Fatalf("error reply: %v", err)
	}
	s.SetOnRefresh(func() mockcache.Reply { return mockcache.Now(resp.IntValue(3)) })
	if _, err := conn.refreshConfig(context.Background()); !errors.As(err, new(*ProtocolError)) {
		t.Fatalf("wrong shape: %v", err)
	}
	if !conn.alive() {
		t.Fatal("a failed refresh closed the connection")
	}
	if n := len(s.Overrides()); n != 1 {
		t.Fatalf("refresh sent overrides: %d", n)
	}
}

// A reply that is already in wins over a done ctx.
func TestAwaitPrefersArrivedReply(t *testing.T) {
	client, server := net.Pipe()
	defer server.Close()
	conn := newConnection(mockConfig(t, nil, "localhost:6379"), client, nil)
	defer conn.close()
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	for range 100 {
		e := track(t, conn.pending, false, time.Now().Add(time.Hour))
		conn.pending.resolveHead(resp.IntValue(7))
		if v, err := conn.await(ctx, e); err != nil || v.Int != 7 {
			t.Fatalf("%v %v", v, err)
		}
	}
}

// The admission end is checked again under the write turn: past it, nothing is registered or
// written and the slot is free.
func TestWriteAfterAdmissionEndNotSent(t *testing.T) {
	client, server := net.Pipe()
	defer server.Close()
	conn := newConnection(mockConfig(t, nil, "localhost:6379"), client, nil)
	defer conn.close()
	if err := conn.pending.slots.acquire(context.Background(), budget(time.Second)); err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	_, err := conn.write(context.Background(), []byte("x"), true, sendBudget{deadline: now.Add(time.Second), sendBy: now})
	if !errors.As(err, new(*RequestNotSentError)) {
		t.Fatalf("%v", err)
	}
	if free, _ := conn.pending.slots.state(); free != conn.cfg.maxPending || conn.pending.pending() != 0 {
		t.Fatalf("free %d, pending %d", free, conn.pending.pending())
	}
}

// TestRequestLargeBodyRoundTrip sends bodies written in place in their room, over TLS, beside
// small ones, and checks the cache receives every body and command intact.
func TestRequestLargeBodyRoundTrip(t *testing.T) {
	s, addr := startMock(t, mockcache.Config{TLS: true})
	s.SetOnRequest(func(cmd mockcache.Command) mockcache.Reply {
		return mockcache.Now(mockcache.Served(200, nil, []byte(cmd.Arg(4))))
	})
	conn, _, _, err := openMock(t, s, mockConfig(t, s, addr))
	if err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	for g := range 8 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := range 6 {
				r := getItem("https://dynamodb.us-east-1.amazonaws.com/")
				n := (g%2)*(directBodyMin+i*1000) + i
				var err error
				r.body, r.room, err = readBody(bytes.NewReader(bytes.Repeat([]byte{byte('a' + g)}, n)), int64(n))
				if err != nil {
					t.Error(err)
					return
				}
				reply, err := conn.request(context.Background(), r)
				if err != nil {
					t.Error(err)
					return
				}
				if reply.failed || !bytes.Equal(reply.response.body, r.body) {
					t.Errorf("goroutine %d request %d: %d bytes back, sent %d", g, i, len(reply.response.body), len(r.body))
					return
				}
			}
		}()
	}
	wg.Wait()
	if !conn.alive() || conn.pending.pending() != 0 {
		t.Fatalf("alive %v, pending %d", conn.alive(), conn.pending.pending())
	}
}

func BenchmarkRequestHit(b *testing.B) {
	for _, size := range []int{1 << 10, 64 << 10} {
		b.Run(fmt.Sprintf("%dKiB", size>>10), func(b *testing.B) {
			s, err := mockcache.New(mockcache.Config{})
			if err != nil {
				b.Fatal(err)
			}
			defer s.Close()
			body := make([]byte, size)
			s.SetOnRequest(func(mockcache.Command) mockcache.Reply {
				return mockcache.Now(mockcache.Served(200, map[string]string{"x-amz-crc32": "1"}, body))
			})
			c, err := Options{Endpoint: "localhost:6379", CacheName: "my-cache"}.validate()
			if err != nil {
				b.Fatal(err)
			}
			c.plaintext, c.dial = true, s.Dial
			conn, _, err := openConnection(context.Background(), c, &staticAuth{}, time.Now().Add(5*time.Second), nil)
			if err != nil {
				b.Fatal(err)
			}
			defer conn.close()
			r := getItem("https://dynamodb.us-east-1.amazonaws.com/")
			b.ReportAllocs()
			b.ResetTimer()
			for b.Loop() {
				if _, err := conn.request(context.Background(), r); err != nil {
					b.Fatal(err)
				}
			}
		})
	}
}

// BenchmarkRequestLargeBody sends request bodies over TLS, the transport the cache uses.
func BenchmarkRequestLargeBody(b *testing.B) {
	for _, size := range []int{1 << 10, 64 << 10, 390 << 10} {
		b.Run(fmt.Sprintf("%dKiB", size>>10), func(b *testing.B) {
			s, err := mockcache.New(mockcache.Config{TLS: true})
			if err != nil {
				b.Fatal(err)
			}
			defer s.Close()
			addr, err := s.Listen()
			if err != nil {
				b.Fatal(err)
			}
			s.SetOnRequest(func(mockcache.Command) mockcache.Reply {
				return mockcache.Now(mockcache.Served(200, map[string]string{"x-amz-crc32": "1"}, []byte("{}")))
			})
			c, err := Options{Endpoint: addr, CacheName: "my-cache", Logger: slog.New(slog.DiscardHandler)}.validate()
			if err != nil {
				b.Fatal(err)
			}
			c.rootCAs = s.CertPool()
			conn, _, err := openConnection(context.Background(), c, &staticAuth{}, time.Now().Add(5*time.Second), nil)
			if err != nil {
				b.Fatal(err)
			}
			defer conn.close()
			r := getItem("https://dynamodb.us-east-1.amazonaws.com/")
			if r.body, r.room, err = readBody(bytes.NewReader(make([]byte, size)), int64(size)); err != nil {
				b.Fatal(err)
			}
			b.SetBytes(int64(size))
			b.ReportAllocs()
			for b.Loop() {
				if _, err := conn.request(context.Background(), r); err != nil {
					b.Fatal(err)
				}
			}
		})
	}
}
