// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package testutil

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"testing/slogtest"
	"time"
)

func TestClearAWSEnv(t *testing.T) {
	t.Setenv("AWS_REGION", "us-west-2")
	t.Setenv("AWS_PROFILE", "someone")
	ClearAWSEnv(t)
	if _, ok := os.LookupEnv("AWS_REGION"); ok {
		t.Error("AWS_REGION still set")
	}
	if _, ok := os.LookupEnv("AWS_PROFILE"); ok {
		t.Error("AWS_PROFILE still set")
	}
	if _, err := os.Stat(os.Getenv("AWS_CONFIG_FILE")); !os.IsNotExist(err) {
		t.Errorf("AWS_CONFIG_FILE exists: %v", err)
	}
}

func TestPoll(t *testing.T) {
	n := 0
	if !Poll(time.Second, func() bool { n++; return n == 3 }) || n != 3 {
		t.Errorf("Poll returned before cond held: %d calls, want 3", n)
	}
	start := time.Now()
	if Poll(20*time.Millisecond, func() bool { return false }) {
		t.Error("Poll(20ms, false) = true, want false")
	}
	if took := time.Since(start); took < 20*time.Millisecond {
		t.Errorf("Poll(20ms, false) returned after %v, want at least 20ms", took)
	}
}

func TestStaticCredentials(t *testing.T) {
	c, err := StaticCredentials().Retrieve(context.Background())
	if err != nil || c.AccessKeyID != AccessKeyID || c.SecretAccessKey != SecretAccessKey ||
		c.SessionToken != SessionToken || c.CanExpire {
		t.Fatalf("got %+v %v", c, err)
	}
}

func TestSharedFixturesDecodeStrict(t *testing.T) {
	for _, name := range []string{"protocol-vectors.json", "sdk-request-cases.json"} {
		var v any
		DecodeStrict(t, SharedFixture(t, name), &v)
		if v == nil {
			t.Errorf("%s decoded to nil", name)
		}
	}
}

func TestDecodeStrictRefusesUnknownField(t *testing.T) {
	path := filepath.Join(t.TempDir(), "f.json")
	if err := os.WriteFile(path, []byte(`{"known":1,"extra":2}`), 0o600); err != nil {
		t.Fatal(err)
	}
	var v struct{ Known int }
	ft := &fakeTB{TB: t}
	func() {
		defer func() { _ = recover() }()
		DecodeStrict(ft, path, &v)
	}()
	if !ft.failed || !strings.Contains(ft.msg, "extra") {
		t.Fatalf("unknown field not refused: %q", ft.msg)
	}
}

func TestDecodeStrictRefusesTrailingData(t *testing.T) {
	for _, data := range []string{`{"known":1}}`, `{"known":1}]`, `{"known":1} {}`} {
		path := filepath.Join(t.TempDir(), "f.json")
		if err := os.WriteFile(path, []byte(data), 0o600); err != nil {
			t.Fatal(err)
		}
		var v struct{ Known int }
		ft := &fakeTB{TB: t}
		func() {
			defer func() { _ = recover() }()
			DecodeStrict(ft, path, &v)
		}()
		if !ft.failed {
			t.Errorf("%s: trailing data accepted", data)
		}
	}
}

func TestAssertNoSecrets(t *testing.T) {
	ft := &fakeTB{TB: t}
	AssertNoSecrets(ft, "clean text")
	if ft.failed {
		t.Fatal("clean text failed")
	}
	for _, leak := range append(Secrets(),
		"AKIAABCDEFGHIJKLMNOP", "asiaabcdefghijklmnop", "X-Amz-Signature=ab", "AWS4-HMAC-SHA256") {
		ft := &fakeTB{TB: t}
		AssertNoSecrets(ft, "x "+leak+" y")
		if !ft.failed {
			t.Errorf("%q was not found", leak)
		}
	}
}

func TestSecretsCoverEveryMarker(t *testing.T) {
	got := strings.Join(Secrets(), " ")
	for _, m := range []string{AccessKeyID, SecretAccessKey, SessionToken, BodyMarker, ResponseMarker} {
		if !strings.Contains(got, m) {
			t.Errorf("Secrets() has no %s", m)
		}
	}
}

func TestTokenSecrets(t *testing.T) {
	token := "cache.example.com/?Action=connect&User=u&X-Amz-Signature=0123456789abcdef0123&X-Amz-Date=1"
	got := TokenSecrets(token)
	if len(got) != 2 || got[0] != token || got[1] != "0123456789abcdef0123" {
		t.Fatalf("TokenSecrets = %q", got)
	}
}

func TestLogCaptureConformsToSlogtest(t *testing.T) {
	var capture *LogCapture
	slogtest.Run(t, func(*testing.T) slog.Handler {
		var l *slog.Logger
		capture, l = NewLogCapture()
		return l.Handler()
	}, func(t *testing.T) map[string]any {
		records := capture.Records()
		if len(records) != 1 {
			t.Fatalf("%d records", len(records))
		}
		r := records[0]
		m := map[string]any{slog.LevelKey: r.Level, slog.MessageKey: r.Message}
		if !r.Time.IsZero() {
			m[slog.TimeKey] = r.Time
		}
		r.Attrs(func(a slog.Attr) bool {
			addAttr(t, m, a)
			return true
		})
		return m
	})
}

// addAttr adds a to m as a JSON handler would, and fails on a key written twice.
func addAttr(t *testing.T, m map[string]any, a slog.Attr) {
	t.Helper()
	v := a.Value.Resolve()
	if a.Equal(slog.Attr{}) {
		return
	}
	if v.Kind() != slog.KindGroup {
		if _, dup := m[a.Key]; dup {
			t.Errorf("key %q written twice", a.Key)
		}
		m[a.Key] = v.Any()
		return
	}
	if len(v.Group()) == 0 {
		return
	}
	sub := m
	if a.Key != "" {
		if _, dup := m[a.Key]; dup {
			t.Errorf("group %q written twice", a.Key)
		}
		sub = map[string]any{}
		m[a.Key] = sub
	}
	for _, g := range v.Group() {
		addAttr(t, sub, g)
	}
}

func TestLogCaptureKeepsAttrsGroupsAndIsConcurrent(t *testing.T) {
	capture, l := NewLogCapture()
	l = l.With("a", 1).WithGroup("g").With("b", 2)
	var wg sync.WaitGroup
	for range 20 {
		wg.Add(1)
		go func() { defer wg.Done(); l.Debug("m", "c", 3) }()
	}
	wg.Wait()
	records := capture.Records()
	if len(records) != 20 {
		t.Fatalf("%d records", len(records))
	}
	if v, ok := Attr(records[0], "a"); !ok || v.Int64() != 1 {
		t.Errorf("a = %v %v", v, ok)
	}
	text := capture.Text()
	for _, want := range []string{"a=1", "g.b=2", "g.c=3", "level=DEBUG"} {
		if !strings.Contains(text, want) {
			t.Errorf("text has no %q: %s", want, text)
		}
	}
	if records[0].Level != slog.LevelDebug {
		t.Errorf("level = %v", records[0].Level)
	}
}

// fakeTB records failures instead of failing the real test.
type fakeTB struct {
	testing.TB
	failed bool
	msg    string
}

func (f *fakeTB) Helper() {}

func (f *fakeTB) Errorf(format string, a ...any) {
	f.failed = true
	f.msg = fmt.Sprintf(format, a...)
}

func (f *fakeTB) Fatalf(format string, a ...any) {
	f.Errorf(format, a...)
	panic("fatal")
}

func (f *fakeTB) Fatal(a ...any) {
	f.failed = true
	f.msg = fmt.Sprint(a...)
	panic("fatal")
}
