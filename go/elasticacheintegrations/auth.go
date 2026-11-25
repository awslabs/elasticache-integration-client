// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"context"
	"errors"
	"math/rand/v2"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	v4 "github.com/aws/aws-sdk-go-v2/aws/signer/v4"
	"github.com/aws/smithy-go/middleware"
)

const (
	tokenExpirySeconds = 900
	tokenService       = "elasticache"
	emptySHA256        = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

	tokenServeLifetime   = 880 * time.Second // 900 s minus the toolkit's 20 s margin
	credsExpiryMargin    = 60 * time.Second  // a token is signed again once its credentials expire this soon
	refreshMaxAttempts   = 8
	refreshBackoffBase   = 100 * time.Millisecond
	refreshBackoffMax    = 5 * time.Second
	refreshBackoffJitter = 0.2
)

// tokenScope is what an IAM token grants: connecting to cache as user, signed in region.
type tokenScope struct {
	cache  string
	user   string
	region string
}

// signToken returns the IAM token: the presigned ElastiCache connect URL without its scheme.
// The SDK writes the query in SigV4 canonical order.
func signToken(ctx context.Context, creds aws.Credentials, scope tokenScope, now time.Time) (secret, error) {
	cache, region := scope.cache, scope.region
	q := url.Values{
		"Action":        {"connect"},
		"User":          {scope.user},
		"ResourceType":  {"ServerlessCache"},
		"X-Amz-Expires": {strconv.Itoa(tokenExpirySeconds)},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, "https://"+strings.ToLower(cache)+"/?"+q.Encode(), nil)
	if err != nil {
		return secret{}, &ConnectionError{Msg: "cannot build the IAM token request", Err: err}
	}
	uri, _, err := v4.NewSigner().PresignHTTP(ctx, creds, req, emptySHA256, tokenService, region, now)
	if err != nil {
		return secret{}, &ConnectionError{Msg: "cannot sign the IAM token", Err: err}
	}
	token, ok := strings.CutPrefix(uri, "https://")
	if !ok {
		return secret{}, &ConnectionError{Msg: "the IAM token signer returned an unexpected scheme"}
	}
	return newSecret(token), nil
}

// resolveIdentity checks the DynamoDB client's region and credentials before the cache is
// contacted. It retrieves the credentials once, so a provider that cannot resolve fails here.
func resolveIdentity(ctx context.Context, region string, provider aws.CredentialsProvider) error {
	if region == "" {
		return &ConfigError{Option: "Region", Msg: "is required: the DynamoDB client has no region"}
	}
	if provider == nil || aws.IsCredentialsProvider(provider, aws.AnonymousCredentials{}) {
		return &ConfigError{Option: "Credentials", Msg: "are required: the DynamoDB client has no credentials or anonymous credentials"}
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	creds, err := provider.Retrieve(ctx)
	if ctxErr := ctx.Err(); ctxErr != nil {
		return ctxErr
	}
	if err != nil {
		return &ConfigError{Option: "Credentials", Msg: "cannot be retrieved", Err: err}
	}
	if !creds.HasKeys() {
		return &ConfigError{Option: "Credentials", Msg: "have no access key id or secret access key"}
	}
	return nil
}

// matchesIdentity reports whether a request signed with signedKeyID in signedRegion may use
// the client's cache connection: signedRegion is clientRegion and the SDK signed it with the
// key the client's own provider returned for this call.
func matchesIdentity(ctx context.Context, clientRegion, signedKeyID, signedRegion string) bool {
	if signedRegion != clientRegion || signedKeyID == "" {
		return false
	}
	slot, ok := ctx.Value(signerSlotKey{}).(*signerSlot)
	return ok && slot.keyID == signedKeyID
}

// signerProvider is the DynamoDB client's credentials provider as the caller gave it, retrieved
// on every call as before. It records the access key id it returns in the call's signerSlot.
// A per-call Credentials override replaces it, so that call records nothing.
type signerProvider struct {
	provider aws.CredentialsProvider
}

// newSignerProvider wraps p; a nil or anonymous provider is left for resolveIdentity to refuse.
func newSignerProvider(p aws.CredentialsProvider) aws.CredentialsProvider {
	if p == nil || aws.IsCredentialsProvider(p, aws.AnonymousCredentials{}) {
		return p
	}
	return &signerProvider{provider: p}
}

func (p *signerProvider) Retrieve(ctx context.Context) (aws.Credentials, error) {
	creds, err := p.provider.Retrieve(ctx)
	if slot, ok := ctx.Value(signerSlotKey{}).(*signerSlot); ok && err == nil {
		slot.keyID = creds.AccessKeyID
	}
	return creds, err
}

// IsCredentialsProvider lets aws.IsCredentialsProvider see the wrapped provider.
func (p *signerProvider) IsCredentialsProvider(target aws.CredentialsProvider) bool {
	return aws.IsCredentialsProvider(p.provider, target)
}

// ProviderSources reports the wrapped provider's sources for the SDK's user agent.
func (p *signerProvider) ProviderSources() []aws.CredentialSource {
	if s, ok := p.provider.(aws.CredentialProviderSource); ok {
		return s.ProviderSources()
	}
	return nil
}

// signerSlot holds the access key id the client's provider returned for one SDK operation.
type signerSlot struct {
	keyID string
}

type signerSlotKey struct{}

var signerSlotMiddleware = middleware.InitializeMiddlewareFunc("ElastiCacheIntegrationsSignerSlot",
	func(ctx context.Context, in middleware.InitializeInput, next middleware.InitializeHandler) (
		middleware.InitializeOutput, middleware.Metadata, error) {
		return next.HandleInitialize(context.WithValue(ctx, signerSlotKey{}, &signerSlot{}), in)
	})

// addSignerSlot gives every operation a signerSlot before the SDK resolves its credentials.
func addSignerSlot(s *middleware.Stack) error {
	return s.Initialize.Add(signerSlotMiddleware, middleware.Before)
}

// authCredentials is the user and token for HELLO 3 AUTH and AUTH.
type authCredentials struct {
	user  string
	token secret
}

// authProvider supplies AUTH credentials to the session. tokenManager is the one the client
// uses; tests supply their own.
type authProvider interface {
	// credentials returns the user and a token that is still valid.
	credentials(ctx context.Context) (authCredentials, error)
	// forceRefresh drops the current token and waits for a new one, after the cache
	// rejected an AUTH.
	forceRefresh(ctx context.Context) error
	// close stops the provider and waits for its goroutines.
	close()
}

// tokenManager caches one IAM token and signs its replacement in the background, as the
// developer toolkit's token manager does. One worker goroutine does every refresh.
type tokenManager struct {
	provider     aws.CredentialsProvider
	scope        tokenScope
	refreshDelay time.Duration
	random       func() float64 // in [0, 1), for the backoff jitter

	ctx    context.Context // the worker's lifetime, cancelled by close; never a caller's context
	cancel context.CancelFunc
	kick   chan struct{} // wakes the worker for a pending refresh
	wg     sync.WaitGroup

	mu             sync.Mutex
	closed         bool
	cached         *cachedToken
	inflight       *refreshCall // the refresh callers wait for; nil when none is pending
	retryNotBefore time.Time    // no refresh is started by token before this
}

type cachedToken struct {
	token       secret
	refreshAt   time.Time
	expiresAt   time.Time
	credsExpire time.Time // zero when the credentials cannot expire
}

func (c *cachedToken) usable(now time.Time) bool {
	return now.Before(c.expiresAt) &&
		(c.credsExpire.IsZero() || now.Before(c.credsExpire.Add(-credsExpiryMargin)))
}

type refreshCall struct {
	done  chan struct{} // closed when token or err is set
	token secret
	err   error
}

var _ authProvider = (*tokenManager)(nil)

// newTokenManager starts a token manager for the client's cache, user and refresh delay,
// signing with region and the credentials of provider.
func newTokenManager(cfg *config, region string, provider aws.CredentialsProvider) *tokenManager {
	m := buildTokenManager(cfg, region, provider)
	m.start()
	return m
}

func buildTokenManager(cfg *config, region string, provider aws.CredentialsProvider) *tokenManager {
	ctx, cancel := context.WithCancel(context.Background())
	return &tokenManager{
		provider:     provider,
		scope:        tokenScope{cache: cfg.cacheName, user: cfg.userID, region: region},
		refreshDelay: cfg.iamTokenRefreshDelay,
		random:       rand.Float64,
		ctx:          ctx,
		cancel:       cancel,
		kick:         make(chan struct{}, 1),
	}
}

func (m *tokenManager) start() {
	m.wg.Add(1)
	go m.run()
}

func newAuthClosedError() error { return &ConnectionError{Msg: "the client is closed"} }

func (m *tokenManager) credentials(ctx context.Context) (authCredentials, error) {
	tok, err := m.token(ctx)
	if err != nil {
		return authCredentials{}, err
	}
	return authCredentials{user: m.scope.user, token: tok}, nil
}

// token returns the cached token while it is usable, starting a background refresh once it
// is due, and otherwise waits for a refresh.
func (m *tokenManager) token(ctx context.Context) (secret, error) {
	m.mu.Lock()
	if m.closed {
		m.mu.Unlock()
		return secret{}, newAuthClosedError()
	}
	now := time.Now()
	if c := m.cached; c != nil && c.usable(now) {
		if !now.Before(c.refreshAt) && !now.Before(m.retryNotBefore) {
			m.startLocked()
		}
		m.mu.Unlock()
		return c.token, nil
	}
	call := m.startLocked()
	m.mu.Unlock()
	return m.wait(ctx, call)
}

func (m *tokenManager) forceRefresh(ctx context.Context) error {
	m.mu.Lock()
	if m.closed {
		m.mu.Unlock()
		return newAuthClosedError()
	}
	m.cached = nil
	m.retryNotBefore = time.Time{}
	call := m.startLocked()
	m.mu.Unlock()
	_, err := m.wait(ctx, call)
	return err
}

func (m *tokenManager) close() {
	m.mu.Lock()
	if !m.closed {
		m.closed = true
		m.cached = nil
		m.completeLocked(m.inflight, secret{}, newAuthClosedError())
	}
	m.mu.Unlock()
	m.cancel()
	m.wg.Wait()
}

func (m *tokenManager) wait(ctx context.Context, call *refreshCall) (secret, error) {
	select {
	case <-call.done:
		return call.token, call.err
	case <-ctx.Done():
		return secret{}, ctx.Err()
	}
}

// startLocked returns the pending refresh, starting one if none is pending.
func (m *tokenManager) startLocked() *refreshCall {
	if m.inflight == nil {
		m.inflight = &refreshCall{done: make(chan struct{})}
		select {
		case m.kick <- struct{}{}:
		default:
		}
	}
	return m.inflight
}

// completeLocked finishes call unless close already did.
func (m *tokenManager) completeLocked(call *refreshCall, tok secret, err error) {
	if call == nil || m.inflight != call {
		return
	}
	m.inflight = nil
	call.token, call.err = tok, err
	close(call.done)
}

func (m *tokenManager) run() {
	defer m.wg.Done()
	timer := time.NewTimer(time.Hour)
	timer.Stop()
	defer timer.Stop()
	for {
		var scheduled bool
		select {
		case <-m.ctx.Done():
			return
		case <-m.kick:
		case <-timer.C:
			scheduled = true
		}
		m.mu.Lock()
		if scheduled && !m.closed {
			m.startLocked()
			select { // the worker is already awake
			case <-m.kick:
			default:
			}
		}
		call := m.inflight
		m.mu.Unlock()
		if call == nil {
			continue // a refresh that was pending when the worker woke already ran
		}
		next, ok := m.refresh(call)
		timer.Stop()
		if ok {
			timer.Reset(next)
		}
	}
}

// refresh signs a token for call, retrying with backoff. It returns when the worker should
// run the next background refresh, or false for none.
func (m *tokenManager) refresh(call *refreshCall) (time.Duration, bool) {
	for attempt := 1; ; attempt++ {
		issued := time.Now()
		tok, creds, err := m.sign(issued)
		if m.ctx.Err() != nil {
			return 0, false
		}
		if err == nil {
			return m.install(call, tok, creds, issued)
		}
		if attempt == refreshMaxAttempts {
			return m.fail(call, err)
		}
		t := time.NewTimer(m.backoff(attempt))
		select {
		case <-m.ctx.Done():
			t.Stop()
			return 0, false
		case <-t.C:
		}
	}
}

func (m *tokenManager) sign(now time.Time) (secret, aws.Credentials, error) {
	creds, err := m.provider.Retrieve(m.ctx)
	if err != nil {
		return secret{}, creds, err
	}
	if !creds.HasKeys() {
		return secret{}, creds, errors.New("the credentials have no access key id or secret access key")
	}
	tok, err := signToken(m.ctx, creds, m.scope, now)
	return tok, creds, err
}

func (m *tokenManager) install(call *refreshCall, tok secret, creds aws.Credentials, issued time.Time) (time.Duration, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return 0, false
	}
	c := &cachedToken{token: tok, refreshAt: issued.Add(m.refreshDelay), expiresAt: issued.Add(tokenServeLifetime)}
	if creds.CanExpire {
		c.credsExpire = creds.Expires
	}
	m.cached = c
	m.retryNotBefore = time.Time{}
	m.completeLocked(call, tok, nil)
	return m.refreshDelay, true
}

// fail ends a refresh whose every attempt failed. A token that is still usable stays in use
// and the next attempt runs after refreshBackoffMax.
func (m *tokenManager) fail(call *refreshCall, lastErr error) (time.Duration, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return 0, false
	}
	now := time.Now()
	if c := m.cached; c != nil && c.usable(now) {
		m.retryNotBefore = now.Add(refreshBackoffMax)
		m.completeLocked(call, c.token, nil)
		return refreshBackoffMax, true
	}
	err := lastErr
	if !errors.As(err, new(*ConnectionError)) {
		msg := "cannot sign the IAM token"
		if m.cached != nil {
			msg = "cannot sign the IAM token before the previous one expired"
		}
		err = &ConnectionError{Msg: msg, Err: lastErr}
	}
	m.completeLocked(call, secret{}, err)
	return 0, false
}

// backoff is the wait after failed attempt n: min(100 ms·2^(n-1), 5 s), ±20%.
func (m *tokenManager) backoff(attempt int) time.Duration {
	return expBackoff(refreshBackoffBase, refreshBackoffMax, refreshBackoffJitter, attempt, m.random)
}
