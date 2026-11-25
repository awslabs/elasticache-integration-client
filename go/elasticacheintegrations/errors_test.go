// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"context"
	"errors"
	"fmt"
	"go/ast"
	"go/parser"
	"go/token"
	"io"
	"os"
	"sort"
	"strings"
	"testing"
	"unicode/utf8"

	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
)

var errCause = errors.New("cause")

// publicErrors holds a zero value of every exported error type.
var publicErrors = []error{
	&ConfigError{}, &ConnectionError{}, &ConnectionLostError{}, &ProtocolError{},
	&TimeoutError{}, &RejectedError{}, &AuthError{}, &OverridesRejectedError{},
	&ConfigUnevaluableError{}, &RequestNotSentError{}, &CacheUnavailableError{}, &OriginError{},
}

func TestPublicErrorsListsEveryExportedErrorType(t *testing.T) {
	f, err := parser.ParseFile(token.NewFileSet(), "errors.go", nil, 0)
	if err != nil {
		t.Fatal(err)
	}
	var declared []string
	for _, d := range f.Decls {
		fn, ok := d.(*ast.FuncDecl)
		if !ok || fn.Recv == nil || fn.Name.Name != "Error" {
			continue
		}
		star, ok := fn.Recv.List[0].Type.(*ast.StarExpr)
		if !ok {
			t.Fatalf("Error on a value receiver: %v", fn.Recv.List[0].Type)
		}
		if name := star.X.(*ast.Ident).Name; ast.IsExported(name) {
			declared = append(declared, name)
		}
	}
	var listed []string
	for _, e := range publicErrors {
		listed = append(listed, strings.TrimPrefix(fmt.Sprintf("%T", e), "*elasticacheintegrations."))
	}
	sort.Strings(declared)
	sort.Strings(listed)
	if strings.Join(declared, ",") != strings.Join(listed, ",") {
		t.Fatalf("errors.go declares %v, publicErrors lists %v", declared, listed)
	}
}

func TestErrorsMatchErrIntegrationsAndAs(t *testing.T) {
	cases := []struct {
		err  error
		want string
		as   func(error) bool
	}{
		{&ConfigError{Option: "Endpoint", Msg: "is required"},
			"elasticacheintegrations: Endpoint is required",
			func(e error) bool { var v *ConfigError; return errors.As(e, &v) && v.Option == "Endpoint" }},
		{&ConnectionError{Msg: "no connection after 5000 ms", Err: errCause},
			"elasticacheintegrations: cannot connect to the cache: no connection after 5000 ms: cause",
			func(e error) bool { var v *ConnectionError; return errors.As(e, &v) }},
		{&ConnectionLostError{Msg: "read failed", Err: io.EOF},
			"elasticacheintegrations: connection lost: read failed: EOF",
			func(e error) bool { var v *ConnectionLostError; return errors.As(e, &v) }},
		{&ProtocolError{Msg: "invalid UTF-8 in headers", Err: errCause},
			"elasticacheintegrations: protocol error: invalid UTF-8 in headers: cause",
			func(e error) bool { var v *ProtocolError; return errors.As(e, &v) }},
		{&TimeoutError{Msg: "no reply in 5000 ms", Err: os.ErrDeadlineExceeded},
			"elasticacheintegrations: timed out: no reply in 5000 ms: i/o timeout",
			func(e error) bool { var v *TimeoutError; return errors.As(e, &v) }},
		{&RejectedError{Code: "UNSUPPORTED", ServerMessage: "operation not supported"},
			"elasticacheintegrations: request rejected by the cache: UNSUPPORTED operation not supported",
			func(e error) bool { var v *RejectedError; return errors.As(e, &v) && v.Code == "UNSUPPORTED" }},
		{&AuthError{Code: "WRONGPASS", Msg: "invalid username-password pair"},
			"elasticacheintegrations: authentication rejected: WRONGPASS invalid username-password pair",
			func(e error) bool { var v *AuthError; return errors.As(e, &v) && v.Code == "WRONGPASS" }},
		{&OverridesRejectedError{ServerMessage: "unknown key ttl"},
			"elasticacheintegrations: cache policy overrides rejected: unknown key ttl",
			func(e error) bool { var v *OverridesRejectedError; return errors.As(e, &v) }},
		{&ConfigUnevaluableError{Msg: "predicate 0 does not compile", Err: errCause},
			"elasticacheintegrations: cache config cannot be evaluated: predicate 0 does not compile: cause",
			func(e error) bool { var v *ConfigUnevaluableError; return errors.As(e, &v) }},
		{&RequestNotSentError{Msg: "no pending slot in 3750 ms"},
			"elasticacheintegrations: request not sent: no pending slot in 3750 ms",
			func(e error) bool { var v *RequestNotSentError; return errors.As(e, &v) }},
		{&CacheUnavailableError{Msg: "strict mode"},
			"elasticacheintegrations: cache unavailable: strict mode",
			func(e error) bool { var v *CacheUnavailableError; return errors.As(e, &v) }},
		{&OriginError{Code: "ORIGIN_ERROR", ServerMessage: "timeout"},
			"elasticacheintegrations: could not complete the origin request: ORIGIN_ERROR timeout",
			func(e error) bool { var v *OriginError; return errors.As(e, &v) && v.Code == "ORIGIN_ERROR" }},
	}
	for _, tc := range cases {
		t.Run(fmt.Sprintf("%T", tc.err), func(t *testing.T) {
			if got := tc.err.Error(); got != tc.want {
				t.Fatalf("Error() = %q, want %q", got, tc.want)
			}
			// The SDK wraps a transport error twice before the caller sees it.
			wrapped := fmt.Errorf("operation error: %w", fmt.Errorf("send request failed: %w",
				&sdkError{err: tc.err, retryable: true}))
			if !errors.Is(wrapped, ErrIntegrations) {
				t.Fatal("errors.Is(wrapped, ErrIntegrations) = false")
			}
			if !tc.as(wrapped) {
				t.Fatal("errors.As through the wrappers failed")
			}
			if errors.Is(wrapped, errors.New("elasticacheintegrations")) {
				t.Fatal("matched a different error with the same text")
			}
		})
	}
}

func TestErrorsUnwrapToCause(t *testing.T) {
	for _, err := range []error{
		&ConfigError{Option: "CACertsFile", Msg: "cannot read", Err: errCause},
		&ConnectionError{Msg: "m", Err: errCause},
		&ConnectionLostError{Msg: "m", Err: errCause},
		&ProtocolError{Msg: "m", Err: errCause},
		&TimeoutError{Msg: "m", Err: errCause},
		&ConfigUnevaluableError{Msg: "m", Err: errCause},
		&CacheUnavailableError{Msg: "m", Err: errCause},
	} {
		if !errors.Is(err, errCause) {
			t.Errorf("%T does not unwrap to its cause", err)
		}
	}
}

func TestErrorMessagesWithEmptyParts(t *testing.T) {
	for _, tc := range []struct {
		err  error
		want string
	}{
		{&RejectedError{Code: "PARSE_ERROR"}, "elasticacheintegrations: request rejected by the cache: PARSE_ERROR"},
		{&RejectedError{ServerMessage: "bad"}, "elasticacheintegrations: request rejected by the cache: bad"},
		{&OriginError{Code: "INTERNAL_ERROR"}, "elasticacheintegrations: could not complete the origin request: INTERNAL_ERROR"},
		{&CacheUnavailableError{Msg: "fallback window spent", Err: errCause}, "elasticacheintegrations: cache unavailable: fallback window spent: cause"},
		{&ConfigError{Option: "Endpoint"}, "elasticacheintegrations: Endpoint"},
		{&ConfigError{Msg: "no credentials"}, "elasticacheintegrations: no credentials"},
		{&ConfigError{}, "elasticacheintegrations: invalid configuration"},
		{&ProtocolError{}, "elasticacheintegrations: protocol error"},
		{&ProtocolError{Err: errCause}, "elasticacheintegrations: protocol error: cause"},
	} {
		if got := tc.err.Error(); got != tc.want {
			t.Errorf("Error() = %q, want %q", got, tc.want)
		}
	}
	for _, err := range publicErrors {
		msg := err.Error()
		if strings.HasSuffix(msg, " ") || strings.HasSuffix(msg, ":") || strings.Contains(msg, "  ") || strings.Contains(msg, ": :") {
			t.Errorf("%T zero value: Error() = %q", err, msg)
		}
	}
}

func TestTimeoutErrorUnwrapsToDeadline(t *testing.T) {
	err := &sdkError{err: &TimeoutError{Msg: "m", Err: os.ErrDeadlineExceeded}, retryable: true}
	if !errors.Is(err, os.ErrDeadlineExceeded) {
		t.Fatal("TimeoutError does not unwrap to its deadline error")
	}
}

func TestOriginErrorIsNotRejected(t *testing.T) {
	var rejected *RejectedError
	if errors.As(&OriginError{Code: "ORIGIN_ERROR"}, &rejected) {
		t.Fatal("an OriginError must not match *RejectedError")
	}
}

func TestTimeoutErrorIsNetTimeout(t *testing.T) {
	var ne interface{ Timeout() bool }
	if !errors.As(&sdkError{err: &TimeoutError{Msg: "m"}}, &ne) || !ne.Timeout() {
		t.Fatal("TimeoutError does not report Timeout() == true")
	}
}

func TestSDKErrorCarriesRetryDecision(t *testing.T) {
	// The interface the SDK's standard retryer checks (aws/retry RetryableError).
	type retryableError interface{ RetryableError() bool }
	for _, want := range []bool{true, false} {
		var err error = &sdkError{err: &TimeoutError{Msg: "m"}, retryable: want}
		var r retryableError
		if !errors.As(err, &r) || r.RetryableError() != want {
			t.Fatalf("RetryableError() != %v", want)
		}
		if err.Error() != "elasticacheintegrations: timed out: m" {
			t.Fatalf("Error() = %q", err.Error())
		}
	}
}

func TestPublicErrorsDoNotCarryARetryDecision(t *testing.T) {
	// Only sdkError decides retries; a public type that did would override it for the SDK.
	type retryableError interface{ RetryableError() bool }
	for _, err := range publicErrors {
		if _, ok := err.(retryableError); ok {
			t.Errorf("%T implements RetryableError", err)
		}
	}
}

// A package error inside another is written without the prefix, and without words the outer
// error already wrote.
func TestNestedErrorTextDoesNotRepeat(t *testing.T) {
	inner := stepFailed(context.Background(), "connect to h:6379", os.ErrDeadlineExceeded)
	err := &ConnectionError{Msg: "no connection within 5000ms", Err: inner}
	const want = "elasticacheintegrations: cannot connect to the cache: no connection within 5000ms: " +
		"connect to h:6379: timed out: i/o timeout"
	if got := err.Error(); got != want {
		t.Errorf("Error() = %q, want %q", got, want)
	}
	lost := &ConnectionLostError{Msg: "reply stream corrupt", Err: &ProtocolError{Msg: "bad"}}
	if got, want := lost.Error(), "elasticacheintegrations: connection lost: reply stream corrupt: protocol error: bad"; got != want {
		t.Errorf("Error() = %q, want %q", got, want)
	}
}

// Only a cause from this package is trimmed; any other cause keeps its whole text.
func TestNestedErrorTextTrimsOnlyPackageErrors(t *testing.T) {
	foreign := errors.New(errPrefix + "cannot connect to the cache: refused")
	err := &ConnectionError{Msg: "dial", Err: foreign}
	if got, want := err.Error(), "elasticacheintegrations: cannot connect to the cache: dial: "+foreign.Error(); got != want {
		t.Errorf("foreign cause: Error() = %q, want %q", got, want)
	}
	bare := &ConnectionError{Msg: "no connection within 5000ms", Err: &ConnectionError{}}
	if got, want := bare.Error(), "elasticacheintegrations: cannot connect to the cache: no connection within 5000ms"; got != want {
		t.Errorf("cause with only the same words: Error() = %q, want %q", got, want)
	}
	body := &ConnectionLostError{Msg: "m", Err: bodyError(io.ErrUnexpectedEOF)}
	if got, want := body.Error(), "elasticacheintegrations: connection lost: m: cannot read the request body: unexpected EOF"; got != want {
		t.Errorf("wrapped sentinel cause: Error() = %q, want %q", got, want)
	}
}

func TestServerTextCutsOnARuneBoundary(t *testing.T) {
	for _, tc := range []struct {
		in   string
		n    int
		want string
	}{
		{in: "abcdefgh", n: 8, want: "abcdefgh"},
		{in: "abcdefghi", n: 8, want: "abcde..."},
		{in: "abcd\u20acfgh", n: 8, want: "abcd..."},        // the euro sign does not fit
		{in: "ab\U0001F600cdefg", n: 8, want: "ab..."},      // nor a 4-byte rune
		{in: strings.Repeat("\x80", 20), n: 8, want: "..."}, // no rune start to cut at
	} {
		got := serverText(tc.in, tc.n)
		if got != tc.want || len(got) > tc.n {
			t.Errorf("serverText(%q, %d) = %q, want %q", tc.in, tc.n, got, tc.want)
		}
	}
}

// Text from the cache is capped before it reaches an error.
func TestServerTextIsCapped(t *testing.T) {
	long := strings.Repeat("é", 300) // 600 bytes
	v := resp.ErrorValue("CODE"+strings.Repeat("X", 100), long)
	e := newServerError(v)
	if len(e.code) > maxServerCode || len(e.message) > maxServerMessage {
		t.Fatalf("code %d bytes, message %d bytes; want at most %d and %d", len(e.code), len(e.message), maxServerCode, maxServerMessage)
	}
	if !utf8.ValidString(e.message) || !strings.HasSuffix(e.message, "...") {
		t.Errorf("message = %q, want valid UTF-8 ending in ...", e.message)
	}
	short := newServerError(resp.ErrorValue("UNSUPPORTED", "operation not supported"))
	if short.code != "UNSUPPORTED" || short.message != "operation not supported" {
		t.Errorf("short reply = %+v, want it unchanged", short)
	}
	if _, err := parseConfigReply(v, false); err == nil || len(err.Error()) > 400 {
		t.Errorf("aws.config error = %v (%d bytes), want a capped error", err, len(err.Error()))
	}
}

func TestBodyErrorMatchesErrIntegrationsAndCause(t *testing.T) {
	err := bodyError(io.ErrUnexpectedEOF)
	if !errors.Is(err, ErrIntegrations) || !errors.Is(err, io.ErrUnexpectedEOF) {
		t.Fatalf("bodyError(io.ErrUnexpectedEOF) = %v, want it to match ErrIntegrations and the cause", err)
	}
	if got, want := err.Error(), "elasticacheintegrations: cannot read the request body: unexpected EOF"; got != want {
		t.Errorf("Error() = %q, want %q", got, want)
	}
}
