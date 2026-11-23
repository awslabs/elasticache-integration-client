// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	awshttp "github.com/aws/aws-sdk-go-v2/aws/transport/http"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"

	"github.com/awslabs/elasticache-integration-client/go/elasticacheintegrations"
)

type settings struct {
	host, cacheName, userID, region, table, relayTarget string
	port                                                int
}

func loadSettings(getenv func(string) string) (settings, error) {
	env := func(name, def string) string {
		if v := getenv(name); v != "" {
			return v
		}
		return def
	}
	s := settings{
		host:        getenv("ELASTICACHE_CACHE_HOST"),
		cacheName:   getenv("ELASTICACHE_CACHE_NAME"),
		userID:      getenv("ELASTICACHE_CACHE_USER_ID"),
		region:      env("AWS_REGION", "us-east-1"),
		table:       getenv("ELASTICACHE_TABLE_NAME"),
		relayTarget: getenv("LIVE_RELAY_TARGET"),
	}
	for name, v := range map[string]string{
		"ELASTICACHE_CACHE_HOST": s.host, "ELASTICACHE_CACHE_NAME": s.cacheName, "ELASTICACHE_TABLE_NAME": s.table,
	} {
		if v == "" {
			return s, fmt.Errorf("%s is not set", name)
		}
	}
	raw := env("ELASTICACHE_CACHE_PORT", "6379")
	port, err := strconv.Atoi(raw)
	if err != nil || port < 1 || port > 65535 {
		return s, fmt.Errorf("ELASTICACHE_CACHE_PORT must be a port number, got %q", raw)
	}
	s.port = port
	return s, nil
}

// live holds what every mode shares: settings, the plain client, the event log and the results.
type live struct {
	s       settings
	cfg     aws.Config
	plain   *dynamodb.Client
	events  *eventLog
	out     io.Writer
	run     string
	start   time.Time
	mu      sync.Mutex // guards passed and failed
	passed  int
	failed  int
	closers []func() error
}

func newLive(ctx context.Context, s settings, out io.Writer) (*live, error) {
	cfg, err := config.LoadDefaultConfig(ctx, config.WithRegion(s.region))
	if err != nil {
		return nil, fmt.Errorf("loading the AWS config: %w", err)
	}
	b := make([]byte, 6)
	_, _ = rand.Read(b)
	return &live{
		s: s, cfg: cfg, plain: dynamodb.NewFromConfig(cfg), events: newEventLog(out), out: out,
		run: "go-live-" + hex.EncodeToString(b), start: time.Now(),
	}, nil
}

// originCounter is the integrations client's origin: it counts the calls the client sends to
// DynamoDB itself.
type originCounter struct {
	next  aws.HTTPClient
	calls atomic.Int64
}

func (o *originCounter) Do(r *http.Request) (*http.Response, error) {
	o.calls.Add(1)
	return o.next.Do(r)
}

// cacheClient is one DynamoDB client through the cache, with its origin counter.
type cacheClient struct {
	ddb    *dynamodb.Client
	cache  *elasticacheintegrations.Client
	origin *originCounter
}

// open starts a client through the cache. edit changes the options; cfg replaces l.cfg when set.
func (l *live) open(ctx context.Context, cfg *aws.Config, edit func(*elasticacheintegrations.Options)) (*cacheClient, error) {
	if cfg == nil {
		cfg = &l.cfg
	}
	opts := elasticacheintegrations.Options{
		Endpoint:  net.JoinHostPort(l.s.host, strconv.Itoa(l.s.port)),
		CacheName: l.s.cacheName,
		UserID:    l.s.userID,
		Logger:    l.events.logger(),
	}
	if edit != nil {
		edit(&opts)
	}
	origin := &originCounter{next: awshttp.NewBuildableClient()}
	ddb, cache, err := elasticacheintegrations.NewFromConfig(ctx, *cfg, opts, func(o *dynamodb.Options) {
		o.HTTPClient = origin
	})
	if err != nil {
		return nil, err
	}
	l.closers = append(l.closers, cache.Close)
	return &cacheClient{ddb: ddb, cache: cache, origin: origin}, nil
}

// check records and prints one result. It is safe for concurrent use.
func (l *live) check(name string, ok bool, format string, args ...any) {
	verdict := "PASS"
	l.mu.Lock()
	if ok {
		l.passed++
	} else {
		verdict = "FAIL"
		l.failed++
	}
	l.mu.Unlock()
	fmt.Fprintf(l.out, "%8.1fs %s %s: %s\n", time.Since(l.start).Seconds(), verdict, name, fmt.Sprintf(format, args...))
}

// step prints a progress line.
func (l *live) step(format string, args ...any) {
	fmt.Fprintf(l.out, "%8.1fs   %s\n", time.Since(l.start).Seconds(), fmt.Sprintf(format, args...))
}

// key is the item key of one of this run's items.
func (l *live) key(name string) map[string]types.AttributeValue {
	return map[string]types.AttributeValue{"id": str(l.run + "-" + name)}
}

// put writes an item with the plain client.
func (l *live) put(ctx context.Context, item map[string]types.AttributeValue) error {
	_, err := l.plain.PutItem(ctx, &dynamodb.PutItemInput{TableName: aws.String(l.s.table), Item: item})
	return err
}

// direct is the plain client's consistent read of key.
func (l *live) direct(ctx context.Context, key map[string]types.AttributeValue) (map[string]types.AttributeValue, error) {
	out, err := l.plain.GetItem(ctx, &dynamodb.GetItemInput{TableName: aws.String(l.s.table), Key: key, ConsistentRead: aws.Bool(true)})
	if err != nil {
		return nil, err
	}
	return out.Item, nil
}

// get is an eventually consistent GetItem on c.
func (l *live) get(ctx context.Context, c *dynamodb.Client, key map[string]types.AttributeValue) (map[string]types.AttributeValue, error) {
	out, err := c.GetItem(ctx, &dynamodb.GetItemInput{TableName: aws.String(l.s.table), Key: key})
	if err != nil {
		return nil, err
	}
	return out.Item, nil
}

// pause waits d or until ctx ends.
func pause(ctx context.Context, d time.Duration) error {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}

// close closes every client and deletes this run's items, also after a failure or a signal.
func (l *live) close() error {
	var errs []error
	for _, c := range l.closers {
		errs = append(errs, c())
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	n, err := l.cleanup(ctx)
	if err != nil {
		errs = append(errs, fmt.Errorf("cleanup: %w", err))
	}
	l.step("cleanup: deleted %d items", n)
	return errors.Join(errs...)
}

func (l *live) cleanup(ctx context.Context) (int, error) {
	pages := dynamodb.NewScanPaginator(l.plain, &dynamodb.ScanInput{
		TableName:                 aws.String(l.s.table),
		ConsistentRead:            aws.Bool(true),
		FilterExpression:          aws.String("begins_with(id, :p)"),
		ExpressionAttributeValues: map[string]types.AttributeValue{":p": str(l.run + "-")},
		ProjectionExpression:      aws.String("id"),
	})
	n := 0
	for pages.HasMorePages() {
		page, err := pages.NextPage(ctx)
		if err != nil {
			return n, err
		}
		for _, item := range page.Items {
			if _, err := l.plain.DeleteItem(ctx, &dynamodb.DeleteItemInput{TableName: aws.String(l.s.table), Key: item}); err != nil {
				return n, err
			}
			n++
		}
	}
	return n, nil
}

func str(v string) types.AttributeValue { return &types.AttributeValueMemberS{Value: v} }

func num(v int) types.AttributeValue { return &types.AttributeValueMemberN{Value: strconv.Itoa(v)} }

func ms(d time.Duration) string {
	return strconv.FormatFloat(float64(d)/float64(time.Millisecond), 'f', 1, 64) + " ms"
}

func exitCode(l *live, err error) int {
	if err != nil {
		fmt.Fprintln(os.Stderr, "FAIL:", err)
		return 1
	}
	l.mu.Lock()
	passed, failed := l.passed, l.failed
	l.mu.Unlock()
	fmt.Fprintf(l.out, "%d passed, %d failed\n", passed, failed)
	if failed > 0 {
		return 1
	}
	return 0
}
