// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package resp

import (
	"errors"
	"strings"
	"testing"
)

func TestAppendCommand(t *testing.T) {
	cases := []struct {
		args []string
		want string
	}{
		{[]string{"TEST"}, "*1\r\n$4\r\nTEST\r\n"},
		{[]string{"SET", "key", "value"}, "*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n"},
		{[]string{"CMD", ""}, "*2\r\n$3\r\nCMD\r\n$0\r\n\r\n"},
		{[]string{"aws.config"}, "*1\r\n$10\r\naws.config\r\n"},
		{[]string{"X", "\r\n\x00\xff"}, "*2\r\n$1\r\nX\r\n$4\r\n\r\n\x00\xff\r\n"},
	}
	for _, tc := range cases {
		args := make([][]byte, len(tc.args))
		lens := make([]int, len(tc.args))
		for i, a := range tc.args {
			args[i], lens[i] = []byte(a), len(a)
		}
		if got := string(AppendCommand(nil, args...)); got != tc.want {
			t.Errorf("AppendCommand(%q) = %q, want %q", tc.args, got, tc.want)
		}
		if got := string(AppendCommandStrings(nil, tc.args...)); got != tc.want {
			t.Errorf("AppendCommandStrings(%q) = %q", tc.args, got)
		}
		if got := CommandLen(lens...); got != len(tc.want) {
			t.Errorf("CommandLen(%q) = %d, want %d", tc.args, got, len(tc.want))
		}
	}
	if got := string(AppendCommand([]byte("x"), nil)); got != "x*1\r\n$0\r\n\r\n" {
		t.Fatalf("nil argument: %q", got)
	}
	big := strings.Repeat("b", 12345)
	if got := len(AppendCommandStrings(nil, "aws.request", big)); got != CommandLen(11, 12345) {
		t.Fatalf("CommandLen with a five-digit length: %d", got)
	}
}

func TestAppendCommandGrowsOnce(t *testing.T) {
	if raceEnabled {
		t.Skip("the race detector changes allocation counts")
	}
	args := [][]byte{[]byte("aws.request"), []byte("POST"), []byte(strings.Repeat("h", 300)), []byte(strings.Repeat("b", 4000))}
	if n := testing.AllocsPerRun(100, func() { _ = AppendCommand(nil, args...) }); n != 1 {
		t.Fatalf("%v allocations into a nil buffer, want 1", n)
	}
	buf := make([]byte, 0, 8192)
	if n := testing.AllocsPerRun(100, func() { buf = AppendCommand(buf[:0], args...) }); n != 0 {
		t.Fatalf("%v allocations into a reused buffer, want 0", n)
	}
	if n := testing.AllocsPerRun(100, func() {
		buf = AppendArrayHeader(buf[:0], 2)
		buf = AppendBulkString(buf, "aws.request")
		buf = AppendBulk(buf, args[3])
	}); n != 0 {
		t.Fatalf("%v allocations for the header and bulk writers, want 0", n)
	}
}

func TestAppendValue(t *testing.T) {
	cases := []struct {
		v    Value
		want string
	}{
		{SimpleValue("OK"), "+OK\r\n"},
		{SimpleValue("a\rb\nc\r"), "+a\rb\nc\r\r\n"},
		{ErrorValue("ORIGIN_ERROR", "upstream failed"), "-ORIGIN_ERROR upstream failed\r\n"},
		{ErrorValue("ERR", ""), "-ERR\r\n"},
		{IntValue(-1 << 63), ":-9223372036854775808\r\n"},
		{BulkStringValue("hello"), "$5\r\nhello\r\n"},
		{BulkValue(nil), "$0\r\n\r\n"},
		{NullValue(), "_\r\n"},
		{ArrayValue(), "*0\r\n"},
		{ArrayValue(IntValue(200), BulkStringValue(""), BulkStringValue("{}")), "*3\r\n:200\r\n$0\r\n\r\n$2\r\n{}\r\n"},
		{MapValue(BulkStringValue("proto"), IntValue(3)), "%1\r\n$5\r\nproto\r\n:3\r\n"},
		{PushValue(BulkStringValue("aws.response"), BulkStringValue("7"), NullValue()), ">3\r\n$12\r\naws.response\r\n$1\r\n7\r\n_\r\n"},
	}
	for _, tc := range cases {
		got, err := AppendValue([]byte("p"), tc.v)
		if err != nil || string(got) != "p"+tc.want {
			t.Errorf("AppendValue(%v) = %q, %v; want %q", tc.v, got, err, tc.want)
			continue
		}
		if back := mustDecode(t, tc.want); !equal(back, tc.v) {
			t.Errorf("%q decodes to %#v", tc.want, back)
		}
	}
}

func TestAppendValueRefusesWhatCannotDecodeBack(t *testing.T) {
	deep := IntValue(1)
	for range MaxDepth {
		deep = ArrayValue(deep)
	}
	enc, err := AppendValue(nil, deep)
	if err != nil {
		t.Fatalf("nesting at the cap: %v", err)
	}
	if back := mustDecode(t, string(enc)); !equal(back, deep) {
		t.Fatalf("nesting at the cap does not decode back")
	}
	cases := map[string]Value{
		"simple with CRLF": SimpleValue("a\r\nb"),
		"error with CRLF":  ErrorValue("ERR", "a\r\nb"),
		"odd map":          MapValue(BulkStringValue("k")),
		"invalid kind":     {},
		"unknown kind":     {Kind: Kind(42)},
		"too deep":         ArrayValue(deep),
		"bad element":      ArrayValue(IntValue(1), SimpleValue("\r\n")),
	}
	for name, v := range cases {
		got, err := AppendValue([]byte("keep"), v)
		if !errors.Is(err, ErrInvalidValue) || string(got) != "keep" {
			t.Errorf("%s: %q, %v", name, got, err)
		}
	}
}

func BenchmarkAppendRequest(b *testing.B) {
	headers := []byte("content-type:application/x-amz-json-1.0\nx-amz-target:DynamoDB_20120810.GetItem")
	body := []byte(`{"TableName":"t","Key":{"pk":{"S":"` + strings.Repeat("k", 64) + `"}}}`)
	buf := make([]byte, 0, 4096)
	b.ReportAllocs()
	for b.Loop() {
		buf = AppendArrayHeader(buf[:0], 6)
		buf = AppendBulkString(buf, "aws.request")
		buf = AppendBulkString(buf, "POST")
		buf = AppendBulkString(buf, "https://dynamodb.us-east-1.amazonaws.com/")
		buf = AppendBulk(buf, headers)
		buf = AppendBulk(buf, body)
		buf = AppendBulkString(buf, "service=dynamodb&op=GetItem")
	}
}
