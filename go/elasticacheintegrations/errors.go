// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"errors"
	"strings"
	"unicode/utf8"
)

// ErrIntegrations matches every error this package creates, with [errors.Is]. A caller's done
// ctx is returned as ctx.Err().
var ErrIntegrations = errors.New("elasticacheintegrations")

const errPrefix = "elasticacheintegrations: "

// ConfigError reports an option, region, credentials or CA file that cannot be used.
// It is returned before the cache is contacted, or, for [Options.CachePolicyOverrides] the
// client cannot write, before the first aws.config is sent; it is never retried.
type ConfigError struct {
	Option string // the option or input at fault, such as "Endpoint" or "Region"
	Msg    string // what is wrong, read after Option: "is required", "must be ..."
	Err    error  // the underlying failure, if any
}

func (e *ConfigError) Error() string {
	what := spaced(e.Option, e.Msg)
	if what == "" {
		what = "invalid configuration"
	}
	return format(what, "", e.Err)
}
func (e *ConfigError) Unwrap() error        { return e.Err }
func (e *ConfigError) Is(target error) bool { return target == ErrIntegrations }

// ConnectionError reports that a connection to the cache could not be opened at start-up in
// time: TCP, TLS, HELLO or the first aws.config failed in a way that is not an [AuthError],
// [ConfigError], [OverridesRejectedError] or [ConfigUnevaluableError].
type ConnectionError struct {
	Msg string
	Err error // the last failure, if any
}

func (e *ConnectionError) Error() string {
	return format("cannot connect to the cache", e.Msg, e.Err)
}
func (e *ConnectionError) Unwrap() error        { return e.Err }
func (e *ConnectionError) Is(target error) bool { return target == ErrIntegrations }

// ConnectionLostError reports that the connection closed while the request was pending.
// The request may or may not have reached the cache.
type ConnectionLostError struct {
	Msg string
	Err error // why the connection closed, if known
}

func (e *ConnectionLostError) Error() string {
	return format("connection lost", e.Msg, e.Err)
}
func (e *ConnectionLostError) Unwrap() error        { return e.Err }
func (e *ConnectionLostError) Is(target error) bool { return target == ErrIntegrations }

// ProtocolError reports a corrupt stream or a reply of the wrong shape.
type ProtocolError struct {
	Msg string
	Err error // the decoding failure, if any
}

func (e *ProtocolError) Error() string        { return format("protocol error", e.Msg, e.Err) }
func (e *ProtocolError) Unwrap() error        { return e.Err }
func (e *ProtocolError) Is(target error) bool { return target == ErrIntegrations }

// TimeoutError reports that the cache did not reply in time.
type TimeoutError struct {
	Msg string
	Err error // the I/O deadline error, if any

	closedConn bool // the timeout closed its connection
}

func (e *TimeoutError) Error() string        { return format("timed out", e.Msg, e.Err) }
func (e *TimeoutError) Unwrap() error        { return e.Err }
func (e *TimeoutError) Is(target error) bool { return target == ErrIntegrations }

// Timeout reports true, like the net package's timeout errors.
func (e *TimeoutError) Timeout() bool { return true }

// RejectedError reports a -CODE error reply that ends a request: to aws.request, or at
// start-up to aws.config (other than CONFIG_INVALID), where a [ConnectionError] wraps it.
type RejectedError struct {
	Code          string // the bare code, without "-", such as "PARSE_ERROR" or "UNSUPPORTED"
	ServerMessage string // the rest of the reply, from the cache, at most 256 bytes
}

func (e *RejectedError) Error() string {
	return format("request rejected by the cache", spaced(e.Code, e.ServerMessage), nil)
}
func (e *RejectedError) Is(target error) bool { return target == ErrIntegrations }

// AuthError reports that the cache rejected HELLO or a re-AUTH.
type AuthError struct {
	Code string // the bare code of the error reply, such as "WRONGPASS"; empty for a non-error reply
	Msg  string // what the client saw
}

func (e *AuthError) Error() string {
	return format("authentication rejected", spaced(e.Code, e.Msg), nil)
}
func (e *AuthError) Is(target error) bool { return target == ErrIntegrations }

// OverridesRejectedError reports that the cache rejected [Options.CachePolicyOverrides]
// (-CONFIG_INVALID). Retrying with the same overrides fails the same way.
type OverridesRejectedError struct {
	ServerMessage string // the cache's reason, at most 256 bytes
}

func (e *OverridesRejectedError) Error() string {
	return format("cache policy overrides rejected", e.ServerMessage, nil)
}
func (e *OverridesRejectedError) Is(target error) bool { return target == ErrIntegrations }

// ConfigUnevaluableError reports a config from the cache whose predicates are malformed or
// do not compile.
type ConfigUnevaluableError struct {
	Msg string
	Err error
}

func (e *ConfigUnevaluableError) Error() string {
	return format("cache config cannot be evaluated", e.Msg, e.Err)
}
func (e *ConfigUnevaluableError) Unwrap() error        { return e.Err }
func (e *ConfigUnevaluableError) Is(target error) bool { return target == ErrIntegrations }

// RequestNotSentError reports that a request found no pending slot or write turn in time.
// Nothing was written to the cache. Such a request goes to DynamoDB, or in strict mode fails
// with a [CacheUnavailableError] wrapping it; it is returned as is only while Close runs.
type RequestNotSentError struct {
	Msg string
}

func (e *RequestNotSentError) Error() string        { return format("request not sent", e.Msg, nil) }
func (e *RequestNotSentError) Is(target error) bool { return target == ErrIntegrations }

// CacheUnavailableError reports that a routed request may not go to DynamoDB and the cache
// cannot take it: no connection once the fallback window is spent, or, in strict mode, no
// connection, no room on the connection (wrapping a [RequestNotSentError]), an AUTH_ERROR
// reply, or Close while the request waited for a reconnect. The SDK may retry it.
type CacheUnavailableError struct {
	Msg string // why the request may not go to DynamoDB
	Err error  // what made the cache unavailable, if known
}

func (e *CacheUnavailableError) Error() string {
	return format("cache unavailable", e.Msg, e.Err)
}
func (e *CacheUnavailableError) Unwrap() error        { return e.Err }
func (e *CacheUnavailableError) Is(target error) bool { return target == ErrIntegrations }

// OriginError reports that the cache could not complete its call to DynamoDB
// (-ORIGIN_ERROR or -INTERNAL_ERROR) on a healthy connection. A direct call to DynamoDB can
// fail the same way, so the SDK may retry it. If the connection was down when the reply was
// handled, or Close was running, the reply is returned as a terminal [RejectedError] instead.
type OriginError struct {
	Code          string // the bare code: "ORIGIN_ERROR" or "INTERNAL_ERROR"
	ServerMessage string // the rest of the reply, from the cache, at most 256 bytes
}

func (e *OriginError) Error() string {
	return format("could not complete the origin request", spaced(e.Code, e.ServerMessage), nil)
}
func (e *OriginError) Is(target error) bool { return target == ErrIntegrations }

// sdkError carries the retry decision for the SDK's standard retryer, which checks
// RetryableError before treating a transport error as a retryable connection error.
type sdkError struct {
	err       error
	retryable bool
	charge    *dropCharge // the drop that failed it, if a drop did
}

func (e *sdkError) Error() string        { return e.err.Error() }
func (e *sdkError) Unwrap() error        { return e.err }
func (e *sdkError) RetryableError() bool { return e.retryable }

// format returns "elasticacheintegrations: <what>[: <detail>][: <cause>]". A cause from this
// package is written without the prefix, and without what when it starts with the same words,
// so neither repeats. The text of any other cause is kept whole.
func format(what, detail string, cause error) string {
	s := errPrefix + what
	if detail != "" {
		s += ": " + detail
	}
	if cause != nil {
		c := cause.Error()
		if rest, ok := strings.CutPrefix(c, errPrefix); ok && errors.Is(cause, ErrIntegrations) {
			if rest == what {
				return s
			}
			c, _ = strings.CutPrefix(rest, what+": ")
		}
		s += ": " + c
	}
	return s
}

// Text the cache sends is capped before it goes into an error, so a broken cache cannot make
// errors and log records arbitrarily long.
const (
	maxServerCode    = 64
	maxServerMessage = 256
)

// serverText returns s cut to at most n bytes on a UTF-8 boundary, marked with "..." when cut.
func serverText(s string, n int) string {
	if len(s) <= n {
		return s
	}
	cut := n - len("...")
	for cut > 0 && !utf8.RuneStart(s[cut]) {
		cut--
	}
	return s[:cut] + "..."
}

// spaced joins the non-empty parts with one space.
func spaced(a, b string) string {
	switch {
	case a == "":
		return b
	case b == "":
		return a
	default:
		return a + " " + b
	}
}
