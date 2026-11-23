// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import "errors"

// outcomeKind is what the SDK adapter does with a routed request.
type outcomeKind uint8

const (
	outcomeOrigin outcomeKind = iota // send the signed request to DynamoDB
	outcomeServed                    // return the cache's response
	outcomeFailed                    // fail the attempt with err; the SDK retries it when retryable
)

// requestOutcome is the one result of a request through the session.
type requestOutcome struct {
	kind      outcomeKind
	response  response // outcomeServed
	err       error    // outcomeFailed
	retryable bool     // outcomeFailed
}

func toOrigin() requestOutcome         { return requestOutcome{kind: outcomeOrigin} }
func served(r response) requestOutcome { return requestOutcome{kind: outcomeServed, response: r} }
func failed(err error, retryable bool) requestOutcome {
	return requestOutcome{kind: outcomeFailed, err: err, retryable: retryable}
}

// retryable reports whether the SDK may retry a failed routed request.
func retryable(err error, strict, closed bool) bool {
	switch {
	case closed && strict:
		return false // strict mode never reaches DynamoDB, also after Close
	case errors.As(err, new(*CacheUnavailableError)),
		errors.As(err, new(*OriginError)),
		errors.As(err, new(*ConnectionLostError)),
		errors.As(err, new(*TimeoutError)):
		return true
	default:
		return false // *RejectedError, *ProtocolError, ctx.Err()
	}
}

// noFallback starts every CacheUnavailableError reason, in the words Java, Node and Python use.
const noFallback = "could not serve the request from the cache, and origin fallback is not allowed: "

// closedOutcome is the outcome of a routed request after Close.
func closedOutcome(strict bool) requestOutcome {
	if strict {
		return failed(unavailable(noFallback+"the client is closed", nil), false)
	}
	return toOrigin()
}

// failure fails a request with err and the retry decision retryable gives it.
func failure(err error, strict, closed bool) requestOutcome {
	return failed(err, retryable(err, strict, closed))
}

// unavailable is a *CacheUnavailableError. cause must never be a *RejectedError, or
// errors.As would read a retryable error as terminal.
func unavailable(reason string, cause error) error {
	return &CacheUnavailableError{Reason: reason, Err: cause}
}

// replyOutcome maps the reply, or the error, of a request sent on a live connection. drop is
// why the session must drop that connection, or empty.
func replyOutcome(reply requestReply, err error, strict, closed bool) (o requestOutcome, drop string) {
	if err != nil {
		switch {
		case errors.As(err, new(*RequestNotSentError)):
			if strict {
				return failure(unavailable(noFallback+"the cache connection had no free capacity in time", err), strict, closed), ""
			}
			return toOrigin(), ""
		case !errors.Is(err, ErrIntegrations):
			return failed(err, false), "" // the caller's ctx ended
		}
		return failure(err, strict, closed), ""
	}
	if !reply.failed {
		return served(reply.response), ""
	}
	switch reply.server.kind() {
	case serverAuth:
		drop = "the cache replied AUTH_ERROR"
		if strict {
			return failure(unavailable(noFallback+"the cache replied AUTH_ERROR", nil), strict, closed), drop
		}
		return toOrigin(), drop
	case serverDrop:
		drop = "the cache replied " + reply.server.code
		return failure(&ConnectionLostError{Msg: drop + "; the request did not run"}, strict, closed), drop
	default:
		return failure(reply.server.err(), strict, closed), "" // *OriginError or *RejectedError
	}
}
