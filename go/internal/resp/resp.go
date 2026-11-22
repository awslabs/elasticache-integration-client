// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

// Package resp is the client's RESP3 codec: the value types the Java client keeps, a decoder
// that tells how many bytes an unfinished value still needs, a command encoder, and a
// buffered frame reader. It depends on nothing else in the module.
package resp

import (
	"bytes"
	"errors"
	"math"
	"strconv"
	"strings"
)

// MaxDepth is the deepest aggregate nesting the decoder accepts. The top-level value is
// level 1.
const MaxDepth = 100

// Kind is the RESP type of a Value.
type Kind uint8

// The decoded types. Every other RESP3 type byte is a corrupt stream.
const (
	KindInvalid      Kind = iota
	KindSimpleString      // +
	KindError             // -
	KindInteger           // :
	KindBulkString        // $
	KindArray             // *
	KindMap               // %
	KindPush              // >
	KindNull              // _, and the RESP2 nulls $-1 and *-1
)

var kindNames = [...]string{"Invalid", "SimpleString", "Error", "Integer", "BulkString",
	"Array", "Map", "Push", "Null"}

func (k Kind) String() string {
	if int(k) < len(kindNames) {
		return kindNames[k]
	}
	return "Kind(" + strconv.Itoa(int(k)) + ")"
}

// Value is one decoded RESP value. A decoded value's slices alias the bytes it was decoded
// from.
type Value struct {
	Kind  Kind
	Bytes []byte  // SimpleString, BulkString; Error: the whole line, code first
	Int   int64   // Integer
	Elems []Value // Array, Push; Map: keys and values alternate
}

// String names the kind and size only, so a value never prints its payload.
func (v Value) String() string {
	switch v.Kind {
	case KindInteger:
		return "Integer(" + strconv.FormatInt(v.Int, 10) + ")"
	case KindSimpleString, KindError, KindBulkString:
		return v.Kind.String() + "(" + strconv.Itoa(len(v.Bytes)) + " bytes)"
	case KindArray, KindPush:
		return v.Kind.String() + "(" + strconv.Itoa(len(v.Elems)) + " elements)"
	case KindMap:
		return "Map(" + strconv.Itoa(len(v.Elems)/2) + " entries)"
	default:
		return v.Kind.String()
	}
}

// ErrorCode is the text of an Error up to its first space, as the Java client splits it.
func (v Value) ErrorCode() string {
	code, _, _ := bytes.Cut(v.Bytes, []byte{' '})
	return strings.ToValidUTF8(string(code), "\uFFFD")
}

// ErrorMessage is the text of an Error after its first space, or "".
func (v Value) ErrorMessage() string {
	_, msg, _ := bytes.Cut(v.Bytes, []byte{' '})
	return strings.ToValidUTF8(string(msg), "\uFFFD")
}

// MapGet returns the value of the last entry of a Map whose key is the bulk string key.
// Like the Java client, it does not match a simple-string key.
func (v Value) MapGet(key string) (Value, bool) {
	if v.Kind != KindMap {
		return Value{}, false
	}
	for i := len(v.Elems) - 2; i >= 0; i -= 2 {
		if k := v.Elems[i]; k.Kind == KindBulkString && string(k.Bytes) == key {
			return v.Elems[i+1], true
		}
	}
	return Value{}, false
}

// ErrCorrupt matches every error that means the bytes can never form a valid value.
var ErrCorrupt = errors.New("resp: corrupt stream")

// ErrIncomplete is returned by Decode when the value has not fully arrived.
var ErrIncomplete = errors.New("resp: incomplete value")

// CorruptError says why a stream is corrupt. It never quotes the stream's bytes.
type CorruptError struct{ Msg string }

func (e *CorruptError) Error() string        { return "resp: corrupt stream: " + e.Msg }
func (e *CorruptError) Is(target error) bool { return target == ErrCorrupt }

func corrupt(msg string) error { return &CorruptError{Msg: msg} }

// Extent is what Scan learns about the first value of a buffer.
type Extent struct {
	Len    int  // length of the complete value; 0 while it is incomplete
	Need   int  // incomplete: the fewest bytes still missing, at least 1
	Exact  bool // incomplete: Need is exactly the bytes up to the value's end
	Values int  // complete: elements inside aggregates, at every depth
}

// minValue is the shortest encoding of any value ("_\r\n", "+\r\n").
const minValue = 3

// maxNumberLine is the longest integer line: a sign and 19 digits.
const maxNumberLine = 20

// Scan finds the extent of the first value in b without building it. It returns a
// *CorruptError when the bytes can never form a valid value. The work is proportional to the
// number of values and lines, not to the size of bulk payloads.
func Scan(b []byte) (Extent, error) {
	// left[i] is how many elements the aggregate open at level i+1 still expects.
	var left [MaxDepth]int64
	depth := 0
	pos := 0
	values := 0
	for {
		if depth > 0 {
			left[depth-1]--
		}
		if pos == len(b) {
			return incomplete(minValue, false, &left, depth), nil
		}
		typ := b[pos]
		switch typ {
		case '+', '-', ':', '$', '*', '%', '>', '_':
		default:
			return Extent{}, corrupt("unknown type byte 0x" + strconv.FormatUint(uint64(typ), 16))
		}
		end, more := lineEnd(b, pos+1)
		if end < 0 {
			// A number line and the CR of its CRLF.
			if typ != '+' && typ != '-' && len(b)-pos-1 > maxNumberLine+1 {
				return Extent{}, corrupt("number line too long")
			}
			return incomplete(int64(more), false, &left, depth), nil
		}
		line := b[pos+1 : end]
		pos = end + 2
		switch typ {
		case ':':
			if _, ok := parseInt(line, 64); !ok {
				return Extent{}, corrupt("invalid integer")
			}
		case '_':
			if len(line) != 0 {
				return Extent{}, corrupt("null with a non-empty header")
			}
		case '$':
			n, ok := parseInt(line, 32)
			if !ok || n < -1 {
				return Extent{}, corrupt("invalid bulk string length")
			}
			if n >= 0 {
				if have := int64(len(b) - pos); have < n+2 {
					return incomplete(n+2-have, true, &left, depth), nil
				}
				pos += int(n)
				if b[pos] != '\r' || b[pos+1] != '\n' {
					return Extent{}, corrupt("bulk string not terminated by CRLF")
				}
				pos += 2
			}
		case '*', '%', '>':
			if depth+1 > MaxDepth {
				return Extent{}, corrupt("nesting deeper than " + strconv.Itoa(MaxDepth))
			}
			n, ok := parseInt(line, 32)
			if !ok || n < -1 || (n == -1 && typ != '*') {
				return Extent{}, corrupt("invalid aggregate count")
			}
			if typ == '%' {
				n *= 2
			}
			if n > 0 {
				values += int(n)
				left[depth] = n
				depth++
				continue
			}
		}
		for depth > 0 && left[depth-1] == 0 {
			depth--
		}
		if depth == 0 {
			return Extent{Len: pos, Values: values}, nil
		}
	}
}

// incomplete adds a minimal encoding for every element still expected at open levels to
// the bytes the current value lacks.
func incomplete(need int64, exact bool, left *[MaxDepth]int64, depth int) Extent {
	total := need
	for i := range depth {
		if left[i] > 0 {
			total += minValue * left[i]
			exact = false
		}
	}
	return Extent{Need: int(min(total, maxNeed)), Exact: exact}
}

// maxNeed keeps Need representable in an int on every platform; any frame cap is far below it.
const maxNeed = min(1<<40, math.MaxInt)

// lineEnd returns the index of the first CRLF at or after from, or -1 and the fewest bytes
// that could complete the line.
func lineEnd(b []byte, from int) (int, int) {
	for i := from; i < len(b); {
		j := bytes.IndexByte(b[i:], '\r')
		if j < 0 {
			break
		}
		i += j
		if i+1 == len(b) {
			return -1, 1
		}
		if b[i+1] == '\n' {
			return i, 0
		}
		i++
	}
	return -1, 2
}

// parseInt parses an optional sign and decimal digits that fit in bits, as Java's
// Long.parseLong and Integer.parseInt do, without allocating.
func parseInt(s []byte, bits uint) (int64, bool) {
	neg := false
	if len(s) > 0 && (s[0] == '-' || s[0] == '+') {
		neg = s[0] == '-'
		s = s[1:]
	}
	if len(s) == 0 || len(s) > 19 {
		return 0, false
	}
	limit := uint64(1) << (bits - 1)
	var n uint64
	for _, c := range s {
		if c < '0' || c > '9' {
			return 0, false
		}
		n = n*10 + uint64(c-'0')
	}
	if neg {
		if n > limit {
			return 0, false
		}
		return -int64(n), true
	}
	if n >= limit {
		return 0, false
	}
	return int64(n), true
}

// Decode decodes the first value in b. The value's slices alias b. It returns ErrIncomplete
// when the value has not fully arrived, and a *CorruptError when it never can.
func Decode(b []byte) (Value, int, error) {
	ext, err := Scan(b)
	if err != nil {
		return Value{}, 0, err
	}
	if ext.Len == 0 {
		return Value{}, 0, ErrIncomplete
	}
	return build(b[:ext.Len], ext.Values), ext.Len, nil
}

// build decodes a frame that Scan found complete. Every aggregate's elements are carved out
// of one slice, so a frame costs one allocation however many aggregates it holds.
func build(frame []byte, values int) Value {
	var arena []Value
	if values > 0 {
		arena = make([]Value, values)
	}
	v, _, _ := buildValue(frame, 0, arena)
	return v
}

func buildValue(b []byte, pos int, arena []Value) (Value, int, []Value) {
	typ := b[pos]
	end, _ := lineEnd(b, pos+1)
	line := b[pos+1 : end : end]
	pos = end + 2
	switch typ {
	case '+':
		return Value{Kind: KindSimpleString, Bytes: line}, pos, arena
	case '-':
		return Value{Kind: KindError, Bytes: line}, pos, arena
	case ':':
		n, _ := parseInt(line, 64)
		return Value{Kind: KindInteger, Int: n}, pos, arena
	case '$':
		n, _ := parseInt(line, 32)
		if n < 0 {
			return Value{Kind: KindNull}, pos, arena
		}
		data := b[pos : pos+int(n) : pos+int(n)]
		return Value{Kind: KindBulkString, Bytes: data}, pos + int(n) + 2, arena
	case '*', '%', '>':
		n, _ := parseInt(line, 32)
		if n < 0 {
			return Value{Kind: KindNull}, pos, arena
		}
		kind := KindArray
		if typ == '%' {
			kind, n = KindMap, n*2
		} else if typ == '>' {
			kind = KindPush
		}
		elems := arena[:n:n]
		arena = arena[n:]
		for i := range elems {
			elems[i], pos, arena = buildValue(b, pos, arena)
		}
		return Value{Kind: kind, Elems: elems}, pos, arena
	default: // '_'
		return Value{Kind: KindNull}, pos, arena
	}
}
