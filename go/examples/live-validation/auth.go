// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"context"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/awslabs/elasticache-integration-client/go/elasticacheintegrations"
)

const (
	// reauthEarliest is the earliest the first periodic re-AUTH may come: 900 s -20%.
	reauthEarliest = 720 * time.Second
	// reauthWait bounds the wait for the first periodic re-AUTH: 900 s +20%, plus a margin.
	reauthWait = 19 * time.Minute
)

// runAuth runs checks 6 and 7: the first periodic re-AUTH is sent with a token the cache must
// refuse; the connection keeps serving misses with its FAS; good credentials then recover it.
func runAuth(ctx context.Context, l *live) error {
	sw := &switchableCredentials{next: l.cfg.Credentials}
	cfg := l.cfg.Copy()
	cfg.Credentials = sw
	started := time.Now()
	c, err := l.open(ctx, &cfg, func(o *elasticacheintegrations.Options) { o.IAMTokenRefreshDelay = time.Second })
	if err != nil {
		return fmt.Errorf("start-up: %w", err)
	}
	for i := range 4 {
		if err := l.put(ctx, l.key("auth-"+strconv.Itoa(i))); err != nil {
			return err
		}
	}
	mark := l.events.mark()
	sw.broken.Store(true)
	l.step("credentials broken; waiting up to %s for the first periodic re-AUTH", reauthWait)

	wctx, cancel := context.WithTimeout(ctx, reauthWait)
	failed, ok := l.events.wait(wctx, mark, 1101, "re-AUTH failed", 1)
	cancel()
	if ctx.Err() != nil {
		return ctx.Err()
	}
	after := failed.at.Sub(started)
	l.check("6 the periodic re-AUTH is sent and a bad token is refused", ok && after >= reauthEarliest && strings.Contains(failed.err, "WRONGPASS"),
		"after %s (expected %s to %s): %s", after.Round(time.Second), reauthEarliest, reauthWait, failed.err)
	if !ok {
		return nil
	}

	for i := range 3 {
		if err := l.miss(ctx, c, "7 miss routed after the refused re-AUTH", "auth-"+strconv.Itoa(i)); err != nil {
			return err
		}
	}
	l.check("7 the connection and its FAS are kept", l.events.since(mark, 1103, "") == 0 && l.events.since(mark, 1201, "") == 0,
		"1103 seen %d, 1201 seen %d", l.events.since(mark, 1103, ""), l.events.since(mark, 1201, ""))
	sw.broken.Store(false)

	wctx, cancel = context.WithTimeout(ctx, time.Minute)
	recovered, ok := l.events.wait(wctx, mark, 1102, "re-AUTH succeeded", 1)
	cancel()
	l.check("7 re-AUTH with good credentials succeeds", ok, "after %s: %s, failed attempts %d",
		recovered.at.Sub(failed.at).Round(time.Millisecond), recovered.msg, l.events.since(mark, 1101, "re-AUTH failed"))
	return l.miss(ctx, c, "7 miss routed after the re-AUTH recovered", "auth-3")
}

// miss reads one of this run's items through c and checks that it was routed and correct.
func (l *live) miss(ctx context.Context, c *cacheClient, name, item string) error {
	before := c.origin.calls.Load()
	start := time.Now()
	got, err := l.get(ctx, c.ddb, l.key(item))
	if err != nil {
		l.check(name, false, "%s: %v", item, err)
		return nil
	}
	want, err := l.direct(ctx, l.key(item))
	if err != nil {
		return err
	}
	calls := c.origin.calls.Load() - before
	l.check(name, calls == 0 && canon(got) == canon(want), "%s in %s, origin calls %d, cache %s",
		item, ms(time.Since(start)), calls, canon(got))
	return nil
}
