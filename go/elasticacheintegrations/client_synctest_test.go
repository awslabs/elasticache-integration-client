// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

//go:build go1.25

package elasticacheintegrations

import (
	"context"
	"errors"
	"fmt"
	"sync/atomic"
	"testing"
	"testing/synctest"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"

	"github.com/awslabs/elasticache-integration-client/go/internal/mockcache"
	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
	"github.com/awslabs/elasticache-integration-client/go/internal/testutil"
)

// TestStartLeavesNothingRunning: the bubble ends only when every goroutine start or Close
// left has exited, so a leaked token manager, reader or runner fails the test. A start-up
// failure on one connection stops the others at once, not at ConnectionTimeout.
func TestStartLeavesNothingRunning(t *testing.T) {
	testutil.ClearAWSEnv(t)
	cases := []struct {
		name  string
		setup func(*mockcache.Server)
		check func(error) bool
	}{
		{"opens, then Close", nil, func(err error) bool { return err == nil }},
		{"overrides rejected", func(s *mockcache.Server) {
			intercept(s, "AWS.CONFIG", mockcache.Now(resp.ErrorValue("CONFIG_INVALID", "bad")))
		}, func(err error) bool { return errors.As(err, new(*OverridesRejectedError)) }},
		{"HELLO fails until ConnectionTimeout", func(s *mockcache.Server) {
			intercept(s, "HELLO", mockcache.Now(resp.ErrorValue("ERR", "not now")))
		}, func(err error) bool { return errors.Is(err, ErrIntegrations) }},
		{"one connection rejects overrides, the others wait for aws.config", func(s *mockcache.Server) {
			var configs atomic.Int32
			s.SetIntercept(func(cmd mockcache.Command) *mockcache.Reply {
				if cmd.Name() != "AWS.CONFIG" {
					return nil
				}
				r := mockcache.After(time.Hour, resp.ErrorValue("ERR", "late"))
				if configs.Add(1) == 1 {
					r = mockcache.Now(resp.ErrorValue("CONFIG_INVALID", "bad"))
				}
				return &r
			})
		}, func(err error) bool { return errors.As(err, new(*OverridesRejectedError)) }},
	}
	for _, n := range []int{1, 4} {
		for _, tc := range cases {
			t.Run(fmt.Sprintf("connections=%d/%s", n, tc.name), func(t *testing.T) {
				synctest.Test(t, func(t *testing.T) {
					s, c := pipeMock(t, mockcache.Config{})
					c.connections = n
					if tc.setup != nil {
						tc.setup(s)
					}
					t0 := time.Now()
					ddb, cache, err := start(context.Background(), c, func(fns ...func(*dynamodb.Options)) *dynamodb.Client {
						return dynamodb.NewFromConfig(aws.Config{Region: testRegion, Credentials: testutil.StaticCredentials()}, fns...)
					}, nil)
					if !tc.check(err) {
						t.Fatalf("start = %v", err)
					}
					if err != nil {
						if ddb != nil || cache != nil {
							t.Fatalf("start returned a client with %v", err)
						}
						if errors.As(err, new(*OverridesRejectedError)) && time.Since(t0) != 0 {
							t.Fatalf("start failed after %v; want at once", time.Since(t0))
						}
						return
					}
					if len(cache.sessions) != n {
						t.Fatalf("sessions = %d, want %d", len(cache.sessions), n)
					}
					for i, ss := range cache.sessions {
						if ss.live.Load() == nil {
							t.Fatalf("start returned before session %d was open", i)
						}
					}
					cache.Close()
				})
			})
		}
	}
}
