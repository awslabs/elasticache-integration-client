// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"log"
	"log/slog"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/awslabs/elasticache-integration-client/go/internal/testutil"
)

func TestEventIDsMatchDotNet(t *testing.T) {
	for ev, want := range map[eventID]int{
		eventInsecureSkipVerify:   1001,
		eventPeriodicFailed:       1101,
		eventPeriodicRecovered:    1102,
		eventAuthDegraded:         1103,
		eventConnectionLost:       1201,
		eventInOrderTimeout:       1202,
		eventLatePushDropped:      1203,
		eventConfigApplied:        1301,
		eventBringUpAttemptFailed: 1302,
		eventReconnecting:         1303,
		eventReconnected:          1304,
		eventCacheUnavailable:     1305,
		eventConfigStale:          1306,
		eventConfigUnusable:       1307,
		eventPendingLimitReached:  1308,
	} {
		if int(ev) != want {
			t.Errorf("event %d, want %d", ev, want)
		}
	}
}

func TestLoggerAddsEventAndComponent(t *testing.T) {
	capture, l := testutil.NewLogCapture()
	lg := newLogger(l)
	lg.debug(eventLatePushDropped, "d")
	lg.info(eventConfigApplied, "i", slog.String("version", "1.0"))
	lg.warn(eventReconnecting, "w")
	lg.error(eventCacheUnavailable, "e")

	records := capture.Records()
	want := []struct {
		level slog.Level
		msg   string
		ev    eventID
	}{
		{slog.LevelDebug, "d", eventLatePushDropped},
		{slog.LevelInfo, "i", eventConfigApplied},
		{slog.LevelWarn, "w", eventReconnecting},
		{slog.LevelError, "e", eventCacheUnavailable},
	}
	if len(records) != len(want) {
		t.Fatalf("got %d records", len(records))
	}
	for i, w := range want {
		r := records[i]
		if r.Level != w.level || r.Message != w.msg {
			t.Errorf("record %d = %v %q", i, r.Level, r.Message)
		}
		if v, ok := testutil.Attr(r, "event"); !ok || v.Int64() != int64(w.ev) {
			t.Errorf("record %d event = %v %v", i, v, ok)
		}
		if v, ok := testutil.Attr(r, "component"); !ok || v.String() != "elasticacheintegrations" {
			t.Errorf("record %d component = %v %v", i, v, ok)
		}
	}
	if v, _ := testutil.Attr(records[1], "version"); v.String() != "1.0" {
		t.Errorf("attribute lost: %v", v)
	}
}

// With no Logger option and no slog configuration, records reach stderr through the log
// package at Info and above.
func TestDefaultLoggerWritesInfoAndAboveWithNoConfiguration(t *testing.T) {
	var buf bytes.Buffer
	w, flags := log.Writer(), log.Flags()
	log.SetOutput(&buf)
	t.Cleanup(func() { log.SetOutput(w); log.SetFlags(flags) })
	lg := newLogger(nil)
	lg.debug(eventLatePushDropped, "hidden")
	lg.warn(eventConnectionLost, "shown")
	out := buf.String()
	if strings.Contains(out, "hidden") {
		t.Errorf("debug record written: %s", out)
	}
	if !strings.Contains(out, "WARN shown") || !strings.Contains(out, "event=1201") ||
		!strings.Contains(out, "component=elasticacheintegrations") {
		t.Errorf("unexpected default output: %s", out)
	}
}

type panicHandler struct{ slog.Handler }

func (panicHandler) Enabled(context.Context, slog.Level) bool { panic("enabled") }

type panicOnHandle struct{ slog.Handler }

func (panicOnHandle) Enabled(context.Context, slog.Level) bool  { return true }
func (panicOnHandle) Handle(context.Context, slog.Record) error { panic("handle") }
func (h panicOnHandle) WithAttrs([]slog.Attr) slog.Handler      { return h }

type panicOnWithAttrs struct{ slog.Handler }

func (panicOnWithAttrs) WithAttrs([]slog.Attr) slog.Handler { panic("with attrs") }

// setDefaultLogger makes l slog's default logger for the test, and restores the log
// package's output, which slog.SetDefault redirects. Such tests cannot run in parallel.
func setDefaultLogger(t *testing.T, l *slog.Logger) {
	old, w, flags := slog.Default(), log.Writer(), log.Flags()
	slog.SetDefault(l)
	t.Cleanup(func() {
		slog.SetDefault(old)
		log.SetOutput(w)
		log.SetFlags(flags)
	})
}

func TestSetDefaultLoggerRestoresLogOutput(t *testing.T) {
	w, flags := log.Writer(), log.Flags()
	t.Run("swap", func(t *testing.T) { setDefaultLogger(t, slog.New(slog.DiscardHandler)) })
	if log.Writer() != w || log.Flags() != flags {
		t.Fatal("the log package's output was left redirected")
	}
}

func TestNewLoggerFallsBackWhenWithAttrsPanics(t *testing.T) {
	capture, l := testutil.NewLogCapture()
	setDefaultLogger(t, l)
	newLogger(slog.New(panicOnWithAttrs{slog.DiscardHandler})).info(eventConfigApplied, "m")
	records := capture.Records()
	if len(records) != 1 {
		t.Fatalf("slog.Default() got %d records, want 1", len(records))
	}
	if v, ok := testutil.Attr(records[0], "component"); !ok || v.String() != "elasticacheintegrations" {
		t.Errorf("component = %v %v, want elasticacheintegrations", v, ok)
	}
}

// With no Logger option the client logs through slog.Default(), so an application that sets
// it gets the client's records in its own format.
func TestNewLoggerDefaultsToSlogDefault(t *testing.T) {
	capture, l := testutil.NewLogCapture()
	setDefaultLogger(t, l)
	newLogger(nil).warn(eventConnectionLost, "w")
	records := capture.Records()
	if len(records) != 1 || records[0].Message != "w" || records[0].Level != slog.LevelWarn {
		t.Fatalf("records = %v, want the one warning", records)
	}
	if v, ok := testutil.Attr(records[0], "component"); !ok || v.String() != "elasticacheintegrations" {
		t.Errorf("component = %v %v, want elasticacheintegrations", v, ok)
	}
	if v, ok := testutil.Attr(records[0], "event"); !ok || v.Int64() != int64(eventConnectionLost) {
		t.Errorf("event = %v %v, want %d", v, ok, eventConnectionLost)
	}
}

// slog.Default() is read for each record, so an application that configures slog after the
// client starts gets every record, at its own level, from then on.
func TestDefaultLoggerFollowsLaterSetDefault(t *testing.T) {
	lg := newLogger(nil)
	capture, l := testutil.NewLogCapture()
	setDefaultLogger(t, l)
	lg.warn(eventConnectionLost, "w")
	records := capture.Records()
	if len(records) != 1 || records[0].Level != slog.LevelWarn || records[0].Message != "w" {
		t.Fatalf("records = %v, want the warning at Warn", records)
	}
	if v, ok := testutil.Attr(records[0], "component"); !ok || v.String() != "elasticacheintegrations" {
		t.Errorf("component = %v %v, want elasticacheintegrations", v, ok)
	}
}

func TestRecordSourceIsTheCaller(t *testing.T) {
	capture, l := testutil.NewLogCapture()
	newLogger(l).warn(eventConnectionLost, "m")
	r := capture.Records()[0]
	frame, _ := runtime.CallersFrames([]uintptr{r.PC}).Next()
	if filepath.Base(frame.File) != "logging_test.go" {
		t.Fatalf("source = %s:%d, want logging_test.go", frame.File, frame.Line)
	}
}

func TestLoggerSurvivesPanickingHandler(t *testing.T) {
	for _, h := range []slog.Handler{panicHandler{slog.DiscardHandler}, panicOnHandle{slog.DiscardHandler}} {
		lg := &logger{l: slog.New(h)}
		lg.warn(eventConnectionLost, "m")
		var flag atomic.Bool
		lg.warnOnce(&flag, eventInsecureSkipVerify, "m")
	}
	setDefaultLogger(t, slog.New(panicOnHandle{slog.DiscardHandler}))
	newLogger(nil).warn(eventConnectionLost, "m")
}

func TestWarnOnceWritesOnceAndOnlyWhenEnabled(t *testing.T) {
	var flag atomic.Bool
	quiet := newLogger(slog.New(slog.DiscardHandler))
	quiet.warnOnce(&flag, eventInsecureSkipVerify, "w")
	if flag.Load() {
		t.Fatal("a logger that drops warnings used up the flag")
	}

	capture, l := testutil.NewLogCapture()
	lg := newLogger(l)
	var wg sync.WaitGroup
	for range 50 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			lg.warnOnce(&flag, eventInsecureSkipVerify, "w")
		}()
	}
	wg.Wait()
	if n := len(capture.Records()); n != 1 {
		t.Fatalf("%d warnings, want 1", n)
	}
}

// The warning is written once per client: two clients in one process each warn.
func TestWarnInsecureSkipVerifyOncePerClient(t *testing.T) {
	t.Parallel()
	capture, l := testutil.NewLogCapture()
	first, second := newLogger(l), newLogger(l)
	first.warnInsecureSkipVerify()
	first.warnInsecureSkipVerify()
	second.warnInsecureSkipVerify()
	records := capture.Records()
	if len(records) != 2 {
		t.Fatalf("%d records, want 2 (one per client): %v", len(records), records)
	}
	for _, r := range records {
		if r.Level != slog.LevelWarn || !strings.Contains(r.Message, "InsecureSkipVerify") {
			t.Fatalf("record = %v, want a Warn naming InsecureSkipVerify", r)
		}
	}
}

func TestFailureLogFirstAndEveryHundredth(t *testing.T) {
	var f failureLog
	var logged []uint64
	for range 250 {
		if n, ok := f.failed(); ok {
			logged = append(logged, n)
		}
	}
	if fmt.Sprint(logged) != "[1 100 200]" {
		t.Fatalf("logged at %v", logged)
	}
	if n := f.recovered(); n != 250 {
		t.Fatalf("recovered() = %d", n)
	}
	if n, ok := f.failed(); n != 1 || !ok {
		t.Fatalf("after recovery: %d %v", n, ok)
	}
}

// A burst ends only after burstGap with no event; events closer together, however many, are one
// burst, logged once.
func TestBurstLogFirstOfEachBurst(t *testing.T) {
	var b burstLog
	t0 := time.Unix(1000, 0)
	var starts []int
	for i := range 250 {
		if b.hit(t0.Add(time.Duration(i) * (burstGap - time.Millisecond))) {
			starts = append(starts, i)
		}
	}
	if fmt.Sprint(starts) != "[0]" {
		t.Fatalf("bursts start at %v", starts)
	}
	if !b.hit(t0.Add(249*(burstGap-time.Millisecond) + burstGap)) {
		t.Fatal("an event a gap later does not start a burst")
	}
}

func TestSecretNeverPrints(t *testing.T) {
	s := newSecret(testutil.SecretAccessKey)
	capture, l := testutil.NewLogCapture()
	newLogger(l).info(eventConfigApplied, "m", slog.Any("token", s), slog.Any("nested", []any{s}))
	var jsonBuf bytes.Buffer
	slog.New(slog.NewJSONHandler(&jsonBuf, nil)).Info("m", "token", s)
	js, _ := json.Marshal(map[string]any{"token": s})

	out := strings.Join([]string{
		capture.Text(), jsonBuf.String(), string(js),
		fmt.Sprint(s), fmt.Sprintf("%v %+v %#v %s %q %x %d", s, s, s, s, s, s, s),
		fmt.Sprintf("%v", struct{ S secret }{s}), s.String(),
	}, "\n")
	testutil.AssertNoSecrets(t, out, testutil.SecretAccessKey)
	if !strings.Contains(out, redacted) {
		t.Fatalf("no %s in output: %s", redacted, out)
	}
	if s.reveal() != testutil.SecretAccessKey {
		t.Fatal("reveal() lost the value")
	}
	if (secret{}).reveal() != "" {
		t.Fatal("zero secret reveals a value")
	}
}

// secretHolder keeps a secret in an unexported field, where fmt cannot call its methods.
type secretHolder struct {
	name  string
	token secret
}

func TestSecretInUnexportedFieldNeverPrints(t *testing.T) {
	s := newSecret(testutil.SecretAccessKey)
	h := secretHolder{name: "n", token: s}
	var text, js bytes.Buffer
	slog.New(slog.NewTextHandler(&text, nil)).Info("m", "h", h, "p", &h)
	slog.New(slog.NewJSONHandler(&js, nil)).Info("m", "h", h, "s", s)
	enc, _ := json.Marshal(struct{ S secret }{s})
	err := fmt.Errorf("wrapped: %w", fmt.Errorf("%+v", h))

	out := strings.Join([]string{
		fmt.Sprintf("%v %+v %#v %v %+v", h, h, h, &h, []secretHolder{h}),
		fmt.Sprintf("%p", s),
		text.String(), js.String(), string(enc), err.Error(),
	}, "\n")
	testutil.AssertNoSecrets(t, out, testutil.SecretAccessKey)
}
