// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"context"
	"errors"
	"fmt"
	"net"
	"strconv"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"

	"github.com/awslabs/elasticache-integration-client/go/elasticacheintegrations"
)

// faultTimeout is the ConnectionTimeout and CacheRequestTimeout of the fallback clients.
const faultTimeout = 2 * time.Second

// runFallback runs checks 11-14 through a relay on 127.0.0.1 in front of one cache address. The
// cache host must resolve only to 127.0.0.1 (an /etc/hosts line), so the client reaches the
// cache through the relay and still verifies its certificate.
func runFallback(ctx context.Context, l *live) error {
	if err := checkRelayTarget(l.s.relayTarget); err != nil {
		return err
	}
	if addrs, err := net.LookupHost(l.s.host); err != nil || len(addrs) != 1 || addrs[0] != "127.0.0.1" {
		return fmt.Errorf("%s must resolve only to 127.0.0.1, got %v %v", l.s.host, addrs, err)
	}
	r, err := startRelay(net.JoinHostPort("127.0.0.1", strconv.Itoa(l.s.port)), l.s.relayTarget)
	if err != nil {
		return err
	}
	defer r.close()
	timeouts := func(o *elasticacheintegrations.Options) {
		o.ConnectionTimeout, o.CacheRequestTimeout = faultTimeout, faultTimeout
	}

	r.set(relayReset)
	started := time.Now()
	_, err = l.open(ctx, nil, timeouts)
	took := time.Since(started)
	var connErr *elasticacheintegrations.ConnectionError
	l.check("11 start-up against an unreachable cache fails", errors.As(err, &connErr) && took < faultTimeout+time.Second,
		"after %s: %v", ms(took), err)

	r.set(relayForward)
	c, err := l.open(ctx, nil, timeouts)
	if err != nil {
		return fmt.Errorf("start-up through the relay: %w", err)
	}
	strict, err := l.open(ctx, nil, func(o *elasticacheintegrations.Options) {
		timeouts(o)
		o.FallbackDuration = aws.Duration(0)
	})
	if err != nil {
		return fmt.Errorf("strict start-up through the relay: %w", err)
	}
	for i := range 8 {
		if err := l.put(ctx, l.key("fb-"+strconv.Itoa(i))); err != nil {
			return err
		}
	}
	if err := pause(ctx, settle); err != nil {
		return err
	}
	if err := l.miss(ctx, c, "12 routed through the relay", "fb-0"); err != nil {
		return err
	}

	mark := l.events.mark()
	r.set(relayReset)
	// The read waits ConnectionTimeout for a reconnect, counted from the drop.
	if err := l.fallback(ctx, c, "12 a read in a reset outage goes to DynamoDB", "fb-1",
		faultTimeout-250*time.Millisecond, faultTimeout+time.Second); err != nil {
		return err
	}
	l.check("12 the client saw the drop and reconnects", l.events.since(mark, 1201, "") > 0 && l.events.since(mark, 1303, "") > 0,
		"1201 seen %d, 1303 seen %d", l.events.since(mark, 1201, ""), l.events.since(mark, 1303, ""))
	started = time.Now()
	_, err = l.get(ctx, strict.ddb, l.key("fb-2"))
	var unavailable *elasticacheintegrations.CacheUnavailableError
	l.check("12 a strict client fails and never calls DynamoDB", errors.As(err, &unavailable) && strict.origin.calls.Load() == 0,
		"after %s, origin calls %d: %v", ms(time.Since(started)), strict.origin.calls.Load(), err)

	if err := l.recover(ctx, r, c, strict, "13", "fb-3"); err != nil {
		return err
	}

	mark = l.events.mark()
	r.set(relayHang)
	// The read waits CacheRequestTimeout for its reply, then ConnectionTimeout for a reconnect.
	if err := l.fallback(ctx, c, "14 a read on a hung cache goes to DynamoDB", "fb-4",
		faultTimeout, 2*faultTimeout+time.Second); err != nil {
		return err
	}
	l.check("14 the in-order timeout closed the hung connection", l.events.since(mark, 1202, "") > 0,
		"1202 seen %d", l.events.since(mark, 1202, ""))
	return l.recover(ctx, r, c, strict, "14", "fb-5")
}

// fallback reads item through c and checks that it went to DynamoDB, is correct, and took
// from least to most.
func (l *live) fallback(ctx context.Context, c *cacheClient, name, item string, least, most time.Duration) error {
	before := c.origin.calls.Load()
	started := time.Now()
	got, err := l.get(ctx, c.ddb, l.key(item))
	took := time.Since(started)
	want, derr := l.direct(ctx, l.key(item))
	if derr != nil {
		return derr
	}
	calls := c.origin.calls.Load() - before
	l.check(name, err == nil && calls >= 1 && canon(got) == canon(want) && took >= least && took <= most,
		"after %s (expected %s to %s), origin calls %d, err %v, item %s", ms(took), ms(least), ms(most), calls, err, canon(got))
	return nil
}

// checkRelayTarget accepts only an <ip>:<port> that is not on this host: a loopback or empty
// address would make the relay dial itself.
func checkRelayTarget(target string) error {
	host, port, err := net.SplitHostPort(target)
	if err != nil {
		return fmt.Errorf("LIVE_RELAY_TARGET must be <ip>:<port>, got %q", target)
	}
	ip := net.ParseIP(host)
	if p, perr := strconv.Atoi(port); perr != nil || p < 1 || p > 65535 || ip == nil || ip.IsLoopback() || ip.IsUnspecified() {
		return fmt.Errorf("LIVE_RELAY_TARGET must be the <ip>:<port> of a cache node, not of this host, got %q", target)
	}
	return nil
}

// recover lets the relay forward again and checks that both clients serve through the cache.
func (l *live) recover(ctx context.Context, r *relay, c, strict *cacheClient, check, item string) error {
	mark := l.events.mark()
	r.set(relayForward)
	wctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	_, ok := l.events.wait(wctx, mark, 1304, "reconnected", 2)
	l.check(check+" both clients reconnect when the cache is back", ok, "1304 seen %d", l.events.since(mark, 1304, ""))
	if err := pause(ctx, faultTimeout); err != nil {
		return err
	}
	if err := l.miss(ctx, c, check+" routed through the cache again", item); err != nil {
		return err
	}
	return l.miss(ctx, strict, check+" strict client routed through the cache again", item)
}
