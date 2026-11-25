// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package e2e

import (
	"testing"
	"time"

	"github.com/awslabs/elasticache-integration-client/go/elasticacheintegrations"
	"github.com/awslabs/elasticache-integration-client/go/internal/mockcache"
)

// TestDefaultConnectionsOpenAtOnce builds a client with the default four connections on a
// cache whose HELLO takes 400 ms: one after another they would take longer than the 1 s
// ConnectionTimeout. Each connection is authenticated and configured once.
func TestDefaultConnectionsOpenAtOnce(t *testing.T) {
	t.Parallel()
	slowHello := mockcache.After(400*time.Millisecond, mockcache.Hello())
	h := newHarness(t, withDefaultConnections(),
		withOptions(func(o *elasticacheintegrations.Options) { o.ConnectionTimeout = time.Second }),
		withCache(func(s *mockcache.Server) {
			s.SetIntercept(func(cmd mockcache.Command) *mockcache.Reply {
				if cmd.Name() == "HELLO" {
					return &slowHello
				}
				return nil
			})
		}))
	if h.started >= time.Second {
		t.Errorf("NewFromConfig took %v, want under the 1 s ConnectionTimeout", h.started)
	}
	if got := len(h.hellos()); got != 4 || h.cache.Connections() != 4 || len(h.cache.Overrides()) != 4 {
		t.Fatalf("HELLO %d, configured connections %d, aws.config %d; want 4 each",
			got, h.cache.Connections(), len(h.cache.Overrides()))
	}
	seed(t, h.dynamo)
	if h.getOne(t).Item == nil {
		t.Fatal("no item")
	}
	if h.dynamo.fromClient() != 0 {
		t.Errorf("client calls %d, want 0", h.dynamo.fromClient())
	}
}
