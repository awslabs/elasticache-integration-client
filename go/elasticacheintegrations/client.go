// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"context"
	"sync"

	"github.com/aws/aws-sdk-go-v2/aws"
	awshttp "github.com/aws/aws-sdk-go-v2/aws/transport/http"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
)

// Client owns the cache connection of one DynamoDB client. Call Close when the DynamoDB client
// is no longer used.
type Client struct {
	cfg      *config
	identity identity
	session  *session

	installErr error
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
// DynamoDB.
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

// install wraps the HTTP client the SDK resolved, after every caller option function.
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
}

// open checks the built client's region and credentials and opens the session with them.
func (c *Client) open(ctx context.Context, o dynamodb.Options) error {
	if err := resolveIdentity(ctx, o.Region, o.Credentials); err != nil {
		return err
	}
	c.identity = identity{region: o.Region, provider: o.Credentials}
	s, err := openSession(ctx, c.cfg, newTokenManager(c.cfg, o.Region, o.Credentials))
	if err != nil {
		return err // openSession closed the token manager
	}
	c.session = s
	return nil
}

// Close closes the cache connection, fails the requests still in flight on it, and waits for
// every goroutine the client started. It never waits for the cache. Later calls on the
// DynamoDB client go to DynamoDB, or fail with a non-retryable *CacheUnavailableError when
// FallbackDuration is 0 (strict). It is safe to call more than once and from any goroutine,
// and on a nil *Client. It always returns nil.
func (c *Client) Close() error {
	if c == nil {
		return nil
	}
	c.closeOnce.Do(func() {
		if c.session != nil {
			c.session.close()
		}
	})
	return nil
}
