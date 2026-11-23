// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"errors"
	"io"

	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
)

// readLoop reads every reply on the connection until it dies. It is the only reader of
// c.reader once the bring-up is done.
func (c *connection) readLoop() {
	defer close(c.readerDone)
	for {
		v, err := c.reader.Next()
		if err == nil {
			err = c.dispatch(v)
		}
		if err != nil {
			c.die(eventConnectionLost, readFailed(err), nil, nil)
			return
		}
	}
}

// dispatch routes one reply: a push to its deferred entry, a deferred id to the FIFO head,
// anything else to the FIFO head as its reply. An error means the stream cannot be trusted.
func (c *connection) dispatch(v resp.Value) error {
	if v.Kind == resp.KindPush {
		id, reply, err := parseResponsePush(v)
		if err != nil {
			return err
		}
		if !c.pending.resolveDeferred(id, reply) {
			c.cfg.logger.debug(eventLatePushDropped, "dropped a push for a request that is no longer pending")
		}
		return nil
	}
	id, ok, err := asyncResponseID(v)
	if err != nil {
		return err
	}
	if ok {
		moved, err := c.pending.deferHead(id)
		if err != nil || moved {
			return err
		}
	}
	if !c.pending.resolveHead(v) {
		return &ProtocolError{Msg: "a reply arrived with nothing pending"}
	}
	return nil
}

// readFailed is the error pending requests get when reading or dispatching fails.
func readFailed(err error) error {
	switch {
	case errors.As(err, new(*ProtocolError)):
		return &ConnectionLostError{Msg: "reply stream corrupt", Err: err}
	case errors.Is(err, resp.ErrCorrupt):
		return &ConnectionLostError{Msg: "reply stream corrupt", Err: &ProtocolError{Err: err}}
	case errors.Is(err, io.EOF):
		return &ConnectionLostError{Msg: "the cache closed the connection"}
	case errors.Is(err, io.ErrUnexpectedEOF):
		return &ConnectionLostError{Msg: "the cache closed the connection inside a reply"}
	default:
		return &ConnectionLostError{Msg: "socket read failed", Err: err}
	}
}
