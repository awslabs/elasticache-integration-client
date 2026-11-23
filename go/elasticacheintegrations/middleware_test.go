// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
	"testing"
	"testing/iotest"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	awsmiddleware "github.com/aws/aws-sdk-go-v2/aws/middleware"
	v4 "github.com/aws/aws-sdk-go-v2/aws/signer/v4"

	"github.com/awslabs/elasticache-integration-client/go/internal/testutil"
)

const testRegion = "us-east-1"

func testIdentity() identity {
	return identity{region: testRegion, provider: testutil.StaticCredentials()}
}

// signedRequest is a DynamoDB request as the SDK hands it to its HTTP client: operation name
// in the context, SigV4-signed with creds in region.
func signedRequest(t testing.TB, operation, body string, creds aws.Credentials, region string) *http.Request {
	t.Helper()
	ctx := awsmiddleware.SetOperationName(context.Background(), operation)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, "https://dynamodb."+region+".amazonaws.com/", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	req.GetBody = nil // the SDK sets none
	req.Header.Set("Content-Type", "application/x-amz-json-1.0")
	req.Header.Set("X-Amz-Target", "DynamoDB_20120810."+operation)
	req.Header.Set("User-Agent", "aws-sdk-go-v2/test")
	req.Header.Set("Accept-Encoding", "identity")
	req.Header.Set("Amz-Sdk-Request", "attempt=1; max=3")
	err = v4.NewSigner().SignHTTP(ctx, creds, req, emptySHA256, "dynamodb", region, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	return req
}

func testCredentials(t testing.TB) aws.Credentials {
	t.Helper()
	c, err := testutil.StaticCredentials().Retrieve(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	return c
}

func getItemRequest(t testing.TB) *http.Request {
	return signedRequest(t, "GetItem", `{"TableName":"Music"}`, testCredentials(t), testRegion)
}

func TestAdaptRequest(t *testing.T) {
	req := getItemRequest(t)
	r, ok, err := adaptRequest(req, testIdentity())
	if err != nil || !ok {
		t.Fatalf("adaptRequest = %v, %v", ok, err)
	}
	if r.method != "POST" || r.url != "https://dynamodb.us-east-1.amazonaws.com/" {
		t.Errorf("method, url = %q, %q", r.method, r.url)
	}
	if string(r.body) != `{"TableName":"Music"}` || cap(r.body) != len(r.body) {
		t.Errorf("body = %q (cap %d)", r.body, cap(r.body))
	}
	if r.metadata != (requestMetadata{service: "dynamodb", operation: "GetItem"}) {
		t.Errorf("metadata = %+v", r.metadata)
	}
	want := map[string]string{
		"content-type":         "application/x-amz-json-1.0",
		"x-amz-target":         "DynamoDB_20120810.GetItem",
		"user-agent":           "aws-sdk-go-v2/test",
		"accept-encoding":      "identity",
		"amz-sdk-request":      "attempt=1; max=3",
		"content-length":       "21",
		"authorization":        req.Header.Get("Authorization"),
		"x-amz-date":           req.Header.Get("X-Amz-Date"),
		"x-amz-security-token": testutil.SessionToken,
	}
	if len(r.headers) != len(want) {
		t.Errorf("headers = %v, want %v", r.headers, want)
	}
	for k, v := range want {
		if r.headers[k] != v {
			t.Errorf("header %q = %q, want %q", k, r.headers[k], v)
		}
	}
	if _, ok := r.headers["host"]; ok {
		t.Error("host header added")
	}
}

func TestAdaptRequestHeaders(t *testing.T) {
	req := getItemRequest(t)
	req.Header["X-Multi"] = []string{" a ", "b "}
	req.Header["X-Custom-ÄName"] = []string{"v"}
	req.Header["X-Empty"] = nil
	req.Header["x-dup"] = []string{"zz"}
	req.Header["X-Dup"] = []string{"aa"}
	req.Header["X-DUP"] = []string{" mm"}
	req.Header.Set("Content-Length", "999")
	r, ok, err := adaptRequest(req, testIdentity())
	if err != nil || !ok {
		t.Fatalf("adaptRequest = %v, %v", ok, err)
	}
	checks := map[string]string{
		"x-multi":        "a , b", // joined, then trimmed, as Java
		"x-custom-äname": "v",
		"x-dup":          "aa, mm, zz",
		"content-length": "21", // from req.ContentLength
	}
	for k, v := range checks {
		if r.headers[k] != v {
			t.Errorf("header %q = %q, want %q", k, r.headers[k], v)
		}
	}
	if _, ok := r.headers["x-empty"]; ok {
		t.Error("a header with no values was kept")
	}
}

func TestAdaptRequestHeadersSameLowercaseNameIsDeterministic(t *testing.T) {
	for range 200 {
		req := getItemRequest(t)
		req.Header["x-d"] = []string{"zz"}
		req.Header["X-D"] = []string{"aa"}
		req.Header["X-d"] = []string{"mm"}
		r, ok, err := adaptRequest(req, testIdentity())
		if err != nil || !ok {
			t.Fatalf("adaptRequest = %v, %v", ok, err)
		}
		if got := r.headers["x-d"]; got != "aa, mm, zz" {
			t.Fatalf("x-d = %q, want %q", got, "aa, mm, zz")
		}
	}
}

func TestAdaptRequestEmptyBody(t *testing.T) {
	for _, body := range []io.ReadCloser{nil, http.NoBody} {
		req := signedRequest(t, "ListTables", "", testCredentials(t), testRegion)
		req.Body, req.ContentLength = body, 0
		r, ok, err := adaptRequest(req, testIdentity())
		if err != nil || !ok || len(r.body) != 0 || r.headers["content-length"] != "0" {
			t.Fatalf("body %v: adaptRequest = %+v, %v, %v", body, r, ok, err)
		}
	}
}

func TestAdaptRequestOperationFromTarget(t *testing.T) {
	req := getItemRequest(t)
	req = req.WithContext(context.Background())
	req.Header.Set("X-Amz-Target", "DynamoDB_20120810.Query ")
	r, ok, err := adaptRequest(req, testIdentity())
	if err != nil || !ok || r.metadata.operation != "Query" {
		t.Fatalf("adaptRequest = %+v, %v, %v", r, ok, err)
	}
}

func TestAdaptRequestNotRoutable(t *testing.T) {
	other := testCredentials(t)
	other.AccessKeyID = "AKIDOTHER"
	failing := aws.CredentialsProviderFunc(func(context.Context) (aws.Credentials, error) {
		return aws.Credentials{}, errors.New("no credentials")
	})
	cases := []struct {
		name string
		req  func() *http.Request
		id   identity
	}{
		{"unknown length", func() *http.Request { r := getItemRequest(t); r.ContentLength = -1; return r }, testIdentity()},
		{"zero length with a body", func() *http.Request { r := getItemRequest(t); r.ContentLength = 0; return r }, testIdentity()},
		{"over 16 MiB", func() *http.Request { r := getItemRequest(t); r.ContentLength = maxRequestBody + 1; return r }, testIdentity()},
		{"no operation", func() *http.Request {
			r := getItemRequest(t)
			r = r.WithContext(context.Background())
			r.Header.Del("X-Amz-Target")
			return r
		}, testIdentity()},
		{"two targets", func() *http.Request {
			r := getItemRequest(t).WithContext(context.Background())
			r.Header.Add("X-Amz-Target", "DynamoDB_20120810.Query")
			return r
		}, testIdentity()},
		{"empty target operation", func() *http.Request {
			r := getItemRequest(t).WithContext(context.Background())
			r.Header.Set("X-Amz-Target", "DynamoDB_20120810.")
			return r
		}, testIdentity()},
		{"unsigned", func() *http.Request { r := getItemRequest(t); r.Header.Del("Authorization"); return r }, testIdentity()},
		{"two signatures", func() *http.Request {
			r := getItemRequest(t)
			r.Header.Add("Authorization", r.Header.Get("Authorization"))
			return r
		}, testIdentity()},
		{"other scheme", func() *http.Request { r := getItemRequest(t); r.Header.Set("Authorization", "Bearer x"); return r }, testIdentity()},
		{"no credential", func() *http.Request {
			r := getItemRequest(t)
			r.Header.Set("Authorization", "AWS4-HMAC-SHA256 SignedHeaders=host, Signature=00")
			return r
		}, testIdentity()},
		{"short scope", func() *http.Request {
			r := getItemRequest(t)
			r.Header.Set("Authorization", "AWS4-HMAC-SHA256 Credential="+testutil.AccessKeyID+"/20260101/us-east-1/aws4_request, Signature=00")
			return r
		}, testIdentity()},
		{"other service", func() *http.Request {
			r := getItemRequest(t)
			r.Header.Set("Authorization", "AWS4-HMAC-SHA256 Credential="+testutil.AccessKeyID+"/20260101/us-east-1/s3/aws4_request, Signature=00")
			return r
		}, testIdentity()},
		{"no service", func() *http.Request {
			r := getItemRequest(t)
			r.Header.Set("Authorization", "AWS4-HMAC-SHA256 Credential="+testutil.AccessKeyID+"/20260101/us-east-1//aws4_request, Signature=00")
			return r
		}, testIdentity()},
		{"per-call credentials", func() *http.Request {
			return signedRequest(t, "GetItem", `{}`, other, testRegion)
		}, testIdentity()},
		{"per-call region", func() *http.Request {
			return signedRequest(t, "GetItem", `{}`, testCredentials(t), "us-west-2")
		}, testIdentity()},
		{"provider fails", func() *http.Request { return getItemRequest(t) }, identity{region: testRegion, provider: failing}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			req := tc.req()
			spy := &readSpy{r: req.Body}
			req.Body = spy
			length, auth := req.ContentLength, req.Header.Get("Authorization")
			r, ok, err := adaptRequest(req, tc.id)
			if ok || err != nil || r != nil {
				t.Fatalf("adaptRequest = %+v, %v, %v; want not routable", r, ok, err)
			}
			if req.Body != spy || req.GetBody != nil || req.ContentLength != length || req.Header.Get("Authorization") != auth {
				t.Fatal("the request was changed")
			}
			if spy.reads != 0 {
				t.Fatal("the body was read")
			}
		})
	}
}

// readSpy counts reads of a body.
type readSpy struct {
	r     io.ReadCloser
	reads int
}

func (s *readSpy) Read(p []byte) (int, error) { s.reads++; return s.r.Read(p) }
func (s *readSpy) Close() error               { return s.r.Close() }

func TestAdaptRequestBodyErrors(t *testing.T) {
	cause := errors.New("disk on fire")
	cases := []struct {
		name  string
		body  io.Reader
		cause error
	}{
		{"read error", iotest.ErrReader(cause), cause},
		{"short", strings.NewReader("{}"), io.ErrUnexpectedEOF},
		{"empty", strings.NewReader(""), io.ErrUnexpectedEOF},
		{"nil", nil, io.ErrUnexpectedEOF},
		{"long", strings.NewReader(`{"TableName":"Music"}!`), errBodyTooLong},
		{"error after the body", io.MultiReader(strings.NewReader(`{"TableName":"Music"}`), iotest.ErrReader(cause)), cause},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			req := getItemRequest(t)
			req.Body = nil
			if tc.body != nil {
				req.Body = io.NopCloser(tc.body)
			}
			_, ok, err := adaptRequest(req, testIdentity())
			if ok || err == nil {
				t.Fatalf("adaptRequest = %v, %v; want an error", ok, err)
			}
			if !errors.Is(err, ErrIntegrations) || !errors.Is(err, tc.cause) {
				t.Errorf("err = %v; want ErrIntegrations wrapping %v", err, tc.cause)
			}
			if !strings.HasPrefix(err.Error(), "elasticacheintegrations: cannot read the request body: ") {
				t.Errorf("Error() = %q", err.Error())
			}
		})
	}
}

func TestRestoreBody(t *testing.T) {
	req := getItemRequest(t)
	r, _, err := adaptRequest(req, testIdentity())
	if err != nil {
		t.Fatal(err)
	}
	restoreBody(req, r.body)
	for range 2 {
		if b, _ := io.ReadAll(req.Body); string(b) != `{"TableName":"Music"}` {
			t.Fatalf("Body = %q", b)
		}
		body, err := req.GetBody()
		if err != nil {
			t.Fatal(err)
		}
		req.Body = body
	}

	empty := signedRequest(t, "ListTables", "", testCredentials(t), testRegion)
	empty.Body, empty.ContentLength = http.NoBody, 0
	restoreBody(empty, nil)
	if empty.Body != http.NoBody || empty.GetBody != nil {
		t.Error("an empty body was replaced")
	}
}

func TestAdaptResponse(t *testing.T) {
	req := getItemRequest(t)
	h := http.Header{"X-Amz-Crc32": {"123"}, "Age": {"0"}}
	cases := []struct {
		code   int
		status string
	}{{200, "200 OK"}, {400, "400 Bad Request"}, {599, "599"}}
	for _, tc := range cases {
		resp := adaptResponse(req, response{statusCode: tc.code, headers: h, body: []byte("body")})
		if resp.StatusCode != tc.code || resp.Status != tc.status || resp.Proto != "HTTP/1.1" ||
			resp.ProtoMajor != 1 || resp.ProtoMinor != 1 || resp.Request != req || resp.ContentLength != 4 {
			t.Errorf("response = %+v", resp)
		}
		if resp.Header.Get("X-Amz-Crc32") != "123" {
			t.Errorf("Header = %v", resp.Header)
		}
		b, err := io.ReadAll(resp.Body)
		if err != nil || string(b) != "body" || resp.Body.Close() != nil {
			t.Errorf("Body = %q, %v", b, err)
		}
	}
	resp := adaptResponse(req, response{statusCode: 200})
	if resp.Header == nil || resp.ContentLength != 0 {
		t.Errorf("empty response = %+v", resp)
	}
	if b, _ := io.ReadAll(resp.Body); len(b) != 0 {
		t.Errorf("Body = %q", b)
	}
}
