// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"log/slog"
	"math"
	"testing"
	"time"

	"github.com/awslabs/elasticache-integration-client/go/internal/testutil"
)

// eventRecords returns the captured records of event ev.
func eventRecords(logs *testutil.LogCapture, ev eventID) []slog.Record {
	var out []slog.Record
	for _, r := range logs.Records() {
		if v, ok := testutil.Attr(r, "event"); ok && v.Int64() == int64(ev) {
			out = append(out, r)
		}
	}
	return out
}

func TestBackoff(t *testing.T) {
	c := mockConfig(t, nil, "localhost:6379")
	c.jitter = 0
	want := []time.Duration{100, 200, 400, 800, 1600, 3200, 3200}
	for i, w := range want {
		if got := backoff(c, i+1); got != w*time.Millisecond {
			t.Fatalf("backoff(%d) = %v, want %v", i+1, got, w*time.Millisecond)
		}
	}
	if got := backoff(c, math.MaxInt); got != 3200*time.Millisecond {
		t.Fatalf("backoff(huge) = %v", got)
	}
	if got := backoff(c, 0); got != 100*time.Millisecond {
		t.Fatalf("backoff(0) = %v", got)
	}
	c.backoffMin, c.backoffMax = 100*time.Microsecond, 100*time.Microsecond
	if got := backoff(c, 1); got != time.Millisecond {
		t.Fatalf("backoff below 1ms = %v", got)
	}
}

func TestExpBackoffBounds(t *testing.T) {
	noRand := func() float64 { t.Fatal("rnd called with no jitter"); return 0 }
	cases := []struct {
		lo, hi  time.Duration
		attempt int
		want    time.Duration
	}{
		{lo: 100 * time.Millisecond, hi: time.Second, attempt: 0, want: 100 * time.Millisecond},
		{lo: 100 * time.Millisecond, hi: time.Second, attempt: -5, want: 100 * time.Millisecond},
		{lo: 0, hi: time.Second, attempt: 3, want: time.Millisecond},
		{lo: 2 * time.Second, hi: time.Second, attempt: 3, want: 2 * time.Second},
		{lo: 2 * time.Second, hi: 2 * time.Second, attempt: math.MaxInt, want: 2 * time.Second},
		{lo: 9 * time.Second, hi: time.Hour, attempt: 40, want: time.Hour},
		{lo: 9 * time.Second, hi: time.Hour, attempt: math.MaxInt, want: time.Hour},
		{lo: 1<<34 + 1, hi: time.Hour, attempt: 31, want: time.Hour}, // lo<<30 wraps to about 1s
		{lo: time.Nanosecond, hi: 2 * time.Hour, attempt: 45, want: 2 * time.Hour},
		{lo: time.Nanosecond, hi: 2 * time.Hour, attempt: 64, want: 2 * time.Hour},
		{lo: time.Millisecond, hi: math.MaxInt64, attempt: 64, want: math.MaxInt64},
	}
	for _, tc := range cases {
		if got := expBackoff(tc.lo, tc.hi, 0, tc.attempt, noRand); got != tc.want {
			t.Errorf("expBackoff(%v, %v, 0, %d) = %v, want %v", tc.lo, tc.hi, tc.attempt, got, tc.want)
		}
	}
}

func TestBackoffJitter(t *testing.T) {
	c := mockConfig(t, nil, "localhost:6379")
	lo, hi := time.Hour, time.Duration(0)
	for range 10000 {
		d := backoff(c, 3) // 400ms ±20%
		lo, hi = min(lo, d), max(hi, d)
		if d < 320*time.Millisecond || d > 480*time.Millisecond || d%time.Millisecond != 0 {
			t.Fatalf("backoff(3) = %v, outside 400ms ±20%% or not whole ms", d)
		}
	}
	if hi-lo < 100*time.Millisecond {
		t.Fatalf("jitter spread %v..%v is too narrow", lo, hi)
	}
}

func TestJittered(t *testing.T) {
	if got := jittered(time.Minute, 0); got != time.Minute {
		t.Fatalf("no jitter: %v", got)
	}
	for range 10000 {
		if d := jittered(time.Minute, 0.2); d < 48*time.Second || d >= 72*time.Second {
			t.Fatalf("jittered = %v", d)
		}
	}
}

func TestPeriodicRunnerSetIntervalIgnoresNonPositive(t *testing.T) {
	c := mockConfig(t, nil, "localhost:6379")
	c.jitter = 0
	r := newPeriodicRunner("Test task", time.Minute, c.jitter, c.logger, nil, nil)
	for _, d := range []time.Duration{0, -time.Second} {
		r.setInterval(d)
		if got := r.delay(0); got != time.Minute {
			t.Fatalf("after setInterval(%v) the delay is %v, want 1m", d, got)
		}
	}
}
