// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

//go:build go1.25

package elasticacheintegrations

import (
	"context"
	"errors"
	"log/slog"
	"sync"
	"testing"
	"testing/synctest"
	"time"

	"github.com/awslabs/elasticache-integration-client/go/internal/testutil"
)

// tickResult is what a scripted tick returns.
type tickResult struct {
	ran bool
	err error
}

// scriptedRunner runs a periodicRunner whose ticks return script in order, then succeed. It
// records the time of every tick, from the start.
type scriptedRunner struct {
	mu     sync.Mutex
	at     []time.Duration
	logs   *testutil.LogCapture
	cancel context.CancelFunc
	done   chan struct{}
}

func startScripted(t *testing.T, interval time.Duration, failureDelay func(int) time.Duration, script ...tickResult) (*scriptedRunner, *periodicRunner) {
	t.Helper()
	c := mockConfig(t, nil, "localhost:6379")
	c.jitter = 0
	logs, l := testutil.NewLogCapture()
	c.logger = newLogger(l)
	sr := &scriptedRunner{logs: logs, done: make(chan struct{})}
	start := time.Now()
	n := 0
	r := newPeriodicRunner("Test task", interval, c.jitter, c.logger, failureDelay, func(context.Context) (bool, error) {
		sr.mu.Lock()
		defer sr.mu.Unlock()
		sr.at = append(sr.at, time.Since(start))
		res := tickResult{ran: true}
		if n < len(script) {
			res = script[n]
		}
		n++
		return res.ran, res.err
	})
	ctx, cancel := context.WithCancel(context.Background())
	sr.cancel = cancel
	go func() { r.run(ctx); close(sr.done) }()
	t.Cleanup(func() { cancel(); <-sr.done })
	return sr, r
}

func (sr *scriptedRunner) times() []time.Duration {
	sr.mu.Lock()
	defer sr.mu.Unlock()
	return append([]time.Duration(nil), sr.at...)
}

func equalDurations(a, b []time.Duration) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

var errTick = errors.New("tick failed")

func TestPeriodicRunnerInterval(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		sr, _ := startScripted(t, time.Minute, nil)
		time.Sleep(3*time.Minute + time.Second)
		if got, want := sr.times(), []time.Duration{time.Minute, 2 * time.Minute, 3 * time.Minute}; !equalDurations(got, want) {
			t.Fatalf("ticks at %v, want %v", got, want)
		}
		if n := len(sr.logs.Records()); n != 0 {
			t.Fatalf("%d records for successful ticks", n)
		}
	})
}

func TestPeriodicRunnerFailureDelay(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		delay := func(f int) time.Duration { return time.Duration(f) * time.Second }
		sr, _ := startScripted(t, time.Minute, delay,
			tickResult{err: errTick}, tickResult{err: errTick}, tickResult{err: errTick})
		time.Sleep(2*time.Minute + 7*time.Second)
		// 60s fails, +1s fails, +2s fails, +3s succeeds, then the interval again.
		want := []time.Duration{60 * time.Second, 61 * time.Second, 63 * time.Second, 66 * time.Second, 126 * time.Second}
		if got := sr.times(); !equalDurations(got, want) {
			t.Fatalf("ticks at %v, want %v", got, want)
		}
		failedRecs := eventRecords(sr.logs, eventPeriodicFailed)
		if len(failedRecs) != 3 || failedRecs[0].Level != slog.LevelWarn || failedRecs[1].Level != slog.LevelDebug {
			t.Fatalf("1101 records: %v", failedRecs)
		}
		rec := eventRecords(sr.logs, eventPeriodicRecovered)
		if len(rec) != 1 {
			t.Fatalf("1102 records: %v", rec)
		}
		if v, _ := testutil.Attr(rec[0], "failures"); v.Int64() != 3 {
			t.Fatalf("recovered after %v failures", v)
		}
	})
}

func TestPeriodicRunnerFailureWithoutDelayWaitsInterval(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		sr, _ := startScripted(t, time.Minute, nil, tickResult{err: errTick})
		time.Sleep(2*time.Minute + time.Second)
		if got, want := sr.times(), []time.Duration{time.Minute, 2 * time.Minute}; !equalDurations(got, want) {
			t.Fatalf("ticks at %v, want %v", got, want)
		}
	})
}

func TestPeriodicRunnerLogsFirstAndEveryHundredthFailure(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		script := make([]tickResult, 200)
		for i := range script {
			script[i] = tickResult{err: errTick}
		}
		sr, _ := startScripted(t, time.Second, nil, script...)
		time.Sleep(200*time.Second + time.Millisecond)
		warns := 0
		for _, r := range eventRecords(sr.logs, eventPeriodicFailed) {
			if r.Level == slog.LevelWarn {
				warns++
			}
		}
		if n := len(eventRecords(sr.logs, eventPeriodicFailed)); n != 200 || warns != 3 {
			t.Fatalf("%d failure records, %d warnings; want 200 and 3 (1st, 100th, 200th)", n, warns)
		}
	})
}

// A skipped tick resets the failure count with no log, as Java's Refresher counts it a
// success: the tick after it waits the interval, not the backoff.
func TestPeriodicRunnerSkipResetsFailures(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		delay := func(int) time.Duration { return 100 * time.Millisecond }
		sr, _ := startScripted(t, time.Minute, delay,
			tickResult{err: errTick}, tickResult{}, tickResult{ran: true})
		time.Sleep(2*time.Minute + 101*time.Millisecond)
		want := []time.Duration{time.Minute, time.Minute + 100*time.Millisecond, 2*time.Minute + 100*time.Millisecond}
		if got := sr.times(); !equalDurations(got, want) {
			t.Fatalf("ticks at %v, want %v: a skip must reset the backoff", got, want)
		}
		if len(eventRecords(sr.logs, eventPeriodicRecovered)) != 0 || len(eventRecords(sr.logs, eventPeriodicFailed)) != 1 {
			t.Fatalf("records:\n%s", sr.logs.Text())
		}
	})
}

func TestPeriodicRunnerSkipWithoutFailuresKeepsInterval(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		sr, _ := startScripted(t, time.Minute, func(int) time.Duration { return time.Millisecond }, tickResult{})
		time.Sleep(2*time.Minute + time.Second)
		if got, want := sr.times(), []time.Duration{time.Minute, 2 * time.Minute}; !equalDurations(got, want) {
			t.Fatalf("ticks at %v, want %v", got, want)
		}
		if n := len(sr.logs.Records()); n != 0 {
			t.Fatalf("a skip logged:\n%s", sr.logs.Text())
		}
	})
}

func TestPeriodicRunnerSetInterval(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		sr, r := startScripted(t, time.Minute, nil)
		time.Sleep(30 * time.Second)
		r.setInterval(10 * time.Second) // the armed 60s wait is kept
		time.Sleep(51 * time.Second)
		if got, want := sr.times(), []time.Duration{60 * time.Second, 70 * time.Second, 80 * time.Second}; !equalDurations(got, want) {
			t.Fatalf("ticks at %v, want %v", got, want)
		}
	})
}

func TestPeriodicRunnerStopsOnCancel(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		sr, _ := startScripted(t, time.Minute, nil)
		time.Sleep(90 * time.Second)
		sr.cancel()
		<-sr.done
		time.Sleep(time.Hour)
		if n := len(sr.times()); n != 1 {
			t.Fatalf("%d ticks, want 1", n)
		}
	})
}

func TestPeriodicRunnerCancelDuringTickIsNotAFailure(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		c := mockConfig(t, nil, "localhost:6379")
		logs, l := testutil.NewLogCapture()
		c.logger = newLogger(l)
		ctx, cancel := context.WithCancel(context.Background())
		r := newPeriodicRunner("Test task", time.Second, c.jitter, c.logger, nil, func(ctx context.Context) (bool, error) {
			cancel()
			return true, ctx.Err()
		})
		r.run(ctx)
		if n := len(logs.Records()); n != 0 {
			t.Fatalf("a stop logged:\n%s", logs.Text())
		}
	})
}
