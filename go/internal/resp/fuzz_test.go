// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package resp

import (
	"errors"
	"testing"
)

func addSeeds(f *testing.F) {
	vec := loadVectors(f)
	for _, d := range vec.Decode {
		f.Add([]byte(d.Reply))
	}
	for _, e := range vec.Encode {
		f.Add([]byte(e.Frame))
	}
	for _, s := range []string{"+OK\r\n", "-ERR x\r\n", ":-5\r\n", "$-1\r\n", "*-1\r\n", "_\r\n",
		"%1\r\n$5\r\nproto\r\n:3\r\n", ">3\r\n$12\r\naws.response\r\n$1\r\n1\r\n_\r\n",
		"*2\r\n$3\r\nfoo\r\n", "|1\r\n+a\r\n+b\r\n", "$5\r\nhelloxx", nested("*", MaxDepth)} {
		f.Add([]byte(s))
	}
}

// FuzzDecode checks that Scan and Decode never panic, agree with each other, that every
// prefix of a complete value is incomplete with a Need that never passes the value's end,
// and that a decoded value encodes, decodes back equal, and encodes the same way again.
func FuzzDecode(f *testing.F) {
	addSeeds(f)
	f.Fuzz(func(t *testing.T, data []byte) {
		ext, scanErr := Scan(data)
		v, n, err := Decode(data)
		switch {
		case scanErr != nil:
			if !errors.Is(scanErr, ErrCorrupt) || !errors.Is(err, ErrCorrupt) {
				t.Fatalf("scan %v, decode %v", scanErr, err)
			}
			return
		case ext.Len == 0:
			if !errors.Is(err, ErrIncomplete) || ext.Need < 1 {
				t.Fatalf("incomplete %+v, decode %v", ext, err)
			}
			return
		}
		if err != nil || n != ext.Len || v.Kind == KindInvalid {
			t.Fatalf("scan %+v, decode %d %v", ext, n, err)
		}
		if c := countElems(v); c != ext.Values {
			t.Fatalf("Values %d, the value holds %d elements", ext.Values, c)
		}
		step := 1
		if ext.Len > 512 {
			step = ext.Len / 512
		}
		checkPrefixes(t, data, ext.Len, step)
		again, m, err := Decode(data[:ext.Len])
		if err != nil || m != n || !equal(again, v) {
			t.Fatalf("decoding the value alone differs: %d %v", m, err)
		}
		enc, err := AppendValue(nil, v)
		if err != nil {
			t.Fatalf("encoding a decoded value: %v", err)
		}
		back, k, err := Decode(enc)
		if err != nil || k != len(enc) || !equal(back, v) {
			t.Fatalf("round trip: %d of %d, %v", k, len(enc), err)
		}
		if enc2, err := AppendValue(nil, back); err != nil || string(enc2) != string(enc) {
			t.Fatalf("encoding is not stable: %v", err)
		}
	})
}

// countElems counts the elements inside v's aggregates, at every depth.
func countElems(v Value) int {
	n := len(v.Elems)
	for _, e := range v.Elems {
		n += countElems(e)
	}
	return n
}
