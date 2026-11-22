// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"context"
	"crypto/tls"
	"errors"
	"log/slog"
	"net"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
)

// authRejectionCodes are the HELLO error codes that mean the cache refused the credentials.
var authRejectionCodes = map[string]bool{"WRONGPASS": true, "invalid-iam-credentials": true}

// connection is one cache connection: one socket, one reader goroutine and one request
// tracker. It never reconnects; the session opens a new one.
type connection struct {
	cfg     *config
	conn    net.Conn
	reader  *resp.Reader // read by the bring-up, then only by the reader goroutine
	pending *pendingRequests
	onLost  func(error) // called once when the connection dies, not on close; must not block

	dead       chan struct{} // closed when the connection dies or is closed
	dieOnce    sync.Once
	cause      error // why it died; set before dead is closed
	closing    atomic.Bool
	started    atomic.Bool
	readerDone chan struct{}
}

func newConnection(c *config, nc net.Conn, onLost func(error)) *connection {
	conn := &connection{
		cfg:        c,
		conn:       nc,
		reader:     resp.NewReader(nc, c.readSize, c.maxResponseSize),
		onLost:     onLost,
		dead:       make(chan struct{}),
		readerDone: make(chan struct{}),
	}
	conn.pending = newPendingRequests(c.maxPending, conn.checkDeadlines)
	return conn
}

// openConnection brings one connection up before deadline: TCP, TLS, HELLO 3 AUTH with a
// token from auth, then aws.config with the overrides. On success the reader goroutine runs.
// A done ctx returns ctx.Err().
func openConnection(ctx context.Context, c *config, auth authProvider, deadline time.Time,
	onLost func(error)) (*connection, *resolvedConfig, error) {
	bctx, cancel := context.WithDeadline(ctx, deadline)
	defer cancel()
	nc, err := dialCache(bctx, c)
	if err != nil {
		return nil, nil, bringUpError(ctx, err)
	}
	_ = nc.SetDeadline(deadline)
	// A done bctx moves the socket deadline to the past, so a blocked read or write returns.
	stop := context.AfterFunc(bctx, func() { _ = nc.SetDeadline(time.Unix(1, 0)) })
	conn := newConnection(c, nc, onLost)
	rc, err := conn.handshake(bctx, auth)
	if err == nil && !stop() {
		err = stepFailed(bctx, "bring-up", bctx.Err())
	}
	if err == nil {
		if derr := nc.SetDeadline(time.Time{}); derr != nil {
			err = stepFailed(bctx, "bring-up", derr)
		}
	}
	if err != nil {
		stop()
		_ = closeSocket(nc)
		return nil, nil, bringUpError(ctx, err)
	}
	conn.started.Store(true)
	go conn.readLoop()
	return conn, rc, nil
}

// bringUpError returns ctx.Err() when the caller gave up, otherwise err.
func bringUpError(ctx context.Context, err error) error {
	if ctxErr := ctx.Err(); ctxErr != nil {
		return ctxErr
	}
	return err
}

// handshake sends HELLO 3 AUTH, then aws.config, before the reader goroutine starts.
func (c *connection) handshake(ctx context.Context, auth authProvider) (*resolvedConfig, error) {
	creds, err := auth.credentials(ctx)
	if err != nil {
		if ctx.Err() != nil {
			return nil, stepFailed(ctx, "IAM token", err)
		}
		return nil, err
	}
	hello := resp.AppendCommandStrings(nil, "HELLO", "3", "AUTH", creds.user, creds.token.reveal())
	v, err := c.exchange(ctx, hello, "HELLO 3")
	clear(hello)
	if err != nil {
		return nil, err
	}
	if err := checkHello(v); err != nil {
		return nil, err
	}
	frame, err := appendConfig(nil, c.cfg.cachePolicyOverrides)
	if err != nil {
		return nil, &ConnectionError{Msg: "aws.config cannot be written", Err: err}
	}
	if v, err = c.exchange(ctx, frame, "aws.config"); err != nil {
		return nil, err
	}
	rc, err := parseConfigReply(v, false)
	if err != nil {
		if errors.As(err, new(*OverridesRejectedError)) {
			return nil, err // the caller's to fix
		}
		return nil, &ConnectionError{Msg: "aws.config failed", Err: err}
	}
	return rc, nil
}

// exchange writes one bring-up frame and reads its reply.
func (c *connection) exchange(ctx context.Context, frame []byte, step string) (resp.Value, error) {
	if _, err := c.conn.Write(frame); err != nil {
		return resp.Value{}, stepFailed(ctx, step, err)
	}
	v, err := c.reader.Next()
	if err != nil {
		if !isTimeout(err) {
			err = readFailed(err)
		}
		return resp.Value{}, stepFailed(ctx, step, err)
	}
	return v, nil
}

// checkHello accepts a HELLO 3 reply that is a map with proto 3. Only the code of an error
// reply is kept.
func checkHello(v resp.Value) error {
	switch v.Kind {
	case resp.KindError:
		if code := v.ErrorCode(); authRejectionCodes[code] {
			return &AuthError{Code: code, Msg: "HELLO 3 rejected"}
		}
		return &ConnectionError{Msg: "HELLO 3 rejected: " + v.ErrorCode()}
	case resp.KindMap:
		if p, ok := v.MapGet("proto"); ok && p.Kind == resp.KindInteger && p.Int == 3 {
			return nil
		}
		return &ConnectionError{Msg: "HELLO 3 reply did not report proto 3"}
	default:
		return &ConnectionError{Msg: "HELLO 3 reply is " + v.String() + ", not a map"}
	}
}

// die ends the connection once: it closes the socket, fails every pending entry with lost
// (timedOut with timeoutErr), and, unless its owner closed it, logs and calls onLost.
func (c *connection) die(lost error, timedOut *pendingEntry, timeoutErr error) {
	first := false
	c.dieOnce.Do(func() {
		first = true
		c.cause = lost
		close(c.dead)
		_ = closeSocket(c.conn)
		c.pending.failAll(lost, timedOut, timeoutErr)
	})
	if !first || c.closing.Load() {
		return
	}
	if timedOut != nil {
		c.cfg.logger.warn(eventInOrderTimeout, "an in-order reply did not arrive in time; closing the connection")
	} else {
		c.cfg.logger.warn(eventConnectionLost, "the cache connection was lost", slog.String("error", lost.Error()))
	}
	if c.onLost != nil {
		c.onLost(lost)
	}
}

// closeSocket closes the socket at once. Under TLS it skips the close_notify alert, whose
// write waits up to 5 s for a cache that does not read.
func closeSocket(nc net.Conn) error {
	if tc, ok := nc.(*tls.Conn); ok {
		return tc.NetConn().Close()
	}
	return nc.Close()
}

// close fails every pending request and waits for the reader goroutine. It is idempotent and
// must not be called from onLost.
func (c *connection) close() {
	c.closing.Store(true)
	c.die(&ConnectionLostError{Msg: "the connection was closed"}, nil, nil)
	if c.started.Load() {
		<-c.readerDone
	}
}

// drop closes a live connection the session no longer trusts; onLost runs as for any death.
func (c *connection) drop(reason string) {
	c.die(&ConnectionLostError{Msg: reason}, nil, nil)
}

// alive reports whether the connection has not died or been closed.
func (c *connection) alive() bool {
	select {
	case <-c.dead:
		return false
	default:
		return true
	}
}

// err returns why the connection died, or nil while it is alive.
func (c *connection) err() error {
	if c.alive() {
		return nil
	}
	return c.cause
}

// checkDeadlines is the tracker's timer callback: an in-order entry past its deadline closes
// the connection, whether or not its caller still waits.
func (c *connection) checkDeadlines() {
	if fatal := c.pending.expire(time.Now(), c.timeoutError); fatal != nil {
		c.die(&ConnectionLostError{Msg: "an earlier request on the connection timed out"}, fatal, c.timeoutError())
	}
}

func (c *connection) timeoutError() error {
	ms := strconv.FormatInt(c.cfg.cacheRequestTimeout.Milliseconds(), 10)
	return &TimeoutError{Msg: "no reply within the " + ms + "ms cache request timeout"}
}
