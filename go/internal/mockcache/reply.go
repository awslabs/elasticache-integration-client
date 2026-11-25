// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package mockcache

import (
	"sort"
	"strings"
	"time"

	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
)

type replyKind uint8

const (
	replyNow replyKind = iota
	replyDeferred
	replyHang
	replyDrop
	replyRaw
)

// Reply is what the server does with one command. The zero Reply is invalid and fails the
// server.
type Reply struct {
	kind     replyKind
	value    resp.Value
	hasValue bool
	delay    time.Duration
	later    <-chan resp.Value
	raw      []byte
}

// Now replies v in band.
func Now(v resp.Value) Reply { return Reply{kind: replyNow, value: v, hasValue: true} }

// After replies v in band after d. Later commands on the connection wait for it, as they
// would behind a slow command on the engine.
func After(d time.Duration, v resp.Value) Reply {
	return Reply{kind: replyNow, value: v, hasValue: true, delay: d}
}

// Deferred replies +<id> now, then the aws.response push for that id at once.
func Deferred(v resp.Value) Reply { return Reply{kind: replyDeferred, value: v, hasValue: true} }

// DeferredAfter replies +<id> now, then the push after d. Later commands are answered
// meanwhile.
func DeferredAfter(d time.Duration, v resp.Value) Reply {
	return Reply{kind: replyDeferred, value: v, hasValue: true, delay: d}
}

// DeferredFrom replies +<id> now, then the push with the first value ch yields. A closed ch
// sends no push.
func DeferredFrom(ch <-chan resp.Value) Reply { return Reply{kind: replyDeferred, later: ch} }

// Hang answers nothing more on this connection, which stays open. Pushes already scheduled
// still go out.
func Hang() Reply { return Reply{kind: replyHang} }

// Drop closes the connection without a reply.
func Drop() Reply { return Reply{kind: replyDrop} }

// DropAfter writes v, then closes the connection.
func DropAfter(v resp.Value) Reply { return Reply{kind: replyDrop, value: v, hasValue: true} }

// Raw writes b as it is, for corrupt or unusual streams.
func Raw(b []byte) Reply { return Reply{kind: replyRaw, raw: b} }

// Served is a [status, headers, body] reply to aws.request. Headers are written as
// name:value lines, sorted by name.
func Served(status int, headers map[string]string, body []byte) resp.Value {
	names := make([]string, 0, len(headers))
	for name := range headers {
		names = append(names, name)
	}
	sort.Strings(names)
	var b strings.Builder
	for i, name := range names {
		if i > 0 {
			b.WriteByte('\n')
		}
		b.WriteString(name + ":" + headers[name])
	}
	if body == nil {
		body = []byte{}
	}
	return resp.ArrayValue(resp.IntValue(int64(status)), resp.BulkStringValue(b.String()), resp.BulkValue(body))
}

// ResponsePush is the aws.response push that carries the reply to deferred request id.
func ResponsePush(id string, v resp.Value) resp.Value {
	return resp.PushValue(resp.BulkStringValue("aws.response"), resp.BulkStringValue(id), v)
}

// Hello is the HELLO 3 reply of a Valkey engine.
func Hello() resp.Value {
	return resp.MapValue(
		resp.BulkStringValue("server"), resp.BulkStringValue("valkey"),
		resp.BulkStringValue("version"), resp.BulkStringValue("8.0.0"),
		resp.BulkStringValue("proto"), resp.IntValue(3),
		resp.BulkStringValue("id"), resp.IntValue(1),
		resp.BulkStringValue("mode"), resp.BulkStringValue("cluster"),
		resp.BulkStringValue("role"), resp.BulkStringValue("primary"),
		resp.BulkStringValue("modules"), resp.ArrayValue(),
	)
}

// OK is the +OK reply.
func OK() resp.Value { return resp.SimpleValue("OK") }
