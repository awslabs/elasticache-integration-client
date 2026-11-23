// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package mockcache

import (
	"context"
	"errors"
	"io"
	"net"
	"syscall"
	"testing"
	"time"

	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
)

// newRelay starts a relay in front of a plaintext mock cache.
func newRelay(t *testing.T) (*Server, *Relay) {
	t.Helper()
	s := newServer(t, Config{})
	r, err := NewRelay(listen(t, s))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = r.Close() })
	return s, r
}

// waitFor polls cond every millisecond for up to 5 s.
func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	for end := time.Now().Add(5 * time.Second); !cond(); {
		if time.Now().After(end) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(time.Millisecond)
	}
}

// expectNoBytes fails unless conn stays open and silent for d.
func expectNoBytes(t *testing.T, conn net.Conn, d time.Duration) {
	t.Helper()
	_ = conn.SetReadDeadline(time.Now().Add(d))
	n, err := conn.Read(make([]byte, 1))
	var ne net.Error
	if n != 0 || !errors.As(err, &ne) || !ne.Timeout() {
		t.Fatalf("read = %d, %v; want a timeout with no bytes", n, err)
	}
	_ = conn.SetReadDeadline(time.Time{})
}

// expectReset fails unless a read on conn ends with a connection reset within 5 s.
func expectReset(t *testing.T, conn net.Conn) {
	t.Helper()
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	if _, err := conn.Read(make([]byte, 1)); !errors.Is(err, syscall.ECONNRESET) {
		t.Fatalf("read: %v, want a connection reset", err)
	}
}

// openConns counts the connections the relay tracks.
func (r *Relay) openConns() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.open)
}

func TestRelayForwards(t *testing.T) {
	s, r := newRelay(t)
	c := dialTCP(t, r.Addr())
	c.ready()
	if v := c.request("http://dynamodb/"); v.Kind != resp.KindArray {
		t.Fatalf("aws.request reply %v", v)
	}
	if r.Accepted() != 1 || s.Connections() != 1 || len(s.Requests()) != 1 {
		t.Fatalf("accepted %d, connections %d, requests %d; want 1 each", r.Accepted(), s.Connections(), len(s.Requests()))
	}
}

func TestRelayRefuse(t *testing.T) {
	_, r := newRelay(t)
	open := dialTCP(t, r.Addr())
	open.ready()
	r.SetMode(Refuse)
	refused, err := net.Dial("tcp", r.Addr())
	if err != nil {
		t.Fatal(err)
	}
	defer refused.Close()
	_ = refused.SetReadDeadline(time.Now().Add(5 * time.Second))
	if n, err := refused.Read(make([]byte, 1)); n != 0 || !errors.Is(err, io.EOF) {
		t.Fatalf("read on a refused connection = %d, %v; want the end of the stream", n, err)
	}
	if v := open.request("http://dynamodb/"); v.Kind != resp.KindArray {
		t.Fatalf("the open connection stopped working: %v", v)
	}
	r.SetMode(Forward)
	dialTCP(t, r.Addr()).ready()
	if r.Accepted() != 3 {
		t.Fatalf("accepted %d, want 3", r.Accepted())
	}
}

func TestRelayBlackhole(t *testing.T) {
	s, r := newRelay(t)
	r.SetMode(Blackhole)
	conn, err := net.Dial("tcp", r.Addr())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if _, err := conn.Write(resp.AppendCommandStrings(nil, "HELLO", "3")); err != nil {
		t.Fatal(err)
	}
	expectNoBytes(t, conn, 100*time.Millisecond)
	if len(s.Commands()) != 0 {
		t.Fatalf("the cache saw %d commands through a blackhole", len(s.Commands()))
	}
	r.Disconnect()
	expectReset(t, conn)
}

func TestRelayDisconnectResets(t *testing.T) {
	s, r := newRelay(t)
	c := dialTCP(t, r.Addr())
	c.ready()
	waitFor(t, "the pair to be tracked", func() bool { return r.openConns() == 2 })
	r.Disconnect()
	expectReset(t, c.conn)
	waitFor(t, "the cache to see the connection end", func() bool {
		s.mu.Lock()
		defer s.mu.Unlock()
		return len(s.conns) == 0
	})
	dialTCP(t, r.Addr()).ready()
	if s.Connections() != 2 {
		t.Fatalf("connections %d, want 2", s.Connections())
	}
}

func TestRelayCloseWaits(t *testing.T) {
	_, r := newRelay(t)
	c := dialTCP(t, r.Addr())
	c.ready()
	r.SetMode(Blackhole)
	hung, err := net.Dial("tcp", r.Addr())
	if err != nil {
		t.Fatal(err)
	}
	defer hung.Close()
	waitFor(t, "the blackholed connection to be tracked", func() bool { return r.openConns() == 3 })
	done := make(chan error, 1)
	go func() { done <- r.Close() }()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("Close: %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Close did not return with open connections")
	}
	for _, conn := range []net.Conn{c.conn, hung} {
		expectReset(t, conn)
	}
	if conn, err := net.DialTimeout("tcp", r.Addr(), time.Second); err == nil {
		_ = conn.Close()
		t.Fatal("the relay still accepts after Close")
	}
	if err := r.Close(); err != nil {
		t.Fatalf("second Close: %v", err)
	}
}

// A connection accepted while forwarding, still dialing the target, is reset by Disconnect and
// never forwarded.
func TestRelayDisconnectWhileDialing(t *testing.T) {
	s := newServer(t, Config{})
	target := listen(t, s)
	dialing, release := make(chan struct{}), make(chan struct{})
	r, err := newRelayWithDial(target, func(ctx context.Context, addr string) (net.Conn, error) {
		close(dialing)
		<-release
		return (&net.Dialer{}).DialContext(ctx, "tcp", addr)
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = r.Close() })
	conn, err := net.Dial("tcp", r.Addr())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	<-dialing
	r.SetMode(Refuse)
	r.Disconnect()
	close(release)
	expectReset(t, conn)
	waitFor(t, "the relay to drop the dialed connection", func() bool { return r.openConns() == 0 })
}

// Close does not wait for a dial to the target to time out.
func TestRelayCloseWhileDialing(t *testing.T) {
	dialing := make(chan struct{})
	r, err := newRelayWithDial("127.0.0.1:1", func(ctx context.Context, _ string) (net.Conn, error) {
		close(dialing)
		<-ctx.Done()
		return nil, ctx.Err()
	})
	if err != nil {
		t.Fatal(err)
	}
	conn, err := net.Dial("tcp", r.Addr())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	<-dialing
	done := make(chan error, 1)
	go func() { done <- r.Close() }()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("Close: %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Close waited for the dial")
	}
	expectReset(t, conn)
}

// Another loopback listener cannot take the relay's port, so a client always reaches the relay.
func TestRelayPortNotShared(t *testing.T) {
	_, r := newRelay(t)
	if l, err := net.Listen("tcp", r.Addr()); err == nil {
		_ = l.Close()
		t.Fatal("a second listener bound the relay's address")
	}
}

func TestRelayPauseHoldsBytes(t *testing.T) {
	_, r := newRelay(t)
	c := dialTCP(t, r.Addr())
	c.ready()
	r.Pause()
	r.Pause() // a second Pause changes nothing
	c.send("aws.request", "POST", "http://dynamodb/", "content-type:application/x-amz-json-1.0", "{}", "service=dynamodb&op=GetItem")
	expectNoBytes(t, c.conn, 100*time.Millisecond)
	r.Resume()
	r.Resume() // a second Resume changes nothing
	if v := c.next(); v.Kind != resp.KindArray {
		t.Fatalf("aws.request reply after Resume %v", v)
	}
}

func TestRelayPauseThenDisconnectAndClose(t *testing.T) {
	_, r := newRelay(t)
	c := dialTCP(t, r.Addr())
	c.ready()
	r.Pause()
	c.send("aws.request", "POST", "http://dynamodb/", "content-type:application/x-amz-json-1.0", "{}", "service=dynamodb&op=GetItem")
	expectNoBytes(t, c.conn, 50*time.Millisecond)
	r.Disconnect()
	expectReset(t, c.conn)
	held := dialTCP(t, r.Addr())
	held.send("HELLO", "3", "AUTH", "default.iam-user", "token")
	expectNoBytes(t, held.conn, 50*time.Millisecond)
	done := make(chan error, 1)
	go func() { done <- r.Close() }()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("Close: %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Close did not return while paused")
	}
	expectReset(t, held.conn)
}
