// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import "errors"

// ErrIntegrations matches every error this package returns, with [errors.Is].
var ErrIntegrations = errors.New("elasticache integrations")

const errPrefix = "elasticacheintegrations: "

// ConfigError reports an option, region, credentials or CA file that cannot be used.
// It is returned before any network I/O.
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

// ConnectionError reports that the first connection to the cache could not be opened in
// time: TCP, TLS, HELLO or the first aws.config failed in a way that is not an [AuthError],
// [OverridesRejectedError] or [ConfigUnevaluableError].
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
}

func (e *TimeoutError) Error() string        { return format("timed out", e.Msg, e.Err) }
func (e *TimeoutError) Unwrap() error        { return e.Err }
func (e *TimeoutError) Is(target error) bool { return target == ErrIntegrations }

// Timeout reports true, like the net package's timeout errors.
func (e *TimeoutError) Timeout() bool { return true }

// RejectedError reports a -CODE error reply to aws.request that ends the request.
type RejectedError struct {
	Code    string // the bare code, without "-", such as "PARSE_ERROR" or "UNSUPPORTED"
	Message string // the rest of the reply
}

func (e *RejectedError) Error() string {
	return format("request rejected by the cache", spaced(e.Code, e.Message), nil)
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

// OverridesRejectedError reports that the cache rejected Options.CachePolicyOverrides
// (-CONFIG_INVALID). Retrying with the same overrides fails the same way.
type OverridesRejectedError struct {
	Message string
}

func (e *OverridesRejectedError) Error() string {
	return format("cache policy overrides rejected", e.Message, nil)
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
// Nothing was written to the cache.
type RequestNotSentError struct {
	Msg string
}

func (e *RequestNotSentError) Error() string        { return format("request not sent", e.Msg, nil) }
func (e *RequestNotSentError) Is(target error) bool { return target == ErrIntegrations }

// CacheUnavailableError reports that the cache is down and the request may not go to
// DynamoDB: strict mode, or the fallback window is spent.
type CacheUnavailableError struct {
	Reason string
	Err    error // what made the cache unavailable, if known
}

func (e *CacheUnavailableError) Error() string {
	return format("cache unavailable", e.Reason, e.Err)
}
func (e *CacheUnavailableError) Unwrap() error        { return e.Err }
func (e *CacheUnavailableError) Is(target error) bool { return target == ErrIntegrations }

// OriginError reports that the cache could not complete its call to DynamoDB
// (-ORIGIN_ERROR or -INTERNAL_ERROR). A direct call to DynamoDB can fail the same way, so
// the SDK may retry it.
type OriginError struct {
	Code    string // the bare code: "ORIGIN_ERROR" or "INTERNAL_ERROR"
	Message string // the rest of the reply
}

func (e *OriginError) Error() string {
	return format("the cache could not complete the DynamoDB call", spaced(e.Code, e.Message), nil)
}
func (e *OriginError) Is(target error) bool { return target == ErrIntegrations }

// sdkError carries the retry decision for the SDK's standard retryer, which checks
// RetryableError before treating a transport error as a retryable connection error.
type sdkError struct {
	err       error
	retryable bool
}

func (e *sdkError) Error() string        { return e.err.Error() }
func (e *sdkError) Unwrap() error        { return e.err }
func (e *sdkError) RetryableError() bool { return e.retryable }

// format returns "elasticacheintegrations: <what>[: <detail>][: <cause>]".
func format(what, detail string, cause error) string {
	s := errPrefix + what
	if detail != "" {
		s += ": " + detail
	}
	if cause != nil {
		s += ": " + cause.Error()
	}
	return s
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
