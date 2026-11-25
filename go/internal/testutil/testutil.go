// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

// Package testutil holds helpers shared by the client's tests: log capture, secret markers,
// a hermetic AWS environment, static credentials, the shared fixtures and polling.
package testutil

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
)

// Markers that tests put into credentials, tokens, request bodies and response bodies.
// No-secrets tests assert that none of them appears in a log record or an error string.
const (
	AccessKeyID     = "AKIDTESTMARKER0000001"
	SecretAccessKey = "SECRET-ACCESS-KEY-MARKER-0001"
	SessionToken    = "SESSION-TOKEN-MARKER-0001"
	BodyMarker      = "BODY-MARKER-0001"
	ResponseMarker  = "RESPONSE-MARKER-0001"
)

// Secrets returns every marker.
func Secrets() []string {
	return []string{AccessKeyID, SecretAccessKey, SessionToken, BodyMarker, ResponseMarker}
}

// SecretShapes matches text shaped like a real AWS access key id or a SigV4 signature, for a
// secret that reaches a log some way the markers do not cover.
var SecretShapes = regexp.MustCompile(
	`(?i)(?:AKIA|ASIA)[A-Z0-9]{16}|X-Amz-(?:Signature|Credential|Security-Token)|AWS4-HMAC-SHA256`)

var queryValue = regexp.MustCompile(`=([^&]+)`)

// TokenSecrets returns token and each query-string value of it at least 16 characters long:
// the signature, credential and session token, each a leak on its own.
func TokenSecrets(token string) []string {
	out := []string{token}
	for _, m := range queryValue.FindAllStringSubmatch(token, -1) {
		if len(m[1]) >= 16 {
			out = append(out, m[1])
		}
	}
	return out
}

// AssertNoSecrets fails the test when text contains any of the secrets, or any of Secrets()
// when none are given, or matches SecretShapes.
func AssertNoSecrets(t testing.TB, text string, secrets ...string) {
	t.Helper()
	if len(secrets) == 0 {
		secrets = Secrets()
	}
	for _, s := range secrets {
		if s != "" && strings.Contains(text, s) {
			t.Errorf("found secret %q in:\n%s", s, text)
		}
	}
	if m := SecretShapes.FindString(text); m != "" {
		t.Errorf("found secret-shaped text %q in:\n%s", m, text)
	}
}

// StaticCredentials returns a provider of fixed credentials built from the markers.
func StaticCredentials() aws.CredentialsProvider {
	return aws.CredentialsProviderFunc(func(context.Context) (aws.Credentials, error) {
		return aws.Credentials{
			AccessKeyID:     AccessKeyID,
			SecretAccessKey: SecretAccessKey,
			SessionToken:    SessionToken,
			Source:          "testutil",
		}, nil
	})
}

// Poll calls cond every millisecond until it returns true or timeout passes, and reports
// whether it returned true. Unlike [Eventually] it may run on any goroutine.
func Poll(timeout time.Duration, cond func() bool) bool {
	for end := time.Now().Add(timeout); !cond(); {
		if time.Now().After(end) {
			return false
		}
		time.Sleep(time.Millisecond)
	}
	return true
}

// Eventually fails the test unless cond returns true within timeout. Call it only from the
// test's goroutine.
func Eventually(t testing.TB, what string, timeout time.Duration, cond func() bool) {
	t.Helper()
	if !Poll(timeout, cond) {
		t.Fatalf("timed out after %v waiting for %s", timeout, what)
	}
}

// ClearAWSEnv unsets every AWS_ environment variable for the test and points the shared
// config and credentials files at paths that do not exist, so nothing outside the test can
// supply a region or credentials. The test must not run in parallel.
func ClearAWSEnv(t *testing.T) {
	t.Helper()
	for _, kv := range os.Environ() {
		name, _, _ := strings.Cut(kv, "=")
		if strings.HasPrefix(name, "AWS_") {
			t.Setenv(name, "") // restores the old value after the test
			if err := os.Unsetenv(name); err != nil {
				t.Fatal(err)
			}
		}
	}
	missing := filepath.Join(t.TempDir(), "missing")
	t.Setenv("AWS_CONFIG_FILE", missing)
	t.Setenv("AWS_SHARED_CREDENTIALS_FILE", missing)
	t.Setenv("AWS_EC2_METADATA_DISABLED", "true")
}

// SharedFixture returns the path of a file in the repository's shared/ directory: the
// sibling of the directory holding the module's go.mod, found by walking up from the test's
// working directory.
func SharedFixture(t testing.TB, name string) string {
	t.Helper()
	dir, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	for {
		if _, err := os.Stat(filepath.Join(dir, "go.mod")); err == nil {
			break
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			t.Fatalf("no go.mod above the working directory")
		}
		dir = parent
	}
	path := filepath.Join(filepath.Dir(dir), "shared", name)
	if _, err := os.Stat(path); err != nil {
		t.Fatalf("shared fixture %s: %v", name, err)
	}
	return path
}

// DecodeStrict decodes the JSON file at path into v and fails on a field v does not
// declare, so a new fixture field fails the test until a runner reads it.
func DecodeStrict(t testing.TB, path string, v any) {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil {
		t.Fatalf("%s: %v", path, err)
	}
	if _, err := dec.Token(); !errors.Is(err, io.EOF) {
		t.Fatalf("%s: data after the JSON value", path)
	}
}

// LogCapture is a slog.Handler that keeps every record, at every level. It is safe for
// concurrent use; handlers derived with WithAttrs and WithGroup share its records. A stored
// record holds the handler's attributes and the record's own, nested in the handler's groups
// with one group per name, as a JSON handler would write them.
type LogCapture struct {
	shared *captureStore
	frames []captureFrame // frames[0] is outside every group
}

// captureFrame is one open group and the attributes added inside it.
type captureFrame struct {
	group string
	attrs []slog.Attr
}

type captureStore struct {
	mu      sync.Mutex
	records []slog.Record
}

// NewLogCapture returns an empty capture and a logger that writes to it.
func NewLogCapture() (*LogCapture, *slog.Logger) {
	h := &LogCapture{shared: &captureStore{}, frames: []captureFrame{{}}}
	return h, slog.New(h)
}

// Enabled reports true for every level.
func (h *LogCapture) Enabled(context.Context, slog.Level) bool { return true }

// Handle stores the record.
func (h *LogCapture) Handle(_ context.Context, r slog.Record) error {
	inner := append([]slog.Attr(nil), h.frames[len(h.frames)-1].attrs...)
	r.Attrs(func(a slog.Attr) bool {
		inner = append(inner, a)
		return true
	})
	for i := len(h.frames) - 1; i > 0; i-- {
		outer := append([]slog.Attr(nil), h.frames[i-1].attrs...)
		if len(inner) > 0 {
			args := make([]any, len(inner))
			for j, a := range inner {
				args[j] = a
			}
			outer = append(outer, slog.Group(h.frames[i].group, args...))
		}
		inner = outer
	}
	out := slog.NewRecord(r.Time, r.Level, r.Message, r.PC)
	out.AddAttrs(inner...)
	h.shared.mu.Lock()
	h.shared.records = append(h.shared.records, out)
	h.shared.mu.Unlock()
	return nil
}

// WithAttrs returns a handler that adds attrs to every record, inside the open groups.
func (h *LogCapture) WithAttrs(attrs []slog.Attr) slog.Handler {
	if len(attrs) == 0 {
		return h
	}
	c := h.clone()
	last := &c.frames[len(c.frames)-1]
	last.attrs = append(last.attrs, attrs...)
	return c
}

// WithGroup returns a handler that qualifies later attributes with name.
func (h *LogCapture) WithGroup(name string) slog.Handler {
	if name == "" {
		return h
	}
	c := h.clone()
	c.frames = append(c.frames, captureFrame{group: name})
	return c
}

func (h *LogCapture) clone() *LogCapture {
	frames := make([]captureFrame, len(h.frames))
	for i, f := range h.frames {
		frames[i] = captureFrame{group: f.group, attrs: append([]slog.Attr(nil), f.attrs...)}
	}
	return &LogCapture{shared: h.shared, frames: frames}
}

// Records returns a copy of the records captured so far.
func (h *LogCapture) Records() []slog.Record {
	h.shared.mu.Lock()
	defer h.shared.mu.Unlock()
	return append([]slog.Record(nil), h.shared.records...)
}

// Text renders every captured record with slog's text handler, one per line, LogValuers
// resolved, so a test can search it.
func (h *LogCapture) Text() string {
	var buf bytes.Buffer
	text := slog.NewTextHandler(&buf, &slog.HandlerOptions{Level: slog.LevelDebug})
	for _, r := range h.Records() {
		_ = text.Handle(context.Background(), r)
	}
	return buf.String()
}

// Attr returns the value of the top-level attribute key on r, resolved, and whether r has it.
func Attr(r slog.Record, key string) (slog.Value, bool) {
	var v slog.Value
	found := false
	r.Attrs(func(a slog.Attr) bool {
		if a.Key == key {
			v, found = a.Value.Resolve(), true
			return false
		}
		return true
	})
	return v, found
}
