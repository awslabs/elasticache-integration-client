// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"context"
	"errors"
	"fmt"
	"testing"
)

func TestRetryable(t *testing.T) {
	lost := &ConnectionLostError{Msg: "x"}
	cases := []struct {
		err  error
		want bool
	}{
		{&CacheUnavailableError{Reason: "x"}, true},
		{&OriginError{Code: "ORIGIN_ERROR"}, true},
		{&OriginError{Code: "INTERNAL_ERROR"}, true},
		{lost, true},
		{fmt.Errorf("wrapped: %w", lost), true},
		{&TimeoutError{Msg: "x"}, true},
		{&RejectedError{Code: "UNSUPPORTED"}, false},
		{&ProtocolError{Msg: "x"}, false},
		{context.Canceled, false},
		{context.DeadlineExceeded, false},
	}
	for _, tc := range cases {
		for _, strict := range []bool{false, true} {
			if got := retryable(tc.err, strict, false); got != tc.want {
				t.Errorf("retryable(%T, strict %v, open) = %v", tc.err, strict, got)
			}
			if got := retryable(tc.err, strict, true); got != (tc.want && !strict) {
				t.Errorf("retryable(%T, strict %v, closed) = %v", tc.err, strict, got)
			}
		}
	}
}

func rejected(code string) requestReply {
	return requestReply{failed: true, server: serverError{code: code, message: "msg"}}
}

func TestReplyOutcomeServed(t *testing.T) {
	r := response{statusCode: 400, body: []byte("{}")}
	o, drop := replyOutcome(requestReply{response: r}, nil, true, false)
	if o.kind != outcomeServed || o.response.statusCode != 400 || drop != "" {
		t.Fatalf("outcome %+v, drop %q", o, drop)
	}
}

func TestReplyOutcomeServerCodes(t *testing.T) {
	for _, code := range []string{"PARSE_ERROR", "UNSUPPORTED", "UNIMPLEMENTED", "REGION_MISMATCH", "SOMETHING_NEW"} {
		for _, strict := range []bool{false, true} {
			o, drop := replyOutcome(rejected(code), nil, strict, false)
			var re *RejectedError
			if o.kind != outcomeFailed || o.retryable || drop != "" || !errors.As(o.err, &re) || re.Code != code || re.Message != "msg" {
				t.Fatalf("%s strict %v: %+v, drop %q", code, strict, o, drop)
			}
		}
	}
	for _, code := range []string{"ORIGIN_ERROR", "INTERNAL_ERROR"} {
		o, drop := replyOutcome(rejected(code), nil, false, false)
		var oe *OriginError
		if o.kind != outcomeFailed || !o.retryable || drop != "" || !errors.As(o.err, &oe) || oe.Code != code {
			t.Fatalf("%s: %+v, drop %q", code, o, drop)
		}
		if errors.As(o.err, new(*RejectedError)) {
			t.Fatalf("%s reads as a terminal rejection", code)
		}
		if o, _ := replyOutcome(rejected(code), nil, true, true); o.retryable {
			t.Fatalf("%s strict after close is retryable", code)
		}
	}
}

func TestReplyOutcomeAuthError(t *testing.T) {
	o, drop := replyOutcome(rejected("AUTH_ERROR"), nil, false, false)
	if o.kind != outcomeOrigin || drop == "" {
		t.Fatalf("AUTH_ERROR: %+v, drop %q", o, drop)
	}
	o, drop = replyOutcome(rejected("AUTH_ERROR"), nil, true, false)
	var cu *CacheUnavailableError
	if o.kind != outcomeFailed || !o.retryable || drop == "" || !errors.As(o.err, &cu) {
		t.Fatalf("strict AUTH_ERROR: %+v, drop %q", o, drop)
	}
	if errors.As(o.err, new(*RejectedError)) {
		t.Fatal("strict AUTH_ERROR wraps a *RejectedError")
	}
}

func TestReplyOutcomeConnectionDrop(t *testing.T) {
	for _, code := range []string{"MOVED", "NOAUTH"} {
		for _, strict := range []bool{false, true} {
			o, drop := replyOutcome(rejected(code), nil, strict, false)
			if o.kind != outcomeFailed || !o.retryable || drop == "" || !errors.As(o.err, new(*ConnectionLostError)) {
				t.Fatalf("%s strict %v: %+v, drop %q", code, strict, o, drop)
			}
			if errors.As(o.err, new(*RejectedError)) {
				t.Fatalf("%s wraps a *RejectedError", code)
			}
		}
	}
}

func TestReplyOutcomeErrors(t *testing.T) {
	notSent := &RequestNotSentError{Msg: "x"}
	if o, _ := replyOutcome(requestReply{}, notSent, false, false); o.kind != outcomeOrigin {
		t.Fatalf("not sent: %+v", o)
	}
	o, _ := replyOutcome(requestReply{}, notSent, true, false)
	var cu *CacheUnavailableError
	if o.kind != outcomeFailed || !o.retryable || !errors.As(o.err, &cu) || !errors.Is(o.err, error(notSent)) {
		t.Fatalf("strict not sent: %+v", o)
	}
	for _, err := range []error{&ConnectionLostError{Msg: "x"}, &TimeoutError{Msg: "x"}} {
		o, drop := replyOutcome(requestReply{}, err, false, false)
		if o.kind != outcomeFailed || !o.retryable || o.err != err || drop != "" {
			t.Fatalf("%T: %+v", err, o)
		}
		if o, _ := replyOutcome(requestReply{}, err, false, true); !o.retryable {
			t.Fatalf("%T after close is not retryable", err)
		}
		if o, _ := replyOutcome(requestReply{}, err, true, true); o.retryable {
			t.Fatalf("%T strict after close is retryable", err)
		}
	}
	if o, _ := replyOutcome(requestReply{}, &ProtocolError{Msg: "x"}, false, false); o.kind != outcomeFailed || o.retryable {
		t.Fatalf("protocol error: %+v", o)
	}
	for _, err := range []error{context.Canceled, context.DeadlineExceeded} {
		if o, _ := replyOutcome(requestReply{}, err, false, false); o.kind != outcomeFailed || o.retryable || o.err != err {
			t.Fatalf("%v: %+v", err, o)
		}
	}
}

func TestClosedOutcome(t *testing.T) {
	if o := closedOutcome(false); o.kind != outcomeOrigin {
		t.Fatalf("closed: %+v", o)
	}
	o := closedOutcome(true)
	if o.kind != outcomeFailed || o.retryable || !errors.As(o.err, new(*CacheUnavailableError)) || !errors.Is(o.err, ErrIntegrations) {
		t.Fatalf("closed strict: %+v", o)
	}
}

func TestReplyOutcomeAfterClose(t *testing.T) {
	notSent := &RequestNotSentError{Msg: "x"}
	if o, drop := replyOutcome(rejected("AUTH_ERROR"), nil, false, true); o.kind != outcomeOrigin || drop == "" {
		t.Fatalf("AUTH_ERROR after close: %+v, drop %q", o, drop)
	}
	if o, _ := replyOutcome(requestReply{}, notSent, false, true); o.kind != outcomeOrigin {
		t.Fatalf("not sent after close: %+v", o)
	}
	for _, code := range []string{"MOVED", "NOAUTH"} {
		if o, drop := replyOutcome(rejected(code), nil, false, true); o.kind != outcomeFailed || !o.retryable || drop == "" {
			t.Fatalf("%s after close: %+v, drop %q", code, o, drop)
		}
	}
	// Strict after Close: every failure is final, as a retry could never reach DynamoDB.
	strictClosed := map[string]struct {
		reply requestReply
		err   error
	}{
		"AUTH_ERROR": {reply: rejected("AUTH_ERROR")},
		"MOVED":      {reply: rejected("MOVED")},
		"NOAUTH":     {reply: rejected("NOAUTH")},
		"not sent":   {err: notSent},
	}
	for name, tc := range strictClosed {
		o, _ := replyOutcome(tc.reply, tc.err, true, true)
		if o.kind != outcomeFailed || o.retryable {
			t.Fatalf("strict %s after close: %+v", name, o)
		}
		if errors.As(o.err, new(*RejectedError)) {
			t.Fatalf("strict %s after close wraps a *RejectedError", name)
		}
	}
}
