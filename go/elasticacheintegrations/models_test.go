// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"bytes"
	"fmt"
	"log/slog"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestNewRequestMetadataLowercasesService(t *testing.T) {
	m := newRequestMetadata("DynamoDB", "GetItem")
	if m.service != "dynamodb" || m.operation != "GetItem" {
		t.Fatalf("got %+v", m)
	}
}

func TestModelLogValuesLeaveOutHeadersAndBodies(t *testing.T) {
	const secret = "SECRET-MARKER"
	req := &request{
		method:   "POST",
		url:      "https://dynamodb.us-east-1.amazonaws.com/",
		headers:  map[string]string{"authorization": "AWS4-HMAC-SHA256 Signature=" + secret},
		body:     []byte(`{"Key":"` + secret + `"}`),
		metadata: newRequestMetadata("dynamodb", "GetItem"),
	}
	resp := &response{
		statusCode: 200,
		headers:    map[string]string{"x-amz-crc32": secret},
		body:       []byte(`{"Item":"` + secret + `"}`),
	}
	cfg := &resolvedConfig{
		version:         "1.0",
		refreshInterval: 60 * time.Second,
		raw:             map[string]any{"predicates": secret},
	}

	var buf bytes.Buffer
	log := slog.New(slog.NewTextHandler(&buf, nil))
	log.Info("models", "request", req, "response", resp, "config", cfg)
	out := buf.String()

	if strings.Contains(out, secret) {
		t.Fatalf("log line contains a secret: %s", out)
	}
	for _, want := range []string{
		"request.method=POST", "request.service=dynamodb", "request.operation=GetItem",
		"request.body_bytes=" + strconv.Itoa(len(req.body)),
		"response.status=200", "response.body_bytes=" + strconv.Itoa(len(resp.body)),
		"config.version=1.0", "config.refresh_interval=1m0s",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("log line has no %q: %s", want, out)
		}
	}
}

func TestModelValuesAndFmtLeaveOutHeadersAndBodies(t *testing.T) {
	const secret = "SECRET-MARKER"
	req := request{
		method:   "POST",
		headers:  map[string]string{"authorization": "AWS4-HMAC-SHA256 Signature=" + secret},
		body:     []byte(secret),
		metadata: newRequestMetadata("dynamodb", "GetItem"),
	}
	resp := response{statusCode: 200, headers: map[string]string{"x": secret}, body: []byte(secret)}
	cfg := resolvedConfig{version: "1.0", refreshInterval: time.Minute, raw: map[string]any{"p": secret}}

	var buf bytes.Buffer
	slog.New(slog.NewTextHandler(&buf, nil)).Info("by value", "request", req, "response", resp, "config", cfg)
	out := buf.String()
	if !strings.Contains(out, "request.operation=GetItem") || !strings.Contains(out, "response.status=200") {
		t.Errorf("value LogValue not used: %s", out)
	}
	for _, v := range []any{req, &req, resp, &resp, cfg, &cfg} {
		for _, verb := range []string{"%v", "%+v", "%#v", "%s"} {
			out += fmt.Sprintf(verb, v) + "\n"
		}
	}
	if strings.Contains(out, secret) {
		t.Fatalf("output contains a secret:\n%s", out)
	}
	if got := fmt.Sprint(&resp); got != "{status=200 body_bytes=13}" {
		t.Errorf("fmt.Sprint(response) = %q", got)
	}
}
