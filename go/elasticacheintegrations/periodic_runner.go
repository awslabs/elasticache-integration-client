// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"context"
	"log/slog"
	"math/rand/v2"
	"sync/atomic"
	"time"
)

// backoff is the reconnect and re-AUTH wait after failed attempt n (from 1).
func backoff(cfg *config, attempt int) time.Duration {
	return expBackoff(cfg.backoffMin, cfg.backoffMax, cfg.jitter, attempt, rand.Float64)
}

// expBackoff is the wait after failed attempt n (from 1): lo doubling up to hi, scaled by
// 1-jitter+2*jitter*rnd(), in whole milliseconds and at least 1ms.
func expBackoff(lo, hi time.Duration, jitter float64, attempt int, rnd func() float64) time.Duration {
	d := lo
	if n := min(max(attempt-1, 0), 63); lo < hi {
		d = hi
		if lo <= hi>>n { // lo<<n neither passes hi nor overflows
			d = lo << n
		}
	}
	if jitter != 0 {
		d = time.Duration(float64(d) * (1 - jitter + 2*jitter*rnd()))
	}
	return max(time.Millisecond, d.Round(time.Millisecond))
}

// jittered returns d scaled by a uniform factor in [1-j, 1+j).
func jittered(d time.Duration, j float64) time.Duration {
	if j == 0 {
		return d
	}
	return time.Duration(float64(d) * (1 - j + 2*j*rand.Float64()))
}

// periodicRunner calls tick every interval ±jitter until its context ends. A failed tick
// waits failureDelay(failures), or the interval when failureDelay is nil. A tick that did
// not run (ran false), because there was no live connection, resets the failure count with
// no log, as Java's Refresher counts it a success: a new connection authenticates and fetches
// the config itself.
type periodicRunner struct {
	name         string
	interval     atomic.Int64 // time.Duration
	jitter       float64
	failureDelay func(failures int) time.Duration
	tick         func(context.Context) (ran bool, err error)
	logger       *logger
}

func newPeriodicRunner(name string, interval time.Duration, jitter float64, logger *logger,
	failureDelay func(failures int) time.Duration, tick func(context.Context) (bool, error)) *periodicRunner {
	r := &periodicRunner{name: name, jitter: jitter, failureDelay: failureDelay, tick: tick, logger: logger}
	r.setInterval(interval)
	return r
}

// setInterval changes the interval from the next wait on. A wait already armed keeps its delay.
// A non-positive d is ignored, so the runner never spins.
func (r *periodicRunner) setInterval(d time.Duration) {
	if d > 0 {
		r.interval.Store(int64(d))
	}
}

// run loops until ctx ends. The caller runs it on a goroutine it owns.
func (r *periodicRunner) run(ctx context.Context) {
	failures := 0
	timer := time.NewTimer(r.delay(failures))
	defer timer.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-timer.C:
		}
		ran, err := r.tick(ctx)
		if ctx.Err() != nil {
			return
		}
		switch {
		case err != nil:
			failures++
			level := slog.LevelDebug
			if failures == 1 || failures%100 == 0 {
				level = slog.LevelWarn
			}
			r.logger.log(level, eventPeriodicFailed, r.name+" failed; retrying",
				slog.Int("failures", failures), slog.String("error", err.Error()))
		default:
			if ran && failures > 0 {
				r.logger.info(eventPeriodicRecovered, r.name+" succeeded after failures",
					slog.Int("failures", failures))
			}
			failures = 0
		}
		timer.Reset(r.delay(failures))
	}
}

// delay is the next wait: the jittered interval, or the failure delay after failures.
func (r *periodicRunner) delay(failures int) time.Duration {
	if failures > 0 && r.failureDelay != nil {
		return r.failureDelay(failures)
	}
	return jittered(time.Duration(r.interval.Load()), r.jitter)
}
