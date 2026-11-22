// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

//go:build go1.25

package elasticacheintegrations

import (
	"context"
	"errors"
	"strings"
	"sync"
	"testing"
	"testing/synctest"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/awslabs/elasticache-integration-client/go/internal/testutil"
)

// allAttempts is the time 8 failed attempts take at jitter midpoint: the 7 waits between them.
const allAttempts = (100 + 200 + 400 + 800 + 1600 + 3200 + 5000) * time.Millisecond

func mustToken(t *testing.T, m *tokenManager) secret {
	t.Helper()
	tok, err := m.token(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	return tok
}

func TestTokenManagerCachesAndRefreshesInBackground(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		f := newFakeCreds()
		m := startTestManager(f)
		defer m.close()

		first := mustToken(t, m)
		time.Sleep(defaultIAMTokenRefreshDelay - time.Second)
		if got := mustToken(t, m); got.reveal() != first.reveal() || f.count() != 1 {
			t.Fatalf("token re-signed before the delay: calls %d", f.count())
		}

		release := make(chan struct{})
		f.set(func(f *fakeCreds) { f.block = release })
		time.Sleep(time.Second)
		synctest.Wait()
		if f.count() != 2 {
			t.Fatalf("no background refresh at the delay: calls %d", f.count())
		}
		if got := mustToken(t, m); got.reveal() != first.reveal() {
			t.Fatal("a caller did not get the current token while the refresh runs")
		}

		f.set(func(f *fakeCreds) { f.block = nil })
		close(release)
		synctest.Wait()
		second := mustToken(t, m)
		if second.reveal() == first.reveal() || f.count() != 2 {
			t.Fatalf("refreshed token not installed: calls %d", f.count())
		}
		if tokenQuery(t, second).Get("X-Amz-Date") == tokenQuery(t, first).Get("X-Amz-Date") {
			t.Error("the new token has the old signing time")
		}

		time.Sleep(defaultIAMTokenRefreshDelay)
		synctest.Wait()
		if f.count() != 3 {
			t.Errorf("the refresh after the new token did not run: calls %d", f.count())
		}
	})
}

func TestTokenManagerSharesOneRefresh(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		f := newFakeCreds()
		release := make(chan struct{})
		f.block = release
		m := startTestManager(f)
		defer m.close()

		var wg sync.WaitGroup
		tokens := make([]string, 20)
		for i := range tokens {
			wg.Add(1)
			go func() {
				defer wg.Done()
				tokens[i] = mustToken(t, m).reveal()
			}()
		}
		synctest.Wait()
		close(release)
		wg.Wait()
		if f.count() != 1 {
			t.Errorf("calls %d, want 1", f.count())
		}
		for _, tok := range tokens {
			if tok != tokens[0] || tok == "" {
				t.Fatal("callers got different tokens")
			}
		}
	})
}

func TestTokenManagerRetriesWithBackoff(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		f := newFakeCreds()
		f.failing, f.err = 3, errors.New("provider failed")
		m := startTestManager(f)
		defer m.close()

		start := time.Now()
		mustToken(t, m)
		if elapsed := time.Since(start); elapsed != 700*time.Millisecond {
			t.Errorf("took %v, want 700ms (100+200+400)", elapsed)
		}
		if f.count() != 4 {
			t.Errorf("calls %d, want 4", f.count())
		}
	})
}

func TestTokenManagerKeepsUsableTokenWhenRefreshFails(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		f := newFakeCreds()
		m := startTestManager(f)
		defer m.close()

		first := mustToken(t, m)
		f.set(func(f *fakeCreds) { f.failing, f.err = -1, errors.New("provider failed") })
		time.Sleep(defaultIAMTokenRefreshDelay)
		synctest.Wait()
		if got := mustToken(t, m); got.reveal() != first.reveal() {
			t.Fatal("not the cached token during the failing refresh")
		}
		time.Sleep(allAttempts)
		synctest.Wait()
		if f.count() != 9 {
			t.Fatalf("calls %d, want 1 + 8 attempts", f.count())
		}

		for range 5 {
			if got := mustToken(t, m); got.reveal() != first.reveal() {
				t.Fatal("not the cached token after the failed refresh")
			}
			time.Sleep(time.Second - time.Millisecond)
			synctest.Wait()
		}
		if f.count() != 9 {
			t.Fatalf("a refresh started within 5 s of the failure: calls %d", f.count())
		}
		time.Sleep(5 * time.Millisecond)
		synctest.Wait()
		if f.count() != 10 {
			t.Errorf("no retry 5 s after the failure: calls %d", f.count())
		}
	})
}

func TestTokenManagerFailsWithoutToken(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		providerErr := errors.New("provider failed")
		f := newFakeCreds()
		f.failing, f.err = -1, providerErr
		m := startTestManager(f)
		defer m.close()

		start := time.Now()
		_, err := m.token(context.Background())
		var ce *ConnectionError
		if !errors.As(err, &ce) || !errors.Is(err, providerErr) || !errors.Is(err, ErrIntegrations) {
			t.Fatalf("err = %v", err)
		}
		if elapsed := time.Since(start); elapsed != allAttempts || f.count() != refreshMaxAttempts {
			t.Errorf("took %v and %d calls", elapsed, f.count())
		}

		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		start = time.Now()
		if _, err := m.token(ctx); !errors.Is(err, context.DeadlineExceeded) || time.Since(start) != time.Second {
			t.Errorf("err = %v after %v, want the caller's deadline", err, time.Since(start))
		}
	})
}

func TestTokenManagerFailsOnceTokenExpired(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		providerErr := errors.New("provider failed")
		f := newFakeCreds()
		m := startTestManager(f)
		defer m.close()

		mustToken(t, m)
		f.set(func(f *fakeCreds) { f.failing, f.err = -1, providerErr })
		time.Sleep(tokenServeLifetime)
		_, err := m.token(context.Background())
		if !errors.As(err, new(*ConnectionError)) || !errors.Is(err, providerErr) ||
			!strings.Contains(err.Error(), "previous one expired") {
			t.Errorf("err = %v", err)
		}
	})
}

func TestTokenManagerResignsBeforeCredentialsExpire(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		f := newFakeCreds()
		f.creds.CanExpire, f.creds.Expires = true, time.Now().Add(100*time.Second)
		m := startTestManager(f)
		defer m.close()

		first := mustToken(t, m)
		time.Sleep(40*time.Second - time.Millisecond)
		if got := mustToken(t, m); got.reveal() != first.reveal() || f.count() != 1 {
			t.Fatalf("re-signed with more than 60 s left: calls %d", f.count())
		}

		f.set(func(f *fakeCreds) {
			f.creds = aws.Credentials{AccessKeyID: "AKIDROTATED", SecretAccessKey: "rotated", CanExpire: true,
				Expires: time.Now().Add(time.Hour)}
		})
		time.Sleep(time.Millisecond)
		second := mustToken(t, m)
		if f.count() != 2 {
			t.Fatalf("not re-signed 60 s before the credentials expire: calls %d", f.count())
		}
		if cred := tokenQuery(t, second).Get("X-Amz-Credential"); !strings.HasPrefix(cred, "AKIDROTATED/") {
			t.Errorf("the token does not use the rotated key: %s", cred)
		}
		if tokenQuery(t, second).Has("X-Amz-Security-Token") {
			t.Error("the token keeps the old session token")
		}
	})
}

func TestTokenManagerNearlyExpiredCredentialsDoNotSpin(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		f := newFakeCreds()
		f.creds.CanExpire, f.creds.Expires = true, time.Now().Add(30*time.Second)
		m := startTestManager(f)
		defer m.close()

		mustToken(t, m)
		mustToken(t, m)
		synctest.Wait()
		time.Sleep(10 * time.Second)
		synctest.Wait()
		if f.count() != 2 {
			t.Errorf("calls %d, want one signature per token call", f.count())
		}
		time.Sleep(2 * defaultIAMTokenRefreshDelay)
		synctest.Wait()
		if f.count() != 4 {
			t.Errorf("calls %d, want one background refresh per delay", f.count())
		}
	})
}

func TestTokenManagerForceRefresh(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		f := newFakeCreds()
		m := startTestManager(f)
		defer m.close()

		first := mustToken(t, m)
		time.Sleep(time.Second)
		if err := m.forceRefresh(context.Background()); err != nil {
			t.Fatal(err)
		}
		if f.count() != 2 {
			t.Fatalf("calls %d, want 2", f.count())
		}
		second := mustToken(t, m)
		if second.reveal() == first.reveal() || f.count() != 2 {
			t.Errorf("forceRefresh did not install a new token: calls %d", f.count())
		}
	})
}

func TestTokenManagerCloseStopsWaitersAndWorker(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		f := newFakeCreds()
		f.failing, f.err = -1, errors.New("provider failed")
		m := startTestManager(f)

		errc := make(chan error, 1)
		go func() {
			_, err := m.token(context.Background())
			errc <- err
		}()
		time.Sleep(time.Second) // the worker is in a backoff sleep
		synctest.Wait()
		calls := f.count()
		m.close()
		err := <-errc
		if !errors.As(err, new(*ConnectionError)) || !strings.Contains(err.Error(), "closed") {
			t.Errorf("waiter err = %v", err)
		}
		time.Sleep(time.Minute)
		if f.count() != calls {
			t.Errorf("the worker kept refreshing after close: %d calls, then %d", calls, f.count())
		}
		m.close()
	})
}

func TestTokenManagerCloseDuringRetrieve(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		f := newFakeCreds()
		f.block = make(chan struct{}) // never released; Retrieve returns on ctx
		m := startTestManager(f)

		errc := make(chan error, 1)
		go func() {
			_, err := m.token(context.Background())
			errc <- err
		}()
		synctest.Wait()
		m.close()
		if err := <-errc; !errors.As(err, new(*ConnectionError)) {
			t.Errorf("waiter err = %v", err)
		}
	})
}

func TestTokenManagerErrorsLeaveNoSecrets(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		f := newFakeCreds()
		m := startTestManager(f)
		defer m.close()
		tok := mustToken(t, m)
		secrets := tokenSecrets(tok)

		f.set(func(f *fakeCreds) { f.failing, f.err = -1, errors.New("provider failed") })
		if err := m.forceRefresh(context.Background()); err == nil {
			t.Fatal("no error")
		} else {
			testutil.AssertNoSecrets(t, err.Error(), secrets...)
		}
		f.set(func(f *fakeCreds) { f.failing, f.creds = 0, aws.Credentials{AccessKeyID: testutil.AccessKeyID} })
		if err := m.forceRefresh(context.Background()); err == nil {
			t.Fatal("no error for keyless credentials")
		} else {
			testutil.AssertNoSecrets(t, err.Error(), secrets...)
		}
	})
}

func TestTokenManagerTokenStartsDueRefreshWithoutTimer(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		f := newFakeCreds()
		m := buildTokenManager(testAuthConfig(), "us-east-1", f)
		now := time.Now()
		old := newSecret("old")
		m.cached = &cachedToken{token: old, refreshAt: now, expiresAt: now.Add(tokenServeLifetime)}
		m.retryNotBefore = now.Add(time.Second)
		m.start()
		defer m.close()

		if got := mustToken(t, m); got.reveal() != "old" {
			t.Fatal("not the cached token")
		}
		synctest.Wait()
		if f.count() != 0 {
			t.Fatalf("a refresh started before retryNotBefore: calls %d", f.count())
		}
		time.Sleep(time.Second)
		if got := mustToken(t, m); got.reveal() != "old" {
			t.Fatal("not the cached token while the refresh is due")
		}
		synctest.Wait()
		if f.count() != 1 {
			t.Fatalf("token did not start the due refresh: calls %d", f.count())
		}
		if got := mustToken(t, m); got.reveal() == "old" {
			t.Error("the refreshed token was not installed")
		}
	})
}

func TestTokenManagerTokenAtTheTimerSignsOnce(t *testing.T) {
	for range 20 {
		synctest.Test(t, func(t *testing.T) {
			f := newFakeCreds()
			m := startTestManager(f)
			defer m.close()

			mustToken(t, m)
			time.Sleep(defaultIAMTokenRefreshDelay)
			mustToken(t, m) // due at the same instant the timer fires
			synctest.Wait()
			if f.count() != 2 {
				t.Fatalf("calls %d, want 2", f.count())
			}
			time.Sleep(defaultIAMTokenRefreshDelay - time.Millisecond)
			synctest.Wait()
			if f.count() != 2 {
				t.Fatalf("an extra refresh ran before the next delay: calls %d", f.count())
			}
		})
	}
}

func TestTokenManagerForceRefreshJoinsRefreshInFlight(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		f := newFakeCreds()
		m := startTestManager(f)
		defer m.close()

		first := mustToken(t, m)
		release := make(chan struct{})
		f.set(func(f *fakeCreds) { f.block = release })
		time.Sleep(defaultIAMTokenRefreshDelay)
		synctest.Wait()

		errc := make(chan error, 1)
		go func() { errc <- m.forceRefresh(context.Background()) }()
		synctest.Wait()
		select {
		case err := <-errc:
			t.Fatalf("forceRefresh returned before the refresh in flight: %v", err)
		default:
		}
		f.set(func(f *fakeCreds) { f.block = nil })
		close(release)
		if err := <-errc; err != nil {
			t.Fatal(err)
		}
		if got := mustToken(t, m); got.reveal() == first.reveal() || f.count() != 2 {
			t.Errorf("forceRefresh did not share the refresh in flight: calls %d", f.count())
		}
	})
}

func TestTokenManagerForceRefreshDoesNotReturnTheDroppedToken(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		providerErr := errors.New("provider failed")
		f := newFakeCreds()
		m := startTestManager(f)
		defer m.close()

		mustToken(t, m)
		f.set(func(f *fakeCreds) { f.failing, f.err = -1, providerErr })
		time.Sleep(defaultIAMTokenRefreshDelay)
		synctest.Wait() // a background refresh is retrying
		if err := m.forceRefresh(context.Background()); !errors.Is(err, providerErr) {
			t.Fatalf("forceRefresh err = %v, want the provider's error", err)
		}
		if f.count() != 1+refreshMaxAttempts {
			t.Errorf("calls %d, want the shared refresh only", f.count())
		}
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		if _, err := m.token(ctx); !errors.Is(err, context.DeadlineExceeded) {
			t.Errorf("token after a failed forceRefresh: %v, want a wait for a new token", err)
		}
	})
}
