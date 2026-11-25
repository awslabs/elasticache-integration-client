// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"bytes"
	"cmp"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"math/rand/v2"
	"net/http"
	"net/textproto"
	"reflect"
	"runtime"
	"slices"
	"strconv"
	"strings"
	"testing"
	"time"
	"unicode"
	"unicode/utf16"
	"unicode/utf8"

	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
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
	Decode []struct {
		Name     string `json:"name"`
		Command  string `json:"command"`
		Reply    string `json:"reply"`
		Response *struct {
			Status  int               `json:"status"`
			Headers map[string]string `json:"headers"`
			Body    string            `json:"body"`
		} `json:"response"`
		Config *struct {
			Version         *string         `json:"version"`
			RefreshInterval int64           `json:"refresh_interval"`
			Raw             json.RawMessage `json:"raw"`
		} `json:"config"`
		Error string `json:"error"`
		Code  string `json:"code"`
	} `json:"decode"`
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
				got = c.wire
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
	return string(c.wire)
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
	if got, want := string(c.wire), encodeRequest(t, small); got != want {
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
		}, `{"version":"1.0","f":0.1,"f32":0.1,"g":1e+21,"h":1e-7,"i":-9223372036854775808,"k":60,"n":9007199254740993,"u":18446744073709551615,"z":0}`},
		{"whole floats from 1e6 as integers", map[string]any{
			"a": float64(1e6), "b": float64(999999999), "c": float64(86400000), "d": 1e20, "e": float64(-1e6), "f": 1e-6,
			"g": float32(1e6), "h": float32(16777216), "i": float32(1e21), "j": float32(1e-7), "k": float32(-1.5e7),
		}, `{"version":"1.0","a":1000000,"b":999999999,"c":86400000,"d":100000000000000000000,"e":-1000000,"f":0.000001,"g":1000000,"h":16777216,"i":1e+21,"j":1e-7,"k":-15000000}`},
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

func TestAppendConfigWritesDecodedJSONIntegers(t *testing.T) {
	// encoding/json decodes every number into map[string]any as float64.
	var in map[string]any
	if err := json.Unmarshal([]byte(`{"item_ttl":999999999,"query_ttl":86400000}`), &in); err != nil {
		t.Fatal(err)
	}
	cfg, err := Options{Endpoint: "h:1", CacheName: "c", CachePolicyOverrides: in}.validate()
	if err != nil {
		t.Fatal(err)
	}
	if got, want := configPayload(t, cfg.cachePolicyOverrides), `{"version":"1.0","item_ttl":999999999,"query_ttl":86400000}`; got != want {
		t.Errorf("got  %s\nwant %s", got, want)
	}
}

func TestAppendJSONFloatMatchesEncodingJSON(t *testing.T) {
	check := func(v any) {
		t.Helper()
		if f32, ok := v.(float32); ok && math.IsInf(float64(f32), 0) {
			return // float32 of a value beyond its range; copyOverrides refuses it
		}
		want, err := json.Marshal(v)
		if err != nil {
			t.Fatal(err)
		}
		got, err := appendJSONValue(nil, v)
		if err != nil {
			t.Fatal(err)
		}
		if string(got) != string(want) {
			t.Errorf("%T %v: got %s, want %s", v, v, got, want)
		}
	}
	edges := []float64{
		0, math.Copysign(0, -1), 1, -1, 0.1, 1.5, 60, 1e6, 999999999, 86400000, 1<<53 + 1,
		1e-6, math.Nextafter(1e-6, 0), 1e-7, 1e-9, 1e-10, 1.5e-300,
		1e21, math.Nextafter(1e21, 0), 1e20, 1e22, 1.2345e100,
		math.MaxFloat64, math.SmallestNonzeroFloat64, math.MaxFloat32, math.SmallestNonzeroFloat32,
	}
	for _, f := range edges {
		check(f)
		check(-f)
		check(float32(f))
		check(-float32(f))
	}
	r := rand.New(rand.NewPCG(1, 2))
	for range 100000 {
		if f := math.Float64frombits(r.Uint64()); !math.IsNaN(f) && !math.IsInf(f, 0) {
			check(f)
		}
		if f := math.Float32frombits(r.Uint32()); !math.IsNaN(float64(f)) && !math.IsInf(float64(f), 0) {
			check(f)
		}
		check(float64(r.Int64N(1 << 53)))
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

// TestAppendRequestLargeBody checks that a body from directBodyMin up is not copied when the
// frame fits its room, and that the command is the same bytes as one RESP command either way.
func TestAppendRequestLargeBody(t *testing.T) {
	big := sdkHeaders()
	big["x-big"] = strings.Repeat("v", frameRoom)
	cases := []struct {
		n       int
		headers map[string]string
		op      string
		inPlace bool
	}{
		{directBodyMin - 1, sdkHeaders(), "PutItem", false},
		{directBodyMin, sdkHeaders(), "PutItem", true},
		{directBodyMin + 1, sdkHeaders(), "PutItem", true},
		{390 << 10, sdkHeaders(), "PutItem", true},
		{directBodyMin, big, "PutItem", false},                              // the frame is over frameRoom
		{directBodyMin, sdkHeaders(), strings.Repeat("o", tailRoom), false}, // the tail is over tailRoom
	}
	for _, tc := range cases {
		body, room, err := readBody(bytes.NewReader(bytes.Repeat([]byte{'x'}, tc.n)), int64(tc.n))
		if err != nil {
			t.Fatal(err)
		}
		r := &request{
			method:   "POST",
			url:      "https://dynamodb.us-east-1.amazonaws.com/",
			headers:  tc.headers,
			body:     body,
			room:     room,
			metadata: newRequestMetadata("dynamodb", tc.op),
		}
		want := string(resp.AppendCommandStrings(nil, requestCommand, r.method, r.url,
			headerBlock(r.headers), string(r.body), "service=dynamodb&op="+tc.op))
		c := newSerializedCommand()
		for attempt := range 2 { // a retry encodes the same request again
			c.appendRequest(r)
			if got := string(c.wire); got != want {
				t.Fatalf("%d bytes, attempt %d: the command differs from one RESP command", tc.n, attempt)
			}
		}
		r.body[0] = 'y'
		if inPlace := strings.Contains(string(c.wire), "\r\ny"); inPlace != tc.inPlace {
			t.Errorf("%d bytes, op %.10s: written around the body = %v", tc.n, tc.op, inPlace)
		}
		c.release()
	}
}

// TestNormalizeHTTPHeaders checks that the header block written from an http.Header is the
// one written from requestHeaders.
func TestNormalizeHTTPHeaders(t *testing.T) {
	cases := []http.Header{
		nil,
		{},
		{"Content-Type": {"application/x-amz-json-1.0"}, "Authorization": {"s"}, "X-Amz-Date": {"d"}},
		{"X-A": {"2", " 1"}, "x-a": {"1"}, "X-a": {"0 "}},
		{"Content-Length": {"99"}, "content-length": {"7"}},
		{"X-E": {}, "X-F": {""}, "X-G": {"\x01v\t"}},
		{"X-Ünï": {"v"}, "b": {"a"}, "a-b": {"z"}, "a": {"z"}},
	}
	for i, h := range cases {
		for _, n := range []int64{0, 21} {
			if got, want := httpHeaderBlock(h, n), headerBlock(requestHeaders(h, n)); got != want {
				t.Errorf("case %d, length %d: got %q, want %q", i, n, got, want)
			}
		}
	}
}

func httpHeaderBlock(h http.Header, n int64) string {
	c := newSerializedCommand()
	defer c.release()
	c.normalizeHTTPHeaders(h, n)
	return string(c.scratch)
}

func FuzzNormalizeHTTPHeaders(f *testing.F) {
	f.Add("X-A", "1", "x-a", "2", "Authorization", "s", "x-Ünï", "\x01v", int64(3))
	f.Add("", "", "Content-Length", " ", "AMZ-SDK-REQUEST", "a", "b", "\U00010000", int64(0))
	f.Fuzz(func(t *testing.T, n1, v1, n2, v2, n3, v3, n4, v4 string, n int64) {
		h := http.Header{}
		for _, kv := range [][2]string{{n1, v1}, {n2, v2}, {n3, v3}, {n4, v4}} {
			h[kv[0]] = append(h[kv[0]], kv[1])
		}
		if !trimmedNames(h) {
			t.Skip("adaptRequest builds the map for these names")
		}
		c := newSerializedCommand()
		defer c.release()
		c.normalizeHTTPHeaders(http.Header{"X-Prev-" + n4: {v1 + v2}}, 1)
		c.normalizeHTTPHeaders(h, n)
		if got, want := string(c.scratch), headerBlock(requestHeaders(h, n)); got != want {
			t.Fatalf("got %q, want %q", got, want)
		}
	})
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

func decodeReply(t testing.TB, wire string) resp.Value {
	t.Helper()
	v, n, err := resp.Decode([]byte(wire))
	if err != nil || n != len(wire) {
		t.Fatalf("decode %q: n=%d err=%v", wire, n, err)
	}
	return v
}

// servedHeaders is the header the SDK sees for headers the cache sent.
func servedHeaders(sent map[string]string) http.Header {
	h := http.Header{"Age": {"0"}}
	for k, v := range sent {
		h.Set(k, v)
	}
	return h
}

func TestAlignmentProtocolDecode(t *testing.T) {
	for _, v := range loadProtocolVectors(t).Decode {
		t.Run(v.Name, func(t *testing.T) {
			reply := decodeReply(t, v.Reply)
			switch v.Command {
			case requestCommand:
				checkRequestVector(t, reply, v.Response, v.Error, v.Code)
			case configCommand:
				cfg, err := parseConfigReply(reply, false)
				switch v.Error {
				case "":
					if err != nil {
						t.Fatal(err)
					}
					want := v.Config
					if (want.Version == nil && cfg.version != "") || (want.Version != nil && cfg.version != *want.Version) {
						t.Errorf("version %q, want %v", cfg.version, want.Version)
					}
					if cfg.refreshInterval != time.Duration(want.RefreshInterval)*time.Second {
						t.Errorf("refresh interval %v, want %ds", cfg.refreshInterval, want.RefreshInterval)
					}
					wantRaw := decodeNumbers(t, want.Raw)
					if !reflect.DeepEqual(cfg.raw, wantRaw) {
						t.Errorf("raw %v, want %v", cfg.raw, wantRaw)
					}
				case "overrides_rejected":
					var oe *OverridesRejectedError
					if !errors.As(err, &oe) {
						t.Errorf("err = %v, want *OverridesRejectedError", err)
					}
				case "error":
					if err == nil || !errors.Is(err, ErrIntegrations) {
						t.Errorf("err = %v, want a package error", err)
					}
					var oe *OverridesRejectedError
					if errors.As(err, &oe) {
						t.Errorf("err = %v is an overrides rejection", err)
					}
				default:
					t.Fatalf("unknown error kind %q", v.Error)
				}
			default:
				t.Fatalf("unknown command %q", v.Command)
			}
		})
	}
}

func checkRequestVector(t *testing.T, reply resp.Value, want *struct {
	Status  int               `json:"status"`
	Headers map[string]string `json:"headers"`
	Body    string            `json:"body"`
}, errKind, code string) {
	t.Helper()
	got, err := parseRequestReply(reply)
	switch errKind {
	case "":
		if err != nil || got.failed {
			t.Fatalf("err = %v, failed = %v", err, got.failed)
		}
		if got.response.statusCode != want.Status {
			t.Errorf("status %d, want %d", got.response.statusCode, want.Status)
		}
		if w := servedHeaders(want.Headers); !reflect.DeepEqual(got.response.headers, w) {
			t.Errorf("headers %v, want %v", got.response.headers, w)
		}
		if string(got.response.body) != want.Body {
			t.Errorf("body %q, want %q", got.response.body, want.Body)
		}
	case "protocol":
		var pe *ProtocolError
		if !errors.As(err, &pe) {
			t.Errorf("err = %v, want *ProtocolError", err)
		}
	case "rejected":
		if err != nil || !got.failed || got.server.code != code {
			t.Errorf("reply %+v, err %v; want server error %s", got.server, err, code)
		}
	default:
		t.Fatalf("unknown error kind %q", errKind)
	}
}

func decodeNumbers(t *testing.T, data []byte) map[string]any {
	t.Helper()
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.UseNumber()
	var m map[string]any
	if err := dec.Decode(&m); err != nil {
		t.Fatal(err)
	}
	return m
}

func TestParseRequestReplyShapes(t *testing.T) {
	body := `{"Item":{}}`
	ok := func(status string) string {
		return "*3\r\n" + status + "$0\r\n\r\n$" + strconv.Itoa(len(body)) + "\r\n" + body + "\r\n"
	}
	cases := []struct {
		name, wire string
		status     int // 0: a *ProtocolError
	}{
		{"integer", ok(":200\r\n"), 200},
		{"integer 100", ok(":100\r\n"), 100},
		{"integer 599", ok(":599\r\n"), 599},
		{"integer 99", ok(":99\r\n"), 0},
		{"integer 600", ok(":600\r\n"), 0},
		{"integer huge", ok(":9223372036854775807\r\n"), 0},
		{"bulk", ok("$3\r\n201\r\n"), 201},
		{"bulk padded", ok("$7\r\n \t404\r\n\r\n"), 404},
		{"bulk plus sign", ok("$4\r\n+200\r\n"), 200},
		{"simple", ok("+500\r\n"), 500},
		{"bulk empty", ok("$0\r\n\r\n"), 0},
		{"bulk not a number", ok("$2\r\nok\r\n"), 0},
		{"bulk over 32 bits", ok("$10\r\n4294967496\r\n"), 0},
		{"bulk fraction", ok("$5\r\n200.0\r\n"), 0},
		{"bulk underscore", ok("$4\r\n2_00\r\n"), 0},
		{"bulk invalid UTF-8", ok("$4\r\n200\xff\r\n"), 0},
		{"null status", ok("_\r\n"), 0},
		{"array status", ok("*0\r\n"), 0},
		{"map", "%1\r\n$1\r\na\r\n$1\r\nb\r\n", 0},
		{"simple string reply", "+OK\r\n", 0},
		{"null reply", "_\r\n", 0},
		{"integer reply", ":1\r\n", 0},
		{"push reply", ">3\r\n$12\r\naws.response\r\n$1\r\n1\r\n:1\r\n", 0},
		{"four elements", "*4\r\n:200\r\n$0\r\n\r\n$0\r\n\r\n$0\r\n\r\n", 0},
		{"empty array", "*0\r\n", 0},
		{"null array", "*-1\r\n", 0},
		{"integer headers", "*3\r\n:200\r\n:1\r\n$0\r\n\r\n", 0},
		{"headers checked before status", "*3\r\n:999\r\n_\r\n$0\r\n\r\n", 0},
		{"invalid UTF-8 headers", "*3\r\n:200\r\n$3\r\na:\xff\r\n$0\r\n\r\n", 0},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got, err := parseRequestReply(decodeReply(t, c.wire))
			if c.status == 0 {
				var pe *ProtocolError
				if !errors.As(err, &pe) {
					t.Fatalf("err = %v, want *ProtocolError", err)
				}
				if strings.Contains(err.Error(), "Item") {
					t.Errorf("error quotes the reply: %v", err)
				}
				return
			}
			if err != nil || got.failed {
				t.Fatalf("err = %v, failed = %v", err, got.failed)
			}
			if got.response.statusCode != c.status || string(got.response.body) != body {
				t.Errorf("got %d %q", got.response.statusCode, got.response.body)
			}
		})
	}
}

func TestParseRequestReplyBodyIsNotCopied(t *testing.T) {
	v := decodeReply(t, "*3\r\n:200\r\n$0\r\n\r\n$3\r\nabc\r\n")
	got, err := parseRequestReply(v)
	if err != nil {
		t.Fatal(err)
	}
	if &got.response.body[0] != &v.Elems[2].Bytes[0] {
		t.Error("the body was copied")
	}
}

func TestParseResponseHeaders(t *testing.T) {
	cases := []struct {
		name, in string
		want     http.Header
	}{
		{"empty", "", http.Header{"Age": {"0"}}},
		{"canonical keys", "x-amz-crc32:123\nx-amzn-requestid:R\ncontent-type:application/x-amz-json-1.0\nx-amzn-errortype:com.amazonaws.dynamodb.v20120810#ResourceNotFoundException",
			http.Header{"X-Amz-Crc32": {"123"}, "X-Amzn-Requestid": {"R"}, "Content-Type": {"application/x-amz-json-1.0"},
				"X-Amzn-Errortype": {"com.amazonaws.dynamodb.v20120810#ResourceNotFoundException"}, "Age": {"0"}}},
		{"trimmed, split at the first colon", " Date : Thu, 01 Jan 2026 00:00:00 GMT \r\n", http.Header{"Date": {"Thu, 01 Jan 2026 00:00:00 GMT"}, "Age": {"0"}}},
		{"last duplicate wins across case", "x-a:1\nX-A:2\nx-A:3", http.Header{"X-A": {"3"}, "Age": {"0"}}},
		{"lines without colon and empty lines skipped", "a:1\n\ngarbage\n\nb", http.Header{"A": {"1"}, "Age": {"0"}}},
		{"empty value", "x-e:", http.Header{"X-E": {""}, "Age": {"0"}}},
		{"body length headers dropped", "content-length:999\ntransfer-encoding:chunked\nx:1", http.Header{"X": {"1"}, "Age": {"0"}}},
		{"age kept", "age:120\ndate:d", http.Header{"Age": {"120"}, "Date": {"d"}}},
		{"empty age set", "AGE:", http.Header{"Age": {"0"}}},
		{"blank age set", "date:d\nage: \t", http.Header{"Age": {"0"}, "Date": {"d"}}},
		{"invalid name kept as sent", "x y:1\n:2", http.Header{"x y": {"1"}, "": {"2"}, "Age": {"0"}}},
		{"UTF-8 value", "x-n:café", http.Header{"X-N": {"café"}, "Age": {"0"}}},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got, err := parseResponseHeaders([]byte(c.in))
			if err != nil {
				t.Fatal(err)
			}
			if !reflect.DeepEqual(got, c.want) {
				t.Errorf("got %v, want %v", got, c.want)
			}
			if got.Get("Age") != c.want.Get("Age") {
				t.Errorf("Get(Age) = %q", got.Get("Age"))
			}
		})
	}
}

func TestParseResponseHeadersLimits(t *testing.T) {
	refused := map[string][]byte{
		"too long":       append(bytes.Repeat([]byte("a"), maxHeaderBytes), ":1"...),
		"too many lines": bytes.Repeat([]byte("\n"), maxHeaderLines),
		"1.25 MiB":       bytes.Repeat([]byte("\n\ngarbage\n"), 1<<17),
	}
	for name, in := range refused {
		if h, err := parseResponseHeaders(in); !errors.As(err, new(*ProtocolError)) {
			t.Errorf("%s: headers %d, err %v; want a ProtocolError", name, len(h), err)
		}
	}
	// At both limits: 1024 lines in under 64 KiB.
	var b strings.Builder
	for i := range maxHeaderLines - 1 {
		fmt.Fprintf(&b, "h%d:%s\n", i, strings.Repeat("v", 40))
	}
	b.WriteString("x-amz-crc32:1")
	if b.Len() > maxHeaderBytes {
		t.Fatalf("test block is %d bytes", b.Len())
	}
	h, err := parseResponseHeaders([]byte(b.String()))
	if err != nil || len(h) != maxHeaderLines+1 {
		t.Fatalf("headers %d, err %v", len(h), err)
	}
	exact := append([]byte("a:"), bytes.Repeat([]byte("v"), maxHeaderBytes-2)...)
	if h, err := parseResponseHeaders(exact); err != nil || len(h.Get("A")) != maxHeaderBytes-2 {
		t.Fatalf("a %d-byte block: headers %d, err %v", len(exact), len(h), err)
	}
}

func TestParseResponseHeadersMemoryIsBounded(t *testing.T) {
	in := bytes.Repeat([]byte("\n"), maxHeaderLines-1) // empty lines, no header
	var before, after runtime.MemStats
	runtime.GC()
	runtime.ReadMemStats(&before)
	h, err := parseResponseHeaders(in)
	runtime.ReadMemStats(&after)
	if err != nil || len(h) != 1 {
		t.Fatalf("headers %v, err %v", h, err)
	}
	// The header string plus small change; a size hint from the line count would take more.
	if n := after.TotalAlloc - before.TotalAlloc; n > 8*uint64(len(in))+4096 {
		t.Errorf("allocated %d bytes for %d bytes of headers", n, len(in))
	}
}

func TestResponseHeaderValuesPastTheHintAreIndependent(t *testing.T) {
	var b strings.Builder
	for i := range maxHeaderHint * 2 {
		fmt.Fprintf(&b, "h%d:%d\n", i, i)
	}
	h, err := parseResponseHeaders([]byte(b.String()))
	if err != nil {
		t.Fatal(err)
	}
	if len(h) != maxHeaderHint*2+1 {
		t.Fatalf("%d headers", len(h))
	}
	for i := range maxHeaderHint * 2 {
		vs := h["H"+strconv.Itoa(i)]
		if len(vs) != 1 || cap(vs) != 1 || vs[0] != strconv.Itoa(i) {
			t.Fatalf("H%d = %q, capacity %d", i, vs, cap(vs))
		}
	}
}

func TestResponseHeaderValuesAreIndependent(t *testing.T) {
	h, err := parseResponseHeaders([]byte("a:1\nb:2"))
	if err != nil {
		t.Fatal(err)
	}
	h["A"] = append(h["A"], "x") // must not write into B's value
	h.Add("Age", "y")
	if got := h["B"]; len(got) != 1 || got[0] != "2" {
		t.Errorf("B = %q", got)
	}
}

func TestServerErrorKinds(t *testing.T) {
	cases := []struct {
		wire string
		code string
		msg  string
		kind serverErrorKind
	}{
		{"-PARSE_ERROR bad body\r\n", "PARSE_ERROR", "bad body", serverRejected},
		{"-UNSUPPORTED op Scan\r\n", "UNSUPPORTED", "op Scan", serverRejected},
		{"-UNIMPLEMENTED\r\n", "UNIMPLEMENTED", "", serverRejected},
		{"-REGION_MISMATCH us-west-2\r\n", "REGION_MISMATCH", "us-west-2", serverRejected},
		{"-ERR unknown\r\n", "ERR", "unknown", serverRejected},
		{"-CONFIG_INVALID x\r\n", "CONFIG_INVALID", "x", serverRejected},
		{"-\r\n", "", "", serverRejected},
		{"-ORIGIN_ERROR upstream failed\r\n", "ORIGIN_ERROR", "upstream failed", serverOrigin},
		{"-INTERNAL_ERROR pool\r\n", "INTERNAL_ERROR", "pool", serverOrigin},
		{"-AUTH_ERROR no creds\r\n", "AUTH_ERROR", "no creds", serverAuth},
		{"-MOVED 3999 127.0.0.1:6381\r\n", "MOVED", "3999 127.0.0.1:6381", serverDrop},
		{"-NOAUTH Authentication required.\r\n", "NOAUTH", "Authentication required.", serverDrop},
		{"-origin_error lower\r\n", "origin_error", "lower", serverRejected},
	}
	for _, c := range cases {
		got, err := parseRequestReply(decodeReply(t, c.wire))
		if err != nil || !got.failed {
			t.Fatalf("%q: err = %v, failed = %v", c.wire, err, got.failed)
		}
		if got.server.code != c.code || got.server.message != c.msg || got.server.kind() != c.kind {
			t.Errorf("%q: got %+v kind %d, want %s %q kind %d", c.wire, got.server, got.server.kind(), c.code, c.msg, c.kind)
		}
		e := got.server.err()
		var oe *OriginError
		var re *RejectedError
		switch {
		case c.kind == serverOrigin:
			if !errors.As(e, &oe) || oe.Code != c.code || oe.Message != c.msg || errors.As(e, &re) {
				t.Errorf("%q: err() = %#v, want *OriginError only", c.wire, e)
			}
		default:
			if !errors.As(e, &re) || re.Code != c.code || re.Message != c.msg || errors.As(e, &oe) {
				t.Errorf("%q: err() = %#v, want *RejectedError only", c.wire, e)
			}
		}
		if !errors.Is(e, ErrIntegrations) {
			t.Errorf("%q: %v does not match ErrIntegrations", c.wire, e)
		}
	}
}

func TestAsyncResponseID(t *testing.T) {
	cases := []struct {
		wire, id string
		ok, err  bool
	}{
		{"+17\r\n", "17", true, false},
		{"+abc-1\r\n", "abc-1", true, false},
		{"+\r\n", "", true, false},
		{"+OK\r\n", "", false, false},
		{"+ok\r\n", "ok", true, false},
		{"+\xff\r\n", "", false, true},
		{"$2\r\n17\r\n", "", false, false},
		{"-ERR x\r\n", "", false, false},
		{"*3\r\n:200\r\n$0\r\n\r\n$0\r\n\r\n", "", false, false},
	}
	for _, c := range cases {
		id, ok, err := asyncResponseID(decodeReply(t, c.wire))
		var pe *ProtocolError
		if id != c.id || ok != c.ok || (err != nil) != c.err || (err != nil && !errors.As(err, &pe)) {
			t.Errorf("%q: got %q %v %v", c.wire, id, ok, err)
		}
	}
}

func TestParseResponsePush(t *testing.T) {
	reply := "*3\r\n:200\r\n$0\r\n\r\n$2\r\n{}\r\n"
	cases := []struct {
		name, wire, id string // id "": a *ProtocolError
	}{
		{"bulk kind", ">3\r\n$12\r\naws.response\r\n$2\r\n17\r\n" + reply, "17"},
		{"simple kind", ">3\r\n+aws.response\r\n$2\r\n17\r\n" + reply, "17"},
		{"error reply", ">3\r\n$12\r\naws.response\r\n$1\r\n9\r\n-ORIGIN_ERROR x\r\n", "9"},
		{"unknown kind", ">3\r\n$7\r\nmessage\r\n$2\r\n17\r\n" + reply, ""},
		{"kind case matters", ">3\r\n$12\r\nAWS.RESPONSE\r\n$2\r\n17\r\n" + reply, ""},
		{"invalid UTF-8 kind", ">3\r\n$2\r\n\xff\xfe\r\n$2\r\n17\r\n" + reply, ""},
		{"integer kind", ">3\r\n:1\r\n$2\r\n17\r\n" + reply, ""},
		{"empty", ">0\r\n", ""},
		{"two elements", ">2\r\n$12\r\naws.response\r\n$2\r\n17\r\n", ""},
		{"four elements", ">4\r\n$12\r\naws.response\r\n$2\r\n17\r\n" + reply + ":1\r\n", ""},
		{"simple string id", ">3\r\n$12\r\naws.response\r\n+17\r\n" + reply, ""},
		{"integer id", ">3\r\n$12\r\naws.response\r\n:17\r\n" + reply, ""},
		{"invalid UTF-8 id", ">3\r\n$12\r\naws.response\r\n$1\r\n\xff\r\n" + reply, ""},
		{"not a push", "*3\r\n$12\r\naws.response\r\n$2\r\n17\r\n" + reply, ""},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			id, inner, err := parseResponsePush(decodeReply(t, c.wire))
			if c.id == "" {
				var pe *ProtocolError
				if !errors.As(err, &pe) {
					t.Fatalf("err = %v, want *ProtocolError", err)
				}
				return
			}
			if err != nil || id != c.id {
				t.Fatalf("id %q err %v", id, err)
			}
			if _, err := parseRequestReply(inner); err != nil {
				t.Errorf("inner reply: %v", err)
			}
		})
	}
}

func TestParseConfigReply(t *testing.T) {
	bulk := func(s string) string { return "$" + strconv.Itoa(len(s)) + "\r\n" + s + "\r\n" }
	cases := []struct {
		name, wire string
		version    string
		interval   time.Duration // 0: an error of wantErr's type
		wantErr    error
	}{
		{name: "ok", wire: bulk(`{"version":"1.0","refresh_interval":60}`), version: "1.0", interval: time.Minute},
		{name: "max interval", wire: bulk(`{"refresh_interval":2147483647}`), interval: 2147483647 * time.Second},
		{name: "whitespace around", wire: bulk(" \n{\"refresh_interval\":1}\t\r\n "), interval: time.Second},
		{name: "numeric version", wire: bulk(`{"version":1,"refresh_interval":1}`), version: "1", interval: time.Second},
		{name: "bool version", wire: bulk(`{"version":true,"refresh_interval":1}`), version: "true", interval: time.Second},
		{name: "null interval", wire: bulk(`{"refresh_interval":null}`), wantErr: &ProtocolError{}},
		{name: "string interval", wire: bulk(`{"refresh_interval":"60"}`), wantErr: &ProtocolError{}},
		{name: "exponent interval", wire: bulk(`{"refresh_interval":6e1}`), wantErr: &ProtocolError{}},
		{name: "negative interval", wire: bulk(`{"refresh_interval":-1}`), wantErr: &ProtocolError{}},
		{name: "negative zero interval", wire: bulk(`{"refresh_interval":-0}`), wantErr: &ProtocolError{}},
		{name: "interval over int32", wire: bulk(`{"refresh_interval":2147483648}`), wantErr: &ProtocolError{}},
		{name: "interval over int64", wire: bulk(`{"refresh_interval":99999999999999999999}`), wantErr: &ProtocolError{}},
		{name: "interval under int64", wire: bulk(`{"refresh_interval":-99999999999999999999}`), wantErr: &ProtocolError{}},
		{name: "object version", wire: bulk(`{"version":{},"refresh_interval":1}`), wantErr: &ProtocolError{}},
		{name: "array version", wire: bulk(`{"version":[],"refresh_interval":1}`), wantErr: &ProtocolError{}},
		{name: "JSON null", wire: bulk(`null`), wantErr: &ProtocolError{}},
		{name: "JSON array", wire: bulk(`[]`), wantErr: &ProtocolError{}},
		{name: "JSON string", wire: bulk(`"x"`), wantErr: &ProtocolError{}},
		{name: "trailing data", wire: bulk(`{"refresh_interval":1}{}`), wantErr: &ProtocolError{}},
		{name: "trailing garbage", wire: bulk(`{"refresh_interval":1} x`), wantErr: &ProtocolError{}},
		{name: "empty", wire: bulk(``), wantErr: &ProtocolError{}},
		{name: "invalid UTF-8", wire: bulk("{\"refresh_interval\":1,\"x\":\"\xff\"}"), wantErr: &ProtocolError{}},
		{name: "null at bring-up", wire: "_\r\n", wantErr: &ProtocolError{}},
		{name: "simple string", wire: "+OK\r\n", wantErr: &ProtocolError{}},
		{name: "integer", wire: ":1\r\n", wantErr: &ProtocolError{}},
		{name: "config invalid", wire: "-CONFIG_INVALID unknown key ttl\r\n", wantErr: &OverridesRejectedError{}},
		{name: "other error", wire: "-ERR unknown command 'aws.config'\r\n", wantErr: &RejectedError{}},
		{name: "internal error", wire: "-INTERNAL_ERROR pool\r\n", wantErr: &RejectedError{}},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			cfg, err := parseConfigReply(decodeReply(t, c.wire), false)
			if c.interval == 0 {
				if cfg != nil || err == nil || reflect.TypeOf(err) != reflect.TypeOf(c.wantErr) {
					t.Fatalf("got %v, %v; want %T", cfg, err, c.wantErr)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if cfg.version != c.version || cfg.refreshInterval != c.interval || cfg.raw == nil {
				t.Errorf("got %+v", cfg)
			}
		})
	}
}

func TestParseConfigReplyDetails(t *testing.T) {
	big := `{"refresh_interval":60,"n":9007199254740993,"f":1.5,"predicates":[{"expr":"true"}]}`
	cfg, err := parseConfigReply(decodeReply(t, "$"+strconv.Itoa(len(big))+"\r\n"+big+"\r\n"), true)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.raw["n"] != json.Number("9007199254740993") || cfg.raw["f"] != json.Number("1.5") {
		t.Errorf("numbers are not json.Number: %#v", cfg.raw)
	}
	if _, ok := cfg.raw["predicates"].([]any); !ok {
		t.Errorf("predicates: %#v", cfg.raw["predicates"])
	}
	if got, err := parseConfigReply(decodeReply(t, "_\r\n"), true); got != nil || err != nil {
		t.Errorf("null on refresh: %v, %v", got, err)
	}
	rejected, err := parseConfigReply(decodeReply(t, "-CONFIG_INVALID unknown key ttl\r\n"), true)
	var oe *OverridesRejectedError
	if rejected != nil || !errors.As(err, &oe) || oe.Message != "unknown key ttl" {
		t.Errorf("got %v, %#v", rejected, err)
	}
	_, err = parseConfigReply(decodeReply(t, "-ERR boom now\r\n"), true)
	var re *RejectedError
	if !errors.As(err, &re) || re.Code != "ERR" || re.Message != "boom now" {
		t.Errorf("got %#v", err)
	}
}

func FuzzParseRequestReply(f *testing.F) {
	for _, s := range replySeeds(f) {
		f.Add([]byte(s))
	}
	f.Fuzz(func(t *testing.T, data []byte) {
		v, _, err := resp.Decode(data)
		if err != nil {
			return
		}
		got, err := parseRequestReply(v)
		if err != nil {
			var pe *ProtocolError
			if !errors.As(err, &pe) {
				t.Fatalf("error %T is not a *ProtocolError", err)
			}
			return
		}
		if got.failed {
			if v.Kind != resp.KindError {
				t.Fatalf("a %s reply failed", v.Kind)
			}
			return
		}
		if h := v.Elems[1].Bytes; len(h) > maxHeaderBytes || bytes.Count(h, []byte{'\n'}) >= maxHeaderLines {
			t.Fatalf("a %d-byte header block parsed", len(h))
		}
		r := got.response
		if r.statusCode < 100 || r.statusCode > 599 {
			t.Fatalf("status %d", r.statusCode)
		}
		if r.headers.Get("Content-Length") != "" || r.headers.Get("Transfer-Encoding") != "" || r.headers.Get("Age") == "" {
			t.Fatalf("headers %v", r.headers)
		}
		for k, vs := range r.headers {
			if len(vs) != 1 || cap(vs) != 1 {
				t.Fatalf("header %q has %d values, capacity %d", k, len(vs), cap(vs))
			}
		}
	})
}

func FuzzParsePush(f *testing.F) {
	for _, s := range replySeeds(f) {
		f.Add([]byte(">3\r\n$12\r\naws.response\r\n$1\r\n1\r\n" + s))
	}
	f.Add([]byte("+17\r\n"))
	f.Fuzz(func(t *testing.T, data []byte) {
		v, _, err := resp.Decode(data)
		if err != nil {
			return
		}
		if id, ok, err := asyncResponseID(v); ok && (err != nil || !utf8.ValidString(id) || id == okReply) {
			t.Fatalf("deferred id %q, %v", id, err)
		}
		id, inner, err := parsePushOrNil(v)
		if err != nil {
			return
		}
		if !utf8.ValidString(id) || v.Kind != resp.KindPush || len(v.Elems) != 3 {
			t.Fatalf("accepted push %v with id %q", v, id)
		}
		_, _ = parseRequestReply(inner)
	})
}

// parsePushOrNil parses pushes only, as the reader does.
func parsePushOrNil(v resp.Value) (string, resp.Value, error) {
	if v.Kind != resp.KindPush {
		return "", resp.Value{}, errors.New("not a push")
	}
	return parseResponsePush(v)
}

func FuzzParseConfigReply(f *testing.F) {
	for _, s := range replySeeds(f) {
		f.Add([]byte(s), false)
	}
	f.Add([]byte("_\r\n"), true)
	f.Fuzz(func(t *testing.T, data []byte, refresh bool) {
		v, _, err := resp.Decode(data)
		if err != nil {
			return
		}
		cfg, err := parseConfigReply(v, refresh)
		if err != nil {
			if cfg != nil || !errors.Is(err, ErrIntegrations) {
				t.Fatalf("cfg %v with error %v", cfg, err)
			}
			return
		}
		if cfg == nil {
			if !refresh || v.Kind != resp.KindNull {
				t.Fatalf("nil config for %v", v)
			}
			return
		}
		if cfg.raw == nil || cfg.refreshInterval < time.Second || cfg.refreshInterval > math.MaxInt32*time.Second ||
			cfg.refreshInterval%time.Second != 0 {
			t.Fatalf("config %+v", cfg)
		}
	})
}

// replySeeds are the reply frames of the shared decode vectors.
func replySeeds(f *testing.F) []string {
	var v protocolVectors
	testutil.DecodeStrict(f, testutil.SharedFixture(f, "protocol-vectors.json"), &v)
	var out []string
	for _, d := range v.Decode {
		out = append(out, d.Reply)
	}
	return out
}

// hitReply is an aws.request reply with DynamoDB's response headers and a body of n bytes.
func hitReply(n int) string {
	headers := "content-type:application/x-amz-json-1.0\nx-amzn-requestid:ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCDEFGHIJKLMNOP\n" +
		"x-amz-crc32:2745614147\ncontent-length:" + strconv.Itoa(n) + "\ndate:Thu, 01 Jan 2026 00:00:00 GMT\nserver:Server\nconnection:keep-alive"
	body := strings.Repeat("x", n)
	return "*3\r\n:200\r\n$" + strconv.Itoa(len(headers)) + "\r\n" + headers + "\r\n$" + strconv.Itoa(n) + "\r\n" + body + "\r\n"
}

func TestParseRequestReplyAllocations(t *testing.T) {
	if raceEnabled {
		t.Skip("the race detector allocates")
	}
	v := decodeReply(t, hitReply(1024))
	allocs := testing.AllocsPerRun(100, func() {
		if _, err := parseRequestReply(v); err != nil {
			t.Fatal(err)
		}
	})
	// The header string, the value slice and the map.
	if allocs > 4 {
		t.Errorf("parseRequestReply allocates %v times, want at most 4", allocs)
	}
}

func BenchmarkParseRequestReply(b *testing.B) {
	for _, n := range []int{1 << 10, 390 << 10} {
		b.Run(strconv.Itoa(n>>10)+"KiB", func(b *testing.B) {
			v := decodeReply(b, hitReply(n))
			b.ReportAllocs()
			for b.Loop() {
				if _, err := parseRequestReply(v); err != nil {
					b.Fatal(err)
				}
			}
		})
	}
}

func TestCanonicalHeaderKeyMatchesTextproto(t *testing.T) {
	for lower, canonical := range dynamoDBHeaders {
		if got := textproto.CanonicalMIMEHeaderKey(lower); got != canonical {
			t.Errorf("%s: textproto gives %s, the table %s", lower, got, canonical)
		}
		if canonicalHeaderKey(lower) != canonical {
			t.Errorf("%s not interned", lower)
		}
	}
}

// refresh_interval must be an integral number; 60.0 and 6E1 are refused, as Java refuses them.
func TestRefreshIntervalRealRefused(t *testing.T) {
	for _, body := range []string{`{"refresh_interval":60.0}`, `{"refresh_interval":60.5}`, `{"refresh_interval":6E1}`} {
		if _, err := parseConfigReply(resp.BulkStringValue(body), true); !errors.As(err, new(*ProtocolError)) ||
			!strings.Contains(err.Error(), "not an integer") {
			t.Errorf("%s: %v", body, err)
		}
	}
}
