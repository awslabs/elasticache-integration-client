// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"log/slog"
	"strings"
	"time"
)

// requestMetadata names the service and operation of a request, sent as service=X&op=Y.
type requestMetadata struct {
	service   string // lowercase
	operation string // the SDK operation name, such as "GetItem"
}

func newRequestMetadata(service, operation string) requestMetadata {
	return requestMetadata{service: strings.ToLower(service), operation: operation}
}

// request is a signed SDK request to send to the cache.
type request struct {
	method   string
	url      string
	headers  map[string]string // lowercase names; multiple values already joined with ", "
	body     []byte
	metadata requestMetadata
}

// LogValue, String and GoString never include headers or the body: they carry the
// signature and item data.
func (r request) LogValue() slog.Value {
	return slog.GroupValue(
		slog.String("method", r.method),
		slog.String("service", r.metadata.service),
		slog.String("operation", r.metadata.operation),
		slog.Int("body_bytes", len(r.body)),
	)
}

func (r request) String() string   { return logString(r) }
func (r request) GoString() string { return logString(r) }

// response is a [status, headers, body] reply from the cache. The code that builds one
// from wire data checks that statusCode is in 100-599.
type response struct {
	statusCode int
	headers    map[string]string // lowercase names as sent by the cache
	body       []byte            // owned by the response; never returned to a pool
}

// LogValue, String and GoString never include headers or the body.
func (r response) LogValue() slog.Value {
	return slog.GroupValue(
		slog.Int("status", r.statusCode),
		slog.Int("body_bytes", len(r.body)),
	)
}

func (r response) String() string   { return logString(r) }
func (r response) GoString() string { return logString(r) }

// resolvedConfig is the config the cache resolved in reply to aws.config.
type resolvedConfig struct {
	version         string
	refreshInterval time.Duration // whole seconds, always positive
	raw             map[string]any
}

// LogValue, String and GoString never include the raw document.
func (c resolvedConfig) LogValue() slog.Value {
	return slog.GroupValue(
		slog.String("version", c.version),
		slog.Duration("refresh_interval", c.refreshInterval),
	)
}

func (c resolvedConfig) String() string   { return logString(c) }
func (c resolvedConfig) GoString() string { return logString(c) }

// logString renders a LogValue group as "{key=value ...}" for fmt.
func logString(v slog.LogValuer) string {
	var b strings.Builder
	b.WriteByte('{')
	for i, a := range v.LogValue().Group() {
		if i > 0 {
			b.WriteByte(' ')
		}
		b.WriteString(a.String())
	}
	b.WriteByte('}')
	return b.String()
}
