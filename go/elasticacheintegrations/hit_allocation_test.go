// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"context"
	"io"
	"net/http"
	"runtime"
	"strings"
	"testing"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"

	"github.com/awslabs/elasticache-integration-client/go/internal/mockcache"
)

// TestGetItemHitAllocationBudget pins the hit budget end to end: at most 16 allocations over
// the same call on a plain SDK client, and at most one body (plus 16 KiB) of extra bytes, so a
// second copy of the body fails it.
func TestGetItemHitAllocationBudget(t *testing.T) {
	if raceEnabled {
		t.Skip("the race detector changes allocation counts")
	}
	for _, size := range []int{1 << 10, 390 << 10} {
		body := itemBody(size)
		sum := crc(body)
		cc := newCannedCache(t, mockcache.Served(200, map[string]string{
			"content-type": "application/x-amz-json-1.0", "x-amz-crc32": sum,
		}, []byte(body)))
		c, err := Options{Endpoint: "localhost:6379", CacheName: "my-cache"}.validate()
		if err != nil {
			t.Fatal(err)
		}
		c.plaintext, c.dial = true, cc.dial
		hit, _ := startSDK(t, c, &fakeOrigin{})
		plain := dynamodb.NewFromConfig(awsConfig(), func(o *dynamodb.Options) {
			o.HTTPClient = httpDoFunc(func(req *http.Request) (*http.Response, error) {
				if req.Body != nil {
					_, _ = io.Copy(io.Discard, req.Body)
				}
				return &http.Response{StatusCode: 200, Status: "200 OK", Proto: "HTTP/1.1", ProtoMajor: 1, ProtoMinor: 1,
					Header:        http.Header{"Content-Type": {"application/x-amz-json-1.0"}, "X-Amz-Crc32": {sum}},
					Body:          io.NopCloser(strings.NewReader(body)),
					ContentLength: int64(len(body)), Request: req}, nil
			})
		})
		in := &dynamodb.GetItemInput{TableName: aws.String("Music"), Key: musicKey()}
		call := func(ddb *dynamodb.Client) func() {
			return func() {
				if out, err := ddb.GetItem(context.Background(), in); err != nil || len(out.Item) != 2 {
					t.Fatal(out, err)
				}
			}
		}
		if hitN, plainN := testing.AllocsPerRun(50, call(hit)), testing.AllocsPerRun(50, call(plain)); hitN-plainN > 16 {
			t.Errorf("%d bytes: a hit allocates %v times, a plain call %v: over the budget of 16 more", size, hitN, plainN)
		}
		hitB, plainB := bytesPerRun(50, call(hit)), bytesPerRun(50, call(plain))
		if extra := int64(hitB) - int64(plainB); size > 64<<10 && extra > int64(len(body))+16<<10 {
			t.Errorf("%d bytes: a hit allocates %d bytes over a plain call, more than one %d-byte body", size, extra, len(body))
		}
	}
}

// bytesPerRun is the bytes f allocates per call, after one warm-up call.
func bytesPerRun(runs int, f func()) uint64 {
	f()
	var before, after runtime.MemStats
	runtime.ReadMemStats(&before)
	for range runs {
		f()
	}
	runtime.ReadMemStats(&after)
	return (after.TotalAlloc - before.TotalAlloc) / uint64(runs)
}

// TestPutItemLargeBodyBytes checks that a large PutItem through the cache allocates at most one
// body (plus 16 KiB) more than a plain call: the body read for the cache, not a copy into the
// frame as well.
func TestPutItemLargeBodyBytes(t *testing.T) {
	if raceEnabled {
		t.Skip("the race detector changes allocation counts")
	}
	const size = 390 << 10
	cc := newCannedCache(t, mockcache.Served(200, map[string]string{
		"content-type": "application/x-amz-json-1.0", "x-amz-crc32": crc("{}"),
	}, []byte("{}")))
	c, err := Options{Endpoint: "localhost:6379", CacheName: "my-cache"}.validate()
	if err != nil {
		t.Fatal(err)
	}
	c.plaintext, c.dial = true, cc.dial
	hit, _ := startSDK(t, c, &fakeOrigin{})
	plain := dynamodb.NewFromConfig(awsConfig(), func(o *dynamodb.Options) {
		o.HTTPClient = httpDoFunc(func(req *http.Request) (*http.Response, error) {
			if req.Body != nil {
				_, _ = io.Copy(io.Discard, req.Body)
			}
			return &http.Response{StatusCode: 200, Status: "200 OK", Proto: "HTTP/1.1", ProtoMajor: 1, ProtoMinor: 1,
				Header:        http.Header{"Content-Type": {"application/x-amz-json-1.0"}, "X-Amz-Crc32": {crc("{}")}},
				Body:          io.NopCloser(strings.NewReader("{}")),
				ContentLength: 2, Request: req}, nil
		})
	})
	in := &dynamodb.PutItemInput{TableName: aws.String("Music"), Item: map[string]types.AttributeValue{
		"Artist": &types.AttributeValueMemberS{Value: "Acme"},
		"Data":   &types.AttributeValueMemberS{Value: strings.Repeat("x", size)},
	}}
	call := func(ddb *dynamodb.Client) func() {
		return func() {
			if _, err := ddb.PutItem(context.Background(), in); err != nil {
				t.Fatal(err)
			}
		}
	}
	hitB, plainB := bytesPerRun(20, call(hit)), bytesPerRun(20, call(plain))
	if extra := int64(hitB) - int64(plainB); extra > size+16<<10 {
		t.Errorf("a %d-byte PutItem through the cache allocates %d bytes over a plain call, more than one body", size, extra)
	}
}
