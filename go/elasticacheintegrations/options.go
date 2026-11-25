// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"context"
	"crypto/x509"
	"log/slog"
	"net"
	"time"
)

// Options configures the cache. The zero value of every optional field means its default.
type Options struct {
	// Endpoint is the cache endpoint, "host:port". Required.
	Endpoint string
	// CacheName is the serverless cache name. Required. It is lowercased for the IAM token.
	CacheName string
	// UserID is the cache user. Default "default.iam-user". It is lowercased.
	UserID string

	// InsecureSkipVerify skips TLS certificate checks. Use it only against a local or test
	// endpoint; the client logs a warning once.
	InsecureSkipVerify bool
	// CACertsFile is a PEM bundle that replaces the system roots. It is read once at start-up.
	CACertsFile string

	// CacheRequestTimeout bounds one cache request once it has a connection.
	// Default 5s. Whole milliseconds, from 1ms to 2^31-1 ms.
	CacheRequestTimeout time.Duration
	// ConnectionTimeout bounds start-up and how long a request waits for a reconnect during
	// an outage. Each background reconnect attempt has its own fixed 5s budget. Default 5s.
	// Whole milliseconds, from 1ms to 2^31-1 ms.
	ConnectionTimeout time.Duration

	// FallbackDuration bounds how long requests go to DynamoDB while the cache is down,
	// counted after the outage's reconnect wait. nil means no limit (the default).
	// aws.Duration(0) means strict: a routed request never goes to DynamoDB. Whole seconds.
	FallbackDuration *time.Duration

	// CachePolicyOverrides is sent in the first aws.config. It is copied at start-up; values
	// must be JSON values (maps with string keys, slices, strings, bools, numbers,
	// json.Number or nil). A time.Duration, or a type with its own JSON or text encoding,
	// is refused; give TTLs as whole seconds.
	CachePolicyOverrides map[string]any
	// CachePolicyTimeout is how long the client keeps using a config it cannot refresh
	// before it reconnects. Default 1h. Whole seconds.
	CachePolicyTimeout time.Duration
	// IAMTokenRefreshDelay is how long after signing an IAM token the client signs its
	// replacement in the background. Default 300s. Whole seconds, from 1s to 879s.
	IAMTokenRefreshDelay time.Duration

	// Connections is how many cache connections the client opens. Each has its own AUTH,
	// re-AUTH, config refresh, reconnect and fallback; they share one IAM token, and start-up
	// opens them at once. Each call goes to the one that is up with the fewest calls in flight,
	// passing over for one second one where a call failed. One connection serves about 35,000
	// calls or 200 MB of replies per second. Use 16 for items of 100 KiB or more, and 1 when
	// start-up time matters more than throughput. Default 4, at most 16.
	Connections int

	// Logger receives the client's log records. Default: Info and above, as text, to stderr.
	// Use slog.New(slog.DiscardHandler) to silence it.
	Logger *slog.Logger
}

const (
	defaultUserID               = "default.iam-user"
	defaultCacheRequestTimeout  = 5 * time.Second
	defaultConnectionTimeout    = 5 * time.Second
	defaultCachePolicyTimeout   = time.Hour
	defaultIAMTokenRefreshDelay = 300 * time.Second

	maxTimeout              = (1<<31 - 1) * time.Millisecond
	maxIAMTokenRefreshDelay = 879 * time.Second

	defaultConnections = 4
	maxConnections     = 16

	defaultMaxPending       = 100
	defaultMaxResponseSize  = 16 << 20
	defaultReadSize         = 64 << 10
	defaultReauthInterval   = 900 * time.Second
	defaultReconnectAttempt = 5 * time.Second
	defaultBackoffMin       = 100 * time.Millisecond
	defaultBackoffMax       = 3200 * time.Millisecond
	defaultJitter           = 0.2
)

// config is a validated Options with every default applied. Later fields are not public;
// tests set them directly.
type config struct {
	endpoint string // as given, for messages
	host     string // brackets of an IPv6 literal removed
	port     int

	cacheName string // lowercase
	userID    string // lowercase

	insecureSkipVerify bool
	rootCAs            *x509.CertPool // nil: system roots

	cacheRequestTimeout time.Duration
	connectionTimeout   time.Duration

	connections int

	fallbackUnlimited bool
	fallbackDuration  time.Duration // used only when fallbackUnlimited is false; 0 is strict

	cachePolicyOverrides map[string]any // deep copy; nil when none were given
	cachePolicyTimeout   time.Duration
	iamTokenRefreshDelay time.Duration

	logger *logger

	maxPending              int
	maxResponseSize         int
	readSize                int
	reauthInterval          time.Duration
	reconnectAttemptTimeout time.Duration
	backoffMin              time.Duration
	backoffMax              time.Duration
	jitter                  float64 // ± fraction applied to re-AUTH, refresh and backoff waits

	plaintext bool                                                                 // no TLS, for the mock cache
	dial      func(ctx context.Context, network, address string) (net.Conn, error) // nil: a net.Dialer
}

// strict reports whether a routed request may never go to DynamoDB.
func (c *config) strict() bool {
	return !c.fallbackUnlimited && c.fallbackDuration == 0
}
