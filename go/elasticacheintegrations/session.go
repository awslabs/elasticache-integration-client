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

	live atomic.Pointer[connection] // nil while there is no connection
	// inflight counts the calls the client has routed here that have not returned.
	inflight atomic.Int64
	// failedAt is when a call routed here last failed, in Client.sinceStart nanoseconds; 0 if never.
	failedAt atomic.Int64
	snap     atomic.Pointer[snapshot]
	outage   atomic.Pointer[outage] // the latest outage; set before live is cleared

	isClosed atomic.Bool // set under mu when the state becomes closed

	mu           sync.Mutex // never held across I/O, a wait, or a call to drop or close
	state        sessionState
	reconnecting bool        // a reconnect goroutine runs
	dead         *connection // the dropped connection the next reconnect goroutine reaps
	confirmedAt  time.Time   // the last time the cache confirmed the config
	refresh      *periodicRunner

	reconnectFailures failureLog
	notSent           burstLog
}

// openSession opens the first connection, retrying until ConnectionTimeout has passed, and
// starts re-AUTH and config refresh. It closes auth on failure and on close; a client passes
// a sharedAuth, whose close does nothing.
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
	if s.refresh == nil {
		s.startRunners(snap.config.refreshInterval)
	} else {
		s.refresh.setInterval(snap.config.refreshInterval)
	}
	o := s.outage.Load()
	s.mu.Unlock()
	if o != nil {
		o.end()
	}
	s.lg.debug(eventConfigApplied, "applied the cache config", slog.Any("config", snap.config))
	return failures, true
}

// startRunners starts re-AUTH and config refresh. It runs once, under mu.
func (s *session) startRunners(refreshInterval time.Duration) {
	reauth := newPeriodicRunner("re-AUTH", s.cfg.reauthInterval, s.cfg,
		func(failures int) time.Duration { return backoff(s.cfg, failures) }, s.reauthOnce)
	s.refresh = newPeriodicRunner("config refresh", refreshInterval, s.cfg, nil, s.refreshOnce)
	s.wg.Add(2)
	go func() { defer s.wg.Done(); reauth.run(s.ctx) }()
	go func() { defer s.wg.Done(); s.refresh.run(s.ctx) }()
}

// reauthOnce sends AUTH with fresh credentials on the live connection (Java
// reauthenticateOnce). It does not run while there is none.
func (s *session) reauthOnce(ctx context.Context) (bool, error) {
	c := s.live.Load()
	if c == nil || !c.alive() {
		return false, nil
	}
	cctx, cancel := context.WithTimeout(ctx, s.cfg.cacheRequestTimeout)
	creds, err := s.auth.credentials(cctx)
	cancel()
	if err != nil {
		return true, err
	}
	if s.live.Load() != c {
		return false, nil
	}
	if err := c.reauth(ctx, creds); err != nil {
		if errors.As(err, new(*AuthError)) && c.alive() {
			fctx, cancel := context.WithTimeout(ctx, s.cfg.cacheRequestTimeout)
			_ = s.auth.forceRefresh(fctx)
			cancel()
		}
		return true, err
	}
	return s.live.Load() == c, nil
}

// refreshOnce fetches the config on the live connection and applies it (Java refreshOnce).
// It does not run while there is none.
func (s *session) refreshOnce(ctx context.Context) (bool, error) {
	c := s.live.Load()
	if c == nil || !c.alive() {
		return false, nil
	}
	rc, err := c.refreshConfig(ctx)
	var next *snapshot
	if err == nil && rc != nil {
		var preds *predicates
		if preds, err = compilePredicates(rc.raw); err == nil {
			next = &snapshot{config: rc, predicates: preds}
		}
	}
	if err != nil {
		return s.refreshFailed(c, err)
	}
	return s.refreshConfirmed(c, next), nil
}

// refreshConfirmed restarts the staleness window and installs next, when not nil, if c is
// still the live connection. It reports false when c was replaced.
func (s *session) refreshConfirmed(c *connection, next *snapshot) bool {
	s.mu.Lock()
	if s.state != sessionOpen || s.live.Load() != c {
		s.mu.Unlock()
		return false
	}
	if next != nil {
		s.snap.Store(next)
		s.refresh.setInterval(next.config.refreshInterval)
	}
	s.confirmedAt = time.Now()
	s.mu.Unlock()
	if next != nil {
		s.lg.debug(eventConfigApplied, "applied the cache config", slog.Any("config", next.config))
	}
	return true
}

// refreshFailed keeps the current config. Once it has gone unconfirmed for
// CachePolicyTimeout, it drops c, so the reconnect fetches a current one.
func (s *session) refreshFailed(c *connection, err error) (bool, error) {
	if !c.alive() {
		return true, err // the death already started the reconnect
	}
	s.mu.Lock()
	if s.state != sessionOpen || s.live.Load() != c {
		s.mu.Unlock()
		return false, nil
	}
	age := time.Since(s.confirmedAt)
	s.mu.Unlock()
	if errors.As(err, new(*ConfigUnevaluableError)) || errors.As(err, new(*OverridesRejectedError)) {
		s.lg.error(eventConfigUnusable, "the cache sent a config this client cannot use; keeping the current one",
			slog.String("error", err.Error()))
	}
	if age >= s.cfg.cachePolicyTimeout {
		s.lg.warn(eventConfigStale, "the config was not confirmed within CachePolicyTimeout; reconnecting to fetch a current one",
			slog.Duration("age", age), slog.Duration("cache_policy_timeout", s.cfg.cachePolicyTimeout))
		c.drop("the config was not confirmed within CachePolicyTimeout")
	}
	return true, err
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
				level := slog.LevelInfo
				if n == 0 {
					level = slog.LevelDebug // the first attempt worked; 1201 already said why
				}
				s.lg.log(level, eventReconnected, "reconnected to the cache", slog.Uint64("failed_attempts", n))
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

// connected reports whether the session has a live connection.
func (s *session) connected() bool {
	c := s.live.Load()
	return c != nil && c.alive()
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
	o, _ := s.tryRequest(ctx, r, true)
	return o
}

// tryRequest is request. With mayWait false it does not wait for a reconnect: it reports
// false, with no outcome, when the session has no live connection.
func (s *session) tryRequest(ctx context.Context, r *request, mayWait bool) (requestOutcome, bool) {
	strict := s.cfg.strict()
	for {
		if s.closed() {
			return closedOutcome(strict), true
		}
		if err := ctx.Err(); err != nil {
			return failed(err, false), true
		}
		if snap := s.snap.Load(); snap == nil || !snap.predicates.shouldRoute(r) {
			return toOrigin(), true
		}
		c := s.live.Load()
		if c == nil || !c.alive() {
			if c != nil {
				s.lost(c)
			}
			if !mayWait {
				return requestOutcome{}, false
			}
			if o, done := s.awaitReconnect(ctx, strict); done {
				return o, true
			}
			continue
		}
		reply, err := c.request(ctx, r)
		o, drop := replyOutcome(reply, err, strict, s.closed())
		if drop != "" && c.drop(drop) && reply.server.kind() == serverAuth {
			s.lg.warn(eventAuthDegraded, "the cache has no valid AWS credentials for this connection; reconnecting")
		}
		if o.retryable && !c.alive() && droppedBy(o.err) {
			o.charge = c.charge
		}
		if err != nil && errors.As(err, new(*RequestNotSentError)) {
			if s.notSent.hit(time.Now()) {
				s.lg.warn(eventPendingLimitReached, "a request found no capacity on the cache connection in time")
			}
		}
		return o, true
	}
}

// droppedBy reports whether err is how a dying connection fails its requests. A deferred
// timeout is not, even when the connection dies on the same tick.
func droppedBy(err error) bool {
	var te *TimeoutError
	return errors.As(err, new(*ConnectionLostError)) || errors.As(err, &te) && te.closedConn
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
	return failure(unavailable(noFallback+"there is no cache connection", nil), strict, false), true
}
