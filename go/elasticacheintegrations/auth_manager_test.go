// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/awslabs/elasticache-integration-client/go/internal/testutil"
)

// fakeCreds is a credentials provider that counts calls, can fail, and can block until
// released.
type fakeCreds struct {
	mu      sync.Mutex
	creds   aws.Credentials
	err     error // returned while failing > 0 or forever when failing < 0
	failing int
	calls   int
	block   chan struct{} // when set, Retrieve waits for it to close or for ctx
}

func newFakeCreds() *fakeCreds { return &fakeCreds{creds: testCreds()} }

func (f *fakeCreds) Retrieve(ctx context.Context) (aws.Credentials, error) {
	f.mu.Lock()
	f.calls++
	block := f.block
	f.mu.Unlock()
	if block != nil {
		select {
		case <-block:
		case <-ctx.Done():
			return aws.Credentials{}, ctx.Err()
		}
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.failing != 0 {
		if f.failing > 0 {
			f.failing--
		}
		return aws.Credentials{}, f.err
	}
	return f.creds, nil
}

func (f *fakeCreds) count() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.calls
}

func (f *fakeCreds) set(fn func(f *fakeCreds)) {
	f.mu.Lock()
	defer f.mu.Unlock()
	fn(f)
}

func testAuthConfig() *config {
	return &config{cacheName: "my-cache", userID: "default.iam-user", iamTokenRefreshDelay: defaultIAMTokenRefreshDelay}
}

// startTestManager starts a manager whose backoff jitter is at its midpoint.
func startTestManager(f aws.CredentialsProvider) *tokenManager {
	m := buildTokenManager(testAuthConfig(), "us-east-1", f)
	m.random = func() float64 { return 0.5 }
	m.start()
	return m
}

func tokenQuery(t *testing.T, tok secret) url.Values {
	t.Helper()
	_, q := splitToken(t, tok.reveal())
	return q
}

func TestTokenManagerCredentials(t *testing.T) {
	m := newTokenManager(testAuthConfig(), "us-east-1", newFakeCreds())
	defer m.close()
	got, err := m.credentials(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if got.user != "default.iam-user" {
		t.Errorf("user %q", got.user)
	}
	q := tokenQuery(t, got.token)
	if q.Get("User") != "default.iam-user" || q.Get("X-Amz-Security-Token") != testutil.SessionToken ||
		!strings.HasPrefix(q.Get("X-Amz-Credential"), testutil.AccessKeyID+"/") ||
		!strings.Contains(q.Get("X-Amz-Credential"), "/us-east-1/elasticache/") {
		t.Errorf("token query %v", q)
	}
}

func TestTokenManagerAfterClose(t *testing.T) {
	m := startTestManager(newFakeCreds())
	m.close()
	m.close()
	if _, err := m.token(context.Background()); !errors.As(err, new(*ConnectionError)) {
		t.Errorf("token after close: %v", err)
	}
	if err := m.forceRefresh(context.Background()); !errors.As(err, new(*ConnectionError)) {
		t.Errorf("forceRefresh after close: %v", err)
	}
}

func TestTokenManagerCloseFromManyGoroutines(t *testing.T) {
	m := startTestManager(newFakeCreds())
	var wg sync.WaitGroup
	for range 8 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, _ = m.token(context.Background())
			m.close()
		}()
	}
	wg.Wait()
}

func TestTokenManagerBackoff(t *testing.T) {
	m := buildTokenManager(testAuthConfig(), "us-east-1", newFakeCreds())
	want := []time.Duration{100, 200, 400, 800, 1600, 3200, 5000, 5000}
	for i, w := range want {
		m.random = func() float64 { return 0.5 }
		if got := m.backoff(i + 1); got != w*time.Millisecond {
			t.Errorf("attempt %d: %v, want %v", i+1, got, w*time.Millisecond)
		}
		m.random = func() float64 { return 0 }
		if got := m.backoff(i + 1); got != w*time.Millisecond*8/10 {
			t.Errorf("attempt %d, low jitter: %v", i+1, got)
		}
	}
	if got := m.backoff(1000); got != 4*time.Second {
		t.Errorf("attempt 1000: %v", got)
	}
}

func TestTokenManagerLeavesNoSecrets(t *testing.T) {
	m := startTestManager(newFakeCreds())
	defer m.close()
	got, err := m.credentials(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	m.mu.Lock()
	cached := *m.cached
	m.mu.Unlock()
	out := fmt.Sprintf("%v %+v %#v %v %+v %#v", cached, cached, &cached, got, got, &got)
	testutil.AssertNoSecrets(t, out, tokenSecrets(got.token)...)
}

// tokenSecrets is every secret of a token, without the query values that are not secret.
func tokenSecrets(tok secret) []string {
	var out []string
	for _, s := range testutil.TokenSecrets(tok.reveal()) {
		if s != "default.iam-user" && s != "ServerlessCache" {
			out = append(out, s)
		}
	}
	return append(out, testutil.Secrets()...)
}
