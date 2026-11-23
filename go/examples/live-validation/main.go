// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

// Command live-validation checks the ElastiCache Integrations client against a real cache with
// the DynamoDB integration, in one of four modes:
//
//	ops       the ten routed operations, pass-through, hit, miss, invalidating writes, config
//	auth      a refused periodic re-AUTH keeps the connection serving, then recovers
//	push      a hit overtakes an earlier miss on the same connection (async replies)
//	fallback  start-up, outage, strict mode, recovery and a hung cache through a local relay
//
// Settings come from the environment: ELASTICACHE_CACHE_HOST, ELASTICACHE_CACHE_PORT,
// ELASTICACHE_CACHE_NAME, ELASTICACHE_CACHE_USER_ID, ELASTICACHE_TABLE_NAME, AWS_REGION, and
// LIVE_RELAY_TARGET for fallback. It exits 0 only when every check passes.
package main

import (
	"context"
	"fmt"
	"os"
	"os/signal"
	"syscall"
)

var modes = map[string]func(context.Context, *live) error{
	"ops":      runOperations,
	"auth":     runAuth,
	"push":     runPush,
	"fallback": runFallback,
}

func main() {
	if len(os.Args) != 2 || modes[os.Args[1]] == nil {
		fmt.Fprintln(os.Stderr, "usage: live-validation ops|auth|push|fallback")
		os.Exit(2)
	}
	os.Exit(run(os.Args[1]))
}

func run(mode string) int {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	s, err := loadSettings(os.Getenv)
	if err != nil {
		fmt.Fprintln(os.Stderr, "FAIL:", err)
		return 1
	}
	l, err := newLive(ctx, s, os.Stdout)
	if err != nil {
		fmt.Fprintln(os.Stderr, "FAIL:", err)
		return 1
	}
	l.step("mode %s, cache %s, table %s, items %s-*", mode, s.cacheName, s.table, l.run)
	err = modes[mode](ctx, l)
	if cerr := l.close(); cerr != nil {
		l.check("cleanup", false, "%v", cerr)
	}
	return exitCode(l, err)
}
