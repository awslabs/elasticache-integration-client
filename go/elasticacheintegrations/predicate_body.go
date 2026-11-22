// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"math"
	"math/bits"
	"slices"
	"sync"
	"unicode/utf8"
)

// jsonDoc is a request body that scanJSON accepted. starts and ends hold, in document order,
// the offset of every object and array and the offset just past it, so a container is
// skipped with a binary search instead of a rescan.
type jsonDoc struct {
	b      []byte // the body without its BOM
	root   int    // offset of the top-level object
	starts []int32
	ends   []int32
}

// emptyDoc is the {} an empty body stands for. It is shared and never released.
var emptyDoc = &jsonDoc{b: []byte("{}"), starts: []int32{0}, ends: []int32{2}}

// maxPooledContainers bounds the index a pooled jsonDoc keeps, so one huge body does not
// pin its index in the pool.
const maxPooledContainers = 1 << 16

var jsonDocPool = sync.Pool{New: func() any { return new(jsonDoc) }}

var utf8BOM = []byte{0xEF, 0xBB, 0xBF}

// scanJSON reports whether body is one JSON object that predicates may read: RFC 8259
// grammar, valid UTF-8, nested at most maxBodyDepth levels. A leading UTF-8 BOM is skipped.
// The caller releases an accepted document.
func scanJSON(body []byte) (*jsonDoc, bool) {
	b := bytes.TrimPrefix(body, utf8BOM)
	if len(b) >= math.MaxInt32 {
		return nil, false
	}
	d := jsonDocPool.Get().(*jsonDoc)
	d.b, d.starts, d.ends = b, d.starts[:0], d.ends[:0]
	i := skipWS(b, 0)
	d.root = i
	if i < len(b) && b[i] == '{' {
		if end, ok := d.value(i, 1); ok && skipWS(b, end) == len(b) {
			return d, true
		}
	}
	d.release()
	return nil, false
}

func (d *jsonDoc) release() {
	if d == nil || d == emptyDoc {
		return
	}
	d.b = nil
	if cap(d.starts) <= maxPooledContainers {
		jsonDocPool.Put(d)
	}
}

func skipWS(b []byte, i int) int {
	for i < len(b) && (b[i] == ' ' || b[i] == '\n' || b[i] == '\r' || b[i] == '\t') {
		i++
	}
	return i
}

// value checks the value at b[i] and returns the offset just past it.
func (d *jsonDoc) value(i, depth int) (int, bool) {
	b := d.b
	switch c := b[i]; {
	case c == '{' || c == '[':
		if depth > maxBodyDepth {
			return 0, false
		}
		return d.container(i, depth)
	case c == '"':
		return scanString(b, i)
	case c == 't':
		return literal(b, i, "true")
	case c == 'f':
		return literal(b, i, "false")
	case c == 'n':
		return literal(b, i, "null")
	default:
		return scanNumber(b, i)
	}
}

func (d *jsonDoc) container(i, depth int) (int, bool) {
	b := d.b
	closer := byte('}')
	if b[i] == '[' {
		closer = ']'
	}
	slot := len(d.starts)
	d.starts = append(d.starts, int32(i))
	d.ends = append(d.ends, 0)
	i = skipWS(b, i+1)
	if i < len(b) && b[i] == closer {
		d.ends[slot] = int32(i + 1)
		return i + 1, true
	}
	for {
		if i >= len(b) {
			return 0, false
		}
		var ok bool
		if closer == '}' {
			if b[i] != '"' {
				return 0, false
			}
			if i, ok = scanString(b, i); !ok {
				return 0, false
			}
			if i = skipWS(b, i); i >= len(b) || b[i] != ':' {
				return 0, false
			}
			if i = skipWS(b, i+1); i >= len(b) {
				return 0, false
			}
		}
		if i, ok = d.value(i, depth+1); !ok {
			return 0, false
		}
		if i = skipWS(b, i); i >= len(b) {
			return 0, false
		}
		switch b[i] {
		case ',':
			i = skipWS(b, i+1)
		case closer:
			d.ends[slot] = int32(i + 1)
			return i + 1, true
		default:
			return 0, false
		}
	}
}

func literal(b []byte, i int, word string) (int, bool) {
	if bytes.HasPrefix(b[i:], []byte(word)) {
		return i + len(word), true
	}
	return 0, false
}

// scanNumber checks -?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)? at b[i].
func scanNumber(b []byte, i int) (int, bool) {
	if i < len(b) && b[i] == '-' {
		i++
	}
	switch {
	case i < len(b) && b[i] == '0':
		i++
	case i < len(b) && b[i] >= '1' && b[i] <= '9':
		i = digits(b, i)
	default:
		return 0, false
	}
	if i < len(b) && b[i] == '.' {
		j := digits(b, i+1)
		if j == i+1 {
			return 0, false
		}
		i = j
	}
	if i < len(b) && (b[i] == 'e' || b[i] == 'E') {
		i++
		if i < len(b) && (b[i] == '+' || b[i] == '-') {
			i++
		}
		j := digits(b, i)
		if j == i {
			return 0, false
		}
		i = j
	}
	return i, true
}

func digits(b []byte, i int) int {
	for i < len(b) && b[i] >= '0' && b[i] <= '9' {
		i++
	}
	return i
}

const (
	lsb = 0x0101010101010101
	msb = 0x8080808080808080
)

// stringStop marks, in the top bit of each byte, the bytes of v a string scan must look at:
// '"', '\\', a control byte or a non-ASCII byte. The lowest marked byte is always exact.
func stringStop(v uint64) uint64 {
	control := (v - 0x20*lsb) &^ v & msb
	quote := v ^ '"'*lsb
	quote = (quote - lsb) &^ quote & msb
	backslash := v ^ '\\'*lsb
	backslash = (backslash - lsb) &^ backslash & msb
	return control | quote | backslash | v&msb
}

// scanString checks the string that opens at b[i] and returns the offset just past it.
func scanString(b []byte, i int) (int, bool) {
	j := i + 1
	for {
		for j+8 <= len(b) {
			if m := stringStop(binary.LittleEndian.Uint64(b[j:])); m != 0 {
				j += bits.TrailingZeros64(m) / 8
				break
			}
			j += 8
		}
		if j >= len(b) {
			return 0, false
		}
		switch c := b[j]; {
		case c == '"':
			return j + 1, true
		case c == '\\':
			if j+1 >= len(b) {
				return 0, false
			}
			switch b[j+1] {
			case '"', '\\', '/', 'b', 'f', 'n', 'r', 't':
				j += 2
			case 'u':
				if j+6 > len(b) || !isHex(b[j+2]) || !isHex(b[j+3]) || !isHex(b[j+4]) || !isHex(b[j+5]) {
					return 0, false
				}
				j += 6
			default:
				return 0, false
			}
		case c < 0x20:
			return 0, false
		case c < utf8.RuneSelf:
			j++
		default:
			r, size := utf8.DecodeRune(b[j:])
			if r == utf8.RuneError && size == 1 {
				return 0, false
			}
			j += size
		}
	}
}

func isHex(c byte) bool {
	return c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F'
}

// end returns the offset just past the accepted value at pos.
func (d *jsonDoc) end(pos int) int {
	b := d.b
	switch b[pos] {
	case '{', '[':
		k, _ := slices.BinarySearch(d.starts, int32(pos))
		return int(d.ends[k])
	case '"':
		for j := pos + 1; ; {
			q := bytes.IndexByte(b[j:], '"') + j
			n := 0
			for k := q - 1; b[k] == '\\'; k-- {
				n++
			}
			if n%2 == 0 {
				return q + 1
			}
			j = q + 1
		}
	default:
		j := pos + 1
		for j < len(b) && b[j] != ',' && b[j] != ']' && b[j] != '}' && b[j] != ' ' &&
			b[j] != '\n' && b[j] != '\r' && b[j] != '\t' {
			j++
		}
		return j
	}
}

// jsonIter walks the members of an accepted object or the elements of an accepted array.
type jsonIter struct {
	d      *jsonDoc
	object bool
	i      int // the next member or element, or the closing bracket
}

func (d *jsonDoc) iter(pos int) jsonIter {
	return jsonIter{d: d, object: d.b[pos] == '{', i: skipWS(d.b, pos+1)}
}

// next returns the next member, its raw key with quotes (nil in an array) and the offset of
// its value. ok is false after the last one.
func (it *jsonIter) next() (key []byte, val int, ok bool) {
	b := it.d.b
	if c := b[it.i]; c == '}' || c == ']' {
		return nil, 0, false
	}
	val = it.i
	if it.object {
		keyEnd := it.d.end(val)
		key = b[val:keyEnd]
		val = skipWS(b, skipWS(b, keyEnd)+1)
	}
	j := skipWS(b, it.d.end(val))
	if b[j] == ',' {
		j = skipWS(b, j+1)
	}
	it.i = j
	return key, val, true
}

// equalString reports whether the raw JSON string (with quotes) decodes to s.
func equalString(raw []byte, s string) bool {
	inner := raw[1 : len(raw)-1]
	if bytes.IndexByte(inner, '\\') < 0 {
		return string(inner) == s
	}
	v, ok := decodeString(raw)
	return ok && v == s
}

// decodeString decodes a raw JSON string as encoding/json does for the cel-go view.
func decodeString(raw []byte) (string, bool) {
	inner := raw[1 : len(raw)-1]
	if bytes.IndexByte(inner, '\\') < 0 {
		return string(inner), true
	}
	var s string
	err := json.Unmarshal(raw, &s)
	return s, err == nil
}

// celBody decodes an accepted document for cel-go: objects as map[string]any, numbers as
// int64 when they are integers that fit, else float64.
func (d *jsonDoc) celBody() (map[string]any, bool) {
	if d == emptyDoc {
		return map[string]any{}, true
	}
	dec := json.NewDecoder(bytes.NewReader(d.b))
	dec.UseNumber()
	var v map[string]any
	if err := dec.Decode(&v); err != nil {
		return nil, false
	}
	out, err := celValue(v, 1)
	if err != nil {
		return nil, false
	}
	return out.(map[string]any), true
}
