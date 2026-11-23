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
	response  response    // outcomeServed
	err       error       // outcomeFailed
	retryable bool        // outcomeFailed
	charge    *dropCharge // outcomeFailed by a connection drop
}

func toOrigin() requestOutcome         { return requestOutcome{kind: outcomeOrigin} }
func served(r response) requestOutcome { return requestOutcome{kind: outcomeServed, response: r} }
func failed(err error, retryable bool) requestOutcome {
	return requestOutcome{kind: outcomeFailed, err: err, retryable: retryable}
}

// retryable reports whether the SDK may retry a failed routed request, as the Python client
// decides it. closed is whether the client was closed when the request failed.
func retryable(err error, closed bool) bool {
	switch {
	case errors.As(err, new(*CacheUnavailableError)):
		return true // a retry after Close goes to DynamoDB
	case closed:
		return false // a request failed by Close is not retried
	case errors.As(err, new(*OriginError)),
		errors.As(err, new(*ConnectionLostError)), // includes the MOVED and NOAUTH drop
		errors.As(err, new(*TimeoutError)),
		errors.As(err, new(*ConnectionError)):
		return true
	default:
		return false // *RejectedError, *ProtocolError, ctx.Err()
	}
}

// noFallback starts every CacheUnavailableError reason, in the words Java, Node and Python use.
const noFallback = "could not serve the request from the cache, and origin fallback is not allowed: "

// closedOutcome is the outcome of a routed request that finds the session closed. waited is
// whether it was waiting for a reconnect when Close came: it then goes to DynamoDB, or in
// strict mode fails with a retryable *CacheUnavailableError, whose retry goes to DynamoDB.
// Otherwise it raced with Close and fails, not retried, as a request in flight at Close does.
func closedOutcome(strict, waited bool) requestOutcome {
	switch {
	case !waited:
		return failed(&ConnectionLostError{Msg: "the client is closed"}, false)
	case strict:
		return failed(unavailable(noFallback+"the client is closed", nil), true)
	}
	return toOrigin()
}

// failure fails a request with err and the retry decision retryable gives it.
func failure(err error, closed bool) requestOutcome {
	return failed(err, retryable(err, closed))
}

// unavailable is a *CacheUnavailableError. cause must never be a *RejectedError, or
// errors.As would read a retryable error as terminal.
func unavailable(reason string, cause error) error {
	return &CacheUnavailableError{Reason: reason, Err: cause}
}

// replyOutcome maps the reply, or the error, of a request sent on a live connection. healthy
// is whether the session has a live connection now. ORIGIN_ERROR and INTERNAL_ERROR are a
// retryable *OriginError only when healthy and not closed, else the *RejectedError the cache
// sent. drop is why the session must drop that connection, or empty.
func replyOutcome(reply requestReply, err error, strict, closed, healthy bool) (o requestOutcome, drop string) {
	if err != nil {
		switch {
		case errors.As(err, new(*RequestNotSentError)):
			switch {
			case strict:
				return failure(unavailable(noFallback+"the cache connection had no free capacity in time", err), closed), ""
			case closed:
				return failed(err, false), ""
			}
			return toOrigin(), ""
		case !errors.Is(err, ErrIntegrations):
			return failed(err, false), "" // the caller's ctx ended
		}
		return failure(err, closed), ""
	}
	if !reply.failed {
		return served(reply.response), ""
	}
	switch reply.server.kind() {
	case serverAuth:
		drop = "the cache replied AUTH_ERROR"
		if strict {
			return failure(unavailable(noFallback+"the cache replied AUTH_ERROR", nil), closed), drop
		}
		return toOrigin(), drop
	case serverDrop:
		drop = "the cache replied " + reply.server.code
		return failure(&ConnectionLostError{Msg: drop + "; the request did not run"}, closed), drop
	case serverOrigin:
		if healthy && !closed {
			return failed(reply.server.err(), true), "" // *OriginError
		}
		return failed(&RejectedError{Code: reply.server.code, Message: reply.server.message}, false), ""
	default:
		return failed(reply.server.err(), false), "" // *RejectedError
	}
}
