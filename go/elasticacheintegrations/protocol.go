// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"maps"
	"math"
	"net/http"
	"net/textproto"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"
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
	wire    []byte // the command to write: frame, or the request's room around its body
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
	c.wire = nil
	c.frame = keepPooled(c.frame)
	c.pairs = c.pairs[:0]
	c.names = keepPooled(c.names)
	c.scratch = keepPooled(c.scratch)
	commandPool.Put(c)
}

// keepPooled returns b emptied for reuse, or nil when it is too large to keep in the pool.
func keepPooled(b []byte) []byte {
	if cap(b) > maxPooledCommand {
		return nil
	}
	return b[:0]
}

// appendRequest writes the aws.request command for r into c.wire:
// [method, url, normalized headers, body, metadata].
func (c *serializedCommand) appendRequest(r *request) {
	if r.headers == nil && r.header != nil {
		c.normalizeHTTPHeaders(r.header, r.contentLength)
	} else {
		c.normalizeHeaders(r.headers)
	}
	hdrEnd := len(c.scratch)
	c.scratch = appendMetadata(c.scratch, r.metadata)
	hdr, meta := c.scratch[:hdrEnd], c.scratch[hdrEnd:]

	if r.room != nil && c.appendAround(r, hdr, meta) {
		return
	}
	n := resp.CommandLen(len(requestCommand), len(r.method), len(r.url), len(hdr), len(r.body), len(meta))
	f := appendRequestPrefix(slices.Grow(c.frame[:0], n), r, hdr)
	f = resp.AppendBulk(f, r.body)
	c.frame = resp.AppendBulk(f, meta)
	c.wire = c.frame
}

// appendAround writes the frame around r.body in r.room, so the body is not copied and the
// command is still one Write. It reports false when the frame does not fit.
func (c *serializedCommand) appendAround(r *request, hdr, meta []byte) bool {
	if len(r.body) == 0 || len(r.room) < frameRoom+len(r.body) || &r.room[frameRoom] != &r.body[0] {
		return false
	}
	n := resp.CommandLen(len(requestCommand), len(r.method), len(r.url), len(hdr), 0, len(meta))
	f := appendRequestPrefix(slices.Grow(c.frame[:0], n+16), r, hdr) // the body length takes up to 16 more digits
	f = append(f, '$')
	f = strconv.AppendInt(f, int64(len(r.body)), 10)
	f = append(f, "\r\n"...)
	head := len(f)
	f = append(f, "\r\n"...)
	c.frame = resp.AppendBulk(f, meta)
	start, end := frameRoom-head, frameRoom+len(r.body)
	if start < 0 || end+len(c.frame)-head > len(r.room) {
		return false
	}
	copy(r.room[start:], c.frame[:head])
	end += copy(r.room[end:], c.frame[head:])
	c.wire = r.room[start:end:end]
	return true
}

// appendRequestPrefix appends the aws.request frame up to the body: the array header, the
// command, the method, the URL and the header block.
func appendRequestPrefix(f []byte, r *request, hdr []byte) []byte {
	f = resp.AppendArrayHeader(f, 6)
	f = resp.AppendBulkString(f, requestCommand)
	f = resp.AppendBulkString(f, r.method)
	f = resp.AppendBulkString(f, r.url)
	return resp.AppendBulk(f, hdr)
}

// directBodyMin is the body size from which the body is read into a room with space for the
// frame around it. frameRoom and tailRoom are that space, before and after the body.
const (
	directBodyMin = 32 << 10
	frameRoom     = 1 << 10
	tailRoom      = 64
)

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
	slices.SortFunc(c.pairs, compareHeaderPair)
	for i, p := range c.pairs {
		if i > 0 {
			c.scratch = append(c.scratch, '\n')
		}
		c.scratch = append(c.scratch, p.name...)
		c.scratch = append(c.scratch, ':')
		c.scratch = append(c.scratch, p.value...)
	}
}

// normalizeHTTPHeaders writes what normalizeHeaders writes for requestHeaders(h, n), without
// building the map: pairs with the same lowercased name become one line, values in byte order.
func (c *serializedCommand) normalizeHTTPHeaders(h http.Header, n int64) {
	c.scratch = c.scratch[:0]
	size := len("content-length")
	for name := range h {
		size += lowerLen(trimJava(name))
	}
	c.names = slices.Grow(c.names[:0], size)
	c.pairs = slices.Grow(c.pairs[:0], len(h)+1)
	for name, values := range h {
		if len(values) == 0 {
			continue
		}
		start := len(c.names)
		c.names = appendLower(c.names, trimJava(name))
		lower := c.names[start:len(c.names):len(c.names)]
		if excludedHeaders[string(lower)] || string(lower) == "content-length" {
			c.names = c.names[:start]
			continue
		}
		v := values[0]
		if len(values) > 1 {
			v = strings.Join(values, ", ")
		}
		c.pairs = append(c.pairs, headerPair{name: lower, value: trimJava(v)})
	}
	start := len(c.names)
	c.names = append(c.names, "content-length"...)
	c.pairs = append(c.pairs, headerPair{name: c.names[start:len(c.names):len(c.names)], value: strconv.FormatInt(n, 10)})
	total := 0
	for _, p := range c.pairs {
		total += len(p.name) + len(p.value) + 2
	}
	c.scratch = slices.Grow(c.scratch, total+64) // the metadata follows
	slices.SortFunc(c.pairs, compareHeaderPair)
	value := 0 // where the current line's value starts
	for i, p := range c.pairs {
		if i > 0 && bytes.Equal(p.name, c.pairs[i-1].name) {
			c.scratch = append(c.scratch, ", "...)
		} else {
			if i > 0 {
				c.scratch = append(trimRight(c.scratch, value), '\n')
			}
			c.scratch = append(c.scratch, p.name...)
			c.scratch = append(c.scratch, ':')
			value = len(c.scratch)
		}
		c.scratch = append(c.scratch, p.value...)
	}
	c.scratch = trimRight(c.scratch, value)
}

// compareHeaderPair orders header pairs by name, then value, in byte order.
func compareHeaderPair(a, b headerPair) int {
	if n := bytes.Compare(a.name, b.name); n != 0 {
		return n
	}
	return strings.Compare(a.value, b.value)
}

// trimRight drops the bytes <= ' ' at the end of b, down to b[from], as trimJava does to a
// joined value.
func trimRight(b []byte, from int) []byte {
	for len(b) > from && b[len(b)-1] <= ' ' {
		b = b[:len(b)-1]
	}
	return b
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
		return appendJSONFloat(dst, v, 64), nil
	case float32:
		return appendJSONFloat(dst, float64(v), 32), nil
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
		// Unreachable: copyOverrides produces only the kinds above.
		return nil, &ConfigError{Option: "CachePolicyOverrides", Msg: fmt.Sprintf("holds a %T, which cannot be written as JSON", v)}
	}
}

// appendJSONFloat writes a finite float as encoding/json does: plain decimal, so a whole number
// below 1e21 is an integer, and exponent form only below 1e-6 or from 1e21.
func appendJSONFloat(dst []byte, f float64, bits int) []byte {
	fmtByte := byte('f')
	if abs := math.Abs(f); abs != 0 {
		if bits == 64 && (abs < 1e-6 || abs >= 1e21) ||
			bits == 32 && (float32(abs) < 1e-6 || float32(abs) >= 1e21) {
			fmtByte = 'e'
		}
	}
	dst = strconv.AppendFloat(dst, f, fmtByte, -1, bits)
	if fmtByte == 'e' {
		// e-09 to e-9
		if n := len(dst); n >= 4 && dst[n-4] == 'e' && dst[n-3] == '-' && dst[n-2] == '0' {
			dst[n-2] = dst[n-1]
			dst = dst[:n-1]
		}
	}
	return dst
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

const (
	responsePushKind  = "aws.response"
	configInvalidCode = "CONFIG_INVALID"
	okReply           = "OK"
)

// serverErrorKind is what a -CODE reply to aws.request means to the session.
type serverErrorKind uint8

const (
	serverRejected serverErrorKind = iota // PARSE_ERROR, UNSUPPORTED, UNIMPLEMENTED, REGION_MISMATCH, any other code
	serverOrigin                          // ORIGIN_ERROR, INTERNAL_ERROR: DynamoDB may answer a retry
	serverAuth                            // AUTH_ERROR: drop the connection, send the request to DynamoDB
	serverDrop                            // MOVED, NOAUTH: the engine refused it; drop the connection
)

// serverError is a -CODE reply, split at the first space.
type serverError struct {
	code    string
	message string
}

// newServerError splits the error reply v, each part capped by serverText.
func newServerError(v resp.Value) serverError {
	return serverError{code: serverText(v.ErrorCode(), maxServerCode), message: serverText(v.ErrorMessage(), maxServerMessage)}
}

func (e serverError) kind() serverErrorKind {
	switch e.code {
	case "ORIGIN_ERROR", "INTERNAL_ERROR":
		return serverOrigin
	case "AUTH_ERROR":
		return serverAuth
	case "MOVED", "NOAUTH":
		return serverDrop
	default:
		return serverRejected
	}
}

// err is the error a request ends with when the session returns the reply as is.
func (e serverError) err() error {
	if e.kind() == serverOrigin {
		return &OriginError{Code: e.code, ServerMessage: e.message}
	}
	return &RejectedError{Code: e.code, ServerMessage: e.message}
}

// requestReply is a parsed aws.request reply: a served response or a server error.
type requestReply struct {
	response response
	failed   bool        // the cache answered with a RESP error
	server   serverError // when failed
}

// parseRequestReply reads the reply to aws.request, or the reply an aws.response push
// carries: a RESP error, or [status, headers, body]. A reply of any other shape is a
// *ProtocolError; the stream itself is still in step.
func parseRequestReply(v resp.Value) (requestReply, error) {
	switch v.Kind {
	case resp.KindError:
		return requestReply{failed: true, server: newServerError(v)}, nil
	case resp.KindArray:
	default:
		return requestReply{}, &ProtocolError{Msg: "aws.request reply is " + v.String() + ", not an array"}
	}
	if len(v.Elems) != 3 {
		return requestReply{}, &ProtocolError{Msg: "aws.request reply has " + strconv.Itoa(len(v.Elems)) + " elements, not 3"}
	}
	headers, body := v.Elems[1], v.Elems[2]
	if headers.Kind != resp.KindBulkString || body.Kind != resp.KindBulkString {
		return requestReply{}, &ProtocolError{Msg: "aws.request reply has headers " + headers.Kind.String() +
			" and body " + body.Kind.String() + ", not bulk strings"}
	}
	status, err := parseStatus(v.Elems[0])
	if err != nil {
		return requestReply{}, err
	}
	h, err := parseResponseHeaders(headers.Bytes)
	if err != nil {
		return requestReply{}, err
	}
	return requestReply{response: response{statusCode: status, headers: h, body: body.Bytes}}, nil
}

// parseStatus reads an integer status, or a string one as Java's Integer.parseInt after
// trim, and checks it is in 100-599.
func parseStatus(v resp.Value) (int, error) {
	var n int64
	switch v.Kind {
	case resp.KindInteger:
		n = v.Int
	case resp.KindBulkString, resp.KindSimpleString:
		if !utf8.Valid(v.Bytes) {
			return 0, &ProtocolError{Msg: "invalid UTF-8 in the response status"}
		}
		var err error
		if n, err = strconv.ParseInt(trimJava(string(v.Bytes)), 10, 32); err != nil {
			return 0, &ProtocolError{Msg: "the response status is not an integer"}
		}
	default:
		return 0, &ProtocolError{Msg: "the response status is " + v.Kind.String()}
	}
	if n < 100 || n > 599 {
		return 0, &ProtocolError{Msg: "response status " + strconv.FormatInt(n, 10) + " is out of range (100-599)"}
	}
	return int(n), nil
}

// parseResponseHeaders reads LF-separated name:value lines as Java does (split at the first
// colon, trim both, skip lines without one, the last duplicate wins) into the header the SDK
// reads: canonical keys, no Content-Length or Transfer-Encoding (the body sets the length),
// and Age: 0 unless the cache sent a non-empty Age, so the SDK learns no clock skew from a
// replayed Date.
func parseResponseHeaders(b []byte) (http.Header, error) {
	if len(b) > maxHeaderBytes {
		return nil, &ProtocolError{Msg: "the response headers are longer than " + strconv.Itoa(maxHeaderBytes) + " bytes"}
	}
	lines := bytes.Count(b, []byte{'\n'}) + 1
	if lines > maxHeaderLines {
		return nil, &ProtocolError{Msg: "the response headers have more than " + strconv.Itoa(maxHeaderLines) + " lines"}
	}
	if !utf8.Valid(b) {
		return nil, &ProtocolError{Msg: "invalid UTF-8 in the response headers"}
	}
	// Bounded, so empty or colon-free lines cost no memory.
	hint := min(lines, maxHeaderHint) + 1
	h := make(http.Header, hint)
	values := make([]string, 0, hint) // backs every value, so a header costs no allocation of its own
	set := func(name, value string) {
		values = append(values, value)
		h[name] = values[len(values)-1 : len(values) : len(values)]
	}
	block := string(b) // names and values are substrings of one string
	for len(block) > 0 {
		line, rest, _ := strings.Cut(block, "\n")
		block = rest
		name, value, ok := strings.Cut(line, ":")
		if !ok {
			continue
		}
		name = canonicalHeaderKey(trimJava(name))
		if name == "Content-Length" || name == "Transfer-Encoding" {
			continue
		}
		set(name, trimJava(value))
	}
	if a := h["Age"]; len(a) == 0 || a[0] == "" { // the SDK reads an empty Age as none
		set("Age", "0")
	}
	return h, nil
}

const (
	// maxHeaderHint bounds the header count parseResponseHeaders sizes its map for.
	maxHeaderHint = 64
	// maxHeaderBytes and maxHeaderLines bound the header block of a served response. A
	// DynamoDB response has about ten headers; a block past either limit is a broken reply,
	// which would otherwise cost many times its size in memory.
	maxHeaderBytes = 64 << 10
	maxHeaderLines = 1024
)

// dynamoDBHeaders are DynamoDB response headers textproto does not intern, by the lowercase
// name the cache sends.
var dynamoDBHeaders = map[string]string{
	"x-amz-crc32":      "X-Amz-Crc32",
	"x-amzn-requestid": "X-Amzn-Requestid",
	"x-amzn-errortype": "X-Amzn-Errortype",
}

func canonicalHeaderKey(name string) string {
	if k, ok := dynamoDBHeaders[name]; ok {
		return k
	}
	return textproto.CanonicalMIMEHeaderKey(name)
}

// asyncResponseID reports whether v is a deferred request id: a simple string other than
// OK. The id must be valid UTF-8, or a deferred reply could resolve the wrong request.
func asyncResponseID(v resp.Value) (string, bool, error) {
	if v.Kind != resp.KindSimpleString || string(v.Bytes) == okReply {
		return "", false, nil
	}
	if !utf8.Valid(v.Bytes) {
		return "", false, &ProtocolError{Msg: "invalid UTF-8 in a deferred request id"}
	}
	return string(v.Bytes), true, nil
}

// parseResponsePush reads an aws.response push: [kind, id, reply]. Any other push is a
// *ProtocolError: the client cannot place it, so the stream cannot be trusted.
func parseResponsePush(v resp.Value) (string, resp.Value, error) {
	if v.Kind != resp.KindPush || len(v.Elems) == 0 ||
		(v.Elems[0].Kind != resp.KindBulkString && v.Elems[0].Kind != resp.KindSimpleString) {
		return "", resp.Value{}, &ProtocolError{Msg: "push without a string kind"}
	}
	if kind := v.Elems[0].Bytes; !utf8.Valid(kind) || string(kind) != responsePushKind {
		return "", resp.Value{}, &ProtocolError{Msg: "unknown push kind"}
	}
	if len(v.Elems) != 3 {
		return "", resp.Value{}, &ProtocolError{Msg: "aws.response push has " + strconv.Itoa(len(v.Elems)) + " elements, not 3"}
	}
	id := v.Elems[1]
	if id.Kind != resp.KindBulkString {
		return "", resp.Value{}, &ProtocolError{Msg: "aws.response push id is " + id.Kind.String() + ", not a bulk string"}
	}
	if !utf8.Valid(id.Bytes) {
		return "", resp.Value{}, &ProtocolError{Msg: "invalid UTF-8 in an aws.response push id"}
	}
	return string(id.Bytes), v.Elems[2], nil
}

// parseConfigReply reads the reply to aws.config: a JSON object in a bulk string. A null
// reply to the no-argument form (refresh) means the config is unchanged: nil, nil.
func parseConfigReply(v resp.Value, refresh bool) (*resolvedConfig, error) {
	switch v.Kind {
	case resp.KindError:
		e := newServerError(v)
		if e.code != configInvalidCode {
			return nil, &RejectedError{Code: e.code, ServerMessage: e.message}
		}
		return nil, &OverridesRejectedError{ServerMessage: e.message}
	case resp.KindNull:
		if refresh {
			return nil, nil
		}
		return nil, &ProtocolError{Msg: "aws.config reply is null"}
	case resp.KindBulkString:
	default:
		return nil, &ProtocolError{Msg: "aws.config reply is " + v.String() + ", not a bulk string"}
	}
	// encoding/json would replace invalid UTF-8 instead of refusing it.
	if !utf8.Valid(v.Bytes) {
		return nil, &ProtocolError{Msg: "invalid UTF-8 in the aws.config reply"}
	}
	dec := json.NewDecoder(bytes.NewReader(v.Bytes))
	dec.UseNumber()
	var raw map[string]any
	if err := dec.Decode(&raw); err != nil {
		return nil, &ProtocolError{Msg: "aws.config reply is not a JSON object", Err: err}
	}
	if raw == nil {
		return nil, &ProtocolError{Msg: "aws.config reply is not a JSON object"}
	}
	if _, err := dec.Token(); err != io.EOF {
		return nil, &ProtocolError{Msg: "aws.config reply has data after the JSON object"}
	}
	version, err := configVersion(raw["version"])
	if err != nil {
		return nil, err
	}
	interval, err := refreshInterval(raw["refresh_interval"])
	if err != nil {
		return nil, err
	}
	return &resolvedConfig{version: version, refreshInterval: interval, raw: raw}, nil
}

// configVersion is the negotiated version as text, as Java's String.valueOf; "" when absent.
func configVersion(v any) (string, error) {
	switch v := v.(type) {
	case nil:
		return "", nil
	case string:
		return v, nil
	case json.Number:
		return string(v), nil
	case bool:
		return strconv.FormatBool(v), nil
	default:
		return "", &ProtocolError{Msg: "aws.config version is not a string"}
	}
}

// refreshInterval reads refresh_interval: required, an integral JSON number (60.0 is not),
// positive and at most 2^31-1 seconds.
func refreshInterval(v any) (time.Duration, error) {
	if v == nil {
		return 0, &ProtocolError{Msg: "aws.config reply has no refresh_interval"}
	}
	num, ok := v.(json.Number)
	if !ok || strings.ContainsAny(string(num), ".eE") {
		return 0, &ProtocolError{Msg: "aws.config refresh_interval is not an integer"}
	}
	n, err := strconv.ParseInt(string(num), 10, 64)
	switch {
	case err == nil && n > 0 && n <= math.MaxInt32:
		return time.Duration(n) * time.Second, nil
	case n < 0 || err == nil && n == 0:
		return 0, &ProtocolError{Msg: "aws.config refresh_interval must be positive"}
	default:
		return 0, &ProtocolError{Msg: "aws.config refresh_interval exceeds 2147483647 seconds"}
	}
}
