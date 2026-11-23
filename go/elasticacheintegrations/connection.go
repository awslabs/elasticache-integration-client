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

// maxAuthErrorCode is the longest HELLO or AUTH error code kept in an error.
const maxAuthErrorCode = 32

// authErrorCode is the code of an error reply to HELLO or AUTH, when it looks like one: 1 to
// 32 letters, digits, '_', '-' or '.'. Those commands carry the IAM token, so a broken cache
// that echoes it, or its 64-digit signature, cannot put it into an error or a log record.
func authErrorCode(v resp.Value) string {
	code := v.ErrorCode()
	if len(code) == 0 || len(code) > maxAuthErrorCode {
		return "(unrecognized error code)"
	}
	for i := 0; i < len(code); i++ {
		switch b := code[i]; {
		case 'a' <= b && b <= 'z', 'A' <= b && b <= 'Z', '0' <= b && b <= '9', b == '_', b == '-', b == '.':
		default:
			return "(unrecognized error code)"
		}
	}
	return code
}

// connection is one cache connection: one socket, one reader goroutine and one request
// tracker. It never reconnects; the session opens a new one.
type connection struct {
	cfg     *config
	conn    net.Conn
	reader  *resp.Reader // read by the bring-up, then only by the reader goroutine
	pending *pendingRequests
	turn    chan struct{} // the write turn: taken by a send, given back by a receive
	onLost  func(error)   // called once when the connection dies, not on close; must not block

	dead       chan struct{} // closed when the connection dies or is closed
	dieOnce    sync.Once
	cause      error // why it died; set before dead is closed
	closing    atomic.Bool
	ownerEnded bool // closing was set when the connection died; set before dead is closed
	started    atomic.Bool
	readerDone chan struct{}
}

func newConnection(c *config, nc net.Conn, onLost func(error)) *connection {
	conn := &connection{
		cfg:        c,
		conn:       nc,
		reader:     resp.NewReader(nc, c.readSize, c.maxResponseSize),
		turn:       make(chan struct{}, 1),
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
		code := authErrorCode(v)
		if authRejectionCodes[code] {
			return &AuthError{Code: code, Msg: "HELLO 3 rejected"}
		}
		return &ConnectionError{Msg: "HELLO 3 rejected: " + code}
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
// (timedOut with timeoutErr), and, unless its owner closed it first, logs ev and calls onLost.
// It reports whether this call ended it.
func (c *connection) die(ev eventID, lost error, timedOut *pendingEntry, timeoutErr error) bool {
	first := c.markDead(lost)
	if first {
		_ = closeSocket(c.conn)
		c.pending.failAll(lost, timedOut, timeoutErr)
	}
	return c.reportDeath(first, ev, lost)
}

// markDead marks the connection dead once, before any entry fails, so a caller that gets its
// error also finds the connection dead. It reports whether this call marked it.
func (c *connection) markDead(lost error) bool {
	first := false
	c.dieOnce.Do(func() {
		first = true
		c.cause = lost
		c.ownerEnded = c.closing.Load()
		close(c.dead)
	})
	return first
}

// reportDeath logs ev and calls onLost for the call that ended the connection, unless its
// owner closed it before it died, and returns first.
func (c *connection) reportDeath(first bool, ev eventID, lost error) bool {
	if !first || c.ownerEnded {
		return first
	}
	if ev == eventInOrderTimeout {
		c.cfg.logger.warn(ev, "an in-order reply did not arrive in time; closing the connection")
	} else {
		// One drop is routine (a proxy deployment); 1303 warns when reconnecting keeps failing.
		c.cfg.logger.info(ev, "the cache connection was lost", slog.String("error", lost.Error()))
	}
	if c.onLost != nil {
		c.onLost(lost)
	}
	return true
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
	c.die(eventConnectionLost, &ConnectionLostError{Msg: "the connection was closed"}, nil, nil)
	if c.started.Load() {
		<-c.readerDone
	}
}

// drop closes a live connection the session no longer trusts; onLost runs as for any death.
// It reports whether this call ended the connection.
func (c *connection) drop(reason string) bool {
	return c.die(eventConnectionLost, &ConnectionLostError{Msg: reason}, nil, nil)
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
// the connection, whether or not its caller still waits. expire marks the connection dead and
// fails every entry with its cause in one critical section.
func (c *connection) checkDeadlines() {
	first := false
	fatal := c.pending.expire(time.Now(), c.timeoutError, errEarlierTimedOut, func(lost error) error {
		first = c.markDead(lost)
		return c.cause
	})
	if fatal != nil && first {
		_ = closeSocket(c.conn)
		c.reportDeath(true, eventInOrderTimeout, c.cause)
	}
}

func errEarlierTimedOut() error {
	return &ConnectionLostError{Msg: "an earlier request on the connection timed out"}
}

func (c *connection) timeoutError() error {
	ms := strconv.FormatInt(c.cfg.cacheRequestTimeout.Milliseconds(), 10)
	return &TimeoutError{Msg: "no reply within the " + ms + "ms cache request timeout"}
}

// request sends one aws.request and returns its parsed reply. A *ProtocolError there is a
// reply of the wrong shape; the connection stays open.
func (c *connection) request(ctx context.Context, r *request) (requestReply, error) {
	cmd := newSerializedCommand()
	cmd.appendRequest(r)
	e, err := c.start(ctx, cmd.frame, true)
	cmd.release()
	if err != nil {
		return requestReply{}, err
	}
	v, err := c.await(ctx, e)
	if err != nil {
		return requestReply{}, err
	}
	return parseRequestReply(v)
}

// reauth sends AUTH with fresh credentials. An error reply keeps the connection; any reply
// other than +OK closes it. A timeout has already closed it.
func (c *connection) reauth(ctx context.Context, creds authCredentials) error {
	frame := resp.AppendCommandStrings(nil, "AUTH", creds.user, creds.token.reveal())
	e, err := c.start(ctx, frame, false)
	clear(frame)
	if err != nil {
		return err
	}
	v, err := c.await(ctx, e)
	if err != nil {
		return err
	}
	switch {
	case v.Kind == resp.KindError:
		return &AuthError{Code: authErrorCode(v), Msg: "AUTH rejected"}
	case v.Kind == resp.KindSimpleString && string(v.Bytes) == okReply:
		return nil
	}
	c.drop("the cache answered AUTH with " + v.String())
	return &AuthError{Msg: "AUTH reply is " + v.String() + ", not OK"}
}

// refreshConfig sends the no-argument aws.config. nil, nil means the config is unchanged.
func (c *connection) refreshConfig(ctx context.Context) (*resolvedConfig, error) {
	e, err := c.start(ctx, appendConfigRefresh(nil), false)
	if err != nil {
		return nil, err
	}
	v, err := c.await(ctx, e)
	if err != nil {
		return nil, err
	}
	return parseConfigReply(v, true)
}

// start writes frame and returns its pending entry. The request budget starts now: waiting
// for a pending slot and the write turn may use 75% of it, the reply gets the rest, and at
// least 25% of it after the write.
func (c *connection) start(ctx context.Context, frame []byte, deferrable bool) (*pendingEntry, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	timeout := c.cfg.cacheRequestTimeout
	deadline := time.Now().Add(timeout)
	a := admission{until: deadline.Add(-timeout / 4)}
	defer a.stop()
	if err := c.pending.slots.acquire(ctx, &a); err != nil {
		return nil, err
	}
	if err := c.takeTurn(ctx, &a); err != nil {
		c.pending.slots.release()
		return nil, err
	}
	e, err := c.write(ctx, frame, deferrable, deadline, a.until)
	<-c.turn
	return e, err
}

func errNoTurn() error {
	return &RequestNotSentError{Msg: "the request was not sent within its budget for sending"}
}

// takeTurn waits for the write turn until a's budget ends.
func (c *connection) takeTurn(ctx context.Context, a *admission) error {
	select {
	case c.turn <- struct{}{}:
		return nil
	default:
	}
	select {
	case c.turn <- struct{}{}:
		return nil
	case <-a.expired():
		return errNoTurn()
	case <-ctx.Done():
		return ctx.Err()
	case <-c.dead:
		return errConnectionClosed()
	}
}

// write registers the entry and writes the whole frame, under the write turn, so FIFO order
// is wire order. The caller's slot belongs to the entry once it is registered.
func (c *connection) write(ctx context.Context, frame []byte, deferrable bool, deadline, sendBy time.Time) (*pendingEntry, error) {
	if err := ctx.Err(); err != nil {
		c.pending.slots.release()
		return nil, err
	}
	if !time.Now().Before(sendBy) {
		c.pending.slots.release()
		return nil, errNoTurn()
	}
	e, err := c.pending.register(deferrable)
	if err != nil {
		c.pending.slots.release()
		return nil, err
	}
	_ = c.conn.SetWriteDeadline(deadline)
	_, err = c.conn.Write(frame)
	// A TLS read may write too (a key update), so no stale deadline is left behind.
	_ = c.conn.SetWriteDeadline(time.Time{})
	if err != nil {
		// Part of the frame may be on the wire, so the stream is out of step.
		if isTimeout(err) {
			c.die(eventConnectionLost, &ConnectionLostError{Msg: "a request write timed out"}, e,
				&TimeoutError{Msg: "the request write timed out", Err: err})
		} else {
			c.die(eventConnectionLost, &ConnectionLostError{Msg: "socket write failed", Err: err}, nil, nil)
		}
		<-e.done
		if e.err == nil {
			return e, nil // the reply arrived before the death
		}
		return nil, e.err
	}
	// The reply gets what is left of the budget, and at least a quarter of it.
	c.pending.setDeadline(e, laterOf(deadline, time.Now().Add(c.cfg.cacheRequestTimeout/4)))
	return e, nil
}

// await waits for e's reply or ctx. The tracker's timer ends e at its deadline. A caller
// whose ctx ends gets ctx.Err() unless the reply had already arrived.
func (c *connection) await(ctx context.Context, e *pendingEntry) (resp.Value, error) {
	select {
	case <-e.done:
	case <-ctx.Done():
		if c.pending.abandon(e) {
			return resp.Value{}, ctx.Err()
		}
		<-e.done
	}
	return e.value, e.err
}
