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
	"sync/atomic"
	"testing"
	"testing/synctest"
	"time"

	"github.com/awslabs/elasticache-integration-client/go/internal/mockcache"
	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
	"github.com/awslabs/elasticache-integration-client/go/internal/testutil"
)

// commandTimes returns when, from start, each command named name arrived. It is filled by an
// intercept that answers the command with reply(n) for its n-th arrival, or lets it through
// when reply returns nil.
type commandTimes struct {
	mu sync.Mutex
	at []time.Duration
}

func (ct *commandTimes) get() []time.Duration {
	ct.mu.Lock()
	defer ct.mu.Unlock()
	return append([]time.Duration(nil), ct.at...)
}

func timeCommand(m *mockcache.Server, name string, start time.Time, reply func(n int) *mockcache.Reply) *commandTimes {
	ct := &commandTimes{}
	m.SetIntercept(func(cmd mockcache.Command) *mockcache.Reply {
		if cmd.Name() != name || (name == "AWS.CONFIG" && len(cmd.Args) != 1) {
			return nil
		}
		ct.mu.Lock()
		ct.at = append(ct.at, time.Since(start))
		n := len(ct.at)
		ct.mu.Unlock()
		return reply(n)
	})
	return ct
}

func pass(int) *mockcache.Reply { return nil }

func reply(v resp.Value) *mockcache.Reply {
	r := mockcache.Now(v)
	return &r
}

const routeGetItem = `{"version":"1.0","refresh_interval":30,"predicates":[{"expr":"operation == 'GetItem'"}]}`

func TestReauthEveryInterval(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, _ := sessionMock(t, mockcache.Config{})
		auth := &sessionAuth{}
		start := time.Now()
		s := openTestSession(t, c, auth)
		ct := timeCommand(m, "AUTH", start, pass)
		time.Sleep(2*c.reauthInterval + time.Second)
		if got, want := ct.get(), []time.Duration{900 * time.Second, 1800 * time.Second}; !equalDurations(got, want) {
			t.Fatalf("AUTH at %v, want %v", got, want)
		}
		if creds, _, _ := auth.counts(); creds != 3 {
			t.Fatalf("%d credentials calls, want one per HELLO and per AUTH", creds)
		}
		if !s.live.Load().alive() {
			t.Fatal("connection lost")
		}
	})
}

func TestReauthRejectedKeepsConnectionAndBacksOff(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, logs := sessionMock(t, mockcache.Config{})
		auth := &sessionAuth{}
		start := time.Now()
		s := openTestSession(t, c, auth)
		conn := s.live.Load()
		ct := timeCommand(m, "AUTH", start, func(n int) *mockcache.Reply {
			if n <= 3 {
				return reply(resp.ErrorValue("WRONGPASS", "invalid"))
			}
			return nil
		})
		time.Sleep(c.reauthInterval + time.Second + c.reauthInterval)
		want := []time.Duration{900 * time.Second, 900*time.Second + 100*time.Millisecond,
			900*time.Second + 300*time.Millisecond, 900*time.Second + 700*time.Millisecond,
			1800*time.Second + 700*time.Millisecond}
		if got := ct.get(); !equalDurations(got, want) {
			t.Fatalf("AUTH at %v, want %v", got, want)
		}
		if _, forced, _ := auth.counts(); forced != 3 {
			t.Fatalf("forceRefresh %d times, want one per rejection", forced)
		}
		if s.live.Load() != conn || !conn.alive() {
			t.Fatal("a rejected AUTH dropped the connection")
		}
		if len(eventRecords(logs, eventPeriodicFailed)) != 3 || len(eventRecords(logs, eventPeriodicRecovered)) != 1 {
			t.Fatalf("records:\n%s", logs.Text())
		}
	})
}

func TestReauthBadReplyOrTimeoutReconnects(t *testing.T) {
	for name, r := range map[string]mockcache.Reply{
		"non-OK reply": mockcache.Now(resp.IntValue(1)),
		"timeout":      mockcache.Hang(),
	} {
		t.Run(name, func(t *testing.T) {
			synctest.Test(t, func(t *testing.T) {
				m, c, _ := sessionMock(t, mockcache.Config{})
				auth := &sessionAuth{}
				s := openTestSession(t, c, auth)
				conn := s.live.Load()
				intercept(m, "AUTH", r)
				time.Sleep(c.reauthInterval)
				waitLive(t, s, conn)
				if conn.alive() {
					t.Fatal("old connection alive")
				}
				if _, forced, _ := auth.counts(); forced != 0 {
					t.Fatalf("forceRefresh %d times on a closed connection", forced)
				}
			})
		})
	}
}

func TestReauthCredentialsFailure(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, _ := sessionMock(t, mockcache.Config{})
		auth := &sessionAuth{}
		start := time.Now()
		s := openTestSession(t, c, auth)
		auth.staticAuth.mu.Lock()
		auth.err = &ConnectionError{Msg: "no token"}
		auth.staticAuth.mu.Unlock()
		ct := timeCommand(m, "AUTH", start, pass)
		time.Sleep(c.reauthInterval + time.Second)
		creds, _, _ := auth.counts()
		// 900 s, then 0.1, 0.2, 0.4 s backoffs: four failed credentials calls, no AUTH.
		if creds != 1+4 || len(ct.get()) != 0 || !s.live.Load().alive() {
			t.Fatalf("%d credentials calls, AUTH at %v", creds, ct.get())
		}
	})
}

func TestReauthSkippedWithoutConnection(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, logs := sessionMock(t, mockcache.Config{})
		start := time.Now()
		openTestSession(t, c, &sessionAuth{})
		ct := timeCommand(m, "HELLO", start, func(int) *mockcache.Reply {
			return reply(resp.ErrorValue("ERR", "down"))
		})
		m.DropAll()
		time.Sleep(2 * c.reauthInterval)
		auths := 0
		for _, cmd := range m.Commands() {
			if cmd.Name() == "AUTH" {
				auths++
			}
		}
		if auths != 0 || len(ct.get()) < 100 {
			t.Fatalf("%d AUTHs during the outage, %d HELLOs", auths, len(ct.get()))
		}
		if n := len(eventRecords(logs, eventPeriodicFailed)); n != 0 {
			t.Fatalf("a skipped run failed:\n%s", logs.Text())
		}
	})
}

func TestConfigRefreshAppliesNewConfig(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, _ := sessionMock(t, mockcache.Config{})
		start := time.Now()
		s := openTestSession(t, c, &sessionAuth{})
		ct := timeCommand(m, "AWS.CONFIG", start, func(n int) *mockcache.Reply {
			if n == 1 {
				return reply(resp.BulkStringValue(routeGetItem))
			}
			return reply(resp.NullValue())
		})
		put := getItem("u")
		put.metadata = newRequestMetadata("dynamodb", "PutItem")
		if !s.snap.Load().predicates.shouldRoute(put) {
			t.Fatal("PutItem not routed before the refresh")
		}
		time.Sleep(time.Minute + 30*time.Second + time.Second)
		if got, want := ct.get(), []time.Duration{60 * time.Second, 90 * time.Second}; !equalDurations(got, want) {
			t.Fatalf("refresh at %v, want %v: the new 30s interval applies", got, want)
		}
		snap := s.snap.Load()
		if snap.config.refreshInterval != 30*time.Second || snap.predicates.shouldRoute(put) ||
			!snap.predicates.shouldRoute(getItem("u")) {
			t.Fatalf("snapshot %+v", snap.config)
		}
		if o := s.request(context.Background(), put); o.kind != outcomeOrigin {
			t.Fatalf("PutItem after the refresh: %+v", o)
		}
	})
}

func TestConfigRefreshFailureKeepsConfigAndWaitsInterval(t *testing.T) {
	for name, r := range map[string]resp.Value{
		"error reply":       resp.ErrorValue("INTERNAL_ERROR", "metadata"),
		"wrong shape":       resp.IntValue(3),
		"unevaluable":       resp.BulkStringValue(`{"version":"1.0","refresh_interval":5,"predicates":[{"expr":"("}]}`),
		"overrides refused": resp.ErrorValue("CONFIG_INVALID", "bad"),
	} {
		t.Run(name, func(t *testing.T) {
			synctest.Test(t, func(t *testing.T) {
				m, c, logs := sessionMock(t, mockcache.Config{})
				start := time.Now()
				s := openTestSession(t, c, &sessionAuth{})
				before := s.snap.Load()
				conn := s.live.Load()
				ct := timeCommand(m, "AWS.CONFIG", start, func(int) *mockcache.Reply { return reply(r) })
				time.Sleep(3*time.Minute + time.Second)
				if got, want := ct.get(), []time.Duration{60 * time.Second, 120 * time.Second, 180 * time.Second}; !equalDurations(got, want) {
					t.Fatalf("refresh at %v, want %v", got, want)
				}
				if s.snap.Load() != before || s.live.Load() != conn || !conn.alive() {
					t.Fatal("a failed refresh changed the config or the connection")
				}
				unusable := len(eventRecords(logs, eventConfigUnusable))
				if (name == "unevaluable" || name == "overrides refused") != (unusable == 3) {
					t.Fatalf("%d 1307 records", unusable)
				}
				if n := len(eventRecords(logs, eventPeriodicFailed)); n != 3 {
					t.Fatalf("%d 1101 records", n)
				}
			})
		})
	}
}

func TestConfigStaleDropsConnection(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, logs := sessionMock(t, mockcache.Config{})
		c.cachePolicyTimeout = 150 * time.Second
		start := time.Now()
		s := openTestSession(t, c, &sessionAuth{})
		conn := s.live.Load()
		// Confirmed at 60 s; failures at 120 and 180 s keep it; the one at 240 s drops it.
		timeCommand(m, "AWS.CONFIG", start, func(n int) *mockcache.Reply {
			if n == 1 {
				return reply(resp.NullValue())
			}
			return reply(resp.ErrorValue("INTERNAL_ERROR", "metadata"))
		})
		time.Sleep(4*time.Minute - time.Second)
		if !conn.alive() || len(eventRecords(logs, eventConfigStale)) != 0 {
			t.Fatal("dropped inside the window")
		}
		time.Sleep(time.Second)
		synctest.Wait()
		if conn.alive() {
			t.Fatal("stale config kept past CachePolicyTimeout")
		}
		recs := eventRecords(logs, eventConfigStale)
		if len(recs) != 1 || recs[0].Level != slog.LevelWarn {
			t.Fatalf("1306 records: %v", recs)
		}
		next := waitLive(t, s, conn)
		if len(m.Overrides()) != 2 || !next.alive() {
			t.Fatalf("overrides sent %d times", len(m.Overrides()))
		}
	})
}

func TestConfigStaleThenOutageIsStrict(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, _ := sessionMock(t, mockcache.Config{})
		strictConfig(c)
		c.cachePolicyTimeout = 60 * time.Second
		s := openTestSession(t, c, &sessionAuth{})
		m.SetIntercept(func(cmd mockcache.Command) *mockcache.Reply {
			switch {
			case cmd.Name() == "AWS.CONFIG" && len(cmd.Args) == 1:
				return reply(resp.ErrorValue("INTERNAL_ERROR", "metadata"))
			case cmd.Name() == "HELLO":
				return reply(resp.ErrorValue("ERR", "down"))
			}
			return nil
		})
		time.Sleep(time.Minute)
		synctest.Wait()
		o := s.request(context.Background(), getItem("u"))
		if o.kind != outcomeFailed || !errors.As(o.err, new(*CacheUnavailableError)) {
			t.Fatalf("after a stale drop, strict: %+v", o)
		}
	})
}

func TestReconnectAppliesNewRefreshInterval(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, _ := sessionMock(t, mockcache.Config{})
		s := openTestSession(t, c, &sessionAuth{})
		conn := s.live.Load()
		m.SetConfigJSON(routeGetItem)
		drop := time.Now()
		m.DropAll()
		waitLive(t, s, conn)
		ct := timeCommand(m, "AWS.CONFIG", drop, pass)
		time.Sleep(61 * time.Second)
		// The wait armed at start-up (60 s) is kept; then the new 30 s interval.
		if got, want := ct.get(), []time.Duration{60 * time.Second}; !equalDurations(got, want) {
			t.Fatalf("refresh at %v, want %v", got, want)
		}
		time.Sleep(30 * time.Second)
		if got := ct.get(); len(got) != 2 || got[1] != 90*time.Second {
			t.Fatalf("refresh at %v", got)
		}
		if s.snap.Load().config.refreshInterval != 30*time.Second {
			t.Fatal("new config not applied")
		}
	})
}

func TestSessionNoSecretsInLogs(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, logs := sessionMock(t, mockcache.Config{})
		var text string
		// Start-up: rejected with the token echoed back, then a usable connection.
		helloFails(m, 2, resp.ErrorValue("WRONGPASS", testToken))
		s := openTestSession(t, c, &sessionAuth{})
		// Re-AUTH rejected and refused, request errors, a drop and a reconnect.
		var mu sync.Mutex
		n := 0
		m.SetIntercept(func(cmd mockcache.Command) *mockcache.Reply {
			if cmd.Name() != "AUTH" {
				return nil
			}
			mu.Lock()
			defer mu.Unlock()
			n++
			if n == 1 {
				return reply(resp.ErrorValue("WRONGPASS", testToken))
			}
			return reply(resp.BulkStringValue(testToken))
		})
		for _, code := range []string{"AUTH_ERROR", "UNSUPPORTED", "ORIGIN_ERROR", "MOVED"} {
			replyWith(m, mockcache.Now(resp.ErrorValue(code, "detail")))
			if o := s.request(context.Background(), getItem("u")); o.err != nil {
				text += o.err.Error()
			}
		}
		time.Sleep(c.reauthInterval + time.Second)
		synctest.Wait()
		mu.Lock()
		auths := n
		mu.Unlock()
		// Both AUTH replies that echo the token reached the client: the rejection and the drop.
		if auths < 2 || len(runnerRecords(logs, eventPeriodicFailed, "re-AUTH")) < 2 {
			t.Fatalf("%d AUTHs; records:\n%s", auths, logs.Text())
		}
		text += logs.Text()
		testutil.AssertNoSecrets(t, text, testutil.TokenSecrets(testToken)...)
	})
}

// hookAuth runs hook before each credentials call (n counts the calls from 1), and force in
// place of forceRefresh.
type hookAuth struct {
	sessionAuth
	n     atomic.Int32
	hook  func(ctx context.Context, n int) error
	force func(ctx context.Context) error
}

func (a *hookAuth) credentials(ctx context.Context) (authCredentials, error) {
	if n := int(a.n.Add(1)); a.hook != nil {
		if err := a.hook(ctx, n); err != nil {
			return authCredentials{}, err
		}
	}
	return a.sessionAuth.credentials(ctx)
}

func (a *hookAuth) forceRefresh(ctx context.Context) error {
	if a.force != nil {
		return a.force(ctx)
	}
	return a.sessionAuth.forceRefresh(ctx)
}

// runnerRecords returns the records of event ev logged by the runner named name.
func runnerRecords(logs *testutil.LogCapture, ev eventID, name string) []slog.Record {
	var out []slog.Record
	for _, r := range eventRecords(logs, ev) {
		if strings.HasPrefix(r.Message, name+" ") {
			out = append(out, r)
		}
	}
	return out
}

func TestReauthCredentialsBoundedByRequestTimeout(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, logs := sessionMock(t, mockcache.Config{})
		auth := &hookAuth{}
		auth.hook = func(ctx context.Context, n int) error {
			if n != 2 {
				return nil
			}
			<-ctx.Done() // a token refresh slower than the request timeout
			return ctx.Err()
		}
		start := time.Now()
		s := openTestSession(t, c, auth)
		conn := s.live.Load()
		ct := timeCommand(m, "AUTH", start, pass)
		time.Sleep(c.reauthInterval + c.cacheRequestTimeout + time.Second)
		want := []time.Duration{c.reauthInterval + c.cacheRequestTimeout + 100*time.Millisecond}
		if got := ct.get(); !equalDurations(got, want) {
			t.Fatalf("AUTH at %v, want %v", got, want)
		}
		if len(runnerRecords(logs, eventPeriodicFailed, "re-AUTH")) != 1 || s.live.Load() != conn || !conn.alive() {
			t.Fatalf("records:\n%s", logs.Text())
		}
	})
}

func TestReauthSkipsConnectionReplacedDuringCredentials(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, logs := sessionMock(t, mockcache.Config{})
		var sp atomic.Pointer[session]
		auth := &hookAuth{}
		auth.hook = func(_ context.Context, n int) error {
			if n != 2 {
				return nil
			}
			s := sp.Load()
			old := s.live.Load()
			m.DropAll()
			for c := s.live.Load(); c == nil || c == old; c = s.live.Load() {
				time.Sleep(time.Millisecond)
			}
			return nil
		}
		start := time.Now()
		s := openTestSession(t, c, auth)
		sp.Store(s)
		ct := timeCommand(m, "AUTH", start, pass)
		time.Sleep(c.reauthInterval + time.Second)
		if got := ct.get(); len(got) != 0 || len(runnerRecords(logs, eventPeriodicFailed, "re-AUTH")) != 0 {
			t.Fatalf("AUTH at %v; records:\n%s", got, logs.Text())
		}
		// The skip keeps the schedule: the next run re-authenticates the new connection.
		time.Sleep(c.reauthInterval)
		if got := ct.get(); len(got) != 1 {
			t.Fatalf("AUTH at %v", got)
		}
	})
}

func TestReauthWaitsForAPendingSlot(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, logs := sessionMock(t, mockcache.Config{})
		c.maxPending = 1
		start := time.Now()
		s := openTestSession(t, c, &sessionAuth{})
		conn := s.live.Load()
		replyWith(m, mockcache.DeferredFrom(make(chan resp.Value))) // holds the slot to its deadline
		ct := timeCommand(m, "AUTH", start, pass)
		time.Sleep(c.reauthInterval - time.Second)
		go s.request(context.Background(), getItem("u"))
		time.Sleep(time.Second + c.cacheRequestTimeout)
		// Re-AUTH waits 75% of the timeout for the slot, then retries on the backoff and gets
		// it when the request's deadline frees it.
		want := []time.Duration{c.reauthInterval - time.Second + c.cacheRequestTimeout}
		if got := ct.get(); !equalDurations(got, want) {
			t.Fatalf("AUTH at %v, want %v", got, want)
		}
		failed := runnerRecords(logs, eventPeriodicFailed, "re-AUTH")
		if len(failed) != 1 || len(runnerRecords(logs, eventPeriodicRecovered, "re-AUTH")) != 1 || !conn.alive() {
			t.Fatalf("records:\n%s", logs.Text())
		}
		if v, _ := testutil.Attr(failed[0], "error"); !strings.Contains(v.String(), "not sent") {
			t.Fatalf("failure %v", v)
		}
	})
}

func TestConfigRefreshTimeoutReconnectsAtOnce(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, logs := sessionMock(t, mockcache.Config{})
		c.cachePolicyTimeout = time.Second
		start := time.Now()
		s := openTestSession(t, c, &sessionAuth{})
		conn := s.live.Load()
		timeCommand(m, "AWS.CONFIG", start, func(n int) *mockcache.Reply {
			if n == 1 {
				r := mockcache.Hang()
				return &r
			}
			return nil
		})
		time.Sleep(time.Minute + c.cacheRequestTimeout)
		next := waitLive(t, s, conn)
		if !next.alive() || time.Since(start) != time.Minute+c.cacheRequestTimeout {
			t.Fatalf("reconnected at %v", time.Since(start))
		}
		// The timeout closed the connection: one refresh failure, and no stale drop.
		if len(eventRecords(logs, eventPeriodicFailed)) != 1 || len(eventRecords(logs, eventConfigStale)) != 0 {
			t.Fatalf("records:\n%s", logs.Text())
		}
	})
}

func TestConfigRefreshFromReplacedConnectionChangesNothing(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, logs := sessionMock(t, mockcache.Config{})
		c.cachePolicyTimeout = time.Second
		s := openTestSession(t, c, &sessionAuth{})
		live := s.live.Load()
		other, _ := openPipe(t, m, c) // alive, but not the session's connection
		time.Sleep(2 * time.Second)
		before := s.snap.Load()
		s.mu.Lock()
		confirmed := s.confirmedAt
		s.mu.Unlock()
		if s.refreshConfirmed(other, &snapshot{config: before.config, predicates: before.predicates}) {
			t.Fatal("a reply from another connection confirmed the config")
		}
		s.mu.Lock()
		same := s.confirmedAt.Equal(confirmed)
		s.mu.Unlock()
		if s.snap.Load() != before || !same {
			t.Fatal("a reply from another connection changed the config")
		}
		ran, err := s.refreshFailed(other, &ConfigUnevaluableError{Msg: "x"})
		if ran || err != nil || !other.alive() || !live.alive() || s.live.Load() != live {
			t.Fatalf("refreshFailed on another connection: %v %v", ran, err)
		}
		if len(eventRecords(logs, eventConfigStale)) != 0 || len(eventRecords(logs, eventConfigUnusable)) != 0 {
			t.Fatalf("records:\n%s", logs.Text())
		}
		// The same failure on the live connection, past the timeout, drops it.
		if ran, err := s.refreshFailed(live, &ConfigUnevaluableError{Msg: "x"}); !ran || err == nil || live.alive() {
			t.Fatalf("refreshFailed on the live connection: %v %v", ran, err)
		}
	})
}

func TestCloseDuringBlockedBackgroundRun(t *testing.T) {
	blockOn := func(ctx context.Context) error { <-ctx.Done(); return ctx.Err() }
	for name, setup := range map[string]func(*mockcache.Server, *hookAuth) time.Duration{
		"credentials": func(_ *mockcache.Server, a *hookAuth) time.Duration {
			a.hook = func(ctx context.Context, n int) error {
				if n == 2 {
					return blockOn(ctx)
				}
				return nil
			}
			return 900*time.Second + time.Second
		},
		"forceRefresh": func(m *mockcache.Server, a *hookAuth) time.Duration {
			intercept(m, "AUTH", mockcache.Now(resp.ErrorValue("WRONGPASS", "invalid")))
			a.force = blockOn
			return 900*time.Second + time.Second
		},
		"config refresh": func(m *mockcache.Server, _ *hookAuth) time.Duration {
			timeCommand(m, "AWS.CONFIG", time.Now(), func(int) *mockcache.Reply { r := mockcache.Hang(); return &r })
			return time.Minute + time.Second
		},
	} {
		t.Run(name, func(t *testing.T) {
			synctest.Test(t, func(t *testing.T) {
				m, c, _ := sessionMock(t, mockcache.Config{})
				auth := &hookAuth{}
				wait := setup(m, auth)
				s := openTestSession(t, c, auth)
				time.Sleep(wait)
				at := time.Now()
				s.close()
				if d := time.Since(at); d != 0 {
					t.Fatalf("close took %v", d)
				}
			})
		})
	}
}
