// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

//go:build go1.25

package elasticacheintegrations

import (
	"context"
	"errors"
	"log/slog"
	"net"
	"strings"
	"testing"
	"testing/synctest"
	"time"

	"github.com/awslabs/elasticache-integration-client/go/internal/mockcache"
	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
	"github.com/awslabs/elasticache-integration-client/go/internal/testutil"
)

// pipeMock is a mock cache served over net.Pipe, for synctest bubbles.
func pipeMock(t *testing.T, c mockcache.Config) (*mockcache.Server, *config) {
	t.Helper()
	s, err := mockcache.New(c)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := s.Close(); err != nil {
			t.Errorf("mock cache: %v", err)
		}
	})
	cfg := mockConfig(t, s, "localhost:6379")
	cfg.dial = s.Dial
	return s, cfg
}

func TestBringUpTimesOutAtEachStep(t *testing.T) {
	cases := []struct {
		name  string
		setup func(s *mockcache.Server)
		step  string
	}{
		{"TLS", func(s *mockcache.Server) { s.Hold() }, "TLS handshake"},
		{"HELLO", func(s *mockcache.Server) { intercept(s, "HELLO", mockcache.Hang()) }, "HELLO 3"},
		{"aws.config", func(s *mockcache.Server) { intercept(s, "AWS.CONFIG", mockcache.Hang()) }, "aws.config"},
		// The cache stops reading: closing the socket must not wait on the TLS close alert.
		{"HELLO, cache not reading", func(s *mockcache.Server) { intercept(s, "HELLO", mockcache.After(time.Hour, resp.NullValue())) }, "HELLO 3"},
		{"aws.config, cache not reading", func(s *mockcache.Server) {
			intercept(s, "AWS.CONFIG", mockcache.After(time.Hour, resp.NullValue()))
		}, "aws.config"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			synctest.Test(t, func(t *testing.T) {
				s, c := pipeMock(t, mockcache.Config{TLS: true})
				tc.setup(s)
				defer s.Release()
				start := time.Now()
				conn, _, err := openConnection(context.Background(), c, &staticAuth{}, start.Add(5*time.Second), nil)
				var ce *ConnectionError
				if conn != nil || !errors.As(err, &ce) || !errors.As(err, new(*TimeoutError)) ||
					errors.As(err, new(*ConnectionLostError)) ||
					!strings.HasPrefix(ce.Msg, tc.step) || !strings.Contains(ce.Msg, "timed out") {
					t.Fatalf("err %v", err)
				}
				if took := time.Since(start); took != 5*time.Second {
					t.Fatalf("returned after %v, want the 5s deadline", took)
				}
			})
		})
	}
}

func TestBringUpCancelledMidHello(t *testing.T) {
	for name, reply := range map[string]mockcache.Reply{
		"hang":              mockcache.Hang(),
		"cache not reading": mockcache.After(time.Hour, resp.NullValue()),
	} {
		t.Run(name, func(t *testing.T) {
			synctest.Test(t, func(t *testing.T) {
				s, c := pipeMock(t, mockcache.Config{TLS: true})
				intercept(s, "HELLO", reply)
				ctx, cancel := context.WithCancel(context.Background())
				time.AfterFunc(time.Second, cancel)
				start := time.Now()
				_, _, err := openConnection(ctx, c, &staticAuth{}, start.Add(time.Minute), nil)
				if !errors.Is(err, context.Canceled) || time.Since(start) != time.Second {
					t.Fatalf("err %v after %v", err, time.Since(start))
				}
			})
		})
	}
}

// noClearConn refuses to clear its deadline.
type noClearConn struct{ net.Conn }

func (c noClearConn) SetDeadline(t time.Time) error {
	if t.IsZero() {
		return errors.New("cannot clear the deadline")
	}
	return c.Conn.SetDeadline(t)
}

func TestBringUpClearDeadlineFailure(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s, c := pipeMock(t, mockcache.Config{})
		c.dial = func(ctx context.Context, network, addr string) (net.Conn, error) {
			nc, err := s.Dial(ctx, network, addr)
			return noClearConn{nc}, err
		}
		conn, _, err := openConnection(context.Background(), c, &staticAuth{}, time.Now().Add(5*time.Second), nil)
		var ce *ConnectionError
		if conn != nil || !errors.As(err, &ce) || !errors.Is(err, ErrIntegrations) {
			t.Fatalf("err %v", err)
		}
	})
}

// A dying TLS connection fails its requests at once, even when the cache does not read.
func TestDeathDoesNotWaitForAnUnreadingCache(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s, c := pipeMock(t, mockcache.Config{TLS: true})
		s.SetOnRefresh(func() mockcache.Reply { return mockcache.After(time.Hour, resp.NullValue()) })
		lost := newLostRecorder()
		conn, _, err := openConnection(context.Background(), c, &staticAuth{}, time.Now().Add(5*time.Second), lost.onLost)
		if err != nil {
			t.Fatal(err)
		}
		e := track(t, conn.pending, false, later())
		if _, err := conn.conn.Write(appendConfigRefresh(nil)); err != nil {
			t.Fatal(err)
		}
		synctest.Wait() // the mock sleeps on the refresh and reads nothing more
		start := time.Now()
		conn.drop("the cache replied MOVED")
		select {
		case <-e.done:
		default:
			t.Fatal("the pending request was not failed")
		}
		conn.close()
		if took := time.Since(start); took != 0 {
			t.Fatalf("drop and close took %v", took)
		}
	})
}

// An in-order entry past its deadline closes the connection: it gets *TimeoutError, every
// other entry *ConnectionLostError, and onLost runs once.
func TestCheckDeadlinesClosesConnection(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		_, c := pipeMock(t, mockcache.Config{})
		logs, logger := testutil.NewLogCapture()
		c.logger = newLogger(logger)
		lost := newLostRecorder()
		conn, _, err := openConnection(context.Background(), c, &staticAuth{}, time.Now().Add(5*time.Second), lost.onLost)
		if err != nil {
			t.Fatal(err)
		}
		inOrder := track(t, conn.pending, false, time.Now().Add(time.Second))
		other := track(t, conn.pending, true, time.Now().Add(time.Minute))
		time.Sleep(time.Second)
		synctest.Wait()
		<-conn.readerDone
		var cl *ConnectionLostError
		if !errors.As(inOrder.err, new(*TimeoutError)) || !errors.As(other.err, &cl) ||
			cl.Msg != "an earlier request on the connection timed out" {
			t.Fatalf("in-order %v, other %v", inOrder.err, other.err)
		}
		if conn.alive() || lost.count() != 1 || <-lost.ch != conn.err() {
			t.Fatalf("alive %v, onLost %d", conn.alive(), lost.count())
		}
		var found bool
		for _, rec := range logs.Records() {
			if v, _ := testutil.Attr(rec, "event"); v.Int64() == int64(eventInOrderTimeout) {
				found = true
			}
		}
		if !found {
			t.Fatalf("no in-order timeout record:\n%s", logs.Text())
		}
		conn.close()
	})
}

// A slow but successful bring-up leaves the socket without a deadline: an idle connection
// stays up.
func TestIdleConnectionStaysUp(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s, c := pipeMock(t, mockcache.Config{})
		intercept(s, "AWS.CONFIG", mockcache.After(4*time.Second, resp.BulkStringValue(mockcache.RouteTenOperations)))
		lost := newLostRecorder()
		conn, _, err := openConnection(context.Background(), c, &staticAuth{}, time.Now().Add(5*time.Second), lost.onLost)
		if err != nil {
			t.Fatal(err)
		}
		time.Sleep(time.Hour)
		synctest.Wait()
		if !conn.alive() || lost.count() != 0 {
			t.Fatalf("an idle connection died: %v", conn.err())
		}
		conn.close()
	})
}

// holdHandler holds every record of event ev until release is closed.
type holdHandler struct {
	ev      eventID
	held    chan struct{} // closed when the first record of ev arrives
	release chan struct{}
}

func (h *holdHandler) Enabled(context.Context, slog.Level) bool { return true }
func (h *holdHandler) WithAttrs([]slog.Attr) slog.Handler       { return h }
func (h *holdHandler) WithGroup(string) slog.Handler            { return h }
func (h *holdHandler) Handle(_ context.Context, r slog.Record) error {
	r.Attrs(func(a slog.Attr) bool {
		if a.Key == "event" && a.Value.Int64() == int64(h.ev) {
			select {
			case <-h.held:
			default:
				close(h.held)
			}
			<-h.release
			return false
		}
		return true
	})
	return nil
}

// A deadline check that has marked the connection dead and is still reporting it holds up
// close: close returns only after the check has logged and called onLost.
func TestConnectionCloseWaitsForTheDeadlineCheck(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s, c := pipeMock(t, mockcache.Config{})
		h := &holdHandler{ev: eventInOrderTimeout, held: make(chan struct{}), release: make(chan struct{})}
		c.logger = newLogger(slog.New(h))
		s.SetOnRequest(func(mockcache.Command) mockcache.Reply { return mockcache.Hang() })
		lost := newLostRecorder()
		conn, _, err := openConnection(context.Background(), c, &staticAuth{}, time.Now().Add(5*time.Second), lost.onLost)
		if err != nil {
			t.Fatal(err)
		}
		reply := send(context.Background(), conn, "a", time.Now())
		<-h.held // the deadline check is logging 1202, before onLost
		<-reply
		closed := make(chan struct{})
		go func() { conn.close(); close(closed) }()
		synctest.Wait()
		select {
		case <-closed:
			close(h.release)
			t.Fatal("close returned while the deadline check was still running")
		default:
		}
		close(h.release)
		<-closed
		if n := lost.count(); n != 1 {
			t.Fatalf("onLost ran %d times by the time close returned, want 1", n)
		}
	})
}
