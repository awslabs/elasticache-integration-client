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
	r := newPeriodicRunner("Test task", time.Minute, c, nil, nil)
	for _, d := range []time.Duration{0, -time.Second} {
		r.setInterval(d)
		if got := r.delay(0); got != time.Minute {
			t.Fatalf("after setInterval(%v) the delay is %v, want 1m", d, got)
		}
	}
}
