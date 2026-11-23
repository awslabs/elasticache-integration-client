// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"strings"
	"sync"
	"time"
)

// event is one log record of the client.
type event struct {
	at    time.Time
	id    int
	level slog.Level
	msg   string
	err   string
}

// eventLog records every client log record at Debug and up, and prints Info and up.
type eventLog struct {
	mu     sync.Mutex
	events []event
	out    io.Writer
	start  time.Time
}

func newEventLog(out io.Writer) *eventLog { return &eventLog{out: out, start: time.Now()} }

func (l *eventLog) logger() *slog.Logger { return slog.New(&eventHandler{log: l}) }

// mark is the number of events so far, for since.
func (l *eventLog) mark() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.events)
}

// since counts events after mark with the id and message prefix.
func (l *eventLog) since(mark, id int, prefix string) int {
	l.mu.Lock()
	defer l.mu.Unlock()
	n := 0
	for _, e := range l.events[mark:] {
		if e.id == id && strings.HasPrefix(e.msg, prefix) {
			n++
		}
	}
	return n
}

// wait blocks until n events with the id and prefix are recorded after mark, or ctx ends. It
// returns the nth.
func (l *eventLog) wait(ctx context.Context, mark, id int, prefix string, n int) (event, bool) {
	tick := time.NewTicker(50 * time.Millisecond)
	defer tick.Stop()
	for {
		l.mu.Lock()
		seen := 0
		for _, e := range l.events[mark:] {
			if e.id == id && strings.HasPrefix(e.msg, prefix) {
				if seen++; seen == n {
					l.mu.Unlock()
					return e, true
				}
			}
		}
		l.mu.Unlock()
		select {
		case <-ctx.Done():
			return event{}, false
		case <-tick.C:
		}
	}
}

func (l *eventLog) add(e event) {
	l.mu.Lock()
	l.events = append(l.events, e)
	l.mu.Unlock()
	if e.level >= slog.LevelInfo {
		line := fmt.Sprintf("  [client %s %d] %s", e.level, e.id, e.msg)
		if e.err != "" {
			line += ": " + e.err
		}
		fmt.Fprintf(l.out, "%8.1fs%s\n", e.at.Sub(l.start).Seconds(), line)
	}
}

type eventHandler struct {
	log   *eventLog
	attrs []slog.Attr
}

func (h *eventHandler) Enabled(context.Context, slog.Level) bool { return true }

func (h *eventHandler) Handle(_ context.Context, r slog.Record) error {
	e := event{at: r.Time, level: r.Level, msg: r.Message}
	if e.at.IsZero() {
		e.at = time.Now()
	}
	visit := func(a slog.Attr) bool {
		v := a.Value.Resolve()
		switch {
		case a.Key == "event" && v.Kind() == slog.KindInt64:
			e.id = int(v.Int64())
		case a.Key == "error":
			e.err = v.String()
		}
		return true
	}
	for _, a := range h.attrs {
		visit(a)
	}
	r.Attrs(visit)
	h.log.add(e)
	return nil
}

func (h *eventHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	return &eventHandler{log: h.log, attrs: append(h.attrs[:len(h.attrs):len(h.attrs)], attrs...)}
}

func (h *eventHandler) WithGroup(string) slog.Handler { return h }
