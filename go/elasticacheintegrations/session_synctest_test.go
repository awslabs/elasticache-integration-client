// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

//go:build go1.25

package elasticacheintegrations

import (
	"context"
	"errors"
	"log/slog"
	"strings"
	"sync"
	"testing"
	"testing/synctest"
	"time"

	"github.com/awslabs/elasticache-integration-client/go/internal/mockcache"
	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
	"github.com/awslabs/elasticache-integration-client/go/internal/testutil"
)

// sessionAuth is an authProvider that counts its calls.
type sessionAuth struct {
	staticAuth
	mu      sync.Mutex
	forced  int
	closedN int
}

func (a *sessionAuth) forceRefresh(context.Context) error {
	a.mu.Lock()
	a.forced++
	a.mu.Unlock()
	return nil
}

func (a *sessionAuth) close() {
	a.mu.Lock()
	a.closedN++
	a.mu.Unlock()
}

func (a *sessionAuth) counts() (credentials, forced, closed int) {
	a.staticAuth.mu.Lock()
	credentials = a.calls
	a.staticAuth.mu.Unlock()
	a.mu.Lock()
	defer a.mu.Unlock()
	return credentials, a.forced, a.closedN
}

// sessionMock is a mock cache over net.Pipe with a config for sessions: no jitter, and the
// records captured.
func sessionMock(t *testing.T, mc mockcache.Config) (*mockcache.Server, *config, *testutil.LogCapture) {
	t.Helper()
	s, c := pipeMock(t, mc)
	c.jitter = 0
	logs, l := testutil.NewLogCapture()
	c.logger = newLogger(l)
	return s, c, logs
}

// openTestSession opens a session and closes it at cleanup.
func openTestSession(t *testing.T, c *config, auth authProvider) *session {
	t.Helper()
	s, err := openSession(context.Background(), c, auth)
	if err != nil {
		t.Fatalf("openSession: %v", err)
	}
	t.Cleanup(s.close)
	return s
}

// commandCount counts the commands named name the mock received.
func commandCount(s *mockcache.Server, name string) int {
	n := 0
	for _, c := range s.Commands() {
		if c.Name() == name {
			n++
		}
	}
	return n
}

// helloFails makes the first n HELLOs fail with reply, then lets them through.
func helloFails(s *mockcache.Server, n int, reply resp.Value) {
	var mu sync.Mutex
	s.SetIntercept(func(cmd mockcache.Command) *mockcache.Reply {
		if cmd.Name() != "HELLO" {
			return nil
		}
		mu.Lock()
		defer mu.Unlock()
		if n == 0 {
			return nil
		}
		n--
		r := mockcache.Now(reply)
		return &r
	})
}

func TestOpenSession(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, _ := sessionMock(t, mockcache.Config{})
		auth := &sessionAuth{}
		s, err := openSession(context.Background(), c, auth)
		if err != nil {
			t.Fatal(err)
		}
		if s.live.Load() == nil || s.currentState() != sessionOpen || s.closed() {
			t.Fatal("session not open")
		}
		snap := s.snap.Load()
		if snap == nil || !snap.predicates.shouldRoute(getItem("u")) || snap.config.refreshInterval != time.Minute {
			t.Fatalf("snapshot %+v", snap)
		}
		if len(m.Overrides()) != 1 {
			t.Fatalf("overrides sent %d times", len(m.Overrides()))
		}
		s.close()
		s.close()
		if _, _, closed := auth.counts(); closed != 1 || s.live.Load() != nil || !s.closed() {
			t.Fatalf("after close: auth closed %d times", closed)
		}
	})
}

func TestOpenRetriesUntilConnectionTimeout(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, logs := sessionMock(t, mockcache.Config{})
		helloFails(m, 1000, resp.ErrorValue("ERR", "not now"))
		start := time.Now()
		_, err := openSession(context.Background(), c, &sessionAuth{})
		var ce *ConnectionError
		if !errors.As(err, &ce) || !strings.Contains(ce.Msg, "timed out after 5000ms") || ce.Err == nil {
			t.Fatalf("err %v", err)
		}
		if !strings.Contains(err.Error(), "HELLO 3 rejected: ERR") {
			t.Fatalf("last failure lost: %v", err)
		}
		if took := time.Since(start); took != 5*time.Second {
			t.Fatalf("returned after %v", took)
		}
		// Attempts at 0, 0.1, 0.3, 0.7, 1.5 and 3.1 s; the next backoff is cut at the deadline.
		if n := commandCount(m, "HELLO"); n != 6 {
			t.Fatalf("%d attempts, want 6", n)
		}
		if n := len(eventRecords(logs, eventBringUpAttemptFailed)); n != 6 {
			t.Fatalf("%d 1302 records", n)
		}
	})
}

func TestOpenSucceedsAfterFailures(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, _ := sessionMock(t, mockcache.Config{})
		helloFails(m, 2, resp.ErrorValue("ERR", "not now"))
		start := time.Now()
		s := openTestSession(t, c, &sessionAuth{})
		if took := time.Since(start); took != 300*time.Millisecond || s.live.Load() == nil {
			t.Fatalf("opened after %v", took)
		}
	})
}

func TestOpenAuthErrorForcesRefresh(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, _ := sessionMock(t, mockcache.Config{})
		helloFails(m, 1000, resp.ErrorValue("WRONGPASS", "invalid username-password pair"))
		auth := &sessionAuth{}
		_, err := openSession(context.Background(), c, auth)
		var ae *AuthError
		if !errors.As(err, &ae) || ae.Code != "WRONGPASS" || errors.As(err, new(*ConnectionError)) {
			t.Fatalf("err %v", err)
		}
		creds, forced, closed := auth.counts()
		if forced != 6 || creds != 6 || closed != 1 {
			t.Fatalf("credentials %d, forceRefresh %d, close %d; want 6, 6, 1", creds, forced, closed)
		}
	})
}

func TestOpenStopsAtOnceOnOverridesRejected(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, logs := sessionMock(t, mockcache.Config{})
		intercept(m, "AWS.CONFIG", mockcache.Now(resp.ErrorValue("CONFIG_INVALID", "unknown table")))
		start := time.Now()
		_, err := openSession(context.Background(), c, &sessionAuth{})
		if !errors.As(err, new(*OverridesRejectedError)) || time.Since(start) != 0 || commandCount(m, "HELLO") != 1 {
			t.Fatalf("err %v after %v, %d attempts", err, time.Since(start), commandCount(m, "HELLO"))
		}
		if recs := eventRecords(logs, eventConfigUnusable); len(recs) != 1 || recs[0].Level != slog.LevelError {
			t.Fatalf("1307 records: %v", recs)
		}
	})
}

func TestOpenStopsAtOnceOnUnevaluableConfig(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, logs := sessionMock(t, mockcache.Config{ConfigJSON: `{"version":"1.0","refresh_interval":60,"predicates":[{"expr":"service ==="}]}`})
		start := time.Now()
		_, err := openSession(context.Background(), c, &sessionAuth{})
		if !errors.As(err, new(*ConfigUnevaluableError)) || time.Since(start) != 0 || commandCount(m, "HELLO") != 1 {
			t.Fatalf("err %v after %v", err, time.Since(start))
		}
		if recs := eventRecords(logs, eventConfigUnusable); len(recs) != 1 || recs[0].Level != slog.LevelError {
			t.Fatalf("1307 records: %v", recs)
		}
	})
}

func TestOpenCallerCancels(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, _ := sessionMock(t, mockcache.Config{})
		helloFails(m, 1000, resp.ErrorValue("ERR", "not now"))
		ctx, cancel := context.WithCancel(context.Background())
		time.AfterFunc(time.Second, cancel)
		auth := &sessionAuth{}
		start := time.Now()
		_, err := openSession(ctx, c, auth)
		if !errors.Is(err, context.Canceled) || time.Since(start) != time.Second {
			t.Fatalf("err %v after %v", err, time.Since(start))
		}
		if _, _, closed := auth.counts(); closed != 1 {
			t.Fatal("auth not closed")
		}
	})
}

func TestCloseStopsOpen(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, _ := sessionMock(t, mockcache.Config{})
		intercept(m, "HELLO", mockcache.Hang())
		s := newSession(c, &sessionAuth{})
		done := make(chan error, 1)
		go func() { done <- s.open(context.Background()) }()
		time.Sleep(time.Second)
		s.close()
		var ce *ConnectionError
		if err := <-done; !errors.As(err, &ce) || ce.Msg != "the client is closed" {
			t.Fatalf("open: %v", err)
		}
		if s.live.Load() != nil {
			t.Fatal("a connection was published after close")
		}
	})
}

// currentState reads the state under the lock.
func (s *session) currentState() sessionState {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.state
}

// waitLive waits until the session has a live connection other than old.
func waitLive(t *testing.T, s *session, old *connection) *connection {
	t.Helper()
	for range 1000 {
		synctest.Wait()
		c := s.live.Load()
		if c != nil && c != old {
			return c
		}
		if o := s.outage.Load(); c == nil && o != nil {
			select {
			case <-o.ready:
			case <-time.After(time.Hour):
				t.Fatal("no reconnect within an hour")
			}
			continue
		}
		time.Sleep(time.Second)
	}
	t.Fatal("no new live connection")
	return nil
}

func TestReconnectAfterDrop(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, logs := sessionMock(t, mockcache.Config{})
		s := openTestSession(t, c, &sessionAuth{})
		first := s.live.Load()
		start := time.Now()
		m.DropAll()
		synctest.Wait()
		second := s.live.Load()
		if second == nil || second == first || first.alive() || time.Since(start) != 0 {
			t.Fatalf("not reconnected at once")
		}
		if n := len(m.Overrides()); n != 2 {
			t.Fatalf("overrides sent %d times, want again on reconnect", n)
		}
		select {
		case <-s.outage.Load().ready:
		default:
			t.Fatal("the outage did not end")
		}
		if recs := eventRecords(logs, eventReconnected); len(recs) != 1 || recs[0].Level != slog.LevelDebug {
			t.Fatalf("1304 records: %v; want one at Debug, the first attempt worked", recs)
		}
		m.DropAll() // a second outage
		if third := waitLive(t, s, second); third == second {
			t.Fatal("second drop not reconnected")
		}
	})
}

func TestReconnectBackoffUnbounded(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, logs := sessionMock(t, mockcache.Config{})
		s := openTestSession(t, c, &sessionAuth{})
		first := s.live.Load()
		helloFails(m, 120, resp.ErrorValue("ERR", "not now"))
		start := time.Now()
		m.DropAll()
		waitLive(t, s, first)
		// Failed attempts wait 0.1, 0.2, 0.4, 0.8, 1.6, then 3.2 s each: 115 at 3.2 s.
		want := 3100*time.Millisecond + 115*3200*time.Millisecond
		if took := time.Since(start); took < want || took > want+10*time.Millisecond {
			t.Fatalf("reconnected after %v, want %v", took, want)
		}
		var warns, debugs int
		for _, r := range eventRecords(logs, eventReconnecting) {
			if r.Level == slog.LevelWarn {
				warns++
			} else {
				debugs++
			}
		}
		if warns != 2 || debugs != 118 {
			t.Fatalf("1303: %d warnings, %d debug; want 2 (1st, 100th) and 118", warns, debugs)
		}
		rec := eventRecords(logs, eventReconnected)
		if len(rec) != 1 || rec[0].Level != slog.LevelInfo {
			t.Fatalf("1304: %v", rec)
		}
		if v, _ := testutil.Attr(rec[0], "failed_attempts"); v.Uint64() != 120 {
			t.Fatalf("1304: %v", rec)
		}
	})
}

func TestReconnectAttemptCappedAtFiveSeconds(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, _ := sessionMock(t, mockcache.Config{})
		c.connectionTimeout = 50 * time.Millisecond
		s := openTestSession(t, c, &sessionAuth{})
		first := s.live.Load()
		var mu sync.Mutex
		hung := 0
		m.SetIntercept(func(cmd mockcache.Command) *mockcache.Reply {
			mu.Lock()
			defer mu.Unlock()
			if cmd.Name() != "HELLO" || hung == 2 {
				return nil
			}
			hung++
			r := mockcache.Hang()
			return &r
		})
		start := time.Now()
		m.DropAll()
		waitLive(t, s, first)
		// Two 5s attempts, with 100ms and 200ms backoffs after them.
		if took := time.Since(start); took < 10300*time.Millisecond || took > 10310*time.Millisecond {
			t.Fatalf("reconnected after %v", took)
		}
	})
}

func TestOutageDeadlinesSetOnceAtDrop(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, _ := sessionMock(t, mockcache.Config{})
		window := 30 * time.Second
		c.fallbackUnlimited, c.fallbackDuration = false, window
		s := openTestSession(t, c, &sessionAuth{})
		helloFails(m, 5, resp.ErrorValue("ERR", "not now"))
		drop := time.Now()
		m.DropAll()
		synctest.Wait()
		o := s.outage.Load()
		if o == nil || !o.readyAt.Equal(drop.Add(5*time.Second)) || !o.fallbackUntil.Equal(o.readyAt.Add(window)) || o.unlimited {
			t.Fatalf("outage %+v", o)
		}
		time.Sleep(2 * time.Second) // more failed attempts
		if s.outage.Load() != o || !o.readyAt.Equal(drop.Add(5*time.Second)) {
			t.Fatal("the outage deadlines moved")
		}
	})
}

func TestConnectionDyingAtOnceIsReplaced(t *testing.T) {
	for _, phase := range []string{"open", "reconnect"} {
		t.Run(phase, func(t *testing.T) {
			synctest.Test(t, func(t *testing.T) {
				m, c, _ := sessionMock(t, mockcache.Config{})
				config := mockcache.DropAfter(resp.BulkStringValue(mockcache.RouteTenOperations))
				var mu sync.Mutex
				dropped := 0
				armed := phase == "open"
				arm := func() {
					m.SetIntercept(func(cmd mockcache.Command) *mockcache.Reply {
						mu.Lock()
						defer mu.Unlock()
						if cmd.Name() != "AWS.CONFIG" || dropped == 1 {
							return nil
						}
						dropped++
						return &config
					})
				}
				if armed {
					arm()
				}
				s := openTestSession(t, c, &sessionAuth{})
				if !armed {
					arm()
					m.DropAll()
				}
				live := waitLive(t, s, nil)
				synctest.Wait()
				if s.live.Load() != live || !live.alive() || commandCount(m, "HELLO") < 2 {
					t.Fatalf("live %p, %d HELLOs", s.live.Load(), commandCount(m, "HELLO"))
				}
			})
		})
	}
}

func TestCloseDuringReconnect(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, _ := sessionMock(t, mockcache.Config{})
		auth := &sessionAuth{}
		s, err := openSession(context.Background(), c, auth)
		if err != nil {
			t.Fatal(err)
		}
		intercept(m, "HELLO", mockcache.Hang())
		m.DropAll()
		time.Sleep(time.Second)
		start := time.Now()
		s.close()
		if time.Since(start) != 0 || s.live.Load() != nil {
			t.Fatalf("close took %v", time.Since(start))
		}
		if _, _, closed := auth.counts(); closed != 1 {
			t.Fatal("auth not closed")
		}
		time.Sleep(time.Minute)
		if n := commandCount(m, "HELLO"); n != 2 {
			t.Fatalf("%d HELLOs: the reconnect loop ran after close", n)
		}
	})
}

func TestLostIgnoresOtherConnections(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		_, c, _ := sessionMock(t, mockcache.Config{})
		s := openTestSession(t, c, &sessionAuth{})
		live := s.live.Load()
		s.lost(&connection{})
		if s.live.Load() != live || s.currentState() != sessionOpen || s.outage.Load() != nil {
			t.Fatal("lost acted on a connection that is not live")
		}
	})
}

func TestReconnectLogsRejectedOverridesEveryAttempt(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, logs := sessionMock(t, mockcache.Config{})
		s := openTestSession(t, c, &sessionAuth{})
		first := s.live.Load()
		var mu sync.Mutex
		rejected := 0
		m.SetIntercept(func(cmd mockcache.Command) *mockcache.Reply {
			mu.Lock()
			defer mu.Unlock()
			if cmd.Name() != "AWS.CONFIG" || rejected == 3 {
				return nil
			}
			rejected++
			r := mockcache.Now(resp.ErrorValue("CONFIG_INVALID", "unknown table"))
			return &r
		})
		m.DropAll()
		waitLive(t, s, first)
		if recs := eventRecords(logs, eventConfigUnusable); len(recs) != 3 || recs[0].Level != slog.LevelError {
			t.Fatalf("1307 records: %v", recs)
		}
	})
}

// closeGateAuth is a sessionAuth whose close waits for release.
type closeGateAuth struct {
	sessionAuth
	release chan struct{}
}

func (a *closeGateAuth) close() {
	<-a.release
	a.sessionAuth.close()
}

func TestConcurrentCloseWaitsForTheFirst(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		_, c, _ := sessionMock(t, mockcache.Config{})
		auth := &closeGateAuth{release: make(chan struct{})}
		s, err := openSession(context.Background(), c, auth)
		if err != nil {
			t.Fatal(err)
		}
		go s.close()
		synctest.Wait()
		second := make(chan struct{})
		go func() { s.close(); close(second) }()
		synctest.Wait()
		select {
		case <-second:
			t.Fatal("a second close returned before the first finished")
		default:
		}
		close(auth.release)
		<-second
		if _, _, closed := auth.counts(); closed != 1 {
			t.Fatalf("auth closed %d times", closed)
		}
	})
}

func TestPublishTakesTheReconnectFailures(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		_, c, _ := sessionMock(t, mockcache.Config{})
		s := openTestSession(t, c, &sessionAuth{})
		conn, snap, err := s.attempt(context.Background(), time.Now().Add(time.Second))
		if err != nil {
			t.Fatal(err)
		}
		s.reconnectFailures.failed()
		s.reconnectFailures.failed()
		old := s.live.Load()
		n, ok := s.publish(conn, snap)
		if !ok || n != 2 || s.reconnectFailures.recovered() != 0 {
			t.Fatalf("publish: %v, %d failures", ok, n)
		}
		old.close()
	})
}
