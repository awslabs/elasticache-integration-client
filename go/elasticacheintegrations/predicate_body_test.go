// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"math/rand/v2"
	"reflect"
	"slices"
	"strings"
	"testing"
	"unicode/utf8"
)

var scanAccepted = []string{
	`{}`, ` {} `, "\t\r\n{\t\r\n}\t\r\n", `{"a":1}`, `{"a":[]}`, `{"a":{}}`, `{"a":[1,2,[3,{}]]}`,
	`{"a":true,"b":false,"c":null}`, `{"":""}`, `{"a":"b","a":"c"}`,
	`{"n":[0,-0,1,-1,10,1.5,-1.5,0.0,1e5,1E5,1e+5,1e-5,1.25e-10,123456789012345678901234567890]}`,
	`{"s":"\"\\\/\b\f\n\r\t\u00e9\uD83D\uDE00"}`, `{"lone":"\ud800","low":"\udc00x"}`,
	"{\"u\":\"é日本語😀\"}", "{\"u\":\"\xef\xbf\xbd\"}", "\xef\xbb\xbf{}",
	`{"long":"` + strings.Repeat("abcdefgh", 100) + `"}`,
	`{"esc":"` + strings.Repeat(`\"`, 9) + `x"}`,
}

var scanRefused = []string{
	``, ` `, `[]`, `"s"`, `1`, `null`, `true`, "\xef\xbb\xbf", "\xef\xbb\xbf\xef\xbb\xbf{}",
	`{`, `}`, `{"a"}`, `{"a":}`, `{"a":1,}`, `{,}`, `{"a":1 "b":2}`, `{a:1}`, `{'a':1}`,
	`{"a":[1,]}`, `{"a":[,1]}`, `{"a":[1 2]}`, `{"a":]`, `{"a":[}`, `{} {}`, `{}x`, `{}]`,
	`{"a":01}`, `{"a":-}`, `{"a":1.}`, `{"a":.1}`, `{"a":1e}`, `{"a":1e+}`, `{"a":+1}`, `{"a":0x1}`,
	`{"a":NaN}`, `{"a":Infinity}`, `{"a":tru}`, `{"a":nul}`, `{"a":True}`, `{"a":truex}`,
	`{"a":"\x"}`, `{"a":"\u12"}`, `{"a":"\u12g4"}`, `{"a":"\`, `{"a":"abc`, "{\"a\":\"\x00\"}",
	"{\"a\":\"\x1f\"}", "{\"a\":\"\t\"}", "{\"a\":\"\n\"}",
	"{\"a\":\"\xff\"}", "{\"a\":\"\xc0\xaf\"}", "{\"a\":\"\xed\xa0\x80\"}", "{\"a\":\"\xe6\x97\"}",
	"{\"a\":\"\x80\"}", "{\"a\":\"\xf4\x90\x80\x80\"}", "\xff{}", "{}\xff", "{\"a\":1}\x00",
	"{\"a\" :\xa01}", "\u00a0{}",
}

func TestScanJSONAccepts(t *testing.T) {
	for _, s := range scanAccepted {
		d, ok := scanJSON([]byte(s))
		if !ok {
			t.Errorf("%q refused", s)
			continue
		}
		d.release()
	}
}

func TestScanJSONRefuses(t *testing.T) {
	for _, s := range scanRefused {
		if d, ok := scanJSON([]byte(s)); ok {
			d.release()
			t.Errorf("%q accepted", s)
		}
	}
}

func TestScanJSONDepth(t *testing.T) {
	nest := func(levels int, open, close string) []byte { // the top-level object is level 1
		return []byte(`{"a":` + strings.Repeat(open, levels-1) + "1" + strings.Repeat(close, levels-1) + `}`)
	}
	for _, br := range [][2]string{{"[", "]"}, {`{"a":`, "}"}} {
		if d, ok := scanJSON(nest(maxBodyDepth, br[0], br[1])); !ok {
			t.Errorf("%s: depth %d refused", br[0], maxBodyDepth)
		} else {
			d.release()
		}
		if _, ok := scanJSON(nest(maxBodyDepth+1, br[0], br[1])); ok {
			t.Errorf("%s: depth %d accepted", br[0], maxBodyDepth+1)
		}
	}
}

// Every byte value at every position of an 8-byte word of a string, so a word boundary
// cannot hide a stop byte.
func TestScanJSONStringStopsAtEveryOffset(t *testing.T) {
	for off := 0; off < 17; off++ {
		for c := 0; c < 256; c++ {
			body := []byte(`{"a":"` + strings.Repeat("x", off) + string([]byte{byte(c)}) + `yyyyyyyyyyyyyyyy"}`)
			_, ok := scanJSON(body)
			if want := scanOracle(body); ok != want {
				t.Fatalf("offset %d byte %#x: got %v, want %v", off, c, ok, want)
			}
		}
	}
}

func TestJSONDocNavigation(t *testing.T) {
	body := []byte(` {"a" : [1, "x\"]", {"k":[]} ,true] , "b\u0063":{"z":null}, "s":"v\\"} `)
	d, ok := scanJSON(body)
	if !ok {
		t.Fatal("refused")
	}
	defer d.release()
	var keys []string
	var vals []string
	it := d.iter(d.root)
	for key, val, ok := it.next(); ok; key, val, ok = it.next() {
		k, _ := decodeString(key)
		keys = append(keys, k)
		vals = append(vals, string(d.b[val:d.end(val)]))
	}
	if strings.Join(keys, ",") != "a,bc,s" {
		t.Fatalf("keys %q", keys)
	}
	want := []string{`[1, "x\"]", {"k":[]} ,true]`, `{"z":null}`, `"v\\"`}
	for i := range want {
		if vals[i] != want[i] {
			t.Errorf("value %d: %q, want %q", i, vals[i], want[i])
		}
	}
	var elems []string
	arr := d.iter(bytes.IndexByte(d.b, '['))
	for key, val, ok := arr.next(); ok; key, val, ok = arr.next() {
		if key != nil {
			t.Fatal("an array element has a key")
		}
		elems = append(elems, string(d.b[val:d.end(val)]))
	}
	if strings.Join(elems, "|") != `1|"x\"]"|{"k":[]}|true` {
		t.Fatalf("elements %q", elems)
	}
	empty := d.iter(bytes.Index(d.b, []byte("[]")))
	if _, _, ok := empty.next(); ok {
		t.Fatal("an empty array has an element")
	}
}

func TestEqualString(t *testing.T) {
	cases := []struct {
		raw, s string
		want   bool
	}{
		{`"Users"`, "Users", true}, {`"Users"`, "Users2", false}, {`""`, "", true},
		{`"U\u0073ers"`, "Users", true}, {`"a\"b"`, `a"b`, true}, {`"\ud800"`, "\uFFFD", true},
		{`"\u00e9"`, "é", true}, {`"\u00e9"`, "e", false},
	}
	for _, c := range cases {
		if got := equalString([]byte(c.raw), c.s); got != c.want {
			t.Errorf("%s vs %q: got %v", c.raw, c.s, got)
		}
	}
}

func TestEmptyBodyIsAnEmptyObject(t *testing.T) {
	pr := predicateRequest{req: &request{}}
	defer pr.release()
	if !pr.loadBody() || pr.doc != emptyDoc {
		t.Fatal("an empty body is not {}")
	}
	if m, ok := pr.body(); !ok || len(m) != 0 {
		t.Fatal("the cel-go view of an empty body is not {}")
	}
	pr.release()
	if emptyDoc.b == nil {
		t.Fatal("releasing the empty document cleared it")
	}
}

func TestReleasedDocDropsTheBody(t *testing.T) {
	d, _ := scanJSON([]byte(`{"a":1}`))
	d.release()
	if d.b != nil {
		t.Fatal("a pooled document keeps the request body")
	}
}

// scanOracle is what scanJSON must accept, from the standard library.
func scanOracle(body []byte) bool {
	b := bytes.TrimPrefix(body, []byte(utf8BOM))
	if !json.Valid(b) || !utf8.Valid(b) {
		return false
	}
	if t := bytes.TrimLeft(b, " \t\r\n"); len(t) == 0 || t[0] != '{' {
		return false
	}
	dec := json.NewDecoder(bytes.NewReader(b))
	depth := 0
	for {
		tok, err := dec.Token()
		if err != nil {
			return true
		}
		switch tok {
		case json.Delim('{'), json.Delim('['):
			if depth++; depth > maxBodyDepth {
				return false
			}
		case json.Delim('}'), json.Delim(']'):
			depth--
		}
	}
}

func FuzzScanJSON(f *testing.F) {
	for _, s := range append(append([]string{}, scanAccepted...), scanRefused...) {
		f.Add([]byte(s))
	}
	f.Add(benchPutItem(2<<10, true))
	f.Add(benchTransact(3, 5))
	f.Fuzz(func(t *testing.T, body []byte) {
		d, ok := scanJSON(body)
		if want := scanOracle(body); ok != want {
			t.Fatalf("%q: got %v, want %v", body, ok, want)
		}
		if !ok {
			return
		}
		defer d.release()
		for k, start := range d.starts {
			var raw json.RawMessage
			dec := json.NewDecoder(bytes.NewReader(d.b[start:]))
			if err := dec.Decode(&raw); err != nil {
				t.Fatalf("container at %d does not decode: %v", start, err)
			}
			if got, want := int(d.ends[k]), int(start)+int(dec.InputOffset()); got != want {
				t.Fatalf("container at %d ends at %d, want %d", start, got, want)
			}
			next := k + 1
			for next < len(d.starts) && d.starts[next] < d.ends[k] {
				next++
			}
			if int(d.after[k]) != next {
				t.Fatalf("container %d: after is %d, want %d", k, d.after[k], next)
			}
		}
		if _, ok := d.celBody(); !ok {
			t.Fatal("an accepted body does not decode")
		}
		checkScanAgainstStd(t, body)
	})
}

// decodeBodyStd is decodeBody built only from the standard library.
func decodeBodyStd(body []byte) (map[string]any, bool) {
	if len(body) == 0 {
		return map[string]any{}, true
	}
	body = bytes.TrimPrefix(body, []byte(utf8BOM))
	if !utf8.Valid(body) {
		return nil, false
	}
	dec := json.NewDecoder(bytes.NewReader(body))
	dec.UseNumber()
	var v any
	if err := dec.Decode(&v); err != nil {
		return nil, false
	}
	if _, err := dec.Token(); !errors.Is(err, io.EOF) {
		return nil, false
	}
	obj, ok := v.(map[string]any)
	if !ok {
		return nil, false
	}
	out, err := celValue(obj, 1)
	if err != nil {
		return nil, false
	}
	return out.(map[string]any), true
}

// containerIndex returns the index of the container that starts at pos.
func (d *jsonDoc) containerIndex(pos int) int {
	k, _ := slices.BinarySearch(d.starts, int32(pos))
	return k
}

// end returns the offset just past the accepted value at pos.
func (d *jsonDoc) end(pos int) int {
	switch d.b[pos] {
	case '{', '[':
		return int(d.ends[d.containerIndex(pos)])
	}
	return d.scalarEnd(pos)
}

// iter walks the container at pos.
func (d *jsonDoc) iter(pos int) jsonIter { return d.iterAt(pos, d.containerIndex(pos)) }

// walkDoc rebuilds the value at pos through iterAt, next, end and decodeString only. A nested
// container is walked from the span and container index next returned for it.
func walkDoc(t *testing.T, d *jsonDoc, pos int) any {
	t.Helper()
	return walkValue(t, d, pos, d.containerIndex(pos))
}

func walkValue(t *testing.T, d *jsonDoc, pos, ci int) any {
	t.Helper()
	b := d.b
	member := func(it *jsonIter, val int) any {
		if got, want := it.valEnd, d.end(val); got != want {
			t.Fatalf("value at %d: next says it ends at %d, want %d", val, got, want)
		}
		if c := b[val]; c == '{' || c == '[' {
			if got, want := it.valCI, d.containerIndex(val); got != want {
				t.Fatalf("value at %d: next says container %d, want %d", val, got, want)
			}
		}
		return walkValue(t, d, val, it.valCI)
	}
	switch b[pos] {
	case '{':
		m := map[string]any{}
		it := d.iterAt(pos, ci)
		for key, val, ok := it.next(); ok; key, val, ok = it.next() {
			k, kok := decodeString(key)
			if !kok {
				t.Fatalf("key %q does not decode", key)
			}
			if !bytes.Equal(b[it.keyPos:it.keyPos+len(key)], key) {
				t.Fatalf("key %q is not at %d", key, it.keyPos)
			}
			m[k] = member(&it, val)
		}
		return m
	case '[':
		l := []any{}
		it := d.iterAt(pos, ci)
		for key, val, ok := it.next(); ok; key, val, ok = it.next() {
			if key != nil {
				t.Fatal("an array element has a key")
			}
			l = append(l, member(&it, val))
		}
		return l
	case '"':
		s, ok := decodeString(b[pos:d.end(pos)])
		if !ok {
			t.Fatalf("string %q does not decode", b[pos:d.end(pos)])
		}
		return s
	default:
		var v any
		dec := json.NewDecoder(bytes.NewReader(b[pos:d.end(pos)]))
		dec.UseNumber()
		if err := dec.Decode(&v); err != nil || dec.More() {
			t.Fatalf("scalar %q at %d is not one value", b[pos:d.end(pos)], pos)
		}
		out, err := celValue(v, 1)
		if err != nil {
			t.Fatal(err)
		}
		return out
	}
}

func checkScanAgainstStd(t *testing.T, body []byte) {
	t.Helper()
	want, wantOK := decodeBodyStd(body)
	got, gotOK := decodeBody(body)
	if gotOK != wantOK || !reflect.DeepEqual(got, want) {
		t.Fatalf("%q: decodeBody %v %v, standard library %v %v", body, got, gotOK, want, wantOK)
	}
	if len(body) == 0 || !gotOK {
		return
	}
	d, ok := scanJSON(body)
	if !ok {
		t.Fatalf("%q: decodes but the scan refuses it", body)
	}
	defer d.release()
	if root := walkDoc(t, d, d.root); !reflect.DeepEqual(root, want) {
		t.Fatalf("%q: walked %v, want %v", body, root, want)
	}
}

func TestScanJSONMatchesStdDecode(t *testing.T) {
	cases := append(append([]string{}, scanAccepted...), scanRefused...)
	cases = append(cases,
		`{"a":"\\"}`, `{"a":"\\\\"}`, `{"a":"x\\\"y"}`, `{"a":["\\",1]}`, `{"a":[1],"b":2}`,
		`{"a":[-0.5e-3]}`, `{"a":1e9}`, `{"a" : 12 , "b" : [ 3 ,4 ] }`, `{"a":{"b":-1}}`,
		`{"a":"`+"\u2028\u2029"+`"}`, `{"a":"\u0000"}`, `{"\"":"\\"}`,
	)
	for _, s := range cases {
		checkScanAgainstStd(t, []byte(s))
	}
}

// genJSON writes a random value biased to the scanner's edges: backslash runs before quotes,
// escapes, multi-byte runes across word boundaries, numbers next to brackets, odd whitespace.
func genJSON(r *rand.Rand, buf *bytes.Buffer, depth int) {
	ws := func() {
		for range r.IntN(3) {
			buf.WriteByte(" \t\r\n"[r.IntN(4)])
		}
	}
	kind := r.IntN(8)
	if depth > 6 {
		kind = 2 + r.IntN(6)
	}
	switch kind {
	case 0, 1:
		open, close := byte('{'), byte('}')
		if kind == 1 {
			open, close = '[', ']'
		}
		buf.WriteByte(open)
		ws()
		for k := range r.IntN(4) {
			if k > 0 {
				buf.WriteByte(',')
				ws()
			}
			if kind == 0 {
				genString(r, buf)
				ws()
				buf.WriteByte(':')
				ws()
			}
			genJSON(r, buf, depth+1)
			ws()
		}
		buf.WriteByte(close)
	case 2, 3:
		genString(r, buf)
	case 4, 5:
		buf.WriteString([]string{"0", "-0", "7", "-12", "1.5", "-0.25e-3", "1E9", "12345678901234567890", "1e400"}[r.IntN(9)])
	default:
		buf.WriteString([]string{"true", "false", "null"}[r.IntN(3)])
	}
}

func genString(r *rand.Rand, buf *bytes.Buffer) {
	parts := []string{
		"a", "abcdefgh", `\\`, `\"`, `\\\"`, `\\\\`, `\/`, `\n`, `\u0041`, `\ud800`, `\uDE00`,
		"é", "日本", "😀", "\u2028", `}`, `]`, `,`, `:`, " ",
	}
	buf.WriteByte('"')
	for range r.IntN(12) {
		buf.WriteString(parts[r.IntN(len(parts))])
	}
	buf.WriteByte('"')
}

func TestScanJSONRandomDocuments(t *testing.T) {
	r := rand.New(rand.NewPCG(1, 2))
	var buf bytes.Buffer
	for range 20000 {
		buf.Reset()
		buf.WriteByte('{')
		for k := range r.IntN(5) {
			if k > 0 {
				buf.WriteByte(',')
			}
			genString(r, &buf)
			buf.WriteByte(':')
			genJSON(r, &buf, 2)
		}
		buf.WriteByte('}')
		checkScanAgainstStd(t, buf.Bytes())
		// A truncated document must be refused like the standard library refuses it.
		checkScanAgainstStd(t, buf.Bytes()[:r.IntN(buf.Len())])
	}
}

func TestReleasedDocLeavesThePoolClean(t *testing.T) {
	body := []byte(`{"a":[1,{"b":[]}]}`)
	for range 100 {
		d, ok := scanJSON(body)
		if !ok {
			t.Fatal("refused")
		}
		d.release()
		if d.b != nil {
			t.Fatal("a released document keeps the body")
		}
	}
	if emptyDoc.b == nil || len(emptyDoc.starts) != 1 || emptyDoc.ends[0] != 2 {
		t.Fatal("the empty document changed")
	}
}
