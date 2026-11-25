// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"context"
	"errors"
	"sync"

	"github.com/aws/aws-sdk-go-v2/aws"
)

// dropCharge is the retry quota cost of one connection drop, shared by every call the drop
// failed. The first of them to ask for a retry token pays it; the others follow its answer.
type dropCharge struct {
	mu      sync.Mutex
	settled bool          // the retryer granted the token or refused it
	err     error         // the refusal, once settled
	paying  chan struct{} // non-nil while a call pays; closed when it is done
}

// dropRetryer is the SDK retryer of a client. A call failed by a connection drop costs the
// retry quota only once per drop, as one dropped HTTP connection costs one retry without the
// cache. Every other decision, and every other retry, is the wrapped retryer's.
type dropRetryer struct {
	aws.RetryerV2
}

// wrapRetryer returns r with the drop retry cost applied, or nil for a nil r.
func wrapRetryer(r aws.Retryer) aws.Retryer {
	if r == nil {
		return nil
	}
	v2, ok := r.(aws.RetryerV2)
	if !ok {
		v2 = retryerV2{r}
	}
	return dropRetryer{v2}
}

// GetRetryToken pays the drop's retry cost for the first call the drop failed. The others
// wait for its answer, or for their own ctx, then retry without a token or are refused as the
// first was. A payment that ends because the paying call's own ctx ended settles nothing: a
// waiting call pays instead.
func (r dropRetryer) GetRetryToken(ctx context.Context, opErr error) (func(error) error, error) {
	var e *sdkError
	if !errors.As(opErr, &e) || e.charge == nil || ctx.Value(payingDrop{}) != nil {
		return r.RetryerV2.GetRetryToken(ctx, opErr)
	}
	d := e.charge
	for {
		d.mu.Lock()
		if d.settled {
			err := d.err
			d.mu.Unlock()
			if err != nil {
				return nil, err
			}
			return noRelease, nil
		}
		paying := d.paying
		if paying == nil {
			paying = make(chan struct{})
			d.paying = paying
			d.mu.Unlock()
			return r.pay(ctx, opErr, d, paying)
		}
		d.mu.Unlock()
		select {
		case <-paying:
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
}

// pay asks the wrapped retryer for the drop's one token.
func (r dropRetryer) pay(ctx context.Context, opErr error, d *dropCharge, paying chan struct{}) (release func(error) error, err error) {
	answered := false
	defer func() {
		d.mu.Lock()
		if answered && (err == nil || ctx.Err() == nil) {
			d.settled, d.err = true, err
		}
		d.paying = nil
		d.mu.Unlock()
		close(paying)
	}()
	release, err = r.RetryerV2.GetRetryToken(context.WithValue(ctx, payingDrop{}, true), opErr)
	answered = true
	return release, err
}

func noRelease(error) error { return nil }

// payingDrop marks the context of a drop's payment, so a dropRetryer wrapped inside another
// delegates instead of waiting on the same charge.
type payingDrop struct{}

// retryerV2 gives an aws.Retryer the attempt token the SDK gives it.
type retryerV2 struct {
	aws.Retryer
}

func (r retryerV2) GetAttemptToken(context.Context) (func(error) error, error) {
	return r.GetInitialToken(), nil
}
