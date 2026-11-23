// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

//go:build go1.25

package elasticacheintegrations

import (
	"context"
	"errors"
	"testing"
	"testing/synctest"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"

	"github.com/awslabs/elasticache-integration-client/go/internal/mockcache"
	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
	"github.com/awslabs/elasticache-integration-client/go/internal/testutil"
)

// TestStartLeavesNothingRunning: the bubble ends only when every goroutine start or Close
// left has exited, so a leaked token manager, reader or runner fails the test.
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
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			synctest.Test(t, func(t *testing.T) {
				s, c := pipeMock(t, mockcache.Config{})
				if tc.setup != nil {
					tc.setup(s)
				}
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
					return
				}
				if cache.session == nil || cache.session.live.Load() == nil {
					t.Fatal("start returned before the session was open")
				}
				cache.Close()
			})
		})
	}
}
