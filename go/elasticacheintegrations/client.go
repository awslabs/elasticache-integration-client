// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"context"
	"errors"
	"fmt"
	"io"
	"sync"
	"sync/atomic"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	awshttp "github.com/aws/aws-sdk-go-v2/aws/transport/http"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
)

// Client owns the cache connections of one DynamoDB client. Call Close when the DynamoDB
// client is no longer used.
type Client struct {
	cfg      *config
	identity identity
	sessions []*session   // one per connection; each reconnects and falls back on its own
	auth     authProvider // shared by every session; closed after them
	next     atomic.Uint64
	start    time.Time // when the client was created; the base of session.failedAt

	installErr error
	isClosed   atomic.Bool
	closeOnce  sync.Once
}

// NewFromConfig builds a DynamoDB client from cfg and optFns, as [dynamodb.NewFromConfig] does,
// and connects it to the cache: TCP, TLS, HELLO 3 AUTH and the first aws.config. It returns
// only when the cache is usable, so a client that would never cache is not returned.
//
// The returned client is a plain *dynamodb.Client. Calls the cache serves go to the cache;
// every other call goes to DynamoDB through the HTTP client the SDK would have used, which
// optFns may set. The IAM token for the cache is signed with the DynamoDB client's own region
// and credentials. A call made with a per-call region, credentials or HTTPClient override
// goes to DynamoDB. The client's retryer is wrapped so that the requests one dropped
// cache connection fails cost its retry quota one retry; a Retryer set per call is not.
func NewFromConfig(ctx context.Context, cfg aws.Config, opts Options,
	optFns ...func(*dynamodb.Options)) (*dynamodb.Client, *Client, error) {
	c, err := opts.validate()
	if err != nil {
		return nil, nil, err
	}
	return start(ctx, c, func(fns ...func(*dynamodb.Options)) *dynamodb.Client {
		return dynamodb.NewFromConfig(cfg, fns...)
	}, optFns)
}

// New is NewFromConfig for callers that build [dynamodb.Options] themselves, as
// [dynamodb.New] does.
func New(ctx context.Context, options dynamodb.Options, opts Options,
	optFns ...func(*dynamodb.Options)) (*dynamodb.Client, *Client, error) {
	c, err := opts.validate()
	if err != nil {
		return nil, nil, err
	}
	return start(ctx, c, func(fns ...func(*dynamodb.Options)) *dynamodb.Client {
		return dynamodb.New(options, fns...)
	}, optFns)
}

// start builds the DynamoDB client with install as its last option function, then opens the
// session. On failure nothing it started keeps running.
func start(ctx context.Context, cfg *config, build func(...func(*dynamodb.Options)) *dynamodb.Client,
	optFns []func(*dynamodb.Options)) (*dynamodb.Client, *Client, error) {
	c := &Client{cfg: cfg, start: time.Now()}
	// The three-index slice keeps append from writing into the caller's array.
	ddb := build(append(optFns[:len(optFns):len(optFns)], c.install)...)
	if c.installErr != nil {
		return nil, nil, c.installErr
	}
	if err := c.open(ctx, ddb.Options()); err != nil {
		return nil, nil, err
	}
	return ddb, c, nil
}

// install wraps the HTTP client, the retryer and the credentials provider the SDK resolved,
// after every caller option function.
func (c *Client) install(o *dynamodb.Options) {
	if _, ok := o.HTTPClient.(*httpClient); ok {
		c.installErr = &ConfigError{Option: "HTTPClient", Msg: "is already an ElastiCache Integrations client"}
		return
	}
	origin := o.HTTPClient
	if origin == nil {
		origin = awshttp.NewBuildableClient()
	}
	o.HTTPClient = &httpClient{client: c, origin: origin}
	o.Retryer = wrapRetryer(o.Retryer)
	o.Credentials = newSignerProvider(o.Credentials)
	o.APIOptions = append(o.APIOptions[:len(o.APIOptions):len(o.APIOptions)], addSignerSlot)
}

// open checks the built client's region and credentials and opens the session with them.
func (c *Client) open(ctx context.Context, o dynamodb.Options) error {
	if err := resolveIdentity(ctx, o.Region, o.Credentials); err != nil {
		return err
	}
	c.identity = identity{region: o.Region}
	auth := newTokenManager(c.cfg, o.Region, o.Credentials)
	sessions, err := openSessions(ctx, c.cfg, auth)
	if err != nil {
		auth.close()
		return err
	}
	c.sessions, c.auth = sessions, auth
	return nil
}

// sharedAuth lends one auth provider to a session. The client closes it after every session.
type sharedAuth struct{ authProvider }

func (sharedAuth) close() {}

// openSessions opens cfg.connections sessions at once. The first failure stops the others
// and is returned; nothing it started keeps running.
func openSessions(ctx context.Context, cfg *config, auth authProvider) ([]*session, error) {
	if cfg.connections == 1 {
		s, err := openSession(ctx, cfg, sharedAuth{auth})
		if err != nil {
			return nil, err
		}
		return []*session{s}, nil
	}
	octx, cancel := context.WithCancel(ctx)
	defer cancel()
	sessions := make([]*session, cfg.connections)
	var (
		wg    sync.WaitGroup
		mu    sync.Mutex
		first error
	)
	for i := range sessions {
		wg.Add(1)
		go func() {
			defer wg.Done()
			s, err := openSession(octx, cfg, sharedAuth{auth})
			if err != nil {
				mu.Lock()
				if first == nil {
					first = err
					cancel()
				}
				mu.Unlock()
				return
			}
			sessions[i] = s
		}()
	}
	wg.Wait()
	if first != nil {
		closeSessions(sessions)
		return nil, first
	}
	return sessions, nil
}

// closeSessions closes every non-nil session at once and waits for all of them.
func closeSessions(sessions []*session) {
	if len(sessions) == 1 {
		if sessions[0] != nil {
			sessions[0].close()
		}
		return
	}
	var wg sync.WaitGroup
	for _, s := range sessions {
		if s != nil {
			wg.Add(1)
			go func() { defer wg.Done(); s.close() }()
		}
	}
	wg.Wait()
}

// failedAvoid is how long pick passes over a connection where a call failed, while another
// connection is up: one that fails fast has the fewest calls in flight and would take them all.
const failedAvoid = time.Second

// pick returns the index of the connected session with the fewest calls in flight, skipping
// the sessions in tried, or -1 when there is none. A session where a call failed within
// failedAvoid comes after every other. The scan starts at the next session in turn, so ties
// are spread. Large replies make one connection's backlog slow to drain, so a call does not
// join it while another connection is idler.
func (c *Client) pick(tried uint32) int {
	n := uint64(len(c.sessions))
	i := c.next.Add(1)
	best, least, bestFailed := -1, int64(0), false
	var now int64
	for k := range n {
		j := (i + k) % n
		s := c.sessions[j]
		if tried&(1<<j) != 0 || !s.connected() {
			continue
		}
		failed := false
		if at := s.failedAt.Load(); at != 0 {
			if now == 0 {
				now = c.sinceStart()
			}
			failed = now-at < int64(failedAvoid)
		}
		f := s.inflight.Load()
		if best < 0 || bestFailed && !failed || failed == bestFailed && f < least {
			best, least, bestFailed = int(j), f, failed
		}
	}
	return best
}

// sinceStart is the monotonic time since the client was created, in nanoseconds, at least 1.
func (c *Client) sinceStart() int64 { return max(1, int64(time.Since(c.start))) }

// lastDown returns the session that lost its connection last, or one that has a connection
// again. Its outage started when the client lost its last connection, so it waits and falls
// back as one connection would.
func (c *Client) lastDown() *session {
	last := c.sessions[0]
	var readyAt time.Time
	for _, s := range c.sessions {
		if s.live.Load() != nil {
			return s // up again, or its drop is not yet seen and starts the newest outage
		}
		if o := s.outage.Load(); o != nil && o.readyAt.After(readyAt) {
			last, readyAt = s, o.readyAt
		}
	}
	return last
}

// request sends r through a connected session, never waiting on one that is reconnecting
// while another is up. When none is connected, the session that lost its connection last
// decides, as one connection would.
func (c *Client) request(ctx context.Context, r *request) requestOutcome {
	if len(c.sessions) == 1 {
		return c.sessions[0].request(ctx, r)
	}
	var tried uint32
	for range len(c.sessions) {
		j := c.pick(tried)
		if j < 0 {
			break
		}
		s := c.sessions[j]
		if o, ok := c.tryOn(ctx, s, r); ok {
			return o
		}
		tried |= 1 << j
	}
	return c.lastDown().request(ctx, r)
}

// tryOn is s.tryRequest without waiting, counted in s.inflight while it runs. A call that
// fails with the package's own error, not the caller's, marks s as failed.
func (c *Client) tryOn(ctx context.Context, s *session, r *request) (requestOutcome, bool) {
	s.inflight.Add(1)
	defer s.inflight.Add(-1)
	o, ok := s.tryRequest(ctx, r, false)
	if ok && o.kind == outcomeFailed && errors.Is(o.err, ErrIntegrations) {
		s.failedAt.Store(c.sinceStart())
	}
	return o, ok
}

// closed reports whether Close has been called.
func (c *Client) closed() bool { return c.isClosed.Load() }

const clientString = "elasticacheintegrations.Client"

// String, GoString and Format print only the type name: the Client holds the DynamoDB
// client's credentials provider, which may hold secrets in exported fields.
func (c *Client) String() string             { return clientString }
func (c *Client) GoString() string           { return clientString }
func (c *Client) Format(f fmt.State, _ rune) { _, _ = io.WriteString(f, clientString) }

// Close closes the cache connections, fails the requests still in flight on them, and waits
// for every goroutine the client started. It never waits for the cache, but waits for a
// credentials Retrieve in flight. A request it fails is not retried; a request waiting for a
// reconnect goes to DynamoDB, or in strict mode fails with a retryable *CacheUnavailableError.
// Later calls on the DynamoDB client go to DynamoDB, strict mode included. It is safe to call
// more than once and from any goroutine, and on a nil *Client. It always returns nil.
func (c *Client) Close() error {
	if c == nil {
		return nil
	}
	c.closeOnce.Do(func() {
		c.isClosed.Store(true)
		closeSessions(c.sessions)
		if c.auth != nil {
			c.auth.close()
		}
	})
	return nil
}
