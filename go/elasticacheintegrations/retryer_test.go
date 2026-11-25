// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/aws/ratelimit"
	"github.com/aws/aws-sdk-go-v2/aws/retry"
	smithyhttp "github.com/aws/smithy-go/transport/http"
)

// sentErr is err as the SDK's retry middleware sees a failed HTTP send.
func sentErr(err error, charge *dropCharge) error {
	return &smithyhttp.RequestSendError{Err: &sdkError{err: err, retryable: true, charge: charge}}
}

func lostErr(charge *dropCharge) error { return sentErr(&ConnectionLostError{Msg: "x"}, charge) }

// tokens counts the retry tokens r grants for errs, in order.
func tokens(r aws.RetryerV2, errs ...error) (granted int) {
	for _, err := range errs {
		if _, e := r.GetRetryToken(context.Background(), err); e == nil {
			granted++
		}
	}
	return granted
}

func repeat(n int, f func() error) []error {
	errs := make([]error, n)
	for i := range errs {
		errs[i] = f()
	}
	return errs
}

func TestDropRetryerOneChargePerDrop(t *testing.T) {
	r := wrapRetryer(retry.NewStandard()).(aws.RetryerV2)
	d := &dropCharge{}
	if n := tokens(r, repeat(100, func() error { return lostErr(d) })...); n != 100 {
		t.Fatalf("%d of 100 calls of one drop got a retry token", n)
	}
	// 500 - 14 left: 34 more retries at 14 tokens.
	if n := tokens(r, repeat(40, func() error { return lostErr(nil) })...); n != 34 {
		t.Fatalf("%d independent retries after one drop, want 34", n)
	}
}

func TestStandardRetryerAloneChargesEveryCall(t *testing.T) {
	if n := tokens(retry.NewStandard(), repeat(100, func() error { return lostErr(&dropCharge{}) })...); n != 35 {
		t.Fatalf("%d of 100 retries without the wrapper, want 35", n)
	}
}

func TestDropRetryerEachDropPays(t *testing.T) {
	r := wrapRetryer(retry.NewStandard()).(aws.RetryerV2)
	var errs []error
	for range 40 {
		d := &dropCharge{}
		errs = append(errs, lostErr(d), sentErr(&TimeoutError{Msg: "x"}, d))
	}
	if n := tokens(r, errs...); n != 70 {
		t.Fatalf("%d of 80 calls of 40 drops got a token, want 35 drops x 2", n)
	}
}

func TestDropRetryerRefusedDropRetriesNone(t *testing.T) {
	inner := &countingRetryer{RetryerV2: retry.NewStandard(func(o *retry.StandardOptions) {
		o.RateLimiter = ratelimit.NewTokenRateLimit(0)
	})}
	r := wrapRetryer(inner).(aws.RetryerV2)
	d := &dropCharge{}
	for i := range 3 {
		if _, err := r.GetRetryToken(context.Background(), lostErr(d)); !errors.As(err, new(ratelimit.QuotaExceededError)) {
			t.Fatalf("call %d: %v", i, err)
		}
	}
	if inner.retryTokens != 1 {
		t.Fatalf("the quota was asked %d times for one drop", inner.retryTokens)
	}
}

func TestDropRetryerReleasesOnlyThePaidToken(t *testing.T) {
	inner := &countingRetryer{RetryerV2: retry.NewStandard()}
	r := wrapRetryer(inner).(aws.RetryerV2)
	d := &dropCharge{}
	for range 3 {
		release, err := r.GetRetryToken(context.Background(), lostErr(d))
		if err != nil {
			t.Fatal(err)
		}
		_ = release(nil)
	}
	if inner.retryTokens != 1 || inner.released != 1 {
		t.Fatalf("tokens %d, released %d; want 1, 1", inner.retryTokens, inner.released)
	}
	for _, err := range []error{lostErr(nil), sentErr(&OriginError{Code: "ORIGIN_ERROR"}, nil), errors.New("other")} {
		if _, e := r.GetRetryToken(context.Background(), err); e != nil {
			t.Fatal(e)
		}
	}
	if inner.retryTokens != 4 {
		t.Fatalf("errors of no drop asked the quota %d times, want 3", inner.retryTokens-1)
	}
}

// countingRetryer counts the retry tokens it hands out and releases.
type countingRetryer struct {
	aws.RetryerV2
	retryTokens, released int
}

func (c *countingRetryer) GetRetryToken(ctx context.Context, err error) (func(error) error, error) {
	release, e := c.RetryerV2.GetRetryToken(ctx, err)
	c.retryTokens++
	if e != nil {
		return nil, e
	}
	return func(err error) error { c.released++; return release(err) }, nil
}

// v1Retryer is an aws.Retryer without GetAttemptToken.
type v1Retryer struct {
	initial int
}

func (*v1Retryer) IsErrorRetryable(error) bool                  { return true }
func (*v1Retryer) MaxAttempts() int                             { return 3 }
func (*v1Retryer) RetryDelay(int, error) (time.Duration, error) { return 0, nil }
func (*v1Retryer) GetRetryToken(context.Context, error) (func(error) error, error) {
	return noRelease, nil
}
func (r *v1Retryer) GetInitialToken() func(error) error {
	return func(error) error { r.initial++; return nil }
}

func TestWrapRetryer(t *testing.T) {
	if wrapRetryer(nil) != nil {
		t.Fatal("nil retryer wrapped")
	}
	v1 := &v1Retryer{}
	r, ok := wrapRetryer(v1).(aws.RetryerV2)
	if !ok || r.MaxAttempts() != 3 {
		t.Fatalf("wrapped %T", r)
	}
	release, err := r.GetAttemptToken(context.Background())
	if err != nil || release(nil) != nil || v1.initial != 1 {
		t.Fatalf("attempt token: %v, initial %d", err, v1.initial)
	}
}

// A retryer wrapped twice, as when New is given the options of a client built by this
// package, pays one drop once and does not deadlock.
func TestDropRetryerWrappedTwice(t *testing.T) {
	inner := &countingRetryer{RetryerV2: retry.NewStandard()}
	r := wrapRetryer(retry.AddWithMaxAttempts(wrapRetryer(inner), 5)).(aws.RetryerV2)
	d := &dropCharge{}
	done := make(chan int, 1)
	go func() { done <- tokens(r, repeat(3, func() error { return lostErr(d) })...) }()
	select {
	case n := <-done:
		if n != 3 || inner.retryTokens != 1 {
			t.Fatalf("granted %d, quota asked %d times; want 3, 1", n, inner.retryTokens)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("GetRetryToken deadlocked")
	}
}

// blockingRetryer holds GetRetryToken until release is closed.
type blockingRetryer struct {
	aws.RetryerV2
	entered chan struct{}
	release chan struct{}
}

func (r *blockingRetryer) GetRetryToken(ctx context.Context, err error) (func(error) error, error) {
	close(r.entered)
	<-r.release
	return noRelease, nil
}

// A call that waits for another call of the same drop to pay gives up when its own ctx ends.
func TestDropRetryerFollowerHonoursItsContext(t *testing.T) {
	inner := &blockingRetryer{entered: make(chan struct{}), release: make(chan struct{})}
	r := wrapRetryer(inner).(aws.RetryerV2)
	d := &dropCharge{}
	leader := make(chan error, 1)
	go func() {
		_, err := r.GetRetryToken(context.Background(), lostErr(d))
		leader <- err
	}()
	<-inner.entered
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	follower := make(chan error, 1)
	go func() {
		_, err := r.GetRetryToken(ctx, lostErr(d))
		follower <- err
	}()
	select {
	case err := <-follower:
		if !errors.Is(err, context.Canceled) {
			t.Errorf("follower: got %v, want context.Canceled", err)
		}
	case <-time.After(5 * time.Second):
		t.Error("the follower waited for the paying call past its own ctx")
	}
	close(inner.release)
	if err := <-leader; err != nil {
		t.Errorf("leader: got %v, want nil", err)
	}
	if _, err := r.GetRetryToken(context.Background(), lostErr(d)); err != nil {
		t.Errorf("a call after the payment: got %v, want nil", err)
	}
}

// ctxRetryer refuses a retry token with ctx.Err() once ctx has ended, as a retryer that waits
// on ctx does, and counts the tokens it grants.
type ctxRetryer struct {
	aws.RetryerV2
	granted int
}

func (r *ctxRetryer) GetRetryToken(ctx context.Context, err error) (func(error) error, error) {
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	r.granted++
	return noRelease, nil
}

// A payment refused only because the paying call's own ctx ended settles nothing: the next
// call of the drop pays, and no call sees another call's context error.
func TestDropRetryerPayerContextEndSettlesNothing(t *testing.T) {
	inner := &ctxRetryer{}
	r := wrapRetryer(inner).(aws.RetryerV2)
	d := &dropCharge{}
	ended, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := r.GetRetryToken(ended, lostErr(d)); !errors.Is(err, context.Canceled) {
		t.Fatalf("payer with an ended ctx: got %v, want context.Canceled", err)
	}
	for i := range 3 {
		if _, err := r.GetRetryToken(context.Background(), lostErr(d)); err != nil {
			t.Fatalf("call %d after it: got %v, want nil", i, err)
		}
	}
	if inner.granted != 1 {
		t.Fatalf("the retryer granted %d tokens for one drop, want 1", inner.granted)
	}
}

// panicRetryer panics on its first GetRetryToken and grants every later one.
type panicRetryer struct {
	aws.RetryerV2
	calls int
}

func (r *panicRetryer) GetRetryToken(context.Context, error) (func(error) error, error) {
	if r.calls++; r.calls == 1 {
		panic("retryer failed")
	}
	return noRelease, nil
}

// A payment that panics settles nothing: the panic reaches the paying call and the next call
// of the drop pays.
func TestDropRetryerPanicSettlesNothing(t *testing.T) {
	inner := &panicRetryer{}
	r := wrapRetryer(inner).(aws.RetryerV2)
	d := &dropCharge{}
	func() {
		defer func() {
			if recover() == nil {
				t.Fatal("the retryer's panic did not reach the paying call")
			}
		}()
		_, _ = r.GetRetryToken(context.Background(), lostErr(d))
	}()
	if _, err := r.GetRetryToken(context.Background(), lostErr(d)); err != nil || inner.calls != 2 {
		t.Fatalf("next call: got %v after %d retryer calls, want nil after 2", err, inner.calls)
	}
}

// Calls of one drop asking at once share one payment.
func TestDropRetryerConcurrentCallsPayOnce(t *testing.T) {
	inner := &countingRetryer{RetryerV2: retry.NewStandard()}
	r := wrapRetryer(inner).(aws.RetryerV2)
	d := &dropCharge{}
	var wg sync.WaitGroup
	var granted atomic.Int32
	for range 50 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := r.GetRetryToken(context.Background(), lostErr(d)); err == nil {
				granted.Add(1)
			}
		}()
	}
	wg.Wait()
	if n := granted.Load(); n != 50 || inner.retryTokens != 1 {
		t.Fatalf("granted %d, quota asked %d times; want 50, 1", n, inner.retryTokens)
	}
}
