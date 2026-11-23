// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

//go:build go1.25

package elasticacheintegrations

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net"
	"strings"
	"sync"
	"testing"
	"testing/synctest"
	"time"

	"github.com/awslabs/elasticache-integration-client/go/internal/mockcache"
	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
	"github.com/awslabs/elasticache-integration-client/go/internal/testutil"
)

// openPipe opens a connection to s over net.Pipe inside a synctest bubble.
func openPipe(t *testing.T, s *mockcache.Server, c *config) (*connection, *lostRecorder) {
	t.Helper()
	lost := newLostRecorder()
	conn, _, err := openConnection(context.Background(), c, &staticAuth{}, time.Now().Add(5*time.Second), lost.onLost)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(conn.close)
	return conn, lost
}

type outcome struct {
	reply requestReply
	err   error
	at    time.Duration
}

// send runs one request in its own goroutine and reports how it ended.
func send(ctx context.Context, conn *connection, url string, start time.Time) <-chan outcome {
	ch := make(chan outcome, 1)
	go func() {
		reply, err := conn.request(ctx, getItem(url))
		ch <- outcome{reply, err, time.Since(start)}
	}()
	return ch
}

func TestInOrderTimeoutClosesConnection(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s, c := pipeMock(t, mockcache.Config{})
		logs, logger := testutil.NewLogCapture()
		c.logger = newLogger(logger)
		s.SetOnRequest(func(mockcache.Command) mockcache.Reply { return mockcache.Hang() })
		conn, lost := openPipe(t, s, c)
		start := time.Now()
		first := send(context.Background(), conn, "a", start)
		synctest.Wait()
		time.Sleep(time.Second)
		second := send(context.Background(), conn, "b", start)

		a, b := <-first, <-second
		var te *TimeoutError
		if !errors.As(a.err, &te) || a.at != 5*time.Second || !strings.Contains(te.Msg, "5000ms") {
			t.Fatalf("first: %v at %v", a.err, a.at)
		}
		if !errors.As(b.err, new(*ConnectionLostError)) || b.at != 5*time.Second {
			t.Fatalf("second: %v at %v", b.err, b.at)
		}
		if err := lost.wait(t); !errors.As(err, new(*ConnectionLostError)) || conn.alive() {
			t.Fatalf("onLost(%v)", err)
		}
		if !strings.Contains(logs.Text(), "in-order reply did not arrive in time") {
			t.Fatalf("no 1202 record:\n%s", logs.Text())
		}
	})
}

func TestCancelledCallersDoNotHideHungConnection(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s, c := pipeMock(t, mockcache.Config{})
		s.SetOnRequest(func(mockcache.Command) mockcache.Reply { return mockcache.Hang() })
		conn, lost := openPipe(t, s, c)
		start := time.Now()
		var outs []<-chan outcome
		for range c.maxPending {
			ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
			defer cancel()
			outs = append(outs, send(ctx, conn, "u", start))
		}
		for _, ch := range outs {
			if o := <-ch; !errors.Is(o.err, context.DeadlineExceeded) || o.at != 100*time.Millisecond {
				t.Fatalf("cancelled caller: %v at %v", o.err, o.at)
			}
		}
		time.Sleep(time.Second)
		if !conn.alive() {
			t.Fatal("closed before the first deadline")
		}
		// The abandoned entries still hold every slot, so a new caller is not sent.
		sentAt := time.Since(start)
		late := <-send(context.Background(), conn, "late", start)
		if !errors.As(late.err, new(*RequestNotSentError)) || late.at != sentAt+3750*time.Millisecond {
			t.Fatalf("late caller: %v at %v", late.err, late.at)
		}
		if err := lost.wait(t); err == nil || time.Since(start) != 5*time.Second {
			t.Fatalf("onLost(%v) after %v", err, time.Since(start))
		}
		sent := 0
		for _, cmd := range s.Commands() {
			if cmd.Name() == "AWS.REQUEST" {
				sent++
			}
		}
		if conn.pending.pending() != 0 || sent != c.maxPending {
			t.Fatalf("pending %d, sent %d", conn.pending.pending(), sent)
		}
	})
}

func TestDeferredTimeoutAlone(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s, c := pipeMock(t, mockcache.Config{})
		late := make(chan resp.Value, 1)
		s.SetOnRequest(func(cmd mockcache.Command) mockcache.Reply {
			if cmd.Arg(2) == "slow" {
				return mockcache.DeferredFrom(late)
			}
			return mockcache.Now(mockcache.Served(200, nil, []byte("ok")))
		})
		conn, lost := openPipe(t, s, c)
		start := time.Now()
		o := <-send(context.Background(), conn, "slow", start)
		if !errors.As(o.err, new(*TimeoutError)) || o.at != 5*time.Second {
			t.Fatalf("deferred: %v at %v", o.err, o.at)
		}
		late <- mockcache.Served(200, nil, []byte("late"))
		synctest.Wait()
		o = <-send(context.Background(), conn, "fast", start)
		if o.err != nil || string(o.reply.response.body) != "ok" || !conn.alive() || lost.count() != 0 {
			t.Fatalf("after a late push: %+v, alive %v", o, conn.alive())
		}
	})
}

func TestCancelledDeferredFreesSlotAtOnce(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s, c := pipeMock(t, mockcache.Config{})
		c.maxPending = 1
		s.SetOnRequest(func(cmd mockcache.Command) mockcache.Reply {
			if cmd.Arg(2) == "held" {
				return mockcache.DeferredFrom(make(chan resp.Value))
			}
			return mockcache.Now(mockcache.Served(200, nil, nil))
		})
		conn, _ := openPipe(t, s, c)
		start := time.Now()
		ctx, cancel := context.WithCancel(context.Background())
		held := send(ctx, conn, "held", start)
		synctest.Wait()
		next := send(context.Background(), conn, "next", start)
		time.Sleep(time.Second)
		cancel()
		if o := <-held; !errors.Is(o.err, context.Canceled) {
			t.Fatalf("held: %v", o.err)
		}
		if o := <-next; o.err != nil || o.at != time.Second {
			t.Fatalf("next: %v at %v", o.err, o.at)
		}
	})
}

func TestCancelledInOrderSlotFreedByItsReply(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s, c := pipeMock(t, mockcache.Config{})
		c.maxPending = 1
		s.SetOnRequest(func(cmd mockcache.Command) mockcache.Reply {
			if cmd.Arg(2) == "slow" {
				return mockcache.After(2*time.Second, mockcache.Served(200, nil, []byte("discarded")))
			}
			return mockcache.Now(mockcache.Served(200, nil, []byte("next")))
		})
		conn, _ := openPipe(t, s, c)
		start := time.Now()
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		slow := send(ctx, conn, "slow", start)
		synctest.Wait()
		next := send(context.Background(), conn, "next", start)
		if o := <-slow; !errors.Is(o.err, context.DeadlineExceeded) || o.at != time.Second {
			t.Fatalf("slow: %v at %v", o.err, o.at)
		}
		if o := <-next; o.err != nil || string(o.reply.response.body) != "next" || o.at != 2*time.Second {
			t.Fatalf("next: %+v", o)
		}
	})
}

func TestRequestNotSentWhenSlotsStayBusy(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s, c := pipeMock(t, mockcache.Config{})
		c.maxPending = 2
		s.SetOnRequest(func(mockcache.Command) mockcache.Reply { return mockcache.DeferredFrom(make(chan resp.Value)) })
		conn, _ := openPipe(t, s, c)
		start := time.Now()
		a, b := send(context.Background(), conn, "a", start), send(context.Background(), conn, "b", start)
		synctest.Wait()
		third := <-send(context.Background(), conn, "c", start)
		if !errors.As(third.err, new(*RequestNotSentError)) || third.at != 3750*time.Millisecond {
			t.Fatalf("third: %v at %v", third.err, third.at)
		}
		<-a
		<-b
		if n := len(s.Requests()); n != 2 {
			t.Fatalf("%d requests sent", n)
		}
	})
}

func TestRequestNotSentWhenWriteTurnStaysBusy(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s, c := pipeMock(t, mockcache.Config{})
		conn, _ := openPipe(t, s, c)
		conn.turn <- struct{}{} // a writer that never finishes
		start := time.Now()
		o := <-send(context.Background(), conn, "u", start)
		if !errors.As(o.err, new(*RequestNotSentError)) || o.at != 3750*time.Millisecond {
			t.Fatalf("%v at %v", o.err, o.at)
		}
		if free, _ := conn.pending.slots.state(); free != c.maxPending || len(s.Requests()) != 0 {
			t.Fatalf("free %d, sent %d", free, len(s.Requests()))
		}
		<-conn.turn
	})
}

// rawConnection is a live connection over net.Pipe whose far end the test drives.
func rawConnection(t *testing.T) (*connection, net.Conn, *lostRecorder) {
	t.Helper()
	c := mockConfig(t, nil, "localhost:6379")
	client, server := net.Pipe()
	lost := newLostRecorder()
	conn := newConnection(c, client, lost.onLost)
	conn.started.Store(true)
	go conn.readLoop()
	t.Cleanup(func() { conn.close(); _ = server.Close() })
	return conn, server, lost
}

func TestWriteTimeoutClosesConnection(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		conn, _, lost := rawConnection(t) // nobody reads the far end, so a write blocks
		start := time.Now()
		o := <-send(context.Background(), conn, "u", start)
		if !errors.As(o.err, new(*TimeoutError)) || o.at != 5*time.Second {
			t.Fatalf("%v at %v", o.err, o.at)
		}
		if err := lost.wait(t); !errors.As(err, new(*ConnectionLostError)) || conn.alive() {
			t.Fatalf("onLost(%v)", err)
		}
	})
}

// faultConn is the client end of a connection whose writes can fail or stall. It records the
// write deadlines it is given.
type faultConn struct {
	net.Conn
	mu        sync.Mutex
	fail      error // Write returns it
	stall     bool  // Write blocks until Close
	failLate  error // Write sends the bytes, waits a second, then returns it
	deadlines []time.Time
	closed    chan struct{}
	closeOnce sync.Once
}

func newFaultConn(nc net.Conn) *faultConn { return &faultConn{Conn: nc, closed: make(chan struct{})} }

func (f *faultConn) Write(b []byte) (int, error) {
	f.mu.Lock()
	fail, stall, failLate := f.fail, f.stall, f.failLate
	f.mu.Unlock()
	switch {
	case failLate != nil:
		if _, err := f.Conn.Write(b); err != nil {
			return 0, err
		}
		time.Sleep(time.Second)
		return len(b), failLate
	case fail != nil:
		return 0, fail
	case stall:
		<-f.closed
		return 0, &net.OpError{Op: "write", Net: "pipe", Err: net.ErrClosed}
	}
	return f.Conn.Write(b)
}

func (f *faultConn) SetWriteDeadline(t time.Time) error {
	f.mu.Lock()
	f.deadlines = append(f.deadlines, t)
	f.mu.Unlock()
	return f.Conn.SetWriteDeadline(t)
}

func (f *faultConn) Close() error {
	f.closeOnce.Do(func() { close(f.closed) })
	return f.Conn.Close()
}

func (f *faultConn) set(fail error, stall bool) {
	f.mu.Lock()
	f.fail, f.stall = fail, stall
	f.mu.Unlock()
}

// faultMock is pipeMock with the client end wrapped in a faultConn.
func faultMock(t *testing.T, mc mockcache.Config) (*mockcache.Server, *config, func() *faultConn) {
	t.Helper()
	s, c := pipeMock(t, mc)
	var fc *faultConn
	c.dial = func(ctx context.Context, network, addr string) (net.Conn, error) {
		nc, err := s.Dial(ctx, network, addr)
		if err != nil {
			return nil, err
		}
		fc = newFaultConn(nc)
		return fc, nil
	}
	return s, c, func() *faultConn { return fc }
}

func TestWriteFailureClosesConnection(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s, c, fault := faultMock(t, mockcache.Config{})
		logs, logger := testutil.NewLogCapture()
		c.logger = newLogger(logger)
		s.SetOnRequest(func(mockcache.Command) mockcache.Reply { return mockcache.DeferredFrom(make(chan resp.Value)) })
		conn, lost := openPipe(t, s, c)
		start := time.Now()
		held := send(context.Background(), conn, "held", start)
		synctest.Wait()
		broken := errors.New("broken pipe")
		fault().set(broken, false)
		o := <-send(context.Background(), conn, "u", start)
		var cl *ConnectionLostError
		if !errors.As(o.err, &cl) || !errors.Is(o.err, broken) || o.at != 0 {
			t.Fatalf("writer: %v at %v", o.err, o.at)
		}
		if h := <-held; !errors.As(h.err, new(*ConnectionLostError)) || h.at != 0 {
			t.Fatalf("held: %v at %v", h.err, h.at)
		}
		if err := lost.wait(t); !errors.Is(err, broken) || lost.count() != 1 || conn.alive() {
			t.Fatalf("onLost(%v) %d times", err, lost.count())
		}
		if !strings.Contains(logs.Text(), "connection was lost") {
			t.Fatalf("no 1201 record:\n%s", logs.Text())
		}
	})
}

// A write in flight when the connection dies fails on the closed TCP socket under TLS; the
// writer gets the death's *ConnectionLostError at once.
func TestWriteRacingDeathIsConnectionLost(t *testing.T) {
	for _, tlsOn := range []bool{false, true} {
		t.Run(map[bool]string{false: "plaintext", true: "TLS"}[tlsOn], func(t *testing.T) {
			synctest.Test(t, func(t *testing.T) {
				s, c, fault := faultMock(t, mockcache.Config{TLS: tlsOn})
				conn, _ := openPipe(t, s, c)
				fault().set(nil, true)
				start := time.Now()
				writer := send(context.Background(), conn, "u", start)
				synctest.Wait()
				time.Sleep(time.Second)
				conn.drop("stale config")
				var cl *ConnectionLostError
				if o := <-writer; !errors.As(o.err, &cl) || cl.Msg != "stale config" || o.at != time.Second {
					t.Fatalf("writer: %v at %v", o.err, o.at)
				}
			})
		})
	}
}

// A write that reports an error after its reply arrived still returns that reply.
func TestWriteErrorAfterReplyKeepsReply(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s, c, fault := faultMock(t, mockcache.Config{})
		s.SetOnRequest(func(mockcache.Command) mockcache.Reply {
			return mockcache.Now(mockcache.Served(200, nil, []byte("ok")))
		})
		conn, lost := openPipe(t, s, c)
		fault().mu.Lock()
		fault().failLate = errors.New("late write error")
		fault().mu.Unlock()
		o := <-send(context.Background(), conn, "u", time.Now())
		if o.err != nil || string(o.reply.response.body) != "ok" {
			t.Fatalf("%+v", o)
		}
		if lost.wait(t) == nil || conn.alive() {
			t.Fatal("a failed write kept the connection")
		}
	})
}

func TestWriteDeadlineClearedAfterWrite(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s, c, fault := faultMock(t, mockcache.Config{TLS: true})
		s.SetOnRequest(func(mockcache.Command) mockcache.Reply {
			return mockcache.Now(mockcache.Served(200, nil, []byte("ok")))
		})
		conn, _ := openPipe(t, s, c)
		start := time.Now()
		if o := <-send(context.Background(), conn, "u", start); o.err != nil {
			t.Fatal(o.err)
		}
		fc := fault()
		fc.mu.Lock()
		d := fc.deadlines
		fc.mu.Unlock()
		if len(d) != 2 || !d[0].Equal(start.Add(5*time.Second)) || !d[1].IsZero() {
			t.Fatalf("write deadlines %v", d)
		}
	})
}

// A write that ends late in the budget still leaves the reply 25% of it, as Java.
func TestSlowWriteLeavesReplyItsMinimumBudget(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		conn, server, lost := rawConnection(t)
		go func() {
			time.Sleep(4900 * time.Millisecond)
			_, _ = io.Copy(io.Discard, server)
		}()
		start := time.Now()
		o := <-send(context.Background(), conn, "u", start)
		if !errors.As(o.err, new(*TimeoutError)) || o.at != 4900*time.Millisecond+1250*time.Millisecond {
			t.Fatalf("%v at %v", o.err, o.at)
		}
		if lost.wait(t) == nil || conn.alive() {
			t.Fatal("an in-order timeout kept the connection")
		}
	})
}

func TestCloseWithRequestsInFlight(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s, c := pipeMock(t, mockcache.Config{})
		s.SetOnRequest(func(cmd mockcache.Command) mockcache.Reply {
			if cmd.Arg(2) == "deferred" {
				return mockcache.DeferredFrom(make(chan resp.Value))
			}
			return mockcache.Hang()
		})
		conn, lost := openPipe(t, s, c)
		start := time.Now()
		var outs []<-chan outcome
		for i := range 6 {
			url := "deferred"
			if i == 5 {
				url = "hang"
			}
			outs = append(outs, send(context.Background(), conn, url, start))
			synctest.Wait()
		}
		time.Sleep(time.Second)
		var wg sync.WaitGroup
		for range 3 {
			wg.Add(1)
			go func() { defer wg.Done(); conn.close() }()
		}
		wg.Wait()
		for _, ch := range outs {
			var cl *ConnectionLostError
			if o := <-ch; !errors.As(o.err, &cl) || cl.Msg != "the connection was closed" || o.at != time.Second {
				t.Fatalf("%v at %v", o.err, o.at)
			}
		}
		if lost.count() != 0 {
			t.Fatal("close called onLost")
		}
		if _, err := conn.request(context.Background(), getItem("after")); !errors.As(err, new(*ConnectionLostError)) {
			t.Fatalf("request after close: %v", err)
		}
		if err := conn.reauth(context.Background(), authCredentials{token: newSecret("t")}); !errors.As(err, new(*ConnectionLostError)) {
			t.Fatalf("reauth after close: %v", err)
		}
	})
}

func TestReauthTimeoutClosesConnection(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s, c := pipeMock(t, mockcache.Config{})
		intercept(s, "AUTH", mockcache.Hang())
		conn, lost := openPipe(t, s, c)
		start := time.Now()
		err := conn.reauth(context.Background(), authCredentials{user: "u", token: newSecret(testToken)})
		if !errors.As(err, new(*TimeoutError)) || time.Since(start) != 5*time.Second {
			t.Fatalf("%v after %v", err, time.Since(start))
		}
		if lost.wait(t) == nil || conn.alive() {
			t.Fatal("a re-AUTH timeout kept the connection")
		}
		testutil.AssertNoSecrets(t, err.Error(), testutil.TokenSecrets(testToken)...)
	})
}

// A reply that lands at the instant the in-order deadline fires either serves its caller with
// the connection kept, or times out with the connection closed; the deadline is decided under
// the tracker's lock, so a served reply never closes the connection.
func TestInOrderDeadlineAndReplyAtOnce(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s, c := pipeMock(t, mockcache.Config{})
		c.cacheRequestTimeout = time.Second
		s.SetOnRequest(func(mockcache.Command) mockcache.Reply {
			return mockcache.After(time.Second, mockcache.Served(200, nil, []byte("{}")))
		})
		for range 100 {
			conn, _ := openPipe(t, s, c)
			_, err := conn.request(context.Background(), getItem("edge"))
			synctest.Wait()
			switch {
			case err == nil && !conn.alive():
				t.Fatalf("the caller was served and the connection closed: %v", conn.err())
			case err != nil && !errors.As(err, new(*TimeoutError)):
				t.Fatalf("request: %v", err)
			case err != nil && conn.alive():
				t.Fatal("the caller timed out and the connection stayed open")
			}
			conn.close()
		}
	})
}

// A write that never completes fails as a write timeout, logged as a lost connection (1201,
// Info), never as a missing in-order reply (1202): the reply deadline starts after the write.
func TestBlockedWriteIsAWriteTimeout(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		for range 20 {
			conn, _, _ := rawConnection(t) // nobody reads the far end, so a write blocks
			logs, l := testutil.NewLogCapture()
			conn.cfg.logger = newLogger(l)
			o := <-send(context.Background(), conn, "u", time.Now())
			var te *TimeoutError
			if !errors.As(o.err, &te) || te.Msg != "the request write timed out" || o.at != 5*time.Second {
				t.Fatalf("%v at %v", o.err, o.at)
			}
			recs := eventRecords(logs, eventConnectionLost)
			if len(recs) != 1 || recs[0].Level != slog.LevelInfo || len(eventRecords(logs, eventInOrderTimeout)) != 0 {
				t.Fatalf("records:\n%s", logs.Text())
			}
		}
	})
}

// One drop logs 1201 at Info; reconnect failures are what warn (1303).
func TestDropLogsConnectionLostAtInfo(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		conn, server, lost := rawConnection(t)
		logs, l := testutil.NewLogCapture()
		conn.cfg.logger = newLogger(l)
		_ = server.Close()
		lost.wait(t)
		recs := eventRecords(logs, eventConnectionLost)
		if len(recs) != 1 || recs[0].Level != slog.LevelInfo {
			t.Fatalf("records:\n%s", logs.Text())
		}
	})
}
