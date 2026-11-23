// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

//go:build go1.25

package elasticacheintegrations

import (
	"context"
	"errors"
	"sync"
	"testing"
	"testing/synctest"
	"time"

	"github.com/awslabs/elasticache-integration-client/go/internal/mockcache"
	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
)

// sessionResult is how one session request ended, and when, from start.
type sessionResult struct {
	requestOutcome
	at time.Duration
}

// sendSession runs one session request in its own goroutine.
func sendSession(ctx context.Context, s *session, start time.Time) <-chan sessionResult {
	ch := make(chan sessionResult, 1)
	go func() {
		o := s.request(ctx, getItem("u"))
		ch <- sessionResult{o, time.Since(start)}
	}()
	return ch
}

// strictConfig makes c strict: a routed request never goes to DynamoDB.
func strictConfig(c *config) { c.fallbackUnlimited, c.fallbackDuration = false, 0 }

// replyWith answers every aws.request with r.
func replyWith(m *mockcache.Server, r mockcache.Reply) {
	m.SetOnRequest(func(mockcache.Command) mockcache.Reply { return r })
}

func TestSessionRequestServed(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, _ := sessionMock(t, mockcache.Config{})
		replyWith(m, mockcache.Deferred(mockcache.Served(400, map[string]string{"x-amzn-errortype": "X"}, []byte("{}"))))
		s := openTestSession(t, c, &sessionAuth{})
		o := s.request(context.Background(), getItem("u"))
		if o.kind != outcomeServed || o.response.statusCode != 400 || o.response.headers.Get("X-Amzn-Errortype") != "X" {
			t.Fatalf("outcome %+v", o)
		}
	})
}

func TestSessionRequestNotRouted(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, _ := sessionMock(t, mockcache.Config{})
		s := openTestSession(t, c, &sessionAuth{})
		r := getItem("u")
		r.metadata = newRequestMetadata("dynamodb", "ListTables")
		if o := s.request(context.Background(), r); o.kind != outcomeOrigin || len(m.Requests()) != 0 {
			t.Fatalf("outcome %+v, %d requests sent", o, len(m.Requests()))
		}
	})
}

func TestSessionRequestServerCodes(t *testing.T) {
	cases := []struct {
		code      string
		strict    bool
		kind      outcomeKind
		retryable bool
		errAs     func(error) bool
		drops     bool
	}{
		{"AUTH_ERROR", false, outcomeOrigin, false, nil, true},
		{"AUTH_ERROR", true, outcomeFailed, true, func(e error) bool { return errors.As(e, new(*CacheUnavailableError)) }, true},
		{"MOVED", false, outcomeFailed, true, func(e error) bool { return errors.As(e, new(*ConnectionLostError)) }, true},
		{"NOAUTH", true, outcomeFailed, true, func(e error) bool { return errors.As(e, new(*ConnectionLostError)) }, true},
		{"ORIGIN_ERROR", false, outcomeFailed, true, func(e error) bool { return errors.As(e, new(*OriginError)) }, false},
		{"INTERNAL_ERROR", true, outcomeFailed, true, func(e error) bool { return errors.As(e, new(*OriginError)) }, false},
		{"UNSUPPORTED", false, outcomeFailed, false, func(e error) bool { return errors.As(e, new(*RejectedError)) }, false},
		{"PARSE_ERROR", true, outcomeFailed, false, func(e error) bool { return errors.As(e, new(*RejectedError)) }, false},
	}
	for _, tc := range cases {
		name := tc.code
		if tc.strict {
			name += "/strict"
		}
		t.Run(name, func(t *testing.T) {
			synctest.Test(t, func(t *testing.T) {
				m, c, logs := sessionMock(t, mockcache.Config{})
				if tc.strict {
					strictConfig(c)
				}
				var mu sync.Mutex
				first := true
				m.SetOnRequest(func(mockcache.Command) mockcache.Reply {
					mu.Lock()
					defer mu.Unlock()
					if first {
						first = false
						return mockcache.Deferred(resp.ErrorValue(tc.code, "detail"))
					}
					return mockcache.Now(mockcache.Served(200, nil, []byte("{}")))
				})
				s := openTestSession(t, c, &sessionAuth{})
				conn := s.live.Load()
				o := s.request(context.Background(), getItem("u"))
				if o.kind != tc.kind || o.retryable != tc.retryable || (tc.errAs != nil && !tc.errAs(o.err)) {
					t.Fatalf("outcome %+v", o)
				}
				if tc.drops == conn.alive() {
					t.Fatalf("connection alive %v after %s", conn.alive(), tc.code)
				}
				if (tc.code == "AUTH_ERROR") != (len(eventRecords(logs, eventAuthDegraded)) == 1) {
					t.Fatalf("1103 records:\n%s", logs.Text())
				}
				// The next request is served, on a new connection after a drop.
				if o := s.request(context.Background(), getItem("u")); o.kind != outcomeServed {
					t.Fatalf("next outcome %+v", o)
				}
				if tc.drops && s.live.Load() == conn {
					t.Fatal("not reconnected")
				}
			})
		})
	}
}

func TestSessionRequestWrongShapeKeepsConnection(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, _ := sessionMock(t, mockcache.Config{})
		replyWith(m, mockcache.Now(resp.IntValue(7)))
		s := openTestSession(t, c, &sessionAuth{})
		conn := s.live.Load()
		o := s.request(context.Background(), getItem("u"))
		if o.kind != outcomeFailed || o.retryable || !errors.As(o.err, new(*ProtocolError)) || !conn.alive() {
			t.Fatalf("outcome %+v, alive %v", o, conn.alive())
		}
	})
}

func TestSessionRequestInOrderTimeout(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, _ := sessionMock(t, mockcache.Config{})
		var mu sync.Mutex
		first := true
		m.SetOnRequest(func(mockcache.Command) mockcache.Reply {
			mu.Lock()
			defer mu.Unlock()
			if first {
				first = false
				return mockcache.Hang()
			}
			return mockcache.Now(mockcache.Served(200, nil, nil))
		})
		s := openTestSession(t, c, &sessionAuth{})
		conn := s.live.Load()
		start := time.Now()
		o := s.request(context.Background(), getItem("u"))
		if o.kind != outcomeFailed || !o.retryable || !errors.As(o.err, new(*TimeoutError)) || time.Since(start) != 5*time.Second {
			t.Fatalf("outcome %+v after %v", o, time.Since(start))
		}
		waitLive(t, s, conn)
		if o := s.request(context.Background(), getItem("u")); o.kind != outcomeServed {
			t.Fatalf("after reconnect: %+v", o)
		}
	})
}

func TestSessionRequestLostInFlight(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, _ := sessionMock(t, mockcache.Config{})
		replyWith(m, mockcache.Hang())
		s := openTestSession(t, c, &sessionAuth{})
		res := sendSession(context.Background(), s, time.Now())
		synctest.Wait()
		m.DropAll()
		o := <-res
		if o.kind != outcomeFailed || !o.retryable || !errors.As(o.err, new(*ConnectionLostError)) {
			t.Fatalf("outcome %+v", o)
		}
		if len(m.Requests()) != 1 {
			t.Fatalf("%d requests: replayed through the cache", len(m.Requests()))
		}
	})
}

func TestSessionRequestNotSentGoesToOrigin(t *testing.T) {
	for _, strict := range []bool{false, true} {
		t.Run(map[bool]string{false: "fallback", true: "strict"}[strict], func(t *testing.T) {
			synctest.Test(t, func(t *testing.T) {
				m, c, logs := sessionMock(t, mockcache.Config{})
				c.maxPending = 1
				if strict {
					strictConfig(c)
				}
				hold := make(chan resp.Value)
				replyWith(m, mockcache.DeferredFrom(hold))
				s := openTestSession(t, c, &sessionAuth{})
				start := time.Now()
				busy := sendSession(context.Background(), s, start)
				synctest.Wait()
				o := s.request(context.Background(), getItem("u"))
				if took := time.Since(start); took != 3750*time.Millisecond {
					t.Fatalf("not sent after %v, want 75%% of 5s", took)
				}
				if strict {
					if o.kind != outcomeFailed || !o.retryable || !errors.As(o.err, new(*CacheUnavailableError)) ||
						!errors.As(o.err, new(*RequestNotSentError)) {
						t.Fatalf("strict outcome %+v", o)
					}
				} else if o.kind != outcomeOrigin {
					t.Fatalf("outcome %+v", o)
				}
				if n := len(eventRecords(logs, eventPendingLimitReached)); n != 1 {
					t.Fatalf("%d 1308 records", n)
				}
				hold <- mockcache.Served(200, nil, nil)
				if b := <-busy; b.kind != outcomeServed {
					t.Fatalf("held request: %+v", b)
				}
			})
		})
	}
}

// TestOutageWaitIsPerOutage: the reconnect wait is counted once per outage, from the drop.
func TestOutageWaitIsPerOutage(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, _ := sessionMock(t, mockcache.Config{})
		s := openTestSession(t, c, &sessionAuth{})
		intercept(m, "HELLO", mockcache.Hang())
		start := time.Now()
		m.DropAll()
		synctest.Wait()
		a := sendSession(context.Background(), s, start)
		time.Sleep(3 * time.Second)
		b := sendSession(context.Background(), s, start)
		ra, rb := <-a, <-b
		if ra.kind != outcomeOrigin || ra.at != 5*time.Second || rb.kind != outcomeOrigin || rb.at != 5*time.Second {
			t.Fatalf("a %+v, b %+v: both wait until 5s after the drop", ra, rb)
		}
		if o := s.request(context.Background(), getItem("u")); o.kind != outcomeOrigin || time.Since(start) != 5*time.Second {
			t.Fatalf("a request after the wait waited again: %+v at %v", o, time.Since(start))
		}
	})
}

// TestReconnectLateInWaitKeepsFullBudget: the request budget starts once a connection is live.
func TestReconnectLateInWaitKeepsFullBudget(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, _ := sessionMock(t, mockcache.Config{})
		s := openTestSession(t, c, &sessionAuth{})
		intercept(m, "HELLO", mockcache.After(4900*time.Millisecond, mockcache.Hello()))
		replyWith(m, mockcache.DeferredAfter(4900*time.Millisecond, mockcache.Served(200, nil, nil)))
		start := time.Now()
		m.DropAll()
		synctest.Wait()
		o := <-sendSession(context.Background(), s, start)
		if o.kind != outcomeServed || o.at != 9800*time.Millisecond {
			t.Fatalf("outcome %+v", o)
		}
	})
}

func TestFallbackWindow(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, logs := sessionMock(t, mockcache.Config{})
		c.fallbackUnlimited, c.fallbackDuration = false, 10*time.Second
		s := openTestSession(t, c, &sessionAuth{})
		intercept(m, "HELLO", mockcache.Now(resp.ErrorValue("ERR", "down")))
		start := time.Now()
		m.DropAll()
		synctest.Wait()
		if o := <-sendSession(context.Background(), s, start); o.kind != outcomeOrigin || o.at != 5*time.Second {
			t.Fatalf("in the wait: %+v", o)
		}
		time.Sleep(10*time.Second - time.Millisecond)
		if o := s.request(context.Background(), getItem("u")); o.kind != outcomeOrigin {
			t.Fatalf("inside the window: %+v", o)
		}
		time.Sleep(time.Millisecond)
		for range 3 {
			o := s.request(context.Background(), getItem("u"))
			if o.kind != outcomeFailed || !o.retryable || !errors.As(o.err, new(*CacheUnavailableError)) {
				t.Fatalf("window spent: %+v", o)
			}
		}
		if n := len(eventRecords(logs, eventCacheUnavailable)); n != 1 {
			t.Fatalf("%d 1305 records, want 1 per outage", n)
		}
	})
}

func TestUnlimitedFallback(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, _ := sessionMock(t, mockcache.Config{})
		s := openTestSession(t, c, &sessionAuth{})
		intercept(m, "HELLO", mockcache.Now(resp.ErrorValue("ERR", "down")))
		m.DropAll()
		synctest.Wait()
		time.Sleep(24 * time.Hour)
		if o := s.request(context.Background(), getItem("u")); o.kind != outcomeOrigin {
			t.Fatalf("outcome %+v", o)
		}
	})
}

func TestReconnectDuringWaitServes(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, _ := sessionMock(t, mockcache.Config{})
		s := openTestSession(t, c, &sessionAuth{})
		intercept(m, "HELLO", mockcache.After(2*time.Second, mockcache.Hello()))
		start := time.Now()
		m.DropAll()
		synctest.Wait()
		if o := <-sendSession(context.Background(), s, start); o.kind != outcomeServed || o.at != 2*time.Second {
			t.Fatalf("outcome %+v", o)
		}
	})
}

func TestCancelDuringOutageWait(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, _ := sessionMock(t, mockcache.Config{})
		s := openTestSession(t, c, &sessionAuth{})
		intercept(m, "HELLO", mockcache.Hang())
		m.DropAll()
		synctest.Wait()
		ctx, cancel := context.WithCancel(context.Background())
		time.AfterFunc(time.Second, cancel)
		o := s.request(ctx, getItem("u"))
		if o.kind != outcomeFailed || o.retryable || !errors.Is(o.err, context.Canceled) {
			t.Fatalf("outcome %+v", o)
		}
		if o := s.request(ctx, getItem("u")); o.kind != outcomeFailed || !errors.Is(o.err, context.Canceled) {
			t.Fatalf("done ctx: %+v", o)
		}
	})
}

func TestCloseWithRequestsInFlightAndWaiting(t *testing.T) {
	for _, strict := range []bool{false, true} {
		t.Run(map[bool]string{false: "fallback", true: "strict"}[strict], func(t *testing.T) {
			synctest.Test(t, func(t *testing.T) {
				m, c, _ := sessionMock(t, mockcache.Config{})
				if strict {
					strictConfig(c)
				}
				replyWith(m, mockcache.Hang())
				s, err := openSession(context.Background(), c, &sessionAuth{})
				if err != nil {
					t.Fatal(err)
				}
				inFlight := sendSession(context.Background(), s, time.Now())
				synctest.Wait()
				s.close()
				o := <-inFlight
				if o.kind != outcomeFailed || o.retryable == strict || !errors.As(o.err, new(*ConnectionLostError)) {
					t.Fatalf("in flight at close: %+v", o)
				}
				after := s.request(context.Background(), getItem("u"))
				if strict {
					if after.kind != outcomeFailed || after.retryable || !errors.As(after.err, new(*CacheUnavailableError)) {
						t.Fatalf("after close: %+v", after)
					}
				} else if after.kind != outcomeOrigin {
					t.Fatalf("after close: %+v", after)
				}
			})
		})
	}
}

func TestCloseWakesOutageWaiters(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, _ := sessionMock(t, mockcache.Config{})
		s, err := openSession(context.Background(), c, &sessionAuth{})
		if err != nil {
			t.Fatal(err)
		}
		intercept(m, "HELLO", mockcache.Hang())
		start := time.Now()
		m.DropAll()
		synctest.Wait()
		waiting := sendSession(context.Background(), s, start)
		time.Sleep(time.Second)
		s.close()
		if o := <-waiting; o.kind != outcomeOrigin || o.at != time.Second {
			t.Fatalf("waiter at close: %+v", o)
		}
	})
}

// TestStrictNeverReachesOrigin: in strict mode no routed request goes to DynamoDB, under
// load, an outage, AUTH_ERROR, a full connection, and after Close.
func TestStrictNeverReachesOrigin(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, _ := sessionMock(t, mockcache.Config{})
		strictConfig(c)
		c.maxPending = 4
		var mu sync.Mutex
		n := 0
		m.SetOnRequest(func(mockcache.Command) mockcache.Reply {
			mu.Lock()
			defer mu.Unlock()
			n++
			switch n % 4 {
			case 0:
				return mockcache.Deferred(resp.ErrorValue("AUTH_ERROR", "no FAS"))
			case 1:
				return mockcache.DeferredAfter(4*time.Second, mockcache.Served(200, nil, nil))
			default:
				return mockcache.Deferred(mockcache.Served(200, nil, nil))
			}
		})
		s, err := openSession(context.Background(), c, &sessionAuth{})
		if err != nil {
			t.Fatal(err)
		}
		start := time.Now()
		var results []<-chan sessionResult
		for range 50 {
			results = append(results, sendSession(context.Background(), s, start))
			time.Sleep(100 * time.Millisecond)
			if len(results) == 25 {
				m.DropAll()
			}
		}
		closing := make([]<-chan sessionResult, 0, 10)
		for range 10 {
			closing = append(closing, sendSession(context.Background(), s, start))
		}
		synctest.Wait()
		s.close()
		served := 0
		for _, ch := range append(results, closing...) {
			o := <-ch
			if o.kind == outcomeOrigin {
				t.Fatalf("a strict request went to DynamoDB: %+v", o)
			}
			if o.kind == outcomeServed {
				served++
			}
		}
		if served == 0 {
			t.Fatal("nothing was served")
		}
		if o := s.request(context.Background(), getItem("u")); o.kind != outcomeFailed || o.retryable {
			t.Fatalf("after close: %+v", o)
		}
	})
}

// TestRequestFindsLiveConnectionDead: a request that finds the live connection dead before
// its onLost ran starts the outage itself, and is served on the new connection.
func TestRequestFindsLiveConnectionDead(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, _ := sessionMock(t, mockcache.Config{})
		replyWith(m, mockcache.Now(mockcache.Served(200, nil, nil)))
		s := openTestSession(t, c, &sessionAuth{})
		conn := s.live.Load()
		conn.closing.Store(true) // dies without calling onLost
		conn.die(eventConnectionLost, &ConnectionLostError{Msg: "lost"}, nil, nil)
		synctest.Wait()
		if s.live.Load() != conn {
			t.Fatal("onLost ran")
		}
		if o := s.request(context.Background(), getItem("u")); o.kind != outcomeServed {
			t.Fatalf("outcome %+v", o)
		}
		if s.live.Load() == conn {
			t.Fatal("not reconnected")
		}
	})
}

// TestAuthErrorBurstLogsOnce: requests in flight on one connection that are all answered
// AUTH_ERROR log event 1103 once for that connection.
func TestAuthErrorBurstLogsOnce(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, logs := sessionMock(t, mockcache.Config{})
		const n = 32
		var burst []byte
		for range n {
			burst = append(burst, "-AUTH_ERROR no FAS\r\n"...)
		}
		var mu sync.Mutex
		seen := 0
		m.SetOnRequest(func(mockcache.Command) mockcache.Reply {
			mu.Lock()
			defer mu.Unlock()
			if seen++; seen < n {
				return mockcache.Raw(nil) // answered with the last one
			}
			if seen == n {
				return mockcache.Raw(burst)
			}
			return mockcache.Now(mockcache.Served(200, nil, nil))
		})
		s := openTestSession(t, c, &sessionAuth{})
		results := make([]<-chan sessionResult, n)
		for i := range results {
			results[i] = sendSession(context.Background(), s, time.Now())
			synctest.Wait()
		}
		for _, ch := range results {
			// The drop fails the replies the reader had not parsed yet.
			if o := <-ch; o.kind != outcomeOrigin && !errors.As(o.err, new(*ConnectionLostError)) {
				t.Fatalf("outcome %+v", o)
			}
		}
		if got := len(eventRecords(logs, eventAuthDegraded)); got != 1 {
			t.Fatalf("%d 1103 records, want 1", got)
		}
	})
}

// 1308 logs the first request of each overload burst: a reply on the connection resets the
// count, so a burst after healthy traffic logs again.
func TestPendingLimitLogResetsAfterAReply(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, logs := sessionMock(t, mockcache.Config{})
		c.maxPending = 1
		hold := make(chan resp.Value)
		replyWith(m, mockcache.DeferredFrom(hold))
		s := openTestSession(t, c, &sessionAuth{})
		burst := func() {
			busy := sendSession(context.Background(), s, time.Now())
			synctest.Wait()
			if o := s.request(context.Background(), getItem("u")); o.kind != outcomeOrigin {
				t.Fatalf("outcome %v", o.kind)
			}
			hold <- mockcache.Served(200, nil, nil)
			if b := <-busy; b.kind != outcomeServed {
				t.Fatalf("held request: %v", b.kind)
			}
		}
		burst()
		time.Sleep(time.Hour)
		burst()
		if n := len(eventRecords(logs, eventPendingLimitReached)); n != 2 {
			t.Fatalf("%d 1308 records, want one per burst", n)
		}
	})
}

// Under sustained overload replies keep arriving between the requests that find no capacity;
// they do not end the burst, so 1308 does not log once per reply.
func TestPendingLimitLogOncePerSustainedBurst(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m, c, logs := sessionMock(t, mockcache.Config{})
		c.maxPending = 1
		c.cacheRequestTimeout = 400 * time.Millisecond
		hold := make(chan resp.Value)
		replyWith(m, mockcache.DeferredFrom(hold))
		s := openTestSession(t, c, &sessionAuth{})
		for range 5 {
			busy := sendSession(context.Background(), s, time.Now())
			synctest.Wait()
			if o := s.request(context.Background(), getItem("u")); o.kind != outcomeOrigin {
				t.Fatalf("outcome %v", o.kind)
			}
			hold <- mockcache.Served(200, nil, nil)
			if b := <-busy; b.kind != outcomeServed {
				t.Fatalf("held request: %v", b.kind)
			}
		}
		if n := len(eventRecords(logs, eventPendingLimitReached)); n != 1 {
			t.Fatalf("%d 1308 records, want one for the burst", n)
		}
	})
}
