// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"context"
	"errors"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/awslabs/elasticache-integration-client/go/internal/mockcache"
	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
	"github.com/awslabs/elasticache-integration-client/go/internal/testutil"
)

// testToken is the token staticAuth hands out; it must never reach an error or a log.
const testToken = "TOKEN-MARKER-0001?X-Amz-Signature=SIGNATURE-MARKER-00000001"

// staticAuth is an authProvider with a fixed token, or a fixed error.
type staticAuth struct {
	err   error
	calls int
	mu    sync.Mutex
}

func (a *staticAuth) credentials(ctx context.Context) (authCredentials, error) {
	a.mu.Lock()
	a.calls++
	a.mu.Unlock()
	if a.err != nil {
		return authCredentials{}, a.err
	}
	if err := ctx.Err(); err != nil {
		return authCredentials{}, err
	}
	return authCredentials{user: "default.iam-user", token: newSecret(testToken)}, nil
}

func (a *staticAuth) forceRefresh(context.Context) error { return nil }
func (a *staticAuth) close()                             {}

// lostRecorder records every onLost call.
type lostRecorder struct {
	mu   sync.Mutex
	errs []error
	ch   chan error
}

func newLostRecorder() *lostRecorder { return &lostRecorder{ch: make(chan error, 16)} }

func (r *lostRecorder) onLost(err error) {
	r.mu.Lock()
	r.errs = append(r.errs, err)
	r.mu.Unlock()
	r.ch <- err
}

func (r *lostRecorder) count() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.errs)
}

func (r *lostRecorder) wait(t *testing.T) error {
	t.Helper()
	select {
	case err := <-r.ch:
		return err
	case <-time.After(5 * time.Second):
		t.Fatal("onLost was not called")
		return nil
	}
}

// openMock opens a connection to the mock on loopback.
func openMock(t *testing.T, s *mockcache.Server, c *config) (*connection, *resolvedConfig, *lostRecorder, error) {
	t.Helper()
	lost := newLostRecorder()
	conn, rc, err := openConnection(context.Background(), c, &staticAuth{}, time.Now().Add(5*time.Second), lost.onLost)
	if conn != nil {
		t.Cleanup(conn.close)
	}
	return conn, rc, lost, err
}

func intercept(s *mockcache.Server, name string, r mockcache.Reply) {
	s.SetIntercept(func(cmd mockcache.Command) *mockcache.Reply {
		if cmd.Name() == name {
			return &r
		}
		return nil
	})
}

func TestOpenConnection(t *testing.T) {
	for _, useTLS := range []bool{false, true} {
		s, addr := startMock(t, mockcache.Config{TLS: useTLS})
		c := mockConfig(t, s, addr)
		c.cachePolicyOverrides = map[string]any{"ttl": int64(5)}
		conn, rc, lost, err := openMock(t, s, c)
		if err != nil {
			t.Fatalf("tls=%v: %v", useTLS, err)
		}
		if rc.version != "1.0" || rc.refreshInterval != 60*time.Second {
			t.Fatalf("config %v", rc)
		}
		cmds := s.Commands()
		if len(cmds) != 2 {
			t.Fatalf("commands %v", cmds)
		}
		hello := cmds[0].Args
		if len(hello) != 5 || string(hello[0]) != "HELLO" || string(hello[1]) != "3" || string(hello[2]) != "AUTH" ||
			string(hello[3]) != "default.iam-user" || string(hello[4]) != testToken {
			t.Fatalf("HELLO %q", hello)
		}
		if got := s.Overrides(); len(got) != 1 || got[0] != `{"version":"1.0","ttl":5}` {
			t.Fatalf("overrides %q", got)
		}
		if s.Connections() != 1 || !conn.alive() || conn.err() != nil {
			t.Fatal("not connected")
		}
		conn.close()
		conn.close()
		if conn.alive() || lost.count() != 0 {
			t.Fatalf("alive %v after close, onLost %d", conn.alive(), lost.count())
		}
		var cl *ConnectionLostError
		if !errors.As(conn.err(), &cl) {
			t.Fatalf("err after close: %v", conn.err())
		}
	}
}

func TestOpenConnectionNoOverridesSendsVersion(t *testing.T) {
	s, addr := startMock(t, mockcache.Config{})
	if _, _, _, err := openMock(t, s, mockConfig(t, s, addr)); err != nil {
		t.Fatal(err)
	}
	if got := s.Overrides(); len(got) != 1 || got[0] != `{"version":"1.0"}` {
		t.Fatalf("overrides %q", got)
	}
}

func TestOpenConnectionHelloReplies(t *testing.T) {
	cases := []struct {
		name  string
		reply resp.Value
		auth  string // the AuthError code, or "" for a ConnectionError
	}{
		{"WRONGPASS", resp.ErrorValue("WRONGPASS", "invalid username-password pair "+testToken), "WRONGPASS"},
		{"invalid-iam-credentials", resp.ErrorValue("invalid-iam-credentials", "expired"), "invalid-iam-credentials"},
		{"ERR", resp.ErrorValue("ERR", "unknown"), ""},
		{"NOPROTO", resp.ErrorValue("NOPROTO", "unsupported"), ""},
		{"token as the code", resp.ErrorValue(testToken, ""), ""},
		{"overlong code", resp.ErrorValue(strings.Repeat("E", maxAuthErrorCode+1), ""), ""},
		{"not a map", resp.SimpleValue("OK"), ""},
		{"proto 2", resp.MapValue(resp.BulkStringValue("proto"), resp.IntValue(2)), ""},
		{"no proto", resp.MapValue(resp.BulkStringValue("server"), resp.BulkStringValue("valkey")), ""},
		{"proto as a string", resp.MapValue(resp.BulkStringValue("proto"), resp.BulkStringValue("3")), ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			s, addr := startMock(t, mockcache.Config{})
			intercept(s, "HELLO", mockcache.Now(tc.reply))
			conn, _, _, err := openMock(t, s, mockConfig(t, s, addr))
			if conn != nil {
				t.Fatal("a connection was returned")
			}
			var ae *AuthError
			var ce *ConnectionError
			switch {
			case tc.auth != "":
				if !errors.As(err, &ae) || ae.Code != tc.auth {
					t.Fatalf("err %v, want AuthError %s", err, tc.auth)
				}
			case !errors.As(err, &ce) || errors.As(err, &ae):
				t.Fatalf("err %v, want a ConnectionError", err)
			}
			testutil.AssertNoSecrets(t, err.Error(), testutil.TokenSecrets(testToken)...)
			if strings.Contains(err.Error(), "invalid username-password pair") {
				t.Fatalf("the server's message reached the error: %v", err)
			}
		})
	}
}

func TestOpenConnectionConfigReplies(t *testing.T) {
	cases := []struct {
		name  string
		reply mockcache.Reply
		check func(error) bool
	}{
		{"CONFIG_INVALID", mockcache.Now(resp.ErrorValue("CONFIG_INVALID", "bad ttl")), func(err error) bool {
			var o *OverridesRejectedError
			return errors.As(err, &o) && o.ServerMessage == "bad ttl" && !errors.As(err, new(*ConnectionError))
		}},
		{"not onboarded", mockcache.Now(resp.ErrorValue("ERR", "unknown command 'aws.config'")), func(err error) bool {
			var r *RejectedError
			return errors.As(err, new(*ConnectionError)) && errors.As(err, &r) && r.Code == "ERR"
		}},
		{"null", mockcache.Now(resp.NullValue()), func(err error) bool {
			return errors.As(err, new(*ConnectionError)) && errors.As(err, new(*ProtocolError))
		}},
		{"integer", mockcache.Now(resp.IntValue(1)), func(err error) bool {
			return errors.As(err, new(*ConnectionError)) && errors.As(err, new(*ProtocolError))
		}},
		{"bad JSON", mockcache.Now(resp.BulkStringValue(`{"version":"1.0"}`)), func(err error) bool {
			return errors.As(err, new(*ConnectionError)) && errors.As(err, new(*ProtocolError))
		}},
		{"dropped", mockcache.Drop(), func(err error) bool {
			var ce *ConnectionError
			return errors.As(err, &ce) && errors.As(err, new(*ConnectionLostError)) && !errors.As(err, new(*TimeoutError)) &&
				strings.Contains(ce.Msg, "aws.config failed")
		}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			s, addr := startMock(t, mockcache.Config{})
			intercept(s, "AWS.CONFIG", tc.reply)
			conn, _, _, err := openMock(t, s, mockConfig(t, s, addr))
			if conn != nil || !tc.check(err) {
				t.Fatalf("conn %v, err %v", conn, err)
			}
		})
	}
}

func TestOpenConnectionCredentialsError(t *testing.T) {
	s, addr := startMock(t, mockcache.Config{})
	want := &ConnectionError{Msg: "cannot sign the IAM token"}
	_, _, err := openConnection(context.Background(), mockConfig(t, s, addr), &staticAuth{err: want},
		time.Now().Add(5*time.Second), nil)
	if err != want {
		t.Fatalf("err %v", err)
	}
	if len(s.Commands()) != 0 {
		t.Fatalf("sent %v without credentials", s.Commands())
	}
}

func TestOpenConnectionCallerCancels(t *testing.T) {
	s, addr := startMock(t, mockcache.Config{})
	intercept(s, "HELLO", mockcache.Hang())
	ctx, cancel := context.WithCancel(context.Background())
	sent := make(chan bool, 1)
	go func() {
		defer cancel()
		sent <- testutil.Poll(5*time.Second, func() bool { return len(s.Commands()) == 1 })
	}()
	start := time.Now()
	_, _, err := openConnection(ctx, mockConfig(t, s, addr), &staticAuth{}, time.Now().Add(time.Minute), nil)
	if !<-sent {
		t.Fatal("timed out waiting for HELLO")
	}
	if !errors.Is(err, context.Canceled) || time.Since(start) > 5*time.Second {
		t.Fatalf("err %v after %v", err, time.Since(start))
	}
	ctx, cancel = context.WithCancel(context.Background())
	cancel()
	if _, _, err := openConnection(ctx, mockConfig(t, s, addr), &staticAuth{}, time.Now().Add(time.Minute), nil); !errors.Is(err, context.Canceled) {
		t.Fatalf("done ctx: %v", err)
	}
}

func TestOpenConnectionNoSecretsInLogs(t *testing.T) {
	logs, logger := testutil.NewLogCapture()
	s, addr := startMock(t, mockcache.Config{})
	c := mockConfig(t, s, addr)
	c.logger = newLogger(logger)
	intercept(s, "HELLO", mockcache.Now(resp.ErrorValue("WRONGPASS", testToken)))
	_, _, _, err := openMock(t, s, c)
	s.SetIntercept(nil)
	conn, _, lost, err2 := openMock(t, s, c)
	if err == nil || err2 != nil {
		t.Fatalf("%v, %v", err, err2)
	}
	s.DropAll()
	lostErr := lost.wait(t)
	conn.close()
	text := logs.Text() + err.Error() + lostErr.Error()
	testutil.AssertNoSecrets(t, text, testutil.TokenSecrets(testToken)...)
}

func TestAuthErrorCode(t *testing.T) {
	for in, want := range map[string]string{
		"WRONGPASS":                             "WRONGPASS",
		"WRONGPASS invalid pair":                "WRONGPASS",
		"invalid-iam-credentials expired":       "invalid-iam-credentials",
		"ERR_1.x":                               "ERR_1.x",
		testToken:                               "(unrecognized error code)",
		"":                                      "(unrecognized error code)",
		strings.Repeat("E", maxAuthErrorCode+1): "(unrecognized error code)",
		strings.Repeat("E", maxAuthErrorCode):   strings.Repeat("E", maxAuthErrorCode),
		strings.Repeat("0123456789abcdef", 4):   "(unrecognized error code)", // a SigV4 signature
	} {
		if got := authErrorCode(resp.Value{Kind: resp.KindError, Bytes: []byte(in)}); got != want {
			t.Errorf("authErrorCode(%q) = %q, want %q", in, got, want)
		}
	}
}
