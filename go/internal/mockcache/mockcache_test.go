// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package mockcache

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
)

// client is a raw RESP client for driving the server.
type client struct {
	t    *testing.T
	conn net.Conn
	r    *resp.Reader
	wait time.Duration // read deadline for each reply
}

func newClient(t *testing.T, conn net.Conn) *client {
	t.Helper()
	t.Cleanup(func() { _ = conn.Close() })
	return &client{t: t, conn: conn, r: resp.NewReader(conn, 4096, 1<<20), wait: 5 * time.Second}
}

func (c *client) send(args ...string) {
	c.t.Helper()
	if _, err := c.conn.Write(resp.AppendCommandStrings(nil, args...)); err != nil {
		c.t.Fatalf("write %v: %v", args, err)
	}
}

func (c *client) next() resp.Value {
	c.t.Helper()
	_ = c.conn.SetReadDeadline(time.Now().Add(c.wait))
	v, err := c.r.Next()
	if err != nil {
		c.t.Fatalf("read: %v", err)
	}
	return v
}

func (c *client) call(args ...string) resp.Value {
	c.t.Helper()
	c.send(args...)
	return c.next()
}

// ready runs the handshake the Go client runs.
func (c *client) ready() {
	c.t.Helper()
	if v := c.call("HELLO", "3", "AUTH", "default.iam-user", "token"); v.Kind != resp.KindMap {
		c.t.Fatalf("HELLO reply %v", v)
	}
	if v := c.call("aws.config", `{"version":"1.0"}`); v.Kind != resp.KindBulkString {
		c.t.Fatalf("aws.config reply %v", v)
	}
}

func (c *client) request(url string) resp.Value {
	c.t.Helper()
	return c.call("aws.request", "POST", url, "content-type:application/x-amz-json-1.0", "{}", "service=dynamodb&op=GetItem")
}

func newServer(t *testing.T, c Config) *Server {
	t.Helper()
	s, err := New(c)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = s.Close() })
	return s
}

func listen(t *testing.T, s *Server) string {
	t.Helper()
	addr, err := s.Listen()
	if err != nil {
		t.Fatal(err)
	}
	return addr
}

func dialTCP(t *testing.T, addr string) *client {
	t.Helper()
	conn, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatal(err)
	}
	return newClient(t, conn)
}

func TestHandshakeAndRequest(t *testing.T) {
	s := newServer(t, Config{})
	c := dialTCP(t, listen(t, s))
	hello := c.call("HELLO", "3", "AUTH", "default.iam-user", "token")
	if p, ok := hello.MapGet("proto"); !ok || p.Int != 3 {
		t.Fatalf("HELLO reply %v", hello)
	}
	if v := c.call("aws.config", `{"version":"1.0","ttl":5}`); string(v.Bytes) != RouteTenOperations {
		t.Fatalf("config reply %q", v.Bytes)
	}
	v := c.request("https://dynamodb.us-east-1.amazonaws.com/")
	if v.Kind != resp.KindArray || len(v.Elems) != 3 || v.Elems[0].Int != 200 || string(v.Elems[2].Bytes) != "{}" {
		t.Fatalf("request reply %v", v)
	}
	if s.Connections() != 1 {
		t.Errorf("Connections() = %d", s.Connections())
	}
	if got := s.Overrides(); len(got) != 1 || got[0] != `{"version":"1.0","ttl":5}` {
		t.Errorf("Overrides() = %q", got)
	}
	if got := s.Requests(); len(got) != 1 || got[0].Arg(2) != "https://dynamodb.us-east-1.amazonaws.com/" || got[0].Conn != 1 {
		t.Errorf("Requests() = %v", got)
	}
	var names []string
	for _, cmd := range s.Commands() {
		names = append(names, cmd.Name())
	}
	if strings.Join(names, ",") != "HELLO,AWS.CONFIG,AWS.REQUEST" {
		t.Errorf("Commands() = %v", names)
	}
	if err := s.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
}

func TestTLS(t *testing.T) {
	s := newServer(t, Config{TLS: true})
	addr := listen(t, s)
	_, port, _ := net.SplitHostPort(addr)
	for _, host := range []string{"localhost", "127.0.0.1"} {
		conn, err := tls.Dial("tcp", net.JoinHostPort(host, port), &tls.Config{RootCAs: s.CertPool(), ServerName: host})
		if err != nil {
			t.Fatalf("%s: %v", host, err)
		}
		c := newClient(t, conn)
		c.ready()
		if v := c.request("u"); v.Kind != resp.KindArray {
			t.Fatalf("%s: reply %v", host, v)
		}
	}
	if _, err := tls.Dial("tcp", addr, &tls.Config{ServerName: "example.com", RootCAs: s.CertPool()}); err == nil {
		t.Fatal("a certificate for another name was accepted")
	}
	if len(s.CertPEM()) == 0 {
		t.Fatal("no PEM")
	}
}

func TestDial(t *testing.T) {
	for _, useTLS := range []bool{false, true} {
		s := newServer(t, Config{TLS: useTLS})
		conn, err := s.Dial(context.Background(), "tcp", "ignored:1")
		if err != nil {
			t.Fatal(err)
		}
		if useTLS {
			conn = tls.Client(conn, &tls.Config{RootCAs: s.CertPool(), ServerName: "localhost"})
		}
		c := newClient(t, conn)
		c.ready()
		if v := c.request("u"); v.Kind != resp.KindArray {
			t.Fatalf("tls=%v: reply %v", useTLS, v)
		}
		ctx, cancel := context.WithCancel(context.Background())
		cancel()
		if _, err := s.Dial(ctx, "tcp", ""); !errors.Is(err, context.Canceled) {
			t.Fatalf("Dial with a done ctx: %v", err)
		}
	}
}

func TestReplyKinds(t *testing.T) {
	s := newServer(t, Config{})
	later := make(chan resp.Value, 1)
	body := Served(200, map[string]string{"x-amz-crc32": "1", "content-type": "j"}, []byte("b"))
	s.SetOnRequest(func(cmd Command) Reply {
		switch cmd.Arg(2) {
		case "now":
			return Now(body)
		case "after":
			return After(50*time.Millisecond, body)
		case "deferred":
			return Deferred(body)
		case "deferred-after":
			return DeferredAfter(50*time.Millisecond, body)
		case "deferred-from":
			return DeferredFrom(later)
		case "raw":
			return Raw([]byte(":7\r\n"))
		case "error":
			return Now(resp.ErrorValue("ORIGIN_ERROR", "boom"))
		}
		return Reply{}
	})
	c := dialTCP(t, listen(t, s))
	c.ready()

	if v := c.request("now"); string(v.Elems[1].Bytes) != "content-type:j\nx-amz-crc32:1" {
		t.Fatalf("headers %q", v.Elems[1].Bytes)
	}
	start := time.Now()
	if v := c.request("after"); v.Kind != resp.KindArray || time.Since(start) < 50*time.Millisecond {
		t.Fatalf("after: %v in %v", v, time.Since(start))
	}
	id := c.request("deferred")
	if id.Kind != resp.KindSimpleString {
		t.Fatalf("deferred reply %v", id)
	}
	if push := c.next(); push.Kind != resp.KindPush || string(push.Elems[1].Bytes) != string(id.Bytes) ||
		push.Elems[2].Kind != resp.KindArray {
		t.Fatalf("push %v", push)
	}
	// A deferred reply does not hold later commands back.
	id1 := c.request("deferred-after")
	if v := c.request("now"); v.Kind != resp.KindArray {
		t.Fatalf("in-band reply after a deferred one: %v", v)
	}
	if push := c.next(); string(push.Elems[1].Bytes) != string(id1.Bytes) {
		t.Fatalf("push %v for id %q", push, id1.Bytes)
	}
	id2 := c.request("deferred-from")
	later <- resp.IntValue(9)
	if push := c.next(); string(push.Elems[1].Bytes) != string(id2.Bytes) || push.Elems[2].Int != 9 {
		t.Fatalf("push %v", push)
	}
	n1, _ := strconv.Atoi(string(id1.Bytes))
	n2, _ := strconv.Atoi(string(id2.Bytes))
	if n1 <= 0 || n2 <= n1 {
		t.Fatalf("ids %q then %q", id1.Bytes, id2.Bytes)
	}
	if v := c.request("raw"); v.Kind != resp.KindInteger || v.Int != 7 {
		t.Fatalf("raw %v", v)
	}
	if v := c.request("error"); v.ErrorCode() != "ORIGIN_ERROR" {
		t.Fatalf("error %v", v)
	}
	// The zero Reply cannot be sent: the server fails.
	c.send("aws.request", "POST", "zero", "", "{}", "service=dynamodb&op=GetItem")
	if _, err := c.r.Next(); err == nil {
		t.Fatal("a zero Reply was answered")
	}
	if s.Err() == nil {
		t.Fatal("a zero Reply did not fail the server")
	}
}

func TestHangAndDrop(t *testing.T) {
	s := newServer(t, Config{})
	s.SetOnRequest(func(cmd Command) Reply {
		switch cmd.Arg(2) {
		case "hang":
			return Hang()
		case "drop-after":
			return DropAfter(resp.IntValue(1))
		}
		return Drop()
	})
	addr := listen(t, s)

	c := dialTCP(t, addr)
	c.ready()
	c.send("aws.request", "POST", "hang", "", "{}", "service=dynamodb&op=GetItem")
	c.send("aws.request", "POST", "now", "", "{}", "service=dynamodb&op=GetItem")
	_ = c.conn.SetReadDeadline(time.Now().Add(100 * time.Millisecond))
	if v, err := c.r.Next(); err == nil {
		t.Fatalf("a hung connection replied %v", v)
	}
	if n := len(s.Commands()); n != 4 {
		t.Fatalf("%d commands recorded after a hang, want 4", n)
	}

	c = dialTCP(t, addr)
	c.ready()
	if v := c.request("drop-after"); v.Int != 1 {
		t.Fatalf("drop-after %v", v)
	}
	if _, err := c.r.Next(); !errors.Is(err, io.EOF) {
		t.Fatalf("after drop-after: %v", err)
	}

	c = dialTCP(t, addr)
	c.ready()
	c.send("aws.request", "POST", "drop", "", "{}", "service=dynamodb&op=GetItem")
	if _, err := c.r.Next(); !errors.Is(err, io.EOF) {
		t.Fatalf("after drop: %v", err)
	}
	if err := s.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
}

func TestOrderAndShapeFailures(t *testing.T) {
	cases := []struct {
		name string
		run  func(c *client)
		want string
	}{
		{"request before HELLO", func(c *client) {
			c.send("aws.request", "POST", "u", "", "{}", "service=dynamodb&op=GetItem")
		}, "before HELLO 3 AUTH"},
		{"AUTH before HELLO", func(c *client) { c.send("AUTH", "u", "t") }, "before HELLO 3 AUTH"},
		{"HELLO without AUTH", func(c *client) {
			c.call("HELLO", "3")
			c.send("aws.config", `{"version":"1.0"}`)
		}, "before HELLO 3 AUTH"},
		{"request before config", func(c *client) {
			c.call("HELLO", "3", "AUTH", "u", "t")
			c.send("aws.request", "POST", "u", "", "{}", "service=dynamodb&op=GetItem")
		}, "before aws.config"},
		{"refresh first", func(c *client) {
			c.call("HELLO", "3", "AUTH", "u", "t")
			c.send("aws.config")
		}, "without overrides first"},
		{"not a command", func(c *client) {
			_, _ = c.conn.Write([]byte("+OK\r\n"))
		}, "not a command"},
		{"corrupt", func(c *client) {
			_, _ = c.conn.Write([]byte("?\r\n"))
		}, "corrupt"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			s := newServer(t, Config{})
			c := dialTCP(t, listen(t, s))
			tc.run(c)
			_ = c.conn.SetReadDeadline(time.Now().Add(5 * time.Second))
			for {
				if _, err := c.r.Next(); err != nil {
					break // the server closed the connection
				}
			}
			err := s.Close()
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("Close() = %v, want %q", err, tc.want)
			}
		})
	}
}

func TestCommandReplies(t *testing.T) {
	s := newServer(t, Config{ConfigJSON: `{"version":"1.0","refresh_interval":5}`})
	c := dialTCP(t, listen(t, s))
	if v := c.call("HELLO", "2"); v.ErrorCode() != "NOPROTO" {
		t.Fatalf("HELLO 2: %v", v)
	}
	if v := c.call("HELLO", "3", "AUTH", "u"); v.ErrorCode() != "ERR" {
		t.Fatalf("short HELLO: %v", v)
	}
	if v := c.call("HELLO", "3"); v.ErrorCode() != "NOAUTH" {
		t.Fatalf("HELLO 3 without AUTH before AUTH: %v", v)
	}
	c.ready()
	if v := c.call("HELLO", "3"); v.Kind != resp.KindMap {
		t.Fatalf("HELLO 3 without AUTH after AUTH: %v", v)
	}
	for _, bad := range []string{`{"version":"2"}`, `{"version":1.0}`, `{"version":"1.x"}`, `{"ttl":5}`} {
		if v := c.call("aws.config", bad); v.ErrorCode() != "CONFIG_INVALID" {
			t.Fatalf("overrides %s: %v", bad, v)
		}
	}
	if v := c.call("aws.config", `{"version":"1.12"}`); v.Kind != resp.KindBulkString {
		t.Fatalf("version 1.12: %v", v)
	}
	if v := c.call("aws.config", `[]`); v.ErrorCode() != "CONFIG_INVALID" {
		t.Fatalf("not an object: %v", v)
	}
	if v := c.call("aws.config"); v.Kind != resp.KindNull {
		t.Fatalf("refresh: %v", v)
	}
	s.SetOnRefresh(func() Reply { return Now(resp.BulkStringValue(`{"version":"2"}`)) })
	if v := c.call("aws.config"); string(v.Bytes) != `{"version":"2"}` {
		t.Fatalf("refresh: %v", v)
	}
	if v := c.call("aws.config", "a", "b"); v.ErrorCode() != "ERR" {
		t.Fatalf("two arguments: %v", v)
	}
	if v := c.call("aws.request", "POST", "u", "", "{}"); v.ErrorCode() != "PARSE_ERROR" {
		t.Fatalf("five arguments: %v", v)
	}
	if v := c.call("aws.request", "POST", "u", "", "{}", "service=dynamodb&op="); v.ErrorCode() != "PARSE_ERROR" {
		t.Fatalf("no op: %v", v)
	}
	if v := c.call("AUTH", "u", "t"); string(v.Bytes) != "OK" {
		t.Fatalf("AUTH: %v", v)
	}
	if v := c.call("AUTH", "t"); v.ErrorCode() != "ERR" {
		t.Fatalf("AUTH with one argument: %v", v)
	}
	if v := c.call("GET", "k"); v.ErrorCode() != "ERR" {
		t.Fatalf("GET: %v", v)
	}
	if len(s.Requests()) != 0 {
		t.Fatalf("invalid requests were recorded: %v", s.Requests())
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestAuthHook(t *testing.T) {
	s := newServer(t, Config{})
	var mu sync.Mutex
	var seen []string
	s.SetOnAuth(func(user, token string) resp.Value {
		mu.Lock()
		defer mu.Unlock()
		seen = append(seen, user+"/"+token)
		if token == "bad" {
			return resp.ErrorValue("WRONGPASS", "invalid username-password pair")
		}
		return OK()
	})
	addr := listen(t, s)
	c := dialTCP(t, addr)
	if v := c.call("HELLO", "3", "AUTH", "u", "bad"); v.ErrorCode() != "WRONGPASS" {
		t.Fatalf("HELLO: %v", v)
	}
	if v := c.call("HELLO", "3", "AUTH", "u", "good"); v.Kind != resp.KindMap {
		t.Fatalf("HELLO: %v", v)
	}
	c.call("aws.config", `{"version":"1.0"}`)
	if v := c.call("AUTH", "u", "bad"); v.ErrorCode() != "WRONGPASS" {
		t.Fatalf("AUTH: %v", v)
	}
	mu.Lock()
	defer mu.Unlock()
	if strings.Join(seen, ",") != "u/bad,u/good,u/bad" {
		t.Fatalf("seen %v", seen)
	}
}

func TestIntercept(t *testing.T) {
	s := newServer(t, Config{})
	s.SetIntercept(func(cmd Command) *Reply {
		if cmd.Name() == "AWS.CONFIG" {
			r := Now(resp.ErrorValue("ERR", "unknown command 'aws.config'"))
			return &r
		}
		return nil
	})
	c := dialTCP(t, listen(t, s))
	if v := c.call("HELLO", "3", "AUTH", "u", "t"); v.Kind != resp.KindMap {
		t.Fatalf("HELLO: %v", v)
	}
	if v := c.call("aws.config", `{"version":"1.0"}`); v.ErrorCode() != "ERR" {
		t.Fatalf("aws.config: %v", v)
	}
	if s.Connections() != 0 {
		t.Fatal("an intercepted config counted as configured")
	}
}

func TestPushWriteRawDropAll(t *testing.T) {
	s := newServer(t, Config{})
	if err := s.Push(resp.IntValue(1)); err == nil {
		t.Fatal("Push with no connection succeeded")
	}
	addr := listen(t, s)
	c := dialTCP(t, addr)
	c.ready()
	if err := s.Push(ResponsePush("7", resp.IntValue(1))); err != nil {
		t.Fatal(err)
	}
	if v := c.next(); v.Kind != resp.KindPush || string(v.Elems[1].Bytes) != "7" {
		t.Fatalf("push %v", v)
	}
	if err := s.WriteRaw([]byte("|1\r\n")); err != nil {
		t.Fatal(err)
	}
	if _, err := c.r.Next(); !errors.Is(err, resp.ErrCorrupt) {
		t.Fatalf("raw: %v", err)
	}
	c = dialTCP(t, addr)
	c.ready()
	s.DropAll()
	_ = c.conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	if _, err := c.r.Next(); !errors.Is(err, io.EOF) {
		t.Fatalf("after DropAll: %v", err)
	}
}

func TestHold(t *testing.T) {
	s := newServer(t, Config{TLS: true})
	addr := listen(t, s)
	s.Hold()
	raw, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatal(err)
	}
	conn := tls.Client(raw, &tls.Config{RootCAs: s.CertPool(), ServerName: "localhost"})
	defer conn.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	if err := conn.HandshakeContext(ctx); err == nil {
		t.Fatal("a held connection completed TLS")
	}
	s.Release()
	conn2, err := tls.Dial("tcp", addr, &tls.Config{RootCAs: s.CertPool(), ServerName: "localhost"})
	if err != nil {
		t.Fatalf("after Release: %v", err)
	}
	newClient(t, conn2).ready()
}

func TestCloseWithPendingPush(t *testing.T) {
	s := newServer(t, Config{})
	s.SetOnRequest(func(Command) Reply { return DeferredFrom(make(chan resp.Value)) })
	c := dialTCP(t, listen(t, s))
	c.ready()
	if v := c.request("u"); v.Kind != resp.KindSimpleString {
		t.Fatalf("reply %v", v)
	}
	done := make(chan error, 1)
	go func() { done <- s.Close() }()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Close did not return")
	}
	if _, err := s.Listen(); err == nil {
		t.Fatal("Listen after Close succeeded")
	}
	client, server := net.Pipe()
	defer client.Close()
	s.ServeConn(server)
	if _, err := client.Read(make([]byte, 1)); err == nil {
		t.Fatal("ServeConn after Close served")
	}
}

// A drop ends the stream cleanly even when the client has pipelined commands the server has
// not read: the client reads every reply written before it, then io.EOF, never a reset.
func TestDropEndsTheStream(t *testing.T) {
	for _, useTLS := range []bool{false, true} {
		for _, how := range []string{"drop", "drop-after", "drop-all"} {
			t.Run(fmt.Sprintf("tls=%v/%s", useTLS, how), func(t *testing.T) {
				s := newServer(t, Config{TLS: useTLS})
				gate := make(chan struct{})
				s.SetOnRequest(func(cmd Command) Reply {
					switch cmd.Arg(2) {
					case "drop":
						<-gate
						return Drop()
					case "drop-after":
						<-gate
						return DropAfter(resp.IntValue(1))
					case "block":
						<-gate
					}
					return Now(resp.IntValue(2))
				})
				addr := listen(t, s)
				var conn net.Conn
				var err error
				if useTLS {
					conn, err = tls.Dial("tcp", addr, &tls.Config{RootCAs: s.CertPool(), ServerName: "127.0.0.1"})
				} else {
					conn, err = net.Dial("tcp", addr)
				}
				if err != nil {
					t.Fatal(err)
				}
				c := newClient(t, conn)
				c.ready()
				want := 0
				if how == "drop-all" {
					if v := c.request("now"); v.Int != 2 {
						t.Fatalf("reply %v", v)
					}
					// The server is in the hook for "block" and has not read "unread" when it drops.
					c.send("aws.request", "POST", "block", "", "{}", "service=dynamodb&op=GetItem")
					time.Sleep(20 * time.Millisecond)
					c.send("aws.request", "POST", "unread", "", "{}", "service=dynamodb&op=GetItem")
					time.Sleep(20 * time.Millisecond)
					s.DropAll()
					close(gate)
				} else {
					c.send("aws.request", "POST", how, "", "{}", "service=dynamodb&op=GetItem")
					time.Sleep(20 * time.Millisecond)
					c.send("aws.request", "POST", "unread", "", "{}", "service=dynamodb&op=GetItem")
					time.Sleep(20 * time.Millisecond)
					close(gate)
					if how == "drop-after" {
						want = 1
					}
				}
				time.Sleep(50 * time.Millisecond)
				if want != 0 {
					if v := c.next(); v.Int != int64(want) {
						t.Fatalf("reply before the drop: %v", v)
					}
				}
				_ = c.conn.SetReadDeadline(time.Now().Add(5 * time.Second))
				if _, err := c.r.Next(); !errors.Is(err, io.EOF) {
					t.Fatalf("after the drop: %v, want io.EOF", err)
				}
				if err := s.WriteRaw([]byte(":1\r\n")); err == nil {
					t.Fatal("WriteRaw wrote to a dropped connection")
				}
				if err := s.Close(); err != nil {
					t.Fatal(err)
				}
			})
		}
	}
}

// DropAll cuts a connection whose reply is stuck behind a client that does not read.
func TestDropAllWithStuckWrite(t *testing.T) {
	s := newServer(t, Config{})
	big := make([]byte, 32<<20)
	s.SetOnRequest(func(Command) Reply { return Now(Served(200, nil, big)) })
	c := dialTCP(t, listen(t, s))
	c.ready()
	c.send("aws.request", "POST", "u", "", "{}", "service=dynamodb&op=GetItem")
	time.Sleep(100 * time.Millisecond)
	done := make(chan struct{})
	go func() { s.DropAll(); close(done) }()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("DropAll blocked on a stuck write")
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestOpenCountsConnectionsBeingServed(t *testing.T) {
	s := newServer(t, Config{})
	addr := listen(t, s)
	a, b := dialTCP(t, addr), dialTCP(t, addr)
	a.ready()
	b.ready()
	if s.Open() != 2 {
		t.Fatalf("Open() = %d, want 2", s.Open())
	}
	frame := resp.AppendCommandStrings(nil, "aws.request", "POST", "http://dynamodb/",
		"content-type:application/x-amz-json-1.0", "{}", "service=dynamodb&op=GetItem")
	if _, err := a.conn.Write(frame[:len(frame)-3]); err != nil {
		t.Fatal(err)
	}
	_ = a.conn.Close()
	waitFor(t, "the cut connection to end", func() bool { return s.Open() == 1 })
	if n := len(s.Requests()); n != 0 {
		t.Fatalf("a cut aws.request was recorded: %d requests", n)
	}
	b.request("http://dynamodb/")
	_ = b.conn.Close()
	waitFor(t, "the second connection to end", func() bool { return s.Open() == 0 })
}
