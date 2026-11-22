// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package resp

import (
	"errors"
	"testing"
)

// errJavaIncomplete is the reference decoder's IncompleteFrame.
var errJavaIncomplete = errors.New("incomplete")

// javaDecode is a line-by-line port of the Java client's recursive RespCodec.decode, with
// the two documented deviations: a number has at most 19 digits after its sign, and a number
// line holding 22 bytes without a CRLF is corrupt.
func javaDecode(b []byte, start, depth int) (Value, int, error) {
	if start == len(b) {
		return Value{}, 0, errJavaIncomplete
	}
	typ := b[start]
	switch typ {
	case '+', ':', '-', '$', '*', '>', '%', '_':
	default:
		return Value{}, 0, ErrCorrupt
	}
	crlf := -1
	for i := start + 1; i < len(b)-1; i++ {
		if b[i] == '\r' && b[i+1] == '\n' {
			crlf = i
			break
		}
	}
	if crlf < 0 {
		if typ != '+' && typ != '-' && len(b)-start-1 > 21 {
			return Value{}, 0, ErrCorrupt
		}
		return Value{}, 0, errJavaIncomplete
	}
	line := b[start+1 : crlf]
	next := crlf + 2
	parse := func(bits uint) (int64, error) {
		n, ok := javaParse(line, bits)
		if !ok {
			return 0, ErrCorrupt
		}
		return n, nil
	}
	switch typ {
	case '+':
		return Value{Kind: KindSimpleString, Bytes: line}, next, nil
	case '-':
		return Value{Kind: KindError, Bytes: line}, next, nil
	case ':':
		n, err := parse(64)
		return Value{Kind: KindInteger, Int: n}, next, err
	case '_':
		if len(line) != 0 {
			return Value{}, 0, ErrCorrupt
		}
		return Value{Kind: KindNull}, next, nil
	case '$':
		n, err := parse(32)
		if err != nil || n < -1 {
			return Value{}, 0, ErrCorrupt
		}
		if n == -1 {
			return Value{Kind: KindNull}, next, nil
		}
		if int64(next)+n+2 > int64(len(b)) {
			return Value{}, 0, errJavaIncomplete
		}
		end := next + int(n)
		if b[end] != '\r' || b[end+1] != '\n' {
			return Value{}, 0, ErrCorrupt
		}
		return Value{Kind: KindBulkString, Bytes: b[next:end]}, end + 2, nil
	}
	if depth > MaxDepth {
		return Value{}, 0, ErrCorrupt
	}
	n, err := parse(32)
	if err != nil || n < -1 || (n == -1 && typ != '*') {
		return Value{}, 0, ErrCorrupt
	}
	if n == -1 {
		return Value{Kind: KindNull}, next, nil
	}
	kind := map[byte]Kind{'*': KindArray, '>': KindPush, '%': KindMap}[typ]
	if typ == '%' {
		n *= 2
	}
	var elems []Value
	for range n {
		var e Value
		e, next, err = javaDecode(b, next, depth+1)
		if err != nil {
			return Value{}, 0, err
		}
		elems = append(elems, e)
	}
	return Value{Kind: kind, Elems: elems}, next, nil
}

// javaParse is Long.parseLong / Integer.parseInt limited to 19 digits.
func javaParse(s []byte, bits uint) (int64, bool) {
	neg := len(s) > 0 && s[0] == '-'
	if len(s) > 0 && (s[0] == '-' || s[0] == '+') {
		s = s[1:]
	}
	if len(s) == 0 || len(s) > 19 {
		return 0, false
	}
	var n uint64
	for _, c := range s {
		if c < '0' || c > '9' {
			return 0, false
		}
		n = n*10 + uint64(c-'0')
	}
	limit := uint64(1) << (bits - 1)
	if neg && n <= limit {
		return int64(-n), true
	}
	if !neg && n < limit {
		return int64(n), true
	}
	return 0, false
}

// FuzzDecodeMatchesJava checks Decode against the port of the Java decoder: the same verdict
// (complete, incomplete or corrupt), the same length and the same value.
func FuzzDecodeMatchesJava(f *testing.F) {
	addSeeds(f)
	f.Fuzz(func(t *testing.T, data []byte) {
		want, wantN, wantErr := javaDecode(data, 0, 1)
		got, n, err := Decode(data)
		switch {
		case errors.Is(wantErr, ErrCorrupt):
			if !errors.Is(err, ErrCorrupt) {
				t.Fatalf("java corrupt, go %v", err)
			}
		case wantErr != nil:
			if !errors.Is(err, ErrIncomplete) {
				t.Fatalf("java incomplete, go %v", err)
			}
		case err != nil || n != wantN || !equal(got, want):
			t.Fatalf("java %v %d, go %v %d %v", want, wantN, got, n, err)
		}
	})
}
