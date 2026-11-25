// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"errors"
	"log/slog"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/awslabs/elasticache-integration-client/go/internal/mockcache"
	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
	"github.com/awslabs/elasticache-integration-client/go/internal/testutil"
)

// readerRig is a live connection whose replies the test writes by hand.
type readerRig struct {
	t    *testing.T
	s    *mockcache.Server
	conn *connection
	lost *lostRecorder
	logs *testutil.LogCapture
}

func newReaderRig(t *testing.T) *readerRig {
	t.Helper()
	s, addr := startMock(t, mockcache.Config{})
	c := mockConfig(t, s, addr)
	logs, logger := testutil.NewLogCapture()
	c.logger = newLogger(logger)
	conn, _, lost, err := openMock(t, s, c)
	if err != nil {
		t.Fatal(err)
	}
	return &readerRig{t: t, s: s, conn: conn, lost: lost, logs: logs}
}

// expect registers an entry as if a request had been written.
func (r *readerRig) expect(deferrable bool) *pendingEntry {
	r.t.Helper()
	return track(r.t, r.conn.pending, deferrable, later())
}

func (r *readerRig) write(frames string) {
	r.t.Helper()
	if err := r.s.WriteRaw([]byte(frames)); err != nil {
		r.t.Fatal(err)
	}
}

func (r *readerRig) push(id string, v resp.Value) {
	r.t.Helper()
	if err := r.s.Push(mockcache.ResponsePush(id, v)); err != nil {
		r.t.Fatal(err)
	}
}

func result(t *testing.T, e *pendingEntry) (resp.Value, error) {
	t.Helper()
	select {
	case <-e.done:
		return e.value, e.err
	case <-time.After(5 * time.Second):
		t.Fatal("no result")
		return resp.Value{}, nil
	}
}

func TestReaderPipelinedReplies(t *testing.T) {
	r := newReaderRig(t)
	a, b, c := r.expect(true), r.expect(false), r.expect(true)
	r.write(":1\r\n+OK\r\n$3\r\nabc\r\n")
	if v, err := result(t, a); err != nil || v.Int != 1 {
		t.Fatalf("a: %v %v", v, err)
	}
	if v, _ := result(t, b); string(v.Bytes) != "OK" {
		t.Fatalf("b: %v", v)
	}
	if v, _ := result(t, c); string(v.Bytes) != "abc" {
		t.Fatalf("c: %v", v)
	}
	if !r.conn.alive() {
		t.Fatal("died")
	}
}

func TestReaderDeferredReplies(t *testing.T) {
	r := newReaderRig(t)
	a, b, cmd := r.expect(true), r.expect(true), r.expect(false)
	r.write("+10\r\n+11\r\n+12\r\n") // the third is a plain simple-string reply to a command
	r.push("11", resp.IntValue(11))
	r.push("10", resp.IntValue(10))
	if v, _ := result(t, a); v.Int != 10 {
		t.Fatalf("a: %v", v)
	}
	if v, _ := result(t, b); v.Int != 11 {
		t.Fatalf("b: %v", v)
	}
	if v, _ := result(t, cmd); string(v.Bytes) != "12" {
		t.Fatalf("command: %v", v)
	}
	// A push nobody waits for is dropped, with a debug record.
	r.push("99", resp.IntValue(1))
	waitFor(t, "the debug record", func() bool { return strings.Contains(r.logs.Text(), "no longer pending") })
	var found bool
	for _, rec := range r.logs.Records() {
		if v, _ := testutil.Attr(rec, "event"); v.Int64() == int64(eventLatePushDropped) {
			found = true
		}
	}
	if !found || !r.conn.alive() || r.conn.pending.pending() != 0 {
		t.Fatalf("found %v, alive %v, pending %d", found, r.conn.alive(), r.conn.pending.pending())
	}
}

func TestReaderStreamFailures(t *testing.T) {
	cases := []struct {
		name   string
		inject func(r *readerRig)
		check  func(error) bool
	}{
		{"push of another kind", func(r *readerRig) {
			_ = r.s.Push(resp.PushValue(resp.BulkStringValue("invalidate"), resp.ArrayValue()))
		}, isCorrupt},
		{"push with two elements", func(r *readerRig) {
			_ = r.s.Push(resp.PushValue(resp.BulkStringValue("aws.response"), resp.BulkStringValue("1")))
		}, isCorrupt},
		{"reply with nothing pending", func(r *readerRig) { r.write(":1\r\n") }, isCorrupt},
		{"id with nothing pending", func(r *readerRig) { r.write("+5\r\n") }, isCorrupt},
		{"invalid UTF-8 id", func(r *readerRig) {
			r.expect(true)
			r.write("+\xff\r\n")
		}, isCorrupt},
		{"duplicate id", func(r *readerRig) {
			r.expect(true)
			r.expect(true)
			r.write("+5\r\n+5\r\n")
		}, isCorrupt},
		{"corrupt bytes", func(r *readerRig) { r.write("|1\r\n") }, func(err error) bool {
			return isCorrupt(err) && errors.Is(err, resp.ErrCorrupt)
		}},
		{"frame over the cap", func(r *readerRig) {
			r.expect(false)
			r.write("$" + "17000000" + "\r\n")
		}, func(err error) bool { return isCorrupt(err) && errors.Is(err, resp.ErrFrameTooLarge) }},
		{"EOF", func(r *readerRig) { r.s.DropAll() }, func(err error) bool {
			var cl *ConnectionLostError
			return errors.As(err, &cl) && cl.Msg == "the cache closed the connection"
		}},
		{"EOF inside a reply", func(r *readerRig) {
			r.expect(false)
			r.write("$10\r\nabc")
			time.Sleep(10 * time.Millisecond)
			r.s.DropAll()
		}, func(err error) bool {
			var cl *ConnectionLostError
			return errors.As(err, &cl) && strings.Contains(cl.Msg, "inside a reply")
		}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			r := newReaderRig(t)
			waiting := r.expect(true) // deferred, so the FIFO is empty for the case
			r.write("+w\r\n")
			waitFor(t, "the deferral", func() bool { return r.conn.pending.deferredCount() == 1 })
			tc.inject(r)
			err := r.lost.wait(t)
			if !tc.check(err) {
				t.Fatalf("onLost(%v)", err)
			}
			if _, werr := result(t, waiting); werr != err {
				t.Fatalf("pending entry got %v, onLost %v", werr, err)
			}
			if r.conn.alive() || r.conn.err() != err {
				t.Fatal("still alive")
			}
			waitFor(t, "the reader to stop", func() bool {
				select {
				case <-r.conn.readerDone:
					return true
				default:
					return false
				}
			})
			r.conn.close()
			if n := r.lost.count(); n != 1 {
				t.Fatalf("onLost called %d times", n)
			}
			if !strings.Contains(r.logs.Text(), "connection was lost") {
				t.Fatalf("no warning:\n%s", r.logs.Text())
			}
		})
	}
}

func (p *pendingRequests) deferredCount() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return len(p.deferred)
}

func isCorrupt(err error) bool {
	var cl *ConnectionLostError
	return errors.As(err, &cl) && cl.Msg == "reply stream corrupt" && errors.As(err, new(*ProtocolError))
}

func TestCloseFailsPendingAndStopsReader(t *testing.T) {
	r := newReaderRig(t)
	a, b := r.expect(true), r.expect(false)
	r.write("+3\r\n")
	waitFor(t, "a deferred", func() bool { return r.conn.pending.deferredCount() == 1 })
	var wg sync.WaitGroup
	for range 8 {
		wg.Add(1)
		go func() { defer wg.Done(); r.conn.close() }()
	}
	wg.Wait()
	for _, e := range []*pendingEntry{a, b} {
		var cl *ConnectionLostError
		if _, err := result(t, e); !errors.As(err, &cl) || cl.Msg != "the connection was closed" {
			t.Fatalf("pending entry: %v", err)
		}
	}
	select {
	case <-r.conn.readerDone:
	default:
		t.Fatal("close returned before the reader stopped")
	}
	if r.lost.count() != 0 || strings.Contains(r.logs.Text(), "connection was lost") {
		t.Fatal("close reported a lost connection")
	}
}

func TestDrop(t *testing.T) {
	r := newReaderRig(t)
	e := r.expect(true)
	r.conn.drop("the cache replied MOVED")
	err := r.lost.wait(t)
	var cl *ConnectionLostError
	if !errors.As(err, &cl) || cl.Msg != "the cache replied MOVED" {
		t.Fatalf("onLost(%v)", err)
	}
	if _, perr := result(t, e); perr != err {
		t.Fatalf("pending: %v", perr)
	}
}

// Over TLS the cache's close is the end of the stream, not a read failure.
func TestReaderTLSDropIsEndOfStream(t *testing.T) {
	s, addr := startMock(t, mockcache.Config{TLS: true})
	_, _, lost, err := openMock(t, s, mockConfig(t, s, addr))
	if err != nil {
		t.Fatal(err)
	}
	s.DropAll()
	var cl *ConnectionLostError
	if err := lost.wait(t); !errors.As(err, &cl) || cl.Msg != "the cache closed the connection" {
		t.Fatalf("onLost(%v)", err)
	}
}

// +OK at the head of an aws.request is that request's reply, not a deferred id.
func TestOKAtRequestHeadResolvesHead(t *testing.T) {
	r := newReaderRig(t)
	a := r.expect(true)
	r.write("+OK\r\n")
	v, err := result(t, a)
	if err != nil || string(v.Bytes) != "OK" || r.conn.pending.deferredCount() != 0 || !r.conn.alive() {
		t.Fatalf("value %v err %v deferred %d alive %v", v, err, r.conn.pending.deferredCount(), r.conn.alive())
	}
}

// A push for an id nobody waits for is dropped, logged at Debug, and the reader goes on.
func TestUnknownPushIDDroppedAtDebug(t *testing.T) {
	r := newReaderRig(t)
	r.push("99", resp.IntValue(1))
	waitFor(t, "the record", func() bool { return len(eventRecords(r.logs, eventLatePushDropped)) == 1 })
	if rec := eventRecords(r.logs, eventLatePushDropped)[0]; rec.Level != slog.LevelDebug {
		t.Fatalf("level %v", rec.Level)
	}
	a := r.expect(true)
	r.write("+OK\r\n")
	if _, err := result(t, a); err != nil || !r.conn.alive() {
		t.Fatalf("the next reply: %v, alive %v", err, r.conn.alive())
	}
}
