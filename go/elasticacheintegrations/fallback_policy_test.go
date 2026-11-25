// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"context"
	"errors"
	"fmt"
	"testing"
)

// TestRetryable pins the Python client's retry decision (hooks.py _handle): a lost
// connection, a timeout, a connection error and ORIGIN_ERROR or INTERNAL_ERROR are retryable;
// nothing is once the client is closed, except a *CacheUnavailableError, whose retry goes to
// DynamoDB.
func TestRetryable(t *testing.T) {
	lost := &ConnectionLostError{Msg: "x"}
	cases := []struct {
		err          error
		open, closed bool // retryable while the client is open, and once it is closed
	}{
		{err: &CacheUnavailableError{Reason: "x"}, open: true, closed: true},
		{err: &OriginError{Code: "ORIGIN_ERROR"}, open: true},
		{err: &OriginError{Code: "INTERNAL_ERROR"}, open: true},
		{err: lost, open: true},
		{err: fmt.Errorf("wrapped: %w", lost), open: true},
		{err: &TimeoutError{Msg: "x"}, open: true},
		{err: &ConnectionError{Msg: "x"}, open: true},
		{err: &RejectedError{Code: "UNSUPPORTED"}},
		{err: &RejectedError{Code: "ORIGIN_ERROR"}},
		{err: &ProtocolError{Msg: "x"}},
		{err: &AuthError{Code: "WRONGPASS"}},
		{err: &RequestNotSentError{Msg: "x"}},
		{err: context.Canceled},
		{err: context.DeadlineExceeded},
	}
	for _, tc := range cases {
		if got := retryable(tc.err, false); got != tc.open {
			t.Errorf("retryable(%T %v, closed=false) = %v, want %v", tc.err, tc.err, got, tc.open)
		}
		if got := retryable(tc.err, true); got != tc.closed {
			t.Errorf("retryable(%T %v, closed=true) = %v, want %v", tc.err, tc.err, got, tc.closed)
		}
	}
}

func rejected(code string) requestReply {
	return requestReply{failed: true, server: serverError{code: code, message: "msg"}}
}

func TestReplyOutcomeServed(t *testing.T) {
	r := response{statusCode: 400, body: []byte("{}")}
	o, drop := replyOutcome(requestReply{response: r}, nil, true, false, true)
	if o.kind != outcomeServed || o.response.statusCode != 400 || drop != "" {
		t.Fatalf("outcome %+v, drop %q; want the served 400 response, no drop", o, drop)
	}
}

func TestReplyOutcomeServerCodes(t *testing.T) {
	for _, code := range []string{"PARSE_ERROR", "UNSUPPORTED", "UNIMPLEMENTED", "REGION_MISMATCH", "SOMETHING_NEW"} {
		for _, strict := range []bool{false, true} {
			o, drop := replyOutcome(rejected(code), nil, strict, false, true)
			var re *RejectedError
			if o.kind != outcomeFailed || o.retryable || drop != "" || !errors.As(o.err, &re) || re.Code != code || re.Message != "msg" {
				t.Errorf("%s strict %v: %+v, drop %q; want a terminal *RejectedError with the code and message, no drop", code, strict, o, drop)
			}
		}
	}
}

// ORIGIN_ERROR and INTERNAL_ERROR are retryable only while the connection is healthy and the
// client open; otherwise they are the terminal rejection the cache sent (Python
// _RETRYABLE_CODES and is_connection_healthy).
func TestReplyOutcomeOriginCodes(t *testing.T) {
	for _, code := range []string{"ORIGIN_ERROR", "INTERNAL_ERROR"} {
		for _, strict := range []bool{false, true} {
			o, drop := replyOutcome(rejected(code), nil, strict, false, true)
			var oe *OriginError
			if o.kind != outcomeFailed || !o.retryable || drop != "" || !errors.As(o.err, &oe) || oe.Code != code {
				t.Errorf("%s strict %v, healthy: %+v, drop %q; want a retryable *OriginError, no drop", code, strict, o, drop)
			}
			if errors.As(o.err, new(*RejectedError)) {
				t.Errorf("%s healthy reads as a *RejectedError, want only an *OriginError", code)
			}
			o, drop = replyOutcome(rejected(code), nil, strict, false, false)
			var re *RejectedError
			if o.kind != outcomeFailed || o.retryable || drop != "" || !errors.As(o.err, &re) || re.Code != code ||
				errors.As(o.err, new(*OriginError)) {
				t.Errorf("%s strict %v, unhealthy: %+v, drop %q; want a terminal *RejectedError, no drop", code, strict, o, drop)
			}
			// After close the rejection is raised as sent, healthy or not.
			for _, healthy := range []bool{false, true} {
				o, _ := replyOutcome(rejected(code), nil, strict, true, healthy)
				if o.kind != outcomeFailed || o.retryable || !errors.As(o.err, &re) || re.Code != code ||
					errors.As(o.err, new(*OriginError)) {
					t.Errorf("%s strict %v after close, healthy %v: %+v; want a terminal *RejectedError", code, strict, healthy, o)
				}
			}
		}
	}
}

func TestReplyOutcomeAuthError(t *testing.T) {
	for _, closed := range []bool{false, true} {
		o, drop := replyOutcome(rejected("AUTH_ERROR"), nil, false, closed, false)
		if o.kind != outcomeOrigin || drop == "" {
			t.Errorf("AUTH_ERROR closed %v: %+v, drop %q; want DynamoDB and a drop", closed, o, drop)
		}
		o, drop = replyOutcome(rejected("AUTH_ERROR"), nil, true, closed, false)
		var cu *CacheUnavailableError
		if o.kind != outcomeFailed || !o.retryable || drop == "" || !errors.As(o.err, &cu) {
			t.Errorf("strict AUTH_ERROR closed %v: %+v, drop %q; want a retryable *CacheUnavailableError and a drop", closed, o, drop)
		}
		if errors.As(o.err, new(*RejectedError)) {
			t.Errorf("strict AUTH_ERROR closed %v wraps a *RejectedError, want none", closed)
		}
	}
}

func TestReplyOutcomeConnectionDrop(t *testing.T) {
	for _, code := range []string{"MOVED", "NOAUTH"} {
		for _, strict := range []bool{false, true} {
			o, drop := replyOutcome(rejected(code), nil, strict, false, false)
			if o.kind != outcomeFailed || !o.retryable || drop == "" || !errors.As(o.err, new(*ConnectionLostError)) {
				t.Errorf("%s strict %v: %+v, drop %q; want a retryable *ConnectionLostError and a drop", code, strict, o, drop)
			}
			if errors.As(o.err, new(*RejectedError)) {
				t.Errorf("%s strict %v wraps a *RejectedError, want none", code, strict)
			}
			if o, drop := replyOutcome(rejected(code), nil, strict, true, false); o.kind != outcomeFailed || o.retryable || drop == "" {
				t.Errorf("%s strict %v after close: %+v, drop %q; want a failure that is not retried, and a drop", code, strict, o, drop)
			}
		}
	}
}

func TestReplyOutcomeErrors(t *testing.T) {
	notSent := &RequestNotSentError{Msg: "x"}
	if o, _ := replyOutcome(requestReply{}, notSent, false, false, true); o.kind != outcomeOrigin {
		t.Errorf("not sent: %+v; want DynamoDB", o)
	}
	if o, _ := replyOutcome(requestReply{}, notSent, false, true, false); o.kind != outcomeFailed || o.retryable || o.err != error(notSent) {
		t.Errorf("not sent after close: %+v; want the *RequestNotSentError, not retried", o)
	}
	for _, closed := range []bool{false, true} {
		o, _ := replyOutcome(requestReply{}, notSent, true, closed, true)
		var cu *CacheUnavailableError
		if o.kind != outcomeFailed || !o.retryable || !errors.As(o.err, &cu) || !errors.Is(o.err, error(notSent)) {
			t.Errorf("strict not sent, closed %v: %+v; want a retryable *CacheUnavailableError wrapping it", closed, o)
		}
	}
	for _, err := range []error{&ConnectionLostError{Msg: "x"}, &TimeoutError{Msg: "x"}} {
		for _, strict := range []bool{false, true} {
			o, drop := replyOutcome(requestReply{}, err, strict, false, false)
			if o.kind != outcomeFailed || !o.retryable || o.err != err || drop != "" {
				t.Errorf("%T strict %v: %+v; want it retryable, no drop", err, strict, o)
			}
			if o, _ := replyOutcome(requestReply{}, err, strict, true, false); o.kind != outcomeFailed || o.retryable {
				t.Errorf("%T strict %v after close: %+v; want a failure that is not retried", err, strict, o)
			}
		}
	}
	if o, _ := replyOutcome(requestReply{}, &ProtocolError{Msg: "x"}, false, false, true); o.kind != outcomeFailed || o.retryable {
		t.Errorf("protocol error: %+v; want a failure that is not retried", o)
	}
	for _, err := range []error{context.Canceled, context.DeadlineExceeded} {
		if o, _ := replyOutcome(requestReply{}, err, false, false, true); o.kind != outcomeFailed || o.retryable || o.err != err {
			t.Errorf("%v: %+v; want it returned as is, not retried", err, o)
		}
	}
}

func TestClosedOutcome(t *testing.T) {
	for _, strict := range []bool{false, true} {
		o := closedOutcome(strict, false)
		if o.kind != outcomeFailed || o.retryable || !errors.As(o.err, new(*ConnectionLostError)) {
			t.Errorf("raced with Close, strict %v: %+v; want a *ConnectionLostError, not retried", strict, o)
		}
	}
	if o := closedOutcome(false, true); o.kind != outcomeOrigin {
		t.Errorf("waiting at Close: %+v; want DynamoDB", o)
	}
	o := closedOutcome(true, true)
	if o.kind != outcomeFailed || !o.retryable || !errors.As(o.err, new(*CacheUnavailableError)) || !errors.Is(o.err, ErrIntegrations) {
		t.Errorf("strict, waiting at Close: %+v; want a retryable *CacheUnavailableError", o)
	}
}

// CacheUnavailableError reasons use the words the Java, Node and Python clients share.
func TestCacheUnavailableReasons(t *testing.T) {
	const java = "could not serve the request from the cache, and origin fallback is not allowed: "
	authErr, _ := replyOutcome(rejected("AUTH_ERROR"), nil, true, false, true)
	notSent, _ := replyOutcome(requestReply{}, &RequestNotSentError{Msg: "x"}, true, false, true)
	for want, o := range map[string]requestOutcome{
		java + "the cache replied AUTH_ERROR":                      authErr,
		java + "the cache connection had no free capacity in time": notSent,
		java + "the client is closed":                              closedOutcome(true, true),
	} {
		var cu *CacheUnavailableError
		if !errors.As(o.err, &cu) || cu.Reason != want {
			t.Errorf("reason %+v, want %q", o.err, want)
		}
	}
}
