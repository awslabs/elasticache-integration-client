// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package resp

import (
	"bytes"
	"errors"
	"fmt"
	"strings"
	"testing"
)

func bulk(s string) Value   { return Value{Kind: KindBulkString, Bytes: []byte(s)} }
func simple(s string) Value { return Value{Kind: KindSimpleString, Bytes: []byte(s)} }
func integer(n int64) Value { return Value{Kind: KindInteger, Int: n} }

var null = Value{Kind: KindNull}

// equal compares kinds and contents; a nil and an empty slice are equal.
func equal(a, b Value) bool {
	if a.Kind != b.Kind || a.Int != b.Int || !bytes.Equal(a.Bytes, b.Bytes) || len(a.Elems) != len(b.Elems) {
		return false
	}
	for i := range a.Elems {
		if !equal(a.Elems[i], b.Elems[i]) {
			return false
		}
	}
	return true
}

func mustDecode(t testing.TB, wire string) Value {
	t.Helper()
	v, n, err := Decode([]byte(wire))
	if err != nil {
		t.Fatalf("Decode(%q): %v", wire, err)
	}
	if n != len(wire) {
		t.Fatalf("Decode(%q) consumed %d of %d bytes", wire, n, len(wire))
	}
	return v
}

func TestDecodeValues(t *testing.T) {
	cases := []struct {
		name, wire string
		want       Value
	}{
		{"simple", "+OK\r\n", simple("OK")},
		{"simple empty", "+\r\n", simple("")},
		{"simple with lone CR and LF", "+a\rb\nc\r\n", simple("a\rb\nc")},
		{"integer", ":42\r\n", integer(42)},
		{"integer negative", ":-1\r\n", integer(-1)},
		{"integer plus sign", ":+7\r\n", integer(7)},
		{"integer zero", ":0\r\n", integer(0)},
		{"integer max", ":9223372036854775807\r\n", integer(1<<63 - 1)},
		{"integer min", ":-9223372036854775808\r\n", integer(-1 << 63)},
		{"error", "-ERR something went wrong\r\n", Value{Kind: KindError, Bytes: []byte("ERR something went wrong")}},
		{"bulk", "$5\r\nhello\r\n", bulk("hello")},
		{"bulk empty", "$0\r\n\r\n", bulk("")},
		{"bulk binary with CRLF inside", "$4\r\n\r\n\x00\xff\r\n", bulk("\r\n\x00\xff")},
		{"bulk minus zero", "$-0\r\n\r\n", bulk("")},
		{"null bulk", "$-1\r\n", null},
		{"null", "_\r\n", null},
		{"null array", "*-1\r\n", null},
		{"array", "*2\r\n$3\r\nfoo\r\n$3\r\nbar\r\n", Value{Kind: KindArray, Elems: []Value{bulk("foo"), bulk("bar")}}},
		{"array empty", "*0\r\n", Value{Kind: KindArray}},
		{"array with null", "*2\r\n_\r\n$3\r\nfoo\r\n", Value{Kind: KindArray, Elems: []Value{null, bulk("foo")}}},
		{"array nested", "*2\r\n*1\r\n:1\r\n*1\r\n:2\r\n", Value{Kind: KindArray, Elems: []Value{
			{Kind: KindArray, Elems: []Value{integer(1)}}, {Kind: KindArray, Elems: []Value{integer(2)}}}}},
		{"array mixed", "*3\r\n:1\r\n$5\r\nhello\r\n+OK\r\n", Value{Kind: KindArray, Elems: []Value{integer(1), bulk("hello"), simple("OK")}}},
		{"map", "%2\r\n$5\r\nproto\r\n:3\r\n$6\r\nserver\r\n$6\r\nvalkey\r\n", Value{Kind: KindMap, Elems: []Value{
			bulk("proto"), integer(3), bulk("server"), bulk("valkey")}}},
		{"map empty", "%0\r\n", Value{Kind: KindMap}},
		{"push", ">3\r\n$12\r\naws.response\r\n$2\r\n17\r\n*3\r\n:200\r\n$0\r\n\r\n$0\r\n\r\n", Value{Kind: KindPush, Elems: []Value{
			bulk("aws.response"), bulk("17"), {Kind: KindArray, Elems: []Value{integer(200), bulk(""), bulk("")}}}}},
		{"push empty", ">0\r\n", Value{Kind: KindPush}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := mustDecode(t, tc.wire); !equal(got, tc.want) {
				t.Fatalf("got %#v, want %#v", got, tc.want)
			}
		})
	}
}

// TestDecodeHello3Reply decodes the HELLO 3 reply of a Valkey engine.
func TestDecodeHello3Reply(t *testing.T) {
	wire := "%7\r\n$6\r\nserver\r\n$6\r\nvalkey\r\n$7\r\nversion\r\n$5\r\n8.1.1\r\n" +
		"$5\r\nproto\r\n:3\r\n$2\r\nid\r\n:5\r\n$4\r\nmode\r\n$7\r\ncluster\r\n" +
		"$4\r\nrole\r\n$6\r\nmaster\r\n$7\r\nmodules\r\n*0\r\n"
	v := mustDecode(t, wire)
	proto, ok := v.MapGet("proto")
	if !ok || proto.Kind != KindInteger || proto.Int != 3 {
		t.Fatalf("proto = %v, %v", proto, ok)
	}
	modules, ok := v.MapGet("modules")
	if !ok || modules.Kind != KindArray || len(modules.Elems) != 0 {
		t.Fatalf("modules = %v, %v", modules, ok)
	}
}

func TestMapGetLastWinsAndMatchesBulkKeysOnly(t *testing.T) {
	v := mustDecode(t, "%3\r\n$1\r\nk\r\n:1\r\n+k\r\n:2\r\n$1\r\nk\r\n:3\r\n")
	if got, ok := v.MapGet("k"); !ok || got.Int != 3 {
		t.Fatalf("MapGet(k) = %v, %v", got, ok)
	}
	v = mustDecode(t, "%1\r\n+k\r\n:2\r\n")
	if _, ok := v.MapGet("k"); ok {
		t.Fatal("MapGet matched a simple-string key")
	}
	if _, ok := bulk("k").MapGet("k"); ok {
		t.Fatal("MapGet on a bulk string")
	}
}

func TestErrorCodeAndMessage(t *testing.T) {
	cases := []struct{ wire, code, msg string }{
		{"-ERR something went wrong\r\n", "ERR", "something went wrong"},
		{"-ERR\r\n", "ERR", ""},
		{"-ORIGIN_ERROR upstream failed\r\n", "ORIGIN_ERROR", "upstream failed"},
		{"- leading space\r\n", "", "leading space"},
		{"-BAD \xff\r\n", "BAD", "\uFFFD"},
	}
	for _, tc := range cases {
		v := mustDecode(t, tc.wire)
		if v.Kind != KindError || v.ErrorCode() != tc.code || v.ErrorMessage() != tc.msg {
			t.Errorf("%q: kind %v code %q msg %q", tc.wire, v.Kind, v.ErrorCode(), v.ErrorMessage())
		}
	}
}

func TestDecodeCorrupt(t *testing.T) {
	cases := map[string]string{
		"unknown type":                  "X garbage\r\n",
		"attribute":                     "|1\r\n+a\r\n+b\r\n+OK\r\n",
		"big number":                    "(3492890328409238509324850943850943825024385\r\n",
		"double":                        ",1.5\r\n",
		"boolean":                       "#t\r\n",
		"blob error":                    "!3\r\nERR\r\n",
		"verbatim":                      "=7\r\ntxt:abc\r\n",
		"set":                           "~1\r\n:1\r\n",
		"streamed string":               "$?\r\n;3\r\nabc\r\n;0\r\n",
		"streamed array":                "*?\r\n:1\r\n.\r\n",
		"element of unknown type":       "*2\r\n:1\r\n#t\r\n",
		"invalid integer":               ":not_a_number\r\n",
		"integer with space":            ": 1\r\n",
		"integer empty":                 ":\r\n",
		"integer sign only":             ":-\r\n",
		"integer overflow":              ":9223372036854775808\r\n",
		"integer underflow":             ":-9223372036854775809\r\n",
		"bulk length text":              "$abc\r\n",
		"bulk length below null":        "$-2\r\n",
		"bulk length over int32":        "$2147483648\r\n",
		"bulk without CRLF":             "$5\r\nhelloxx",
		"array count below null":        "*-2\r\n",
		"map count negative":            "%-1\r\n",
		"push null":                     ">-1\r\n",
		"null with header":              "_garbage\r\n",
		"number line too long, no CRLF": ":1234567890123456789012",
	}
	for name, wire := range cases {
		t.Run(name, func(t *testing.T) {
			_, _, err := Decode([]byte(wire))
			var ce *CorruptError
			if !errors.As(err, &ce) || !errors.Is(err, ErrCorrupt) {
				t.Fatalf("Decode(%q) = %v, want a *CorruptError", wire, err)
			}
		})
	}
}

// TestCorruptErrorNeverQuotesTheStream keeps payload bytes out of error text and logs.
func TestCorruptErrorNeverQuotesTheStream(t *testing.T) {
	for _, wire := range []string{":SECRETVALUE\r\n", "$SECRETVALUE\r\n", "_SECRETVALUE\r\n", "SECRETVALUE"} {
		_, _, err := Decode([]byte(wire))
		if err == nil || strings.Contains(err.Error(), "SECRET") {
			t.Fatalf("Decode(%q) error %v", wire, err)
		}
	}
}

func TestValueStringHidesPayload(t *testing.T) {
	v := mustDecode(t, "*3\r\n$6\r\nSECRET\r\n+SECRET\r\n%1\r\n-SECRET\r\n:7\r\n")
	for _, s := range []string{fmt.Sprint(v), fmt.Sprintf("%v %s", v.Elems[0], v.Elems[1]), v.Elems[2].String(),
		v.Elems[2].Elems[0].String(), v.Elems[2].Elems[1].String(), null.String(), Kind(42).String()} {
		if strings.Contains(s, "SECRET") {
			t.Fatalf("String() printed the payload: %s", s)
		}
	}
}

func TestDecodeIncomplete(t *testing.T) {
	for _, wire := range []string{"", "+OK", "+OK\r", ":4", "$5\r\n", "$5\r\nhel", "$5\r\nhello\r",
		"*2\r\n$3\r\nfoo\r\n", "%1\r\n$5\r\nproto\r\n", "_", "_\r", ">1\r\n",
		"*2000000000\r\n", "%2000000000\r\n"} {
		if _, n, err := Decode([]byte(wire)); !errors.Is(err, ErrIncomplete) || n != 0 {
			t.Errorf("Decode(%q) = %d, %v, want ErrIncomplete", wire, n, err)
		}
	}
}

func TestDecodeStopsAtTheFirstValue(t *testing.T) {
	_, n, err := Decode([]byte("$5\r\nhello\r\n+OK\r\n"))
	if err != nil || n != 11 {
		t.Fatalf("n=%d err=%v", n, err)
	}
	_, n, err = Decode([]byte("_\r\n$3\r\nfoo\r\n"))
	if err != nil || n != 3 {
		t.Fatalf("n=%d err=%v", n, err)
	}
}

func TestScanNeed(t *testing.T) {
	cases := []struct {
		wire  string
		need  int64
		exact bool
	}{
		{"", 3, false},
		{"+OK", 2, false},
		{"+OK\r", 1, false},
		{"$10\r\n", 12, true},
		{"$10\r\nabc", 9, true},
		{"$10\r\nabcdefghij\r", 1, true},
		{"*3\r\n:200\r\n$0\r\n\r\n$5\r\nab", 5, true},  // the body of a response is its last element
		{"*3\r\n:200\r\n$2\r\nab", 2 + 3, false},       // the headers, then at least a body
		{"*2\r\n*2\r\n$4\r\nab", 4 + 3 + 3, false},     // one sibling at each level
		{"%2\r\n$1\r\nk\r\n", 3 * 3, false},            // a value, then a key and a value
		{"*2000000000\r\n", 3 * 2000000000, false},     // reported, so a reader can refuse it
		{"%2147483647\r\n", 3 * 2 * 2147483647, false}, // no overflow
	}
	for _, tc := range cases {
		ext, err := Scan([]byte(tc.wire))
		if err != nil || ext.Len != 0 || int64(ext.Need) != min(tc.need, maxNeed) || ext.Exact != tc.exact {
			t.Errorf("Scan(%q) = %+v, %v; want need %d exact %v", tc.wire, ext, err, tc.need, tc.exact)
		}
	}
}

// checkPrefixes checks that every proper prefix of a complete value is incomplete and that
// Need never overshoots the value's real end.
func checkPrefixes(t testing.TB, wire []byte, length int, step int) {
	t.Helper()
	for k := 0; k < length; k += step {
		ext, err := Scan(wire[:k])
		if err != nil {
			t.Fatalf("prefix %d of %d: %v", k, length, err)
		}
		if ext.Len != 0 || ext.Need < 1 || k+ext.Need > length || (ext.Exact && k+ext.Need != length) {
			t.Fatalf("prefix %d of %d: %+v", k, length, ext)
		}
	}
}

func TestScanPrefixesOfEveryCase(t *testing.T) {
	for _, wire := range []string{"+OK\r\n", ":-12\r\n", "$5\r\nhello\r\n", "*3\r\n:1\r\n$5\r\nhello\r\n+OK\r\n",
		"%2\r\n$5\r\nproto\r\n:3\r\n$6\r\nserver\r\n*1\r\n_\r\n", ">3\r\n$12\r\naws.response\r\n$1\r\n1\r\n*0\r\n"} {
		checkPrefixes(t, []byte(wire), len(wire), 1)
	}
}

func nested(prefix string, levels int) string {
	unit := map[string]string{"*": "*1\r\n", "%": "%1\r\n:0\r\n", ">": ">2\r\n+kind\r\n"}[prefix]
	return strings.Repeat(unit, levels) + ":1\r\n"
}

func TestNestingCap(t *testing.T) {
	for _, p := range []string{"*", "%", ">"} {
		mustDecode(t, nested(p, MaxDepth))
		if _, _, err := Decode([]byte(nested(p, MaxDepth+1))); !errors.Is(err, ErrCorrupt) {
			t.Errorf("%s nested %d: %v", p, MaxDepth+1, err)
		}
	}
	if _, _, err := Decode([]byte(nested("*", 100000))); !errors.Is(err, ErrCorrupt) {
		t.Fatalf("deep nesting: %v", err)
	}
	if _, _, err := Decode([]byte(strings.Repeat("*1\r\n", MaxDepth))); !errors.Is(err, ErrIncomplete) {
		t.Fatalf("truncated nesting under the cap: %v", err)
	}
}

// TestDecodedSlicesAliasTheInput pins the zero-copy contract and that no element can grow
// into the bytes after it.
func TestDecodedSlicesAliasTheInput(t *testing.T) {
	wire := []byte("*2\r\n$3\r\nfoo\r\n$3\r\nbar\r\n")
	v, _, err := Decode(wire)
	if err != nil {
		t.Fatal(err)
	}
	wire[8] = 'F'
	if string(v.Elems[0].Bytes) != "Foo" {
		t.Fatalf("got %q", v.Elems[0].Bytes)
	}
	if cap(v.Elems[0].Bytes) != 3 || cap(v.Elems) != 2 {
		t.Fatalf("cap bytes %d elems %d", cap(v.Elems[0].Bytes), cap(v.Elems))
	}
	v = mustDecode(t, "*3\r\n+ab\r\n-ERR x\r\n$2\r\ncd\r\n")
	for i, want := range []int{2, 5, 2} {
		if c := cap(v.Elems[i].Bytes); c != want {
			t.Fatalf("element %d: cap %d, want %d", i, c, want)
		}
	}
	_ = append(v.Elems[0].Bytes, "XXXXXXXXXXXXXXXXXXXX"...)
	if string(v.Elems[1].Bytes) != "ERR x" || string(v.Elems[2].Bytes) != "cd" {
		t.Fatal("appending to an element overwrote the elements after it")
	}
}

func TestDecodeAllocatesOnceForAllAggregates(t *testing.T) {
	if raceEnabled {
		t.Skip("the race detector changes allocation counts")
	}
	wire := []byte(response(1024))
	if n := testing.AllocsPerRun(100, func() { _, _, _ = Decode(wire) }); n != 1 {
		t.Fatalf("%v allocations per Decode, want 1", n)
	}
	wire = []byte("*2\r\n*2\r\n:1\r\n%1\r\n+k\r\n:2\r\n>1\r\n$1\r\nx\r\n")
	if n := testing.AllocsPerRun(100, func() { _, _, _ = Decode(wire) }); n != 1 {
		t.Fatalf("%v allocations per nested Decode, want 1", n)
	}
	wire = []byte("$5\r\nhello\r\n")
	if n := testing.AllocsPerRun(100, func() { _, _, _ = Decode(wire) }); n != 0 {
		t.Fatalf("%v allocations per scalar Decode, want 0", n)
	}
}

// response is an aws.request reply whose body has n bytes.
func response(n int) string {
	headers := "content-type:application/x-amz-json-1.0\nx-amz-crc32:12345\nx-amzn-requestid:ABC"
	return fmt.Sprintf("*3\r\n:200\r\n$%d\r\n%s\r\n$%d\r\n%s\r\n", len(headers), headers, n, strings.Repeat("a", n))
}

func BenchmarkDecode(b *testing.B) {
	for _, size := range []int{1 << 10, 390 << 10} {
		wire := []byte(response(size))
		b.Run(fmt.Sprintf("%dKiB", size>>10), func(b *testing.B) {
			b.ReportAllocs()
			b.SetBytes(int64(len(wire)))
			for b.Loop() {
				if _, _, err := Decode(wire); err != nil {
					b.Fatal(err)
				}
			}
		})
	}
}
