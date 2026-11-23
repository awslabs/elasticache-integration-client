// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"bytes"
	"errors"
	"io"
	"net/http"
	"slices"
	"strconv"
	"strings"

	"github.com/aws/aws-sdk-go-v2/aws"
	awsmiddleware "github.com/aws/aws-sdk-go-v2/aws/middleware"
)

const (
	// maxRequestBody is the largest body sent to the cache; a larger one goes to DynamoDB.
	maxRequestBody = 16 << 20

	sdkService   = "dynamodb"
	sigV4Prefix  = "AWS4-HMAC-SHA256 "
	sigV4Request = "aws4_request"
)

// identity is the region the cache connection authenticates in.
type identity struct {
	region string
}

// requestBodyError reports that the body of an SDK request could not be read.
type requestBodyError struct {
	Err error
}

func (e *requestBodyError) Error() string {
	return format("cannot read the request body", "", e.Err)
}
func (e *requestBodyError) Unwrap() error        { return e.Err }
func (e *requestBodyError) Is(target error) bool { return target == ErrIntegrations }

// adaptRequest builds the cache request for a signed SDK request. routable is false, with req
// untouched, when the request must go to DynamoDB as it is. Once the body has been read, a
// non-nil error fails the call; req.Body is then spent.
func adaptRequest(req *http.Request, id identity) (r *request, routable bool, err error) {
	n := req.ContentLength
	if n < 0 || n > maxRequestBody || (n == 0 && req.Body != nil && req.Body != http.NoBody) {
		return nil, false, nil // unknown length, or too large to buffer
	}
	operation := operationName(req)
	if operation == "" {
		return nil, false, nil
	}
	keyID, region, ok := signedScope(req.Header)
	if !ok || !matchesIdentity(req.Context(), id.region, keyID, region) {
		return nil, false, nil // a per-call identity or region, or not SigV4-signed
	}
	body, room, err := readBody(req.Body, n)
	if err != nil {
		return nil, false, err
	}
	r = &request{
		method:        req.Method,
		url:           req.URL.String(),
		header:        req.Header,
		contentLength: n,
		body:          body,
		room:          room,
		metadata:      requestMetadata{service: sdkService, operation: operation},
	}
	if !trimmedNames(req.Header) {
		r.headers = requestHeaders(req.Header, n)
	}
	return r, true, nil
}

// trimmedNames reports whether no header name has leading or trailing bytes <= ' ', so the
// header block can be written from h without building the header map.
func trimmedNames(h http.Header) bool {
	for name := range h {
		if trimJava(name) != name {
			return false
		}
	}
	return true
}

// operationName is the SDK operation of req, from its context or else from X-Amz-Target.
func operationName(req *http.Request) string {
	if op := awsmiddleware.GetOperationName(req.Context()); op != "" {
		return op
	}
	target := req.Header.Values("X-Amz-Target")
	if len(target) != 1 {
		return ""
	}
	return trimJava(target[0][strings.LastIndexByte(target[0], '.')+1:])
}

// signedScope returns the access key id and region in the Credential scope of a SigV4
// Authorization header.
func signedScope(h http.Header) (keyID, region string, ok bool) {
	v := h.Values("Authorization")
	if len(v) != 1 {
		return "", "", false
	}
	rest, ok := strings.CutPrefix(v[0], sigV4Prefix)
	if !ok {
		return "", "", false
	}
	scope, ok := strings.CutPrefix(strings.TrimLeft(rest, " "), "Credential=")
	if !ok {
		return "", "", false
	}
	scope, _, _ = strings.Cut(scope, ",")
	keyID, scope, _ = strings.Cut(scope, "/")
	_, scope, _ = strings.Cut(scope, "/") // date
	region, scope, _ = strings.Cut(scope, "/")
	service, scope, _ := strings.Cut(scope, "/")
	if keyID == "" || region == "" || service != sdkService || scope != sigV4Request {
		return "", "", false
	}
	return keyID, region, true
}

// readBody reads exactly n bytes, and fails if body holds fewer or more. From directBodyMin,
// room is the buffer b was read into, with space for the frame around it.
func readBody(body io.Reader, n int64) (b, room []byte, err error) {
	if n == 0 {
		return nil, nil, nil
	}
	if body == nil {
		return nil, nil, &requestBodyError{Err: io.ErrUnexpectedEOF}
	}
	before, after := int64(0), int64(1) // the extra byte detects a body longer than n
	if n >= directBodyMin {
		before, after = frameRoom, tailRoom
	}
	buf := make([]byte, before+n+after)
	b = buf[before : before+n : before+n]
	if _, err := io.ReadFull(body, b); err != nil {
		if err == io.EOF {
			err = io.ErrUnexpectedEOF // n bytes were promised
		}
		return nil, nil, &requestBodyError{Err: err}
	}
	switch _, err := io.ReadFull(body, buf[before+n:before+n+1]); err {
	case io.EOF:
		if before > 0 {
			room = buf
		}
		return b, room, nil
	case nil:
		return nil, nil, &requestBodyError{Err: errBodyTooLong}
	default:
		return nil, nil, &requestBodyError{Err: err}
	}
}

var errBodyTooLong = errors.New("the body is longer than its Content-Length")

// requestHeaders lowercases the names and joins the values with ", " (Java), and adds
// content-length, which net/http keeps out of Header. Names that lowercase alike, possible only
// in a hand-built Header, have their values joined in byte order, so the result does not depend
// on map order.
func requestHeaders(h http.Header, contentLength int64) map[string]string {
	out := make(map[string]string, len(h)+1)
	var same map[string][]string
	for name, values := range h {
		if len(values) == 0 {
			continue
		}
		v := values[0]
		if len(values) > 1 {
			v = strings.Join(values, ", ")
		}
		v = trimJava(v)
		lower := lowerHeaderName(name)
		if prev, ok := out[lower]; ok {
			if same[lower] == nil {
				if same == nil {
					same = make(map[string][]string)
				}
				same[lower] = []string{prev}
			}
			same[lower] = append(same[lower], v)
			continue
		}
		out[lower] = v
	}
	for lower, vs := range same {
		slices.Sort(vs)
		out[lower] = strings.Join(vs, ", ")
	}
	out["content-length"] = strconv.FormatInt(contentLength, 10)
	return out
}

// lowerHeaderName lowercases a header name; the names the SDK sends cost no allocation.
func lowerHeaderName(name string) string {
	switch name {
	case "Accept-Encoding":
		return "accept-encoding"
	case "Amz-Sdk-Invocation-Id":
		return "amz-sdk-invocation-id"
	case "Amz-Sdk-Request":
		return "amz-sdk-request"
	case "Authorization":
		return "authorization"
	case "Content-Length":
		return "content-length"
	case "Content-Type":
		return "content-type"
	case "User-Agent":
		return "user-agent"
	case "X-Amz-Date":
		return "x-amz-date"
	case "X-Amz-Security-Token":
		return "x-amz-security-token"
	case "X-Amz-Target":
		return "x-amz-target"
	}
	return strings.ToLower(name)
}

// restoreBody makes req send body again, for the origin call.
func restoreBody(req *http.Request, body []byte) {
	if req.ContentLength == 0 {
		return // nothing was read
	}
	req.Body = newBodyReader(body)
	req.GetBody = func() (io.ReadCloser, error) { return newBodyReader(body), nil }
}

// adaptResponse is the *http.Response the SDK reads for a served reply.
func adaptResponse(req *http.Request, r response) *http.Response {
	header := r.headers
	if header == nil {
		header = http.Header{}
	}
	s := &servedResponse{}
	s.body.Reset(r.body)
	s.Response = http.Response{
		Status:        statusLine(r.statusCode),
		StatusCode:    r.statusCode,
		Proto:         "HTTP/1.1",
		ProtoMajor:    1,
		ProtoMinor:    1,
		Header:        header,
		Body:          &s.body,
		ContentLength: int64(len(r.body)),
		Request:       req,
	}
	return &s.Response
}

// servedResponse is a served response and its body in one allocation.
type servedResponse struct {
	http.Response
	body bodyReader
}

// statusLine is the Status text net/http gives a response: "200 OK", or the bare code.
func statusLine(code int) string {
	if code == http.StatusOK {
		return "200 OK"
	}
	if text := http.StatusText(code); text != "" {
		return strconv.Itoa(code) + " " + text
	}
	return strconv.Itoa(code)
}

// bodyReader is a body that is its own closer, in one allocation.
type bodyReader struct {
	bytes.Reader
}

func newBodyReader(b []byte) *bodyReader {
	r := &bodyReader{}
	r.Reset(b)
	return r
}

func (*bodyReader) Close() error { return nil }

// httpClient is the dynamodb.Options.HTTPClient of a client built by NewFromConfig or New:
// routed calls go through a cache session, everything else to origin.
type httpClient struct {
	client *Client
	origin aws.HTTPClient // the HTTP client the SDK would have used
}

// Do sends one SDK attempt. A failed cache outcome is an *sdkError, so the SDK's retryer
// sees the session's retry decision.
func (h *httpClient) Do(req *http.Request) (*http.Response, error) {
	c := h.client
	if c.closed() {
		return h.finish(req, closedOutcome(c.cfg.strict()), nil)
	}
	creq, routable, err := adaptRequest(req, c.identity)
	if err != nil {
		return nil, &sdkError{err: err}
	}
	if !routable {
		return h.origin.Do(req)
	}
	return h.finish(req, c.request(req.Context(), creq), creq.body)
}

// finish acts on the outcome of a routed request whose body, already read, is body.
func (h *httpClient) finish(req *http.Request, o requestOutcome, body []byte) (*http.Response, error) {
	switch o.kind {
	case outcomeServed:
		return adaptResponse(req, o.response), nil
	case outcomeFailed:
		return nil, &sdkError{err: o.err, retryable: o.retryable, charge: o.charge}
	default:
		if body != nil {
			restoreBody(req, body)
		}
		return h.origin.Do(req)
	}
}
