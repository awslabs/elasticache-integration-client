// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"os"
	"runtime"
	"sync/atomic"
	"time"
)

// eventID is a stable id on every log record, in the "event" attribute, so a consumer can
// filter on it. The ids match the .NET client's.
type eventID int

const (
	eventInsecureSkipVerify eventID = 1001

	// Re-AUTH and periodic tasks.
	eventPeriodicFailed    eventID = 1101
	eventPeriodicRecovered eventID = 1102
	eventAuthDegraded      eventID = 1103

	// One connection.
	eventConnectionLost  eventID = 1201
	eventInOrderTimeout  eventID = 1202
	eventLatePushDropped eventID = 1203

	// The session.
	eventConfigApplied        eventID = 1301
	eventBringUpAttemptFailed eventID = 1302
	eventReconnecting         eventID = 1303
	eventReconnected          eventID = 1304
	eventCacheUnavailable     eventID = 1305
	eventConfigStale          eventID = 1306
	eventConfigUnusable       eventID = 1307
	eventPendingLimitReached  eventID = 1308
)

// logger writes the client's records. It never panics and never blocks the caller on a
// failing handler beyond the handler's own call.
type logger struct {
	l *slog.Logger
}

// newLogger returns the configured logger, or the default: Info and above, as text, to
// stderr, so warnings are visible with no configuration. A configured handler that panics
// while taking the component attribute is replaced by the default.
func newLogger(configured *slog.Logger) *logger {
	if configured != nil {
		if l := withComponent(configured); l != nil {
			return &logger{l: l}
		}
	}
	return &logger{l: withComponent(slog.New(newDefaultHandler(os.Stderr)))}
}

func withComponent(l *slog.Logger) (out *slog.Logger) {
	defer func() {
		if recover() != nil {
			out = nil
		}
	}()
	return l.With(slog.String("component", "elasticacheintegrations"))
}

func newDefaultHandler(w io.Writer) slog.Handler {
	return slog.NewTextHandler(w, &slog.HandlerOptions{Level: slog.LevelInfo})
}

func (lg *logger) enabled(level slog.Level) (ok bool) {
	defer func() {
		if recover() != nil {
			ok = false
		}
	}()
	return lg.l.Enabled(context.Background(), level)
}

// log writes one record. Its source is the caller of debug, info, warn or error.
func (lg *logger) log(level slog.Level, ev eventID, msg string, attrs ...slog.Attr) {
	// The client logs on failure paths and in its own goroutines; a panicking handler must
	// not take them down.
	defer func() { _ = recover() }()
	ctx := context.Background()
	h := lg.l.Handler()
	if !h.Enabled(ctx, level) {
		return
	}
	var pcs [1]uintptr
	runtime.Callers(3, pcs[:]) // skip Callers, log and the level method
	r := slog.NewRecord(time.Now(), level, msg, pcs[0])
	r.AddAttrs(slog.Int("event", int(ev)))
	r.AddAttrs(attrs...)
	_ = h.Handle(ctx, r)
}

func (lg *logger) debug(ev eventID, msg string, attrs ...slog.Attr) {
	lg.log(slog.LevelDebug, ev, msg, attrs...)
}

func (lg *logger) info(ev eventID, msg string, attrs ...slog.Attr) {
	lg.log(slog.LevelInfo, ev, msg, attrs...)
}

func (lg *logger) warn(ev eventID, msg string, attrs ...slog.Attr) {
	lg.log(slog.LevelWarn, ev, msg, attrs...)
}

func (lg *logger) error(ev eventID, msg string, attrs ...slog.Attr) {
	lg.log(slog.LevelError, ev, msg, attrs...)
}

// insecureSkipVerifyWarned is the only package-level mutable state: the warning is written
// once per process.
var insecureSkipVerifyWarned atomic.Bool

// warnInsecureSkipVerify warns once per process that certificate checks are off.
func (lg *logger) warnInsecureSkipVerify() {
	lg.warnOnce(&insecureSkipVerifyWarned, eventInsecureSkipVerify,
		"TLS certificate verification is disabled (InsecureSkipVerify); use it only against a local or test endpoint")
}

// warnOnce writes a warning the first time flag is passed. A logger that drops warnings
// does not use up the flag.
func (lg *logger) warnOnce(flag *atomic.Bool, ev eventID, msg string, attrs ...slog.Attr) {
	if lg.enabled(slog.LevelWarn) && flag.CompareAndSwap(false, true) {
		lg.warn(ev, msg, attrs...)
	}
}

// failureLog decides which failures of a repeating operation to log: the first, then every
// 100th, until a success resets it.
type failureLog struct {
	n atomic.Uint64
}

// failed counts a failure and reports the count and whether to log it.
func (f *failureLog) failed() (count uint64, log bool) {
	n := f.n.Add(1)
	return n, n == 1 || n%100 == 0
}

// recovered resets the count and returns how many failures came before the success.
func (f *failureLog) recovered() uint64 {
	return f.n.Swap(0)
}

// burstGap ends a burst: an event after this long with none starts a new one (.NET
// PendingBurstGap).
const burstGap = time.Second

// burstLog logs the first event of each burst of a recurring condition. Only the event path
// touches it.
type burstLog struct {
	last atomic.Int64 // UnixNano of the latest event, 0 before the first
}

// hit records an event at now and reports whether it starts a burst.
func (b *burstLog) hit(now time.Time) bool {
	t := now.UnixNano()
	last := b.last.Swap(t)
	return last == 0 || t-last >= int64(burstGap)
}

// secret holds a credential, token or signature. Every way of printing it writes
// "[REDACTED]"; reveal returns the value for the wire. The value sits behind a pointer, so
// fmt printing a struct that holds a secret in an unexported field, where Format is not
// called, writes an address and not the value.
type secret struct{ v *string }

const redacted = "[REDACTED]"

func newSecret(s string) secret { return secret{v: &s} }

func (s secret) reveal() string {
	if s.v == nil {
		return ""
	}
	return *s.v
}

func (secret) String() string               { return redacted }
func (secret) GoString() string             { return redacted }
func (secret) LogValue() slog.Value         { return slog.StringValue(redacted) }
func (secret) MarshalText() ([]byte, error) { return []byte(redacted), nil }
func (secret) Format(f fmt.State, _ rune)   { _, _ = io.WriteString(f, redacted) }
