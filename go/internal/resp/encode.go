// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package resp

import (
	"bytes"
	"errors"
	"slices"
	"strconv"
)

// ErrInvalidValue matches every error AppendValue returns.
var ErrInvalidValue = errors.New("resp: value cannot be encoded")

// AppendCommand appends args as a RESP array of bulk strings, growing dst once. A nil
// argument is an empty bulk string.
func AppendCommand(dst []byte, args ...[]byte) []byte {
	n := headerLen(len(args))
	for _, a := range args {
		n += bulkLen(len(a))
	}
	dst = slices.Grow(dst, n)
	dst = AppendArrayHeader(dst, len(args))
	for _, a := range args {
		dst = AppendBulk(dst, a)
	}
	return dst
}

// AppendCommandStrings is AppendCommand for string arguments.
func AppendCommandStrings(dst []byte, args ...string) []byte {
	n := headerLen(len(args))
	for _, a := range args {
		n += bulkLen(len(a))
	}
	dst = slices.Grow(dst, n)
	dst = AppendArrayHeader(dst, len(args))
	for _, a := range args {
		dst = AppendBulkString(dst, a)
	}
	return dst
}

// CommandLen is the length of the frame AppendCommand writes for bulk arguments of the
// given lengths.
func CommandLen(argLens ...int) int {
	n := headerLen(len(argLens))
	for _, l := range argLens {
		n += bulkLen(l)
	}
	return n
}

// AppendArrayHeader appends the header of an array of n elements.
func AppendArrayHeader(dst []byte, n int) []byte { return appendHeader(dst, '*', int64(n)) }

// AppendBulk appends b as a bulk string.
func AppendBulk(dst []byte, b []byte) []byte {
	dst = appendHeader(dst, '$', int64(len(b)))
	dst = append(dst, b...)
	return append(dst, '\r', '\n')
}

// AppendBulkString appends s as a bulk string.
func AppendBulkString(dst []byte, s string) []byte {
	dst = appendHeader(dst, '$', int64(len(s)))
	dst = append(dst, s...)
	return append(dst, '\r', '\n')
}

func appendHeader(dst []byte, typ byte, n int64) []byte {
	dst = append(dst, typ)
	dst = strconv.AppendInt(dst, n, 10)
	return append(dst, '\r', '\n')
}

// headerLen is the length of a header for a count or length n >= 0.
func headerLen(n int) int {
	d := 1
	for ; n >= 10; n /= 10 {
		d++
	}
	return 3 + d
}

func bulkLen(n int) int { return headerLen(n) + n + 2 }

// AppendValue appends v. It refuses what would not decode back to v: a simple string or
// error line containing CRLF, a map with an odd number of elements, an unknown kind, and
// nesting deeper than MaxDepth. On error dst is returned unchanged.
func AppendValue(dst []byte, v Value) ([]byte, error) {
	out, err := appendValue(dst, v, 1)
	if err != nil {
		return dst, err
	}
	return out, nil
}

var crlf = []byte("\r\n")

func appendValue(dst []byte, v Value, depth int) ([]byte, error) {
	switch v.Kind {
	case KindSimpleString, KindError:
		if bytes.Contains(v.Bytes, crlf) {
			return nil, invalid("line contains CRLF")
		}
		typ := byte('+')
		if v.Kind == KindError {
			typ = '-'
		}
		dst = append(dst, typ)
		dst = append(dst, v.Bytes...)
		return append(dst, '\r', '\n'), nil
	case KindInteger:
		return appendHeader(dst, ':', v.Int), nil
	case KindBulkString:
		return AppendBulk(dst, v.Bytes), nil
	case KindNull:
		return append(dst, '_', '\r', '\n'), nil
	case KindArray, KindPush, KindMap:
		if depth > MaxDepth {
			return nil, invalid("nesting deeper than " + strconv.Itoa(MaxDepth))
		}
		typ, n := byte('*'), len(v.Elems)
		switch v.Kind {
		case KindPush:
			typ = '>'
		case KindMap:
			if n%2 != 0 {
				return nil, invalid("map with an odd number of elements")
			}
			typ, n = '%', n/2
		}
		dst = appendHeader(dst, typ, int64(n))
		var err error
		for _, e := range v.Elems {
			if dst, err = appendValue(dst, e, depth+1); err != nil {
				return nil, err
			}
		}
		return dst, nil
	default:
		return nil, invalid("unknown kind " + v.Kind.String())
	}
}

func invalid(msg string) error { return &encodeError{msg} }

type encodeError struct{ msg string }

func (e *encodeError) Error() string        { return ErrInvalidValue.Error() + ": " + e.msg }
func (e *encodeError) Is(target error) bool { return target == ErrInvalidValue }

// SimpleValue is a simple string.
func SimpleValue(s string) Value { return Value{Kind: KindSimpleString, Bytes: []byte(s)} }

// ErrorValue is an error reply "code message", or "code" when message is empty.
func ErrorValue(code, message string) Value {
	line := code
	if message != "" {
		line += " " + message
	}
	return Value{Kind: KindError, Bytes: []byte(line)}
}

// IntValue is an integer.
func IntValue(n int64) Value { return Value{Kind: KindInteger, Int: n} }

// BulkValue is a bulk string holding b, not a copy.
func BulkValue(b []byte) Value { return Value{Kind: KindBulkString, Bytes: b} }

// BulkStringValue is a bulk string holding s.
func BulkStringValue(s string) Value { return Value{Kind: KindBulkString, Bytes: []byte(s)} }

// ArrayValue is an array of elems.
func ArrayValue(elems ...Value) Value { return Value{Kind: KindArray, Elems: elems} }

// MapValue is a map of alternating keys and values.
func MapValue(keysAndValues ...Value) Value { return Value{Kind: KindMap, Elems: keysAndValues} }

// PushValue is a push of elems.
func PushValue(elems ...Value) Value { return Value{Kind: KindPush, Elems: elems} }

// NullValue is the RESP3 null.
func NullValue() Value { return Value{Kind: KindNull} }
