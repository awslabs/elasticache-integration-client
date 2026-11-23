// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"context"
	"crypto/rand"
	"fmt"
	"slices"
	"strconv"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
	"github.com/aws/smithy-go/middleware"

	"github.com/awslabs/elasticache-integration-client/go/elasticacheintegrations"
)

const (
	pushTrials   = 20
	pushItemSize = 200 << 10
	// hitDelay is how long the hit starts after the miss. A spin, not a timer: a timer can resume
	// after the miss has finished (.NET live run).
	hitDelay = time.Millisecond
	// overtakeMargin is how much earlier the hit's reply must arrive than the miss's. In-order
	// replies arrive back to back, so their arrival times differ only by goroutine scheduling.
	overtakeMargin = time.Millisecond
)

// runPush runs check 8: on one connection the reply to a hit started after a miss arrives
// first, which only async (pushed) replies allow.
func runPush(ctx context.Context, l *live) error {
	c, err := l.open(ctx, nil, func(o *elasticacheintegrations.Options) { o.Connections = 1 })
	if err != nil {
		return fmt.Errorf("start-up: %w", err)
	}
	hot := l.key("push-hot")
	hot["v"] = num(1)
	if err := l.put(ctx, hot); err != nil {
		return err
	}
	for i := range pushTrials {
		item := l.key("push-cold-" + strconv.Itoa(i))
		payload := make([]byte, pushItemSize)
		_, _ = rand.Read(payload)
		item["payload"] = &types.AttributeValueMemberB{Value: payload}
		if err := l.put(ctx, item); err != nil {
			return err
		}
	}
	if err := pause(ctx, 2*time.Second); err != nil {
		return err
	}
	if _, err := l.get(ctx, c.ddb, l.key("push-hot")); err != nil {
		return err
	}
	if _, err := l.plain.UpdateItem(ctx, &dynamodb.UpdateItemInput{
		TableName: aws.String(l.s.table), Key: l.key("push-hot"), UpdateExpression: aws.String("SET v = :v"),
		ExpressionAttributeValues: map[string]types.AttributeValue{":v": num(2)},
	}); err != nil {
		return err
	}
	if err := pause(ctx, 2*time.Second); err != nil {
		return err
	}
	cached, err := l.get(ctx, c.ddb, l.key("push-hot"))
	if err != nil {
		return err
	}
	l.check("8 the hit item is cached", value(cached, "v") == "1", "cache v=%s, DynamoDB v=2", value(cached, "v"))

	hitFirst, bad := 0, 0
	var missTimes, hitTimes []time.Duration
	for i := range pushTrials {
		type result struct {
			took    time.Duration
			arrived time.Time
			item    map[string]types.AttributeValue
			err     error
		}
		missDone := make(chan result, 1)
		start := time.Now()
		go func() {
			var arrived time.Time
			item, err := l.getAt(ctx, c.ddb, l.key("push-cold-"+strconv.Itoa(i)), &arrived)
			missDone <- result{time.Since(start), arrived, item, err}
		}()
		for time.Since(start) < hitDelay {
		}
		var hitArrived time.Time
		hitStart := time.Now()
		hit, err := l.getAt(ctx, c.ddb, l.key("push-hot"), &hitArrived)
		hitTook := time.Since(hitStart)
		miss := <-missDone
		if err != nil || miss.err != nil || value(hit, "v") != "1" || miss.item == nil {
			bad++
			l.step("trial %d: hit v=%s err %v; miss item %v err %v", i, value(hit, "v"), err, miss.item != nil, miss.err)
			continue
		}
		if overtook(hitArrived, miss.arrived) {
			hitFirst++
		}
		missTimes = append(missTimes, miss.took)
		hitTimes = append(hitTimes, hitTook)
	}
	l.check("8 a hit overtakes an earlier miss on the same connection", bad == 0 && hitFirst >= pushTrials/2,
		"hit reply first by over %s in %d of %d, bad trials %d, median miss %s, median hit %s, origin calls %d",
		overtakeMargin, hitFirst, pushTrials, bad, ms(median(missTimes)), ms(median(hitTimes)), c.origin.calls.Load())
	return nil
}

// overtook reports whether the hit's reply arrived before the miss's by more than
// overtakeMargin. Both are raw reply times, taken before the SDK decodes the body: decoding a
// 200 KiB item takes milliseconds, so a hit replied in order would still finish first.
func overtook(hit, miss time.Time) bool {
	return !hit.IsZero() && !miss.IsZero() && hit.Add(overtakeMargin).Before(miss)
}

// getAt is get, and sets *arrived when the raw reply comes back, before the SDK decodes it.
func (l *live) getAt(ctx context.Context, c *dynamodb.Client, key map[string]types.AttributeValue, arrived *time.Time) (map[string]types.AttributeValue, error) {
	out, err := c.GetItem(ctx, &dynamodb.GetItemInput{TableName: aws.String(l.s.table), Key: key}, recordArrival(arrived))
	if err != nil {
		return nil, err
	}
	return out.Item, nil
}

// recordArrival adds a deserialize middleware inside the operation's own, so it sees the raw
// reply before it is decoded.
func recordArrival(at *time.Time) func(*dynamodb.Options) {
	return func(o *dynamodb.Options) {
		o.APIOptions = append(o.APIOptions, func(s *middleware.Stack) error {
			return s.Deserialize.Add(middleware.DeserializeMiddlewareFunc("LiveReplyArrival",
				func(ctx context.Context, in middleware.DeserializeInput, next middleware.DeserializeHandler) (
					middleware.DeserializeOutput, middleware.Metadata, error) {
					out, md, err := next.HandleDeserialize(ctx, in)
					*at = time.Now()
					return out, md, err
				}), middleware.After)
		})
	}
}

func median(d []time.Duration) time.Duration {
	if len(d) == 0 {
		return 0
	}
	s := slices.Clone(d)
	slices.Sort(s)
	return s[len(s)/2]
}
