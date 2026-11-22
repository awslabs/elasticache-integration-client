// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"bytes"
	"encoding/json"
	"fmt"
	"maps"
	"slices"
	"strconv"
	"strings"
	"sync"
	"unicode/utf16"
	"unicode/utf8"

	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
)

const (
	configCommand   = "aws.config"
	requestCommand  = "aws.request"
	protocolVersion = "1.0" // sent in every aws.config overrides payload
	versionKey      = "version"
)

// maxPooledCommand is the largest buffer a serializedCommand keeps for reuse.
const maxPooledCommand = 1 << 20

// serializedCommand is a command frame ready for one Write. A request frame reuses every
// buffer, so encoding a request allocates nothing.
type serializedCommand struct {
	frame   []byte
	pairs   []headerPair // normalizeHeaders scratch
	names   []byte       // lowercased header names
	scratch []byte       // the normalized header block, then the metadata
}

type headerPair struct {
	name  []byte // into serializedCommand.names
	value string
}

var commandPool = sync.Pool{New: func() any { return new(serializedCommand) }}

func newSerializedCommand() *serializedCommand { return commandPool.Get().(*serializedCommand) }

// release returns c to the pool. c and its frame must not be used afterwards.
func (c *serializedCommand) release() {
	clear(c.pairs[:cap(c.pairs)]) // the values point at request strings
	c.frame = keep(c.frame)
	c.pairs = c.pairs[:0]
	c.names = keep(c.names)
	c.scratch = keep(c.scratch)
	commandPool.Put(c)
}

func keep(b []byte) []byte {
	if cap(b) > maxPooledCommand {
		return nil
	}
	return b[:0]
}

// appendRequest writes the aws.request frame for r into c.frame:
// [method, url, normalized headers, body, metadata].
func (c *serializedCommand) appendRequest(r *request) {
	c.normalizeHeaders(r.headers)
	headers := len(c.scratch)
	c.scratch = appendMetadata(c.scratch, r.metadata)
	hdr, meta := c.scratch[:headers], c.scratch[headers:]

	n := resp.CommandLen(len(requestCommand), len(r.method), len(r.url), len(hdr), len(r.body), len(meta))
	f := slices.Grow(c.frame[:0], n)
	f = resp.AppendArrayHeader(f, 6)
	f = resp.AppendBulkString(f, requestCommand)
	f = resp.AppendBulkString(f, r.method)
	f = resp.AppendBulkString(f, r.url)
	f = resp.AppendBulk(f, hdr)
	f = resp.AppendBulk(f, r.body)
	c.frame = resp.AppendBulk(f, meta)
}

// normalizeHeaders writes the header block into c.scratch: names lowercased and trimmed,
// excluded names dropped, values trimmed, sorted by name then value in byte order, which is
// code point order, and joined as name:value lines with LF. Two names that lowercase alike
// are both kept.
func (c *serializedCommand) normalizeHeaders(h map[string]string) {
	c.scratch = c.scratch[:0]
	size := 0
	for name := range h {
		size += lowerLen(trimJava(name))
	}
	c.names = slices.Grow(c.names[:0], size) // never grows below, so the pairs stay valid
	c.pairs = c.pairs[:0]
	for name, value := range h {
		start := len(c.names)
		c.names = appendLower(c.names, trimJava(name))
		lower := c.names[start:len(c.names):len(c.names)]
		if excludedHeaders[string(lower)] {
			c.names = c.names[:start]
			continue
		}
		c.pairs = append(c.pairs, headerPair{name: lower, value: trimJava(value)})
	}
	slices.SortFunc(c.pairs, func(a, b headerPair) int {
		if n := bytes.Compare(a.name, b.name); n != 0 {
			return n
		}
		return strings.Compare(a.value, b.value)
	})
	for i, p := range c.pairs {
		if i > 0 {
			c.scratch = append(c.scratch, '\n')
		}
		c.scratch = append(c.scratch, p.name...)
		c.scratch = append(c.scratch, ':')
		c.scratch = append(c.scratch, p.value...)
	}
}

// trimJava trims every leading and trailing byte <= ' ', as Java's String.trim.
func trimJava(s string) string {
	i, j := 0, len(s)
	for i < j && s[i] <= ' ' {
		i++
	}
	for j > i && s[j-1] <= ' ' {
		j--
	}
	return s[i:j]
}

func isASCII(s string) bool {
	for i := 0; i < len(s); i++ {
		if s[i] >= utf8.RuneSelf {
			return false
		}
	}
	return true
}

func lowerLen(s string) int {
	if isASCII(s) {
		return len(s)
	}
	return len(strings.ToLower(s))
}

func appendLower(dst []byte, s string) []byte {
	if !isASCII(s) {
		return append(dst, strings.ToLower(s)...)
	}
	for i := 0; i < len(s); i++ {
		b := s[i]
		if 'A' <= b && b <= 'Z' {
			b += 'a' - 'A'
		}
		dst = append(dst, b)
	}
	return dst
}

// appendMetadata appends service=<service>&op=<operation>.
func appendMetadata(dst []byte, m requestMetadata) []byte {
	dst = append(dst, "service="...)
	dst = append(dst, m.service...)
	dst = append(dst, "&op="...)
	return append(dst, m.operation...)
}

// appendConfig appends aws.config with the overrides payload {"version":"1.0",...}. Nil
// overrides send {"version":"1.0"}, which bring-up always sends.
func appendConfig(dst []byte, overrides map[string]any) ([]byte, error) {
	return appendConfigFields(dst, sortedFields(overrides))
}

// appendConfigRefresh appends the no-argument aws.config: overrides unchanged since the last
// send on this connection.
func appendConfigRefresh(dst []byte) []byte {
	return resp.AppendCommandStrings(dst, configCommand)
}

func appendConfigFields(dst []byte, fields jsonObject) ([]byte, error) {
	payload, err := appendOverridesJSON(nil, fields)
	if err != nil {
		return dst, err
	}
	dst = resp.AppendArrayHeader(dst, 2)
	dst = resp.AppendBulkString(dst, configCommand)
	return resp.AppendBulk(dst, payload), nil
}

// jsonField is one member of a jsonObject.
type jsonField struct {
	key   string
	value any
}

// jsonObject is a JSON object written in the order of its fields. A map[string]any is
// written with its keys in byte order.
type jsonObject []jsonField

func sortedFields(m map[string]any) jsonObject {
	out := make(jsonObject, 0, len(m))
	for _, k := range slices.Sorted(maps.Keys(m)) {
		out = append(out, jsonField{k, m[k]})
	}
	return out
}

// appendOverridesJSON appends the overrides payload: "version" first, "1.0" unless the
// fields carry their own, then every other field.
func appendOverridesJSON(dst []byte, fields jsonObject) ([]byte, error) {
	var version any = protocolVersion
	for _, f := range fields {
		if f.key == versionKey {
			version = f.value
		}
	}
	dst = append(dst, `{"version":`...)
	dst, err := appendJSONValue(dst, version)
	if err != nil {
		return nil, err
	}
	for _, f := range fields {
		if f.key == versionKey {
			continue
		}
		dst = append(dst, ',')
		dst = appendJSONString(dst, f.key)
		dst = append(dst, ':')
		if dst, err = appendJSONValue(dst, f.value); err != nil {
			return nil, err
		}
	}
	return append(dst, '}'), nil
}

// appendJSONValue writes the value kinds the overrides copy produces.
func appendJSONValue(dst []byte, v any) ([]byte, error) {
	var err error
	switch v := v.(type) {
	case nil:
		return append(dst, "null"...), nil
	case string:
		return appendJSONString(dst, v), nil
	case bool:
		return strconv.AppendBool(dst, v), nil
	case int64:
		return strconv.AppendInt(dst, v, 10), nil
	case uint64:
		return strconv.AppendUint(dst, v, 10), nil
	case float64:
		return strconv.AppendFloat(dst, v, 'g', -1, 64), nil
	case float32:
		return strconv.AppendFloat(dst, float64(v), 'g', -1, 32), nil
	case json.Number:
		return append(dst, v...), nil
	case []any:
		dst = append(dst, '[')
		for i, e := range v {
			if i > 0 {
				dst = append(dst, ',')
			}
			if dst, err = appendJSONValue(dst, e); err != nil {
				return nil, err
			}
		}
		return append(dst, ']'), nil
	case map[string]any:
		return appendJSONObject(dst, sortedFields(v))
	case jsonObject:
		return appendJSONObject(dst, v)
	default:
		return nil, &ProtocolError{Msg: fmt.Sprintf("cannot write a %T override as JSON", v)}
	}
}

func appendJSONObject(dst []byte, fields jsonObject) ([]byte, error) {
	var err error
	dst = append(dst, '{')
	for i, f := range fields {
		if i > 0 {
			dst = append(dst, ',')
		}
		dst = appendJSONString(dst, f.key)
		dst = append(dst, ':')
		if dst, err = appendJSONValue(dst, f.value); err != nil {
			return nil, err
		}
	}
	return append(dst, '}'), nil
}

const hexDigits = "0123456789abcdef"

// appendJSONString writes s as Java's overrides writer does: the short escapes for " \ \b \f
// \n \r \t, other control characters, DEL and every character above U+007F as lowercase
// \uXXXX, a non-BMP character as a surrogate pair. Invalid UTF-8 is written as \ufffd.
func appendJSONString(dst []byte, s string) []byte {
	dst = append(dst, '"')
	for i := 0; i < len(s); {
		b := s[i]
		if b < utf8.RuneSelf {
			i++
			switch b {
			case '"', '\\':
				dst = append(dst, '\\', b)
			case '\b':
				dst = append(dst, '\\', 'b')
			case '\f':
				dst = append(dst, '\\', 'f')
			case '\n':
				dst = append(dst, '\\', 'n')
			case '\r':
				dst = append(dst, '\\', 'r')
			case '\t':
				dst = append(dst, '\\', 't')
			default:
				if b < 0x20 || b == 0x7f {
					dst = appendUnicodeEscape(dst, rune(b))
				} else {
					dst = append(dst, b)
				}
			}
			continue
		}
		r, size := utf8.DecodeRuneInString(s[i:])
		i += size
		if r1, r2 := utf16.EncodeRune(r); r1 != utf8.RuneError {
			dst = appendUnicodeEscape(dst, r1)
			r = r2
		}
		dst = appendUnicodeEscape(dst, r)
	}
	return append(dst, '"')
}

func appendUnicodeEscape(dst []byte, r rune) []byte {
	return append(dst, '\\', 'u', hexDigits[r>>12&0xf], hexDigits[r>>8&0xf], hexDigits[r>>4&0xf], hexDigits[r&0xf])
}
