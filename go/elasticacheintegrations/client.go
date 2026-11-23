// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"context"
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
// and credentials. A call made with a per-call region or credentials override goes to
// DynamoDB, except after Close in strict mode (see [Client.Close]); a call with its own
// HTTPClient always does.
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
	c := &Client{cfg: cfg}
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

// install wraps the HTTP client and the credentials provider the SDK resolved, after every
// caller option function.
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

// pick returns the next session in turn that has a live connection, or nil when none has.
func (c *Client) pick() *session {
	n := uint64(len(c.sessions))
	i := c.next.Add(1)
	for k := range n {
		if s := c.sessions[(i+k)%n]; s.connected() {
			return s
		}
	}
	return nil
}

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
	for range len(c.sessions) {
		s := c.pick()
		if s == nil {
			break
		}
		if o, ok := s.tryRequest(ctx, r, false); ok {
			return o
		}
	}
	return c.lastDown().request(ctx, r)
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
// credentials Retrieve in flight. Later calls on the DynamoDB client go to DynamoDB, or fail
// with a non-retryable *CacheUnavailableError when FallbackDuration is 0 (strict); a call whose
// option function sets its own HTTPClient still goes to DynamoDB. It is safe to call more than
// once and from any goroutine, and on a nil *Client. It always returns nil.
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
