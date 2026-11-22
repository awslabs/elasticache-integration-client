// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"context"
	"errors"
	"log/slog"
	"strconv"
	"sync"
	"sync/atomic"
	"time"
)

type sessionState uint8

const (
	sessionOpening sessionState = iota
	sessionOpen
	sessionReconnecting
	sessionClosed
)

// snapshot is a config and the predicates compiled from it, replaced together.
type snapshot struct {
	config     *resolvedConfig
	predicates *predicates
}

// outage is the time from a drop to the next published connection. Its deadlines are set
// once, at the drop.
type outage struct {
	ready         chan struct{} // closed when a connection is published or the session closes
	readyOnce     sync.Once
	readyAt       time.Time // requests wait for the reconnect until then
	fallbackUntil time.Time // then go to DynamoDB until then
	unlimited     bool      // no fallback limit
	exhausted     atomic.Bool
}

func (o *outage) end() { o.readyOnce.Do(func() { close(o.ready) }) }

// session owns the live cache connection: it opens it, replaces it after a drop, routes
// requests to it, and runs re-AUTH and config refresh on it.
type session struct {
	cfg  *config
	auth authProvider
	lg   *logger

	ctx       context.Context // cancelled by close; every goroutine of the session stops on it
	cancel    context.CancelFunc
	wg        sync.WaitGroup
	closeDone chan struct{} // closed when close has finished

	live   atomic.Pointer[connection] // nil while there is no connection
	snap   atomic.Pointer[snapshot]
	outage atomic.Pointer[outage] // the latest outage; set before live is cleared

	isClosed atomic.Bool // set under mu when the state becomes closed

	mu           sync.Mutex // never held across I/O, a wait, or a call to drop or close
	state        sessionState
	reconnecting bool        // a reconnect goroutine runs
	dead         *connection // the dropped connection the next reconnect goroutine reaps
	confirmedAt  time.Time   // the last time the cache confirmed the config

	reconnectFailures failureLog
	notSent           failureLog
}

// openSession opens the first connection, retrying until ConnectionTimeout has passed. It
// owns auth from now on, and closes it on failure.
func openSession(ctx context.Context, c *config, auth authProvider) (*session, error) {
	s := newSession(c, auth)
	if err := s.open(ctx); err != nil {
		s.close()
		return nil, err
	}
	return s, nil
}

func newSession(c *config, auth authProvider) *session {
	ctx, cancel := context.WithCancel(context.Background())
	return &session{cfg: c, auth: auth, lg: c.logger, ctx: ctx, cancel: cancel, closeDone: make(chan struct{})}
}

// open is the start-up loop (Java Session.open).
func (s *session) open(ctx context.Context) error {
	deadline := time.Now().Add(s.cfg.connectionTimeout)
	actx, stop := s.attemptContext(ctx)
	defer stop()
	var last error
	for attempt := 1; ; attempt++ {
		conn, snap, err := s.attempt(actx, deadline)
		if err == nil {
			if _, ok := s.publish(conn, snap); ok {
				return nil
			}
			err = &ConnectionError{Msg: "the connection closed while opening"}
		}
		if actx.Err() != nil || s.closed() {
			return s.openStopped(ctx)
		}
		if errors.As(err, new(*OverridesRejectedError)) || errors.As(err, new(*ConfigUnevaluableError)) {
			return err // a retry sends the same overrides and gets the same answer
		}
		last = err
		s.lg.debug(eventBringUpAttemptFailed, "connecting to the cache failed; retrying",
			slog.Int("attempt", attempt), slog.String("error", err.Error()))
		left := time.Until(deadline)
		if left <= 0 {
			break
		}
		if !sleepCtx(actx, min(backoff(s.cfg, attempt), left)) {
			return s.openStopped(ctx)
		}
		if time.Until(deadline) <= 0 {
			break
		}
	}
	if errors.As(last, new(*ConnectionError)) {
		ms := strconv.FormatInt(s.cfg.connectionTimeout.Milliseconds(), 10)
		return &ConnectionError{Msg: "opening a connection timed out after " + ms + "ms", Err: last}
	}
	return last
}

// openStopped is the error of a start-up stopped by the caller's ctx or by close.
func (s *session) openStopped(ctx context.Context) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	return &ConnectionError{Msg: "the client is closed"}
}

// attemptContext is ctx, also cancelled when the session closes.
func (s *session) attemptContext(ctx context.Context) (context.Context, func()) {
	actx, cancel := context.WithCancel(ctx)
	stop := context.AfterFunc(s.ctx, cancel)
	return actx, func() { stop(); cancel() }
}

// attempt brings up one connection before deadline and compiles its predicates.
func (s *session) attempt(ctx context.Context, deadline time.Time) (*connection, *snapshot, error) {
	var self atomic.Pointer[connection]
	onLost := func(error) {
		if c := self.Load(); c != nil {
			s.lost(c)
		}
	}
	conn, rc, err := openConnection(ctx, s.cfg, s.auth, deadline, onLost)
	if err != nil {
		if errors.As(err, new(*OverridesRejectedError)) {
			s.lg.error(eventConfigUnusable, "the cache rejected the overrides (CONFIG_INVALID); the connection is closed",
				slog.String("error", err.Error()))
		}
		if errors.As(err, new(*AuthError)) {
			fctx, cancel := context.WithDeadline(ctx, deadline)
			_ = s.auth.forceRefresh(fctx)
			cancel()
		}
		return nil, nil, err
	}
	self.Store(conn)
	preds, err := compilePredicates(rc.raw)
	if err != nil {
		conn.close()
		s.lg.error(eventConfigUnusable, "the cache sent a config this client cannot evaluate; the connection is closed",
			slog.String("error", err.Error()))
		return nil, nil, err
	}
	return conn, &snapshot{config: rc, predicates: preds}, nil
}

// publish makes conn the live connection and returns the reconnect failures before it. It
// reports false, and closes conn, when the session closed or conn died first.
func (s *session) publish(conn *connection, snap *snapshot) (failures uint64, ok bool) {
	s.mu.Lock()
	if s.state == sessionClosed || !conn.alive() {
		s.mu.Unlock()
		conn.close()
		return 0, false
	}
	s.snap.Store(snap)
	s.confirmedAt = time.Now()
	s.live.Store(conn)
	s.state = sessionOpen
	s.reconnecting = false
	failures = s.reconnectFailures.recovered() // before a drop of conn can count a failure
	o := s.outage.Load()
	s.mu.Unlock()
	if o != nil {
		o.end()
	}
	s.lg.debug(eventConfigApplied, "applied the cache config", slog.Any("config", snap.config))
	return failures, true
}

// lost starts an outage when c is the live connection. It runs from onLost, so it must not
// block.
func (s *session) lost(c *connection) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.state != sessionOpen || s.live.Load() != c {
		return
	}
	now := time.Now()
	o := &outage{ready: make(chan struct{}), readyAt: now.Add(s.cfg.connectionTimeout)}
	o.unlimited = s.cfg.fallbackUnlimited
	o.fallbackUntil = o.readyAt.Add(s.cfg.fallbackDuration)
	s.outage.Store(o) // before live is cleared, so a request that sees no connection sees o
	s.live.Store(nil)
	s.state = sessionReconnecting
	s.dead = c
	if !s.reconnecting {
		s.reconnecting = true
		s.wg.Add(1)
		go s.reconnectLoop()
	}
}

// reconnectLoop brings up connections until one is published or the session closes.
func (s *session) reconnectLoop() {
	defer s.wg.Done()
	for attempt := 1; ; attempt++ {
		s.mu.Lock()
		dead := s.dead
		s.dead = nil
		s.mu.Unlock()
		if dead != nil {
			dead.close() // reaps its reader goroutine
		}
		if s.ctx.Err() != nil {
			return
		}
		conn, snap, err := s.attempt(s.ctx, time.Now().Add(s.cfg.reconnectAttemptTimeout))
		if err == nil {
			if n, ok := s.publish(conn, snap); ok {
				s.lg.info(eventReconnected, "reconnected to the cache", slog.Uint64("failed_attempts", n))
				return
			}
			if s.closed() {
				return
			}
			err = &ConnectionError{Msg: "the new connection closed before it was used"}
		}
		if s.ctx.Err() != nil {
			return
		}
		if n, log := s.reconnectFailures.failed(); log {
			s.lg.warn(eventReconnecting, "reconnecting to the cache failed; retrying in the background",
				slog.Uint64("failures", n), slog.String("error", err.Error()))
		} else {
			s.lg.debug(eventReconnecting, "reconnecting to the cache failed",
				slog.Uint64("failures", n), slog.String("error", err.Error()))
		}
		if !sleepCtx(s.ctx, backoff(s.cfg, attempt)) {
			return
		}
	}
}

// closed reports whether close has been called.
func (s *session) closed() bool { return s.isClosed.Load() }

// close stops every goroutine, fails the requests in flight and closes the auth provider.
// It never waits for the cache and is idempotent; a later call waits for the first.
func (s *session) close() {
	s.mu.Lock()
	if s.state == sessionClosed {
		s.mu.Unlock()
		<-s.closeDone
		return
	}
	s.state = sessionClosed
	s.isClosed.Store(true)
	live := s.live.Swap(nil)
	dead := s.dead
	s.dead = nil
	s.mu.Unlock()
	s.cancel()
	if o := s.outage.Load(); o != nil {
		o.end()
	}
	if live != nil {
		live.close()
	}
	if dead != nil {
		dead.close()
	}
	s.wg.Wait()
	s.auth.close()
	close(s.closeDone)
}

// sleepCtx waits d, and reports false when ctx ended first.
func sleepCtx(ctx context.Context, d time.Duration) bool {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-t.C:
		return true
	case <-ctx.Done():
		return false
	}
}

// request sends r through the cache and returns its one outcome (Java Session.request).
func (s *session) request(ctx context.Context, r *request) requestOutcome {
	strict := s.cfg.strict()
	for {
		if s.closed() {
			return closedOutcome(strict)
		}
		if err := ctx.Err(); err != nil {
			return failed(err, false)
		}
		if snap := s.snap.Load(); snap == nil || !snap.predicates.shouldRoute(r) {
			return toOrigin()
		}
		c := s.live.Load()
		if c == nil || !c.alive() {
			if c != nil {
				s.lost(c)
			}
			if o, done := s.awaitReconnect(ctx, strict); done {
				return o
			}
			continue
		}
		reply, err := c.request(ctx, r)
		o, drop := replyOutcome(reply, err, strict, s.closed())
		if drop != "" && c.drop(drop) && reply.server.kind() == serverAuth {
			s.lg.warn(eventAuthDegraded, "the cache has no valid AWS credentials for this connection; reconnecting")
		}
		if err != nil && errors.As(err, new(*RequestNotSentError)) {
			if n, log := s.notSent.failed(); log {
				s.lg.warn(eventPendingLimitReached, "a request found no capacity on the cache connection in time",
					slog.Uint64("count", n))
			}
		}
		return o
	}
}

// awaitReconnect waits for the current outage's reconnect until its ready deadline. done is
// false when a connection was published (or the session closed): the caller looks again.
func (s *session) awaitReconnect(ctx context.Context, strict bool) (o requestOutcome, done bool) {
	out := s.outage.Load()
	if out == nil {
		return closedOutcome(strict), true // no outage and no connection: only after close
	}
	if wait := time.Until(out.readyAt); wait > 0 {
		t := time.NewTimer(wait)
		select {
		case <-out.ready:
			t.Stop()
			return requestOutcome{}, false
		case <-ctx.Done():
			t.Stop()
			return failed(ctx.Err(), false), true
		case <-t.C:
		}
	}
	select {
	case <-out.ready:
		return requestOutcome{}, false
	default:
	}
	if !strict && (out.unlimited || time.Now().Before(out.fallbackUntil)) {
		return toOrigin(), true
	}
	if out.exhausted.CompareAndSwap(false, true) {
		s.lg.warn(eventCacheUnavailable, "the cache is unreachable and fallback to DynamoDB is not allowed; requests fail until it reconnects")
	}
	return failure(unavailable("there is no cache connection, and fallback to DynamoDB is not allowed now", nil), strict, false), true
}
