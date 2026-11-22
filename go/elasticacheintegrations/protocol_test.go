// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"bytes"
	"cmp"
	"encoding/json"
	"errors"
	"fmt"
	"slices"
	"strings"
	"testing"
	"unicode"
	"unicode/utf16"

	"github.com/awslabs/elasticache-integration-client/go/internal/testutil"
)

// protocolVectors is shared/protocol-vectors.json.
type protocolVectors struct {
	Description string `json:"description"`
	Encode      []struct {
		Name    string `json:"name"`
		Request *struct {
			Method   string            `json:"method"`
			URL      string            `json:"url"`
			Headers  map[string]string `json:"headers"`
			Body     string            `json:"body"`
			Metadata struct {
				Service   string `json:"service"`
				Operation string `json:"operation"`
			} `json:"metadata"`
		} `json:"request"`
		Overrides json.RawMessage `json:"overrides"`
		Frame     string          `json:"frame"`
	} `json:"encode"`
	Decode []json.RawMessage `json:"decode"`
}

func loadProtocolVectors(t *testing.T) protocolVectors {
	t.Helper()
	var v protocolVectors
	testutil.DecodeStrict(t, testutil.SharedFixture(t, "protocol-vectors.json"), &v)
	if len(v.Encode) == 0 || len(v.Decode) == 0 {
		t.Fatal("no vectors")
	}
	return v
}

func TestAlignmentProtocolEncode(t *testing.T) {
	for _, v := range loadProtocolVectors(t).Encode {
		t.Run(v.Name, func(t *testing.T) {
			var got []byte
			switch {
			case v.Request != nil:
				r := &request{
					method:   v.Request.Method,
					url:      v.Request.URL,
					headers:  v.Request.Headers,
					body:     []byte(v.Request.Body),
					metadata: newRequestMetadata(v.Request.Metadata.Service, v.Request.Metadata.Operation),
				}
				c := newSerializedCommand()
				defer c.release()
				c.appendRequest(r)
				got = c.frame
			case string(v.Overrides) == "null":
				got = appendConfigRefresh(nil)
			case len(v.Overrides) > 0:
				fields, ok := decodeOrdered(t, v.Overrides).(jsonObject)
				if !ok {
					t.Fatalf("overrides are not an object: %s", v.Overrides)
				}
				var err error
				if got, err = appendConfigFields(nil, fields); err != nil {
					t.Fatal(err)
				}
			default:
				t.Fatal("vector has neither request nor overrides")
			}
			if string(got) != v.Frame {
				t.Errorf("frame\n got %q\nwant %q", got, v.Frame)
			}
		})
	}
}

// decodeOrdered decodes JSON keeping object member order (as jsonObject) and numbers as
// json.Number.
func decodeOrdered(t *testing.T, data []byte) any {
	t.Helper()
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.UseNumber()
	v, err := decodeOrderedValue(dec)
	if err != nil {
		t.Fatal(err)
	}
	return v
}

func decodeOrderedValue(dec *json.Decoder) (any, error) {
	tok, err := dec.Token()
	if err != nil {
		return nil, err
	}
	switch tok {
	case json.Delim('{'):
		obj := jsonObject{}
		for dec.More() {
			k, err := dec.Token()
			if err != nil {
				return nil, err
			}
			v, err := decodeOrderedValue(dec)
			if err != nil {
				return nil, err
			}
			obj = append(obj, jsonField{k.(string), v})
		}
		_, err = dec.Token()
		return obj, err
	case json.Delim('['):
		arr := []any{}
		for dec.More() {
			v, err := decodeOrderedValue(dec)
			if err != nil {
				return nil, err
			}
			arr = append(arr, v)
		}
		_, err = dec.Token()
		return arr, err
	default:
		return tok, nil
	}
}

func TestAlignmentProtocolVectorsCoverEveryShape(t *testing.T) {
	var requests, configs, refresh int
	for _, v := range loadProtocolVectors(t).Encode {
		switch {
		case v.Request != nil:
			requests++
		case string(v.Overrides) == "null":
			refresh++
		default:
			configs++
		}
	}
	if requests == 0 || configs == 0 || refresh == 0 {
		t.Fatalf("requests=%d configs=%d refresh=%d", requests, configs, refresh)
	}
}

func encodeRequest(t *testing.T, r *request) string {
	t.Helper()
	c := newSerializedCommand()
	defer c.release()
	c.appendRequest(r)
	return string(c.frame)
}

func headerBlock(h map[string]string) string {
	c := newSerializedCommand()
	defer c.release()
	c.normalizeHeaders(h)
	return string(c.scratch)
}

func TestNormalizeHeaders(t *testing.T) {
	cases := []struct {
		name string
		in   map[string]string
		want string
	}{
		{"nil", nil, ""},
		{"empty", map[string]string{}, ""},
		{"lowercased and trimmed", map[string]string{" \tX-Custom\r ": "\x01 v a \n", "Accept": "*/*"}, "accept:*/*\nx-custom:v a"},
		{"excluded after lowercasing and trimming", map[string]string{
			" Authorization ": "s", "X-AMZ-DATE": "d", "x-amz-security-token": "t",
			"X-Amz-Request-Id": "r", "Amz-Sdk-Request": "a", "Amz-Sdk-Invocation-Id": "i",
		}, "amz-sdk-invocation-id:i"},
		{"only excluded", map[string]string{"AUTHORIZATION": "secret"}, ""},
		{"duplicates after lowercasing, sorted by value", map[string]string{"X-A": "2", "x-a": "1", " x-A": "1"}, "x-a:1\nx-a:1\nx-a:2"},
		{"sort is by name before value", map[string]string{"b": "a", "a-b": "z", "a": "z"}, "a:z\na-b:z\nb:a"},
		{"code point order", map[string]string{"X-A": "\U00010000", "x-a": "\ue000"}, "x-a:\ue000\nx-a:\U00010000"},
		{"non-ASCII name", map[string]string{"X-Ünï": "v"}, "x-ünï:v"},
		{"empty value", map[string]string{"X-E": "  "}, "x-e:"},
		{"colon in value", map[string]string{"x-c": "a:b"}, "x-c:a:b"},
		{"unicode space is not trimmed", map[string]string{"x-u": "\u00a0v\u00a0"}, "x-u:\u00a0v\u00a0"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := headerBlock(c.in); got != c.want {
				t.Errorf("got %q, want %q", got, c.want)
			}
		})
	}
}

func TestAppendRequestFrame(t *testing.T) {
	r := &request{
		method:   "POST",
		url:      "https://dynamodb.us-east-1.amazonaws.com/",
		headers:  map[string]string{"content-type": "application/x-amz-json-1.0", "authorization": "AWS4-HMAC-SHA256 Credential=" + testutil.AccessKeyID},
		body:     []byte("a\r\nb"),
		metadata: newRequestMetadata("DynamoDB", "GetItem"),
	}
	want := "*6\r\n$11\r\naws.request\r\n$4\r\nPOST\r\n$41\r\nhttps://dynamodb.us-east-1.amazonaws.com/\r\n" +
		"$39\r\ncontent-type:application/x-amz-json-1.0\r\n$4\r\na\r\nb\r\n$27\r\nservice=dynamodb&op=GetItem\r\n"
	got := encodeRequest(t, r)
	if got != want {
		t.Errorf("got %q\nwant %q", got, want)
	}
	testutil.AssertNoSecrets(t, got)
}

func TestAppendRequestEmpty(t *testing.T) {
	got := encodeRequest(t, &request{})
	want := "*6\r\n$11\r\naws.request\r\n$0\r\n\r\n$0\r\n\r\n$0\r\n\r\n$0\r\n\r\n$12\r\nservice=&op=\r\n"
	if got != want {
		t.Errorf("got %q, want %q", got, want)
	}
}

func TestSerializedCommandReuse(t *testing.T) {
	big := &request{method: "POST", body: bytes.Repeat([]byte{'x'}, 2*maxPooledCommand), headers: map[string]string{"a": "1"}}
	small := &request{method: "GET", headers: map[string]string{"b": "2", "c": "3"}}
	c := newSerializedCommand()
	c.appendRequest(big)
	c.appendRequest(small)
	if got, want := string(c.frame), encodeRequest(t, small); got != want {
		t.Errorf("reused command\n got %q\nwant %q", got, want)
	}
	c.appendRequest(big)
	c.normalizeHeaders(map[string]string{"v": strings.Repeat("y", 2*maxPooledCommand)})
	c.release()
	if c.frame != nil {
		t.Error("a frame over maxPooledCommand is kept for reuse")
	}
	if c.scratch != nil {
		t.Error("a header block over maxPooledCommand is kept for reuse")
	}
	for _, p := range c.pairs[:cap(c.pairs)] {
		if p.value != "" || p.name != nil {
			t.Error("released pairs still point at request strings")
		}
	}
}

func TestAppendRequestAllocations(t *testing.T) {
	if raceEnabled {
		t.Skip("the race detector allocates")
	}
	r := &request{
		method:   "POST",
		url:      "https://dynamodb.us-east-1.amazonaws.com/",
		headers:  sdkHeaders(),
		body:     bytes.Repeat([]byte{'x'}, 1024),
		metadata: newRequestMetadata("dynamodb", "GetItem"),
	}
	allocs := testing.AllocsPerRun(100, func() {
		c := newSerializedCommand()
		c.appendRequest(r)
		c.release()
	})
	if allocs != 0 {
		t.Errorf("appendRequest allocates %v times, want 0", allocs)
	}
}

// sdkHeaders are the headers aws-sdk-go-v2 sends with a DynamoDB call, already joined and
// lowercased.
func sdkHeaders() map[string]string {
	return map[string]string{
		"accept-encoding":       "identity",
		"amz-sdk-invocation-id": "8f1d5e1a-0b7c-4d0e-9a8b-1c2d3e4f5a6b",
		"amz-sdk-request":       "attempt=1; max=3",
		"authorization":         "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20260101/us-east-1/dynamodb/aws4_request, SignedHeaders=host, Signature=0",
		"content-length":        "1024",
		"content-type":          "application/x-amz-json-1.0",
		"user-agent":            "aws-sdk-go-v2/1.41.0 ua/2.1 os/linux lang/go#1.26.3 md/GOOS#linux md/GOARCH#arm64 api/dynamodb#1.53.0",
		"x-amz-date":            "20260101T000000Z",
		"x-amz-target":          "DynamoDB_20120810.GetItem",
	}
}

func BenchmarkAppendRequest(b *testing.B) {
	r := &request{
		method:   "POST",
		url:      "https://dynamodb.us-east-1.amazonaws.com/",
		headers:  sdkHeaders(),
		body:     bytes.Repeat([]byte{'x'}, 1024),
		metadata: newRequestMetadata("dynamodb", "GetItem"),
	}
	b.ReportAllocs()
	for b.Loop() {
		c := newSerializedCommand()
		c.appendRequest(r)
		c.release()
	}
}

func configPayload(t *testing.T, overrides map[string]any) string {
	t.Helper()
	frame, err := appendConfig(nil, overrides)
	if err != nil {
		t.Fatal(err)
	}
	const prefix = "*2\r\n$10\r\naws.config\r\n$"
	s := string(frame)
	if len(s) < len(prefix) || s[:len(prefix)] != prefix {
		t.Fatalf("frame %q", s)
	}
	s = s[len(prefix):]
	i := bytes.IndexByte([]byte(s), '\n')
	payload := s[i+1 : len(s)-2]
	if fmt.Sprint(len(payload)) != s[:i-1] {
		t.Fatalf("bulk length %s for %d bytes", s[:i-1], len(payload))
	}
	return payload
}

func TestAppendConfigPayload(t *testing.T) {
	cases := []struct {
		name string
		in   map[string]any
		want string
	}{
		{"nil", nil, `{"version":"1.0"}`},
		{"empty", map[string]any{}, `{"version":"1.0"}`},
		{"keys in byte order at every level", map[string]any{
			"b": int64(1), "a": map[string]any{"z": true, "B": false, "é": nil}, "A": []any{map[string]any{"y": "1", "x": "2"}},
		}, `{"version":"1.0","A":[{"x":"2","y":"1"}],"a":{"B":false,"z":true,"\u00e9":null},"b":1}`},
		{"caller version replaces the default in first place", map[string]any{"a": int64(1), "version": "0.9"}, `{"version":"0.9","a":1}`},
		{"caller version of another type", map[string]any{"version": json.Number("2")}, `{"version":2}`},
		{"numbers", map[string]any{
			"i": int64(-9223372036854775808), "u": uint64(18446744073709551615), "f": 0.1, "g": 1e21, "h": 1e-7, "k": float64(60),
			"f32": float32(0.1), "n": json.Number("9007199254740993"), "z": float64(0),
		}, `{"version":"1.0","f":0.1,"f32":0.1,"g":1e+21,"h":1e-07,"i":-9223372036854775808,"k":60,"n":9007199254740993,"u":18446744073709551615,"z":0}`},
		{"empty containers", map[string]any{"a": []any{}, "m": map[string]any{}}, `{"version":"1.0","a":[],"m":{}}`},
		{"nil containers", map[string]any{"a": []any(nil), "m": map[string]any(nil)}, `{"version":"1.0","a":[],"m":{}}`},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := configPayload(t, c.in); got != c.want {
				t.Errorf("got  %s\nwant %s", got, c.want)
			}
		})
	}
}

func TestAppendJSONStringEscapes(t *testing.T) {
	cases := []struct{ in, want string }{
		{`plain /<>&'`, `"plain /<>&'"`},
		{"\"\\", `"\"\\"`},
		{"\b\f\n\r\t", `"\b\f\n\r\t"`},
		{"\x00\x01\x1f\x7f", `"\u0000\u0001\u001f\u007f"`},
		{"\u0080é\u2028\u2029\uffff", `"\u0080\u00e9\u2028\u2029\uffff"`},
		{"\U0001F680\U00010000\U0010FFFF", `"\ud83d\ude80\ud800\udc00\udbff\udfff"`},
		{"a\xffb\xc3", `"a\ufffdb\ufffd"`},
		{"", `""`},
	}
	for _, c := range cases {
		if got := string(appendJSONString(nil, c.in)); got != c.want {
			t.Errorf("%q: got %s, want %s", c.in, got, c.want)
		}
		// Every escape must decode back to the same text.
		var back string
		if err := json.Unmarshal([]byte(c.want), &back); err != nil {
			t.Errorf("%s does not decode: %v", c.want, err)
		}
	}
}

func TestAppendConfigEscapesKeysAndStrings(t *testing.T) {
	got := configPayload(t, map[string]any{"<&>\u2028": "café\U0001F680"})
	want := `{"version":"1.0","<&>\u2028":"caf\u00e9\ud83d\ude80"}`
	if got != want {
		t.Errorf("got  %s\nwant %s", got, want)
	}
	var back map[string]any
	if err := json.Unmarshal([]byte(got), &back); err != nil || back["<&>\u2028"] != "café\U0001F680" {
		t.Errorf("payload does not decode back: %v %v", back, err)
	}
}

func TestAppendConfigRefusesUnknownType(t *testing.T) {
	dst := []byte("keep")
	got, err := appendConfig(dst, map[string]any{"a": []any{struct{}{}}})
	var pe *ProtocolError
	if !errors.As(err, &pe) {
		t.Fatalf("err = %v, want *ProtocolError", err)
	}
	if string(got) != "keep" {
		t.Errorf("dst changed on error: %q", got)
	}
}

func TestAppendConfigAcceptsValidatedOverrides(t *testing.T) {
	// The copy validate makes is what bring-up sends; every leaf kind it keeps must write.
	in := map[string]any{
		"s": "x", "b": true, "i": 7, "i8": int8(-8), "u16": uint16(16), "f32": float32(2.5), "f": 1.5,
		"n": json.Number("1e3"), "nil": nil, "l": []string{"a"}, "m": map[string]int{"k": 1},
	}
	cfg, err := Options{Endpoint: "h:1", CacheName: "c", CachePolicyOverrides: in}.validate()
	if err != nil {
		t.Fatal(err)
	}
	got := configPayload(t, cfg.cachePolicyOverrides)
	want := `{"version":"1.0","b":true,"f":1.5,"f32":2.5,"i":7,"i8":-8,"l":["a"],"m":{"k":1},"n":1e3,"nil":null,"s":"x","u16":16}`
	if got != want {
		t.Errorf("got  %s\nwant %s", got, want)
	}
}

func TestAppendConfigRefresh(t *testing.T) {
	if got := string(appendConfigRefresh([]byte("x"))); got != "x*1\r\n$10\r\naws.config\r\n" {
		t.Errorf("got %q", got)
	}
}

func FuzzAppendJSONString(f *testing.F) {
	for _, s := range []string{"", "a\"b\\c", "\x00\x7f\u2028<&>", "é\U0001F680", "a\xffb"} {
		f.Add(s)
	}
	f.Fuzz(func(t *testing.T, s string) {
		out := appendJSONString(nil, s)
		for _, b := range out {
			if b < 0x20 || b >= 0x7f {
				t.Fatalf("byte %#x written raw in %q", b, out)
			}
		}
		var back string
		if err := json.Unmarshal(out, &back); err != nil {
			t.Fatalf("%q does not decode: %v", out, err)
		}
		// encoding/json also writes one U+FFFD per invalid byte.
		std, err := json.Marshal(s)
		if err != nil {
			t.Fatal(err)
		}
		var want string
		if err := json.Unmarshal(std, &want); err != nil {
			t.Fatal(err)
		}
		if back != want {
			t.Fatalf("decoded %q, want %q", back, want)
		}
	})
}

// normalizeHeadersOracle is Java's normalizeHeaders written plainly: one string per pair.
func normalizeHeadersOracle(h map[string]string) string {
	type pair struct{ name, value string }
	var pairs []pair
	for name, value := range h {
		n := strings.ToLower(trimJava(name))
		if !excludedHeaders[n] {
			pairs = append(pairs, pair{n, trimJava(value)})
		}
	}
	slices.SortFunc(pairs, func(a, b pair) int {
		return cmp.Or(strings.Compare(a.name, b.name), strings.Compare(a.value, b.value))
	})
	lines := make([]string, len(pairs))
	for i, p := range pairs {
		lines[i] = p.name + ":" + p.value
	}
	return strings.Join(lines, "\n")
}

func FuzzNormalizeHeaders(f *testing.F) {
	f.Add("X-A", "1", " x-a", "2", "Authorization", "s", "x-Ünï ", "\x01v")
	f.Add("", "", " ", " ", "AMZ-SDK-REQUEST", "a", "b", "\U00010000")
	f.Fuzz(func(t *testing.T, n1, v1, n2, v2, n3, v3, n4, v4 string) {
		h := map[string]string{n1: v1, n2: v2, n3: v3, n4: v4}
		c := newSerializedCommand()
		defer c.release()
		// Reuse a command that held other headers, as the pool does.
		c.normalizeHeaders(map[string]string{"x-prev-" + n4: v1 + v2, "ÀÉ" + n3: v3})
		c.normalizeHeaders(h)
		if got, want := string(c.scratch), normalizeHeadersOracle(h); got != want {
			t.Fatalf("got %q, want %q", got, want)
		}
	})
}

// TestAppendJSONStringEveryCodePoint checks every BMP character and a sample of the others
// against the escape rule. The rule was compared with Jackson 2.23.0 as the Java client
// configures it, for the same characters.
func TestAppendJSONStringEveryCodePoint(t *testing.T) {
	short := map[rune]string{'"': `\"`, '\\': `\\`, '\b': `\b`, '\f': `\f`, '\n': `\n`, '\r': `\r`, '\t': `\t`}
	for r := rune(0); r <= unicode.MaxRune; r++ {
		if utf16.IsSurrogate(r) || (r > 0xffff && r&0xfff != 0 && r != unicode.MaxRune) {
			continue
		}
		var want string
		switch {
		case short[r] != "":
			want = short[r]
		case r < 0x20 || r >= 0x7f && r <= 0xffff:
			want = fmt.Sprintf(`\u%04x`, r)
		case r > 0xffff:
			r1, r2 := utf16.EncodeRune(r)
			want = fmt.Sprintf(`\u%04x\u%04x`, r1, r2)
		default:
			want = string(r)
		}
		if got := string(appendJSONString(nil, "a"+string(r)+"b")); got != `"a`+want+`b"` {
			t.Fatalf("U+%04X: got %s, want %s", r, got, `"a`+want+`b"`)
		}
	}
}
