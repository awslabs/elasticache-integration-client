// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

func TestDotEnvValue(t *testing.T) {
	// The expected values are what Node's process.loadEnvFile sets.
	for raw, want := range map[string]string{
		"1 # c":           "1",
		"1#c":             "1",
		`"x # y"`:         "x # y",
		`'q'`:             "q",
		"`bt`":            "bt",
		`'x' # c`:         "x",
		`"a"b`:            "a",
		`"unterminated`:   `"unterminated`,
		"  spaced  ":      "spaced",
		`""`:              "",
		"a=b":             "a=b",
		"host.example:63": "host.example:63",
	} {
		if got := dotEnvValue(raw); got != want {
			t.Errorf("dotEnvValue(%q) = %q, want %q", raw, got, want)
		}
	}
}

func TestLoadDotEnv(t *testing.T) {
	root := t.TempDir()
	for name, content := range map[string]string{
		".env.example": "",
		".env":         "# comment\nE2E_TEST_SET=from-file\nexport E2E_TEST_NEW=\"new value\" # note\nnot a pair\n",
	} {
		if err := os.WriteFile(filepath.Join(root, name), []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	sub := filepath.Join(root, "go", "examples")
	if err := os.MkdirAll(sub, 0o700); err != nil {
		t.Fatal(err)
	}
	t.Chdir(sub)
	t.Setenv("E2E_TEST_SET", "from-env")
	t.Setenv("E2E_TEST_NEW", "")
	if err := os.Unsetenv("E2E_TEST_NEW"); err != nil {
		t.Fatal(err)
	}
	if err := loadDotEnv(); err != nil {
		t.Fatal(err)
	}
	if got := os.Getenv("E2E_TEST_SET"); got != "from-env" {
		t.Errorf("E2E_TEST_SET = %q, want the environment's value", got)
	}
	if got := os.Getenv("E2E_TEST_NEW"); got != "new value" {
		t.Errorf("E2E_TEST_NEW = %q, want %q", got, "new value")
	}
}

func TestRequestTimeout(t *testing.T) {
	for raw, want := range map[string]time.Duration{
		"":            5 * time.Second,
		"0.25":        250 * time.Millisecond,
		"0.0015":      2 * time.Millisecond,
		"0.0005":      time.Millisecond,
		"2147483.647": (1<<31 - 1) * time.Millisecond,
	} {
		t.Setenv("ELASTICACHE_REQUEST_TIMEOUT_SECONDS", raw)
		if got, err := requestTimeout(); err != nil || got != want {
			t.Errorf("requestTimeout(%q) = %v, %v; want %v", raw, got, err, want)
		}
	}
	for _, raw := range []string{"0", "-1", "0.0004", "2147483.648", "1e10", "1e300", "NaN", "Inf", "abc"} {
		t.Setenv("ELASTICACHE_REQUEST_TIMEOUT_SECONDS", raw)
		if got, err := requestTimeout(); err == nil {
			t.Errorf("requestTimeout(%q) = %v, want an error", raw, got)
		}
	}
}

func TestResolvesOnlyTo(t *testing.T) {
	for _, c := range []struct {
		host, tunnel string
		want         bool
	}{
		{"127.0.0.1", "127.0.0.1", true},
		{"127.0.0.1", "::ffff:127.0.0.1", true},
		{"::1", "::1", true},
		{"127.0.0.1", "127.0.0.2", false},
		{"127.0.0.1", "::1", false},
	} {
		if got := resolvesOnlyTo(c.host, c.tunnel); got != c.want {
			t.Errorf("resolvesOnlyTo(%q, %q) = %v, want %v", c.host, c.tunnel, got, c.want)
		}
	}
}

func TestSettleStopsOnCancel(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	started := time.Now()
	if err := (&e2e{}).settle(ctx); !errors.Is(err, context.Canceled) {
		t.Fatalf("settle = %v, want context.Canceled", err)
	}
	if elapsed := time.Since(started); elapsed >= settleTime/2 {
		t.Errorf("settle took %s after cancel", elapsed)
	}
}

func TestAssertLargeItem(t *testing.T) {
	want := map[string]types.AttributeValue{
		"id":      str("large"),
		"payload": &types.AttributeValueMemberB{Value: []byte{1, 2, 3}},
		"text":    str("t"),
	}
	if err := assertLargeItem(want, want); err != nil {
		t.Errorf("matching item: %v", err)
	}
	if err := assertLargeItem(nil, want); err == nil || !strings.Contains(err.Error(), "missing") {
		t.Errorf("missing item: %v, want an error that says the item is missing", err)
	}
	other := map[string]types.AttributeValue{
		"id":      str("large"),
		"payload": &types.AttributeValueMemberB{Value: []byte{1, 2, 4}},
		"text":    str("t"),
	}
	if err := assertLargeItem(other, want); err == nil || strings.Contains(err.Error(), "missing") {
		t.Errorf("mismatched item: %v, want a mismatch error", err)
	}
}
