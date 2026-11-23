// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package e2e

import (
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/awslabs/elasticache-integration-client/go/elasticacheintegrations"
	"github.com/awslabs/elasticache-integration-client/go/internal/mockcache"
	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
	"github.com/awslabs/elasticache-integration-client/go/internal/testutil"
)

func TestTokenSignedWithClientIdentity(t *testing.T) {
	t.Parallel()
	t.Run("identity", func(t *testing.T) {
		t.Parallel()
		h := newHarness(t, withRegion("ap-southeast-2"),
			withOptions(func(o *elasticacheintegrations.Options) { o.UserID = "IAM-User" }))
		var hello mockcache.Command
		for _, cmd := range h.cache.Commands() {
			if cmd.Name() == "HELLO" {
				hello = cmd
			}
		}
		if len(hello.Args) != 5 || hello.Arg(3) != "iam-user" {
			t.Fatalf("HELLO %q; want user iam-user", hello.Args)
		}
		token := hello.Arg(4)
		for _, want := range []string{"X-Amz-Credential=" + testutil.AccessKeyID + "%2F",
			"%2Fap-southeast-2%2Felasticache%2Faws4_request", "X-Amz-Security-Token=", "User=iam-user"} {
			if !strings.Contains(token, want) {
				t.Errorf("the token has no %q", want)
			}
		}
		if !strings.HasPrefix(token, "my-cache/?") {
			t.Errorf("the token does not start with my-cache/?")
		}
	})
	t.Run("close_stops_refresh", func(t *testing.T) {
		t.Parallel()
		creds := &rotating{}
		h := newHarness(t, withCredentials(creds),
			withOptions(func(o *elasticacheintegrations.Options) { o.IAMTokenRefreshDelay = time.Second }))
		start := creds.calls.Load()
		eventually(t, "a background refresh", func() bool { return creds.calls.Load() > start })
		if err := h.client.Close(); err != nil {
			t.Fatal(err)
		}
		closed := creds.calls.Load()
		time.Sleep(1500 * time.Millisecond)
		if n := creds.calls.Load(); n != closed {
			t.Errorf("the credentials were read %d times after Close", n-closed)
		}
	})
}

// helloMap is a HELLO reply map with the given fields after server and version.
func helloMap(fields ...resp.Value) resp.Value {
	return resp.MapValue(append([]resp.Value{resp.BulkStringValue("server"), resp.BulkStringValue("valkey"),
		resp.BulkStringValue("version"), resp.BulkStringValue("8.0.0")}, fields...)...)
}

// replyTo answers every command named name with reply; nil leaves the default.
func replyTo(name string, reply func(mockcache.Command) *mockcache.Reply) func(*mockcache.Server) {
	return func(s *mockcache.Server) {
		s.SetIntercept(func(cmd mockcache.Command) *mockcache.Reply {
			if cmd.Name() != name || (name == "AWS.CONFIG" && len(cmd.Args) != 2) {
				return nil
			}
			return reply(cmd)
		})
	}
}

func always(r mockcache.Reply) func(mockcache.Command) *mockcache.Reply {
	return func(mockcache.Command) *mockcache.Reply { return &r }
}

func authReply(f func(user, token string) resp.Value) func(*mockcache.Server) {
	return func(s *mockcache.Server) { s.SetOnAuth(f) }
}

func TestBringUpFaults(t *testing.T) {
	t.Parallel()
	// Long enough that the last attempt before the deadline is not cut short on a loaded host,
	// which would turn an AuthError into a timeout (start-up returns the last failure).
	const timeout = 2 * time.Second
	asConnection := func(t *testing.T, err error) {
		errorAs[*elasticacheintegrations.ConnectionError](t, err)
	}
	asTimeout := func(t *testing.T, err error) {
		errorAs[*elasticacheintegrations.TimeoutError](t, errorAs[*elasticacheintegrations.ConnectionError](t, err))
	}
	asAuth := func(t *testing.T, err error) { errorAs[*elasticacheintegrations.AuthError](t, err) }
	for _, tc := range []struct {
		name  string
		setup func(*harnessConfig)
		check func(*testing.T, error)
		// retried: more than one HELLO; noConfig: no aws.config sent; refreshed: every HELLO
		// after the first is signed with new credentials, otherwise all with the same ones
		retried, noConfig, refreshed bool
	}{
		{"wrongpass", withCache(authReply(func(string, string) resp.Value {
			return resp.ErrorValue("WRONGPASS", "invalid username-password pair")
		})), asAuth, true, true, true},
		{"invalid_credentials", withCache(authReply(func(string, string) resp.Value {
			return resp.ErrorValue("invalid-iam-credentials", "credentials rejected")
		})), asAuth, true, true, true},
		{"expired_token", withCache(authReply(func(string, string) resp.Value {
			return resp.ErrorValue("expired-iam-token", "token expired")
		})), asConnection, true, true, false},
		{"hello_not_a_map", withCache(replyTo("HELLO", always(mockcache.Now(resp.SimpleValue("NOT-OK"))))), asConnection, true, true, false},
		{"hello_proto_2", withCache(replyTo("HELLO", always(mockcache.Now(helloMap(resp.BulkStringValue("proto"), resp.IntValue(2)))))), asConnection, true, true, false},
		{"hello_no_proto", withCache(replyTo("HELLO", always(mockcache.Now(helloMap())))), asConnection, true, true, false},
		{"hello_noproto", withCache(replyTo("HELLO", always(mockcache.Now(resp.ErrorValue("NOPROTO", "unsupported protocol version"))))), asConnection, true, true, false},
		{"hello_hangs", withCache(replyTo("HELLO", always(mockcache.Hang()))), asTimeout, false, true, false},
		{"hello_drops", withCache(replyTo("HELLO", always(mockcache.Drop()))), asConnection, true, true, false},
		{"config_hangs", withCache(replyTo("AWS.CONFIG", always(mockcache.Hang()))), asTimeout, false, false, false},
		{"tls_blackhole", withRelayMode(mockcache.Blackhole), asTimeout, false, true, false},
		{"cache_down", withRelayMode(mockcache.Refuse), asConnection, false, true, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			creds := &rotating{perCall: true}
			h := newHarness(t, startMayFail(), withCredentials(creds), tc.setup,
				withOptions(func(o *elasticacheintegrations.Options) { o.ConnectionTimeout = timeout }))
			if h.startErr == nil {
				t.Fatal("NewFromConfig succeeded")
			}
			tc.check(t, h.startErr)
			if h.started > timeout+time.Second {
				t.Errorf("start-up took %v with a %v ConnectionTimeout", h.started, timeout)
			}
			hellos := h.hellos()
			if tc.retried && len(hellos) < 2 {
				t.Errorf("%d HELLO; want the bring-up retried", len(hellos))
			}
			for i := 1; i < len(hellos); i++ {
				if same := keyID(hellos[i]) == keyID(hellos[i-1]); same == tc.refreshed {
					t.Errorf("HELLO tokens signed by %v; want a new token after a rejection and only then",
						keyIDs(hellos))
					break
				}
			}
			if tc.noConfig && h.commands("AWS.CONFIG", 1) != 0 {
				t.Error("aws.config sent after a failed HELLO")
			}
			if h.dynamo.fromClient() != 0 {
				t.Error("start-up called DynamoDB")
			}
		})
	}
	t.Run("rejected_token_is_refreshed", func(t *testing.T) {
		t.Parallel()
		creds := &rotating{perCall: true}
		var mu sync.Mutex
		var first string
		h := newHarness(t, withCredentials(creds), withCache(authReply(func(_, token string) resp.Value {
			mu.Lock()
			defer mu.Unlock()
			if first == "" {
				first = keyID(token)
			}
			if keyID(token) == first {
				return resp.ErrorValue("invalid-iam-credentials", "credentials rejected")
			}
			return mockcache.OK()
		})))
		hellos := h.hellos()
		if len(hellos) != 2 || keyID(hellos[0]) == keyID(hellos[1]) {
			t.Fatalf("HELLO tokens signed by %v; want two, the second with new credentials", keyIDs(hellos))
		}
		if h.commands("AWS.CONFIG", 1) != 1 {
			t.Errorf("aws.config sent %d times, want 1", h.commands("AWS.CONFIG", 1))
		}
	})
}
