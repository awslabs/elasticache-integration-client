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
		err        error
		open, done bool // retryable while open, and once closed
	}{
		{&CacheUnavailableError{Reason: "x"}, true, true},
		{&OriginError{Code: "ORIGIN_ERROR"}, true, false},
		{&OriginError{Code: "INTERNAL_ERROR"}, true, false},
		{lost, true, false},
		{fmt.Errorf("wrapped: %w", lost), true, false},
		{&TimeoutError{Msg: "x"}, true, false},
		{&ConnectionError{Msg: "x"}, true, false},
		{&RejectedError{Code: "UNSUPPORTED"}, false, false},
		{&RejectedError{Code: "ORIGIN_ERROR"}, false, false},
		{&ProtocolError{Msg: "x"}, false, false},
		{&AuthError{Code: "WRONGPASS"}, false, false},
		{&RequestNotSentError{Msg: "x"}, false, false},
		{context.Canceled, false, false},
		{context.DeadlineExceeded, false, false},
	}
	for _, tc := range cases {
		if got := retryable(tc.err, false); got != tc.open {
			t.Errorf("retryable(%T %v, open) = %v", tc.err, tc.err, got)
		}
		if got := retryable(tc.err, true); got != tc.done {
			t.Errorf("retryable(%T %v, closed) = %v", tc.err, tc.err, got)
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
		t.Fatalf("outcome %+v, drop %q", o, drop)
	}
}

func TestReplyOutcomeServerCodes(t *testing.T) {
	for _, code := range []string{"PARSE_ERROR", "UNSUPPORTED", "UNIMPLEMENTED", "REGION_MISMATCH", "SOMETHING_NEW"} {
		for _, strict := range []bool{false, true} {
			o, drop := replyOutcome(rejected(code), nil, strict, false, true)
			var re *RejectedError
			if o.kind != outcomeFailed || o.retryable || drop != "" || !errors.As(o.err, &re) || re.Code != code || re.Message != "msg" {
				t.Fatalf("%s strict %v: %+v, drop %q", code, strict, o, drop)
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
				t.Fatalf("%s strict %v, healthy: %+v, drop %q", code, strict, o, drop)
			}
			if errors.As(o.err, new(*RejectedError)) {
				t.Fatalf("%s reads as a terminal rejection", code)
			}
			o, drop = replyOutcome(rejected(code), nil, strict, false, false)
			var re *RejectedError
			if o.kind != outcomeFailed || o.retryable || drop != "" || !errors.As(o.err, &re) || re.Code != code ||
				errors.As(o.err, new(*OriginError)) {
				t.Fatalf("%s strict %v, unhealthy: %+v, drop %q", code, strict, o, drop)
			}
			// After close the rejection is raised as sent, healthy or not.
			for _, healthy := range []bool{false, true} {
				o, _ := replyOutcome(rejected(code), nil, strict, true, healthy)
				if o.kind != outcomeFailed || o.retryable || !errors.As(o.err, &re) || re.Code != code ||
					errors.As(o.err, new(*OriginError)) {
					t.Fatalf("%s strict %v after close, healthy %v: %+v", code, strict, healthy, o)
				}
			}
		}
	}
}

func TestReplyOutcomeAuthError(t *testing.T) {
	for _, closed := range []bool{false, true} {
		o, drop := replyOutcome(rejected("AUTH_ERROR"), nil, false, closed, false)
		if o.kind != outcomeOrigin || drop == "" {
			t.Fatalf("AUTH_ERROR closed %v: %+v, drop %q", closed, o, drop)
		}
		o, drop = replyOutcome(rejected("AUTH_ERROR"), nil, true, closed, false)
		var cu *CacheUnavailableError
		if o.kind != outcomeFailed || !o.retryable || drop == "" || !errors.As(o.err, &cu) {
			t.Fatalf("strict AUTH_ERROR closed %v: %+v, drop %q", closed, o, drop)
		}
		if errors.As(o.err, new(*RejectedError)) {
			t.Fatal("strict AUTH_ERROR wraps a *RejectedError")
		}
	}
}

func TestReplyOutcomeConnectionDrop(t *testing.T) {
	for _, code := range []string{"MOVED", "NOAUTH"} {
		for _, strict := range []bool{false, true} {
			o, drop := replyOutcome(rejected(code), nil, strict, false, false)
			if o.kind != outcomeFailed || !o.retryable || drop == "" || !errors.As(o.err, new(*ConnectionLostError)) {
				t.Fatalf("%s strict %v: %+v, drop %q", code, strict, o, drop)
			}
			if errors.As(o.err, new(*RejectedError)) {
				t.Fatalf("%s wraps a *RejectedError", code)
			}
			if o, drop := replyOutcome(rejected(code), nil, strict, true, false); o.kind != outcomeFailed || o.retryable || drop == "" {
				t.Fatalf("%s strict %v after close: %+v, drop %q", code, strict, o, drop)
			}
		}
	}
}

func TestReplyOutcomeErrors(t *testing.T) {
	notSent := &RequestNotSentError{Msg: "x"}
	if o, _ := replyOutcome(requestReply{}, notSent, false, false, true); o.kind != outcomeOrigin {
		t.Fatalf("not sent: %+v", o)
	}
	if o, _ := replyOutcome(requestReply{}, notSent, false, true, false); o.kind != outcomeFailed || o.retryable || o.err != error(notSent) {
		t.Fatalf("not sent after close: %+v", o)
	}
	for _, closed := range []bool{false, true} {
		o, _ := replyOutcome(requestReply{}, notSent, true, closed, true)
		var cu *CacheUnavailableError
		if o.kind != outcomeFailed || !o.retryable || !errors.As(o.err, &cu) || !errors.Is(o.err, error(notSent)) {
			t.Fatalf("strict not sent, closed %v: %+v", closed, o)
		}
	}
	for _, err := range []error{&ConnectionLostError{Msg: "x"}, &TimeoutError{Msg: "x"}} {
		for _, strict := range []bool{false, true} {
			o, drop := replyOutcome(requestReply{}, err, strict, false, false)
			if o.kind != outcomeFailed || !o.retryable || o.err != err || drop != "" {
				t.Fatalf("%T strict %v: %+v", err, strict, o)
			}
			if o, _ := replyOutcome(requestReply{}, err, strict, true, false); o.kind != outcomeFailed || o.retryable {
				t.Fatalf("%T strict %v after close: %+v", err, strict, o)
			}
		}
	}
	if o, _ := replyOutcome(requestReply{}, &ProtocolError{Msg: "x"}, false, false, true); o.kind != outcomeFailed || o.retryable {
		t.Fatalf("protocol error: %+v", o)
	}
	for _, err := range []error{context.Canceled, context.DeadlineExceeded} {
		if o, _ := replyOutcome(requestReply{}, err, false, false, true); o.kind != outcomeFailed || o.retryable || o.err != err {
			t.Fatalf("%v: %+v", err, o)
		}
	}
}

func TestClosedOutcome(t *testing.T) {
	for _, strict := range []bool{false, true} {
		o := closedOutcome(strict, false)
		if o.kind != outcomeFailed || o.retryable || !errors.As(o.err, new(*ConnectionLostError)) {
			t.Fatalf("raced with Close, strict %v: %+v", strict, o)
		}
	}
	if o := closedOutcome(false, true); o.kind != outcomeOrigin {
		t.Fatalf("waiting at Close: %+v", o)
	}
	o := closedOutcome(true, true)
	if o.kind != outcomeFailed || !o.retryable || !errors.As(o.err, new(*CacheUnavailableError)) || !errors.Is(o.err, ErrIntegrations) {
		t.Fatalf("strict, waiting at Close: %+v", o)
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
