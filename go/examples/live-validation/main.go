// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

// Command live-validation checks the ElastiCache Integrations client against a real cache with
// the DynamoDB integration. Mode ops runs the ten routed operations, a pass-through call, a hit,
// a miss and invalidating writes, and checks the resolved config.
//
// Settings come from the environment: ELASTICACHE_CACHE_HOST, ELASTICACHE_CACHE_PORT,
// ELASTICACHE_CACHE_NAME, ELASTICACHE_CACHE_USER_ID, ELASTICACHE_TABLE_NAME and AWS_REGION. It
// exits 0 only when every check passes.
package main

import (
	"context"
	"fmt"
	"os"
	"os/signal"
	"syscall"
)

var modes = map[string]func(context.Context, *live) error{
	"ops": runOperations,
}

func main() {
	if len(os.Args) != 2 || modes[os.Args[1]] == nil {
		fmt.Fprintln(os.Stderr, "usage: live-validation ops")
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
