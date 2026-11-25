// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"context"
	"sync/atomic"
	"testing"
	"time"

	"github.com/awslabs/elasticache-integration-client/go/internal/mockcache"
	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
	"github.com/awslabs/elasticache-integration-client/go/internal/testutil"
)

// TestReauthThroughSDK drives re-AUTH on a live connection through a real DynamoDB client.
// It is the one end-to-end test in this package (the rest are in go/e2e): the re-AUTH interval
// is not an option, so it is shortened through the unexported config.
func TestReauthThroughSDK(t *testing.T) {
	testutil.ClearAWSEnv(t)
	tokens := func(s *mockcache.Server, name string) []string {
		var out []string
		for _, cmd := range s.Commands() {
			if cmd.Name() == name {
				out = append(out, cmd.Arg(len(cmd.Args)-1))
			}
		}
		return out
	}
	for _, tc := range []struct {
		name string
		// fault answers the nth AUTH (from 1); nil means the default +OK.
		fault     func(n int64) *mockcache.Reply
		reconnect bool
	}{
		{"fresh_token", nil, false},
		{"refused", func(int64) *mockcache.Reply {
			r := mockcache.Now(resp.ErrorValue("WRONGPASS", "invalid username-password pair"))
			return &r
		}, false},
		{"not_ok", func(n int64) *mockcache.Reply {
			if n != 1 {
				return nil
			}
			r := mockcache.Now(resp.IntValue(1))
			return &r
		}, true},
		{"timeout", func(n int64) *mockcache.Reply {
			if n != 1 {
				return nil
			}
			r := mockcache.Hang()
			return &r
		}, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			s, addr := sdkMock(t)
			s.SetOnRequest(func(mockcache.Command) mockcache.Reply { return servedJSON(200, musicItem) })
			var auths atomic.Int64
			s.SetIntercept(func(cmd mockcache.Command) *mockcache.Reply {
				if cmd.Name() != "AUTH" || tc.fault == nil {
					return nil
				}
				return tc.fault(auths.Add(1))
			})
			c := sdkConfig(t, s, addr)
			c.reauthInterval, c.jitter = 200*time.Millisecond, 0
			c.iamTokenRefreshDelay = time.Second
			c.cacheRequestTimeout = 300 * time.Millisecond
			c.connections = 1 // one connection, so its replacement is counted
			origin := &fakeOrigin{}
			ddb, _ := startSDK(t, c, origin, withRetries(1))
			ctx := context.Background()
			get := func() {
				t.Helper()
				if out, err := getMusic(ctx, ddb); err != nil || attrS(t, out.Item, "Artist") != "Acme" {
					t.Fatalf("GetItem = %v, %v", out, err)
				}
			}
			get()
			hello := tokens(s, "HELLO")[0]
			switch {
			case tc.reconnect:
				for deadline := time.Now().Add(10 * time.Second); s.Connections() < 2; {
					if time.Now().After(deadline) {
						t.Fatal("the connection was not replaced after the re-AUTH failed")
					}
					time.Sleep(10 * time.Millisecond)
				}
			default:
				// Calls keep being served while re-AUTH runs, until an AUTH carries a token signed
				// after the first one (the token manager signs again every second).
				for deadline := time.Now().Add(10 * time.Second); ; {
					if time.Now().After(deadline) {
						t.Fatalf("no AUTH with a new token: %d AUTH commands", len(tokens(s, "AUTH")))
					}
					get()
					fresh := false
					for _, tok := range tokens(s, "AUTH") {
						fresh = fresh || tok != hello
					}
					if fresh && len(tokens(s, "AUTH")) >= 3 {
						break
					}
					time.Sleep(10 * time.Millisecond)
				}
				if s.Connections() != 1 {
					t.Errorf("connections = %d; re-AUTH must keep the connection", s.Connections())
				}
			}
			get()
			if origin.count() != 0 {
				t.Errorf("origin calls = %d, want 0", origin.count())
			}
		})
	}
}
