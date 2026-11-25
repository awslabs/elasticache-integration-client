// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

// Command local-e2e runs the ElastiCache Integrations DynamoDB scenarios against a real cache
// and table. Settings come from the environment and the repository-root .env file; see
// .env.example and DEVELOPER.md.
package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"math"
	"net"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	awsmiddleware "github.com/aws/aws-sdk-go-v2/aws/middleware"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
	smithyhttp "github.com/aws/smithy-go/transport/http"

	"github.com/awslabs/elasticache-integration-client/go/elasticacheintegrations"
)

func main() {
	if err := run(context.Background()); err != nil {
		fmt.Fprintln(os.Stderr, "FAIL:", err)
		os.Exit(1)
	}
}

type settings struct {
	cacheHost, cacheName, userID, caCert, tunnelHost string
	cachePort, concurrency                           int
	requestTimeout                                   time.Duration
	region, table, runID                             string
}

func loadSettings() (settings, error) {
	if err := loadDotEnv(); err != nil {
		return settings{}, err
	}
	s := settings{
		cacheHost:  env("ELASTICACHE_CACHE_HOST", "localhost"),
		cacheName:  env("ELASTICACHE_CACHE_NAME", "elasticache-integrations-e2e-cache"),
		userID:     env("ELASTICACHE_CACHE_USER_ID", ""),
		caCert:     env("ELASTICACHE_CA_CERT", ""),
		tunnelHost: env("ELASTICACHE_TUNNEL_HOST", ""),
		region:     env("AWS_REGION", "us-east-1"),
		table:      env("ELASTICACHE_TABLE_NAME", "ElastiCacheIntegrationsTestTable"),
	}
	var err error
	if s.cachePort, err = positiveInt("ELASTICACHE_CACHE_PORT", 6379); err != nil {
		return s, err
	}
	if s.concurrency, err = positiveInt("ELASTICACHE_CONCURRENCY", 16); err != nil {
		return s, err
	}
	if s.requestTimeout, err = requestTimeout(); err != nil {
		return s, err
	}
	// A fresh ID per run forces the first read to be a cache miss and keeps concurrent runs
	// from deleting each other's items.
	s.runID = env("ELASTICACHE_ITEM_ID", "elasticache-integrations-e2e-"+randomID())
	return s, nil
}

// e2e holds the two DynamoDB clients: origin talks to DynamoDB directly, integrations goes
// through the cache.
type e2e struct {
	s            settings
	origin       *dynamodb.Client
	integrations *dynamodb.Client
	mu           sync.Mutex
	seeded       []string
}

func run(ctx context.Context) (err error) {
	s, err := loadSettings()
	if err != nil {
		return err
	}
	if err := checkTunnel(s); err != nil {
		return err
	}
	cfg, err := config.LoadDefaultConfig(ctx, config.WithRegion(s.region))
	if err != nil {
		return fmt.Errorf("loading the AWS config: %w", err)
	}

	integrations, cache, err := elasticacheintegrations.NewFromConfig(ctx, cfg, elasticacheintegrations.Options{
		Endpoint:            net.JoinHostPort(s.cacheHost, strconv.Itoa(s.cachePort)),
		CacheName:           s.cacheName,
		UserID:              s.userID,
		CACertsFile:         s.caCert,
		CacheRequestTimeout: s.requestTimeout,
	})
	if err != nil {
		return fmt.Errorf("connecting to the cache: %w", err)
	}
	defer cache.Close()

	t := &e2e{s: s, origin: dynamodb.NewFromConfig(cfg), integrations: integrations}
	defer func() { err = errors.Join(err, t.cleanup(ctx)) }()

	for _, scenario := range []func(context.Context) error{
		t.cacheHit,
		t.concurrentReads,
		t.query,
		t.writePassThrough,
		t.originError,
		t.largeBinaryItem,
		t.batchReadsAndScan,
		t.deletePassThrough,
	} {
		if err := scenario(ctx); err != nil {
			return err
		}
	}
	fmt.Println("SUCCESS: all ElastiCache Integrations E2E scenarios passed.")
	return nil
}

func (t *e2e) cacheHit(ctx context.Context) error {
	fmt.Println("== Cache hit after origin delete")
	id := t.s.runID + "-hit"
	item, err := t.seed(ctx, id, false)
	if err != nil {
		return err
	}
	if err := t.settle(ctx); err != nil {
		return err
	}
	first, err := t.timedGet(ctx, t.getInput(t.s.table, id), "GetItem #1")
	if err != nil {
		return err
	}
	if err := assertItem(first.Item, item); err != nil {
		return err
	}
	if err := t.deleteFromOrigin(ctx, id, false); err != nil {
		return err
	}
	second, err := t.timedGet(ctx, t.getInput(t.s.table, id), "GetItem #2 (after DynamoDB delete)")
	if err != nil {
		return err
	}
	if err := assertItem(second.Item, item); err != nil {
		return err
	}
	fmt.Println("PASS: ElastiCache Integrations returned the cached item after it was deleted from DynamoDB.")
	return nil
}

func (t *e2e) concurrentReads(ctx context.Context) error {
	fmt.Printf("== %d concurrent reads on distinct keys\n", t.s.concurrency)
	items := make([]map[string]types.AttributeValue, t.s.concurrency)
	for i := range items {
		item, err := t.seed(ctx, fmt.Sprintf("%s-concurrent-%d", t.s.runID, i), true)
		if err != nil {
			return err
		}
		items[i] = item
	}
	if err := t.settle(ctx); err != nil {
		return err
	}
	readAll := func(label string) error {
		started := time.Now()
		got := make([]map[string]types.AttributeValue, len(items))
		errs := make([]error, len(items))
		var wg sync.WaitGroup
		for i, item := range items {
			wg.Add(1)
			go func() {
				defer wg.Done()
				out, err := t.integrations.GetItem(ctx, t.getInput(t.s.table, idOf(item)))
				if err == nil {
					got[i] = out.Item
				}
				errs[i] = err
			}()
		}
		wg.Wait()
		elapsed := time.Since(started)
		if err := errors.Join(errs...); err != nil {
			return err
		}
		// A cross-completed response would carry another key's item.
		for i, item := range items {
			if err := assertItem(got[i], item); err != nil {
				return err
			}
		}
		fmt.Printf("%s: %d GetItems in %s, each matched its key\n", label, len(items), ms(elapsed))
		return nil
	}
	if err := readAll("Cold reads"); err != nil {
		return err
	}
	for _, item := range items {
		if err := t.deleteFromOrigin(ctx, idOf(item), true); err != nil {
			return err
		}
	}
	if err := readAll("Reads after DynamoDB delete"); err != nil {
		return err
	}
	fmt.Println("PASS: concurrent responses matched their requests and were served from the cache.")
	return nil
}

func (t *e2e) query(ctx context.Context) error {
	fmt.Println("== Query through ElastiCache Integrations")
	id := t.s.runID + "-query"
	item, err := t.seed(ctx, id, false)
	if err != nil {
		return err
	}
	if err := t.settle(ctx); err != nil {
		return err
	}
	out, err := t.integrations.Query(ctx, &dynamodb.QueryInput{
		TableName:                 aws.String(t.s.table),
		KeyConditionExpression:    aws.String("id = :id"),
		ExpressionAttributeValues: map[string]types.AttributeValue{":id": str(id)},
	})
	if err != nil {
		return err
	}
	if len(out.Items) != 1 {
		return fmt.Errorf("unexpected Query response: %d items", len(out.Items))
	}
	if err := assertItem(out.Items[0], item); err != nil {
		return err
	}
	fmt.Println("PASS: Query returned the seeded item.")
	return nil
}

func (t *e2e) writePassThrough(ctx context.Context) error {
	fmt.Println("== PutItem through ElastiCache Integrations")
	id := t.s.runID + "-write"
	item := newItem(id)
	t.track(id)
	if _, err := t.integrations.PutItem(ctx, &dynamodb.PutItemInput{TableName: aws.String(t.s.table), Item: item}); err != nil {
		return err
	}
	stored, err := t.consistentGet(ctx, id)
	if err != nil {
		return err
	}
	if err := assertItem(stored, item); err != nil {
		return err
	}
	fmt.Println("PASS: a consistent DynamoDB read returned the item written through ElastiCache Integrations.")
	return nil
}

func (t *e2e) originError(ctx context.Context) error {
	fmt.Println("== Origin error on a missing table")
	missing := t.s.runID + "-missing-table"
	_, err := t.integrations.GetItem(ctx, t.getInput(missing, "missing"))
	if err == nil {
		return fmt.Errorf("GetItem on %s succeeded", missing)
	}
	var notFound *types.ResourceNotFoundException
	if !errors.As(err, &notFound) {
		return fmt.Errorf("expected ResourceNotFoundException, got %w", err)
	}
	fmt.Println("PASS: GetItem on a missing table returned ResourceNotFoundException.")
	return nil
}

func (t *e2e) largeBinaryItem(ctx context.Context) error {
	fmt.Println("== Large binary item")
	id := t.s.runID + "-large"
	// DynamoDB caps an item at 400 KB, so this stays just under the limit.
	payload := make([]byte, 380_000)
	if _, err := rand.Read(payload); err != nil {
		return err
	}
	item := map[string]types.AttributeValue{
		"id":      str(id),
		"payload": &types.AttributeValueMemberB{Value: payload},
		"text":    str("caf\u00e9 \u2713 \u65e5\u672c\u8a9e \U0001f680"),
	}
	fmt.Printf("Seeding %s/%s (%d byte payload) in DynamoDB...\n", t.s.table, id, len(payload))
	if _, err := t.origin.PutItem(ctx, &dynamodb.PutItemInput{TableName: aws.String(t.s.table), Item: item}); err != nil {
		return err
	}
	t.track(id)
	if err := t.settle(ctx); err != nil {
		return err
	}
	for i, label := range []string{"GetItem #1", "GetItem #2 (after DynamoDB delete)"} {
		if i == 1 {
			if err := t.deleteFromOrigin(ctx, id, false); err != nil {
				return err
			}
		}
		out, err := t.timedGet(ctx, t.getInput(t.s.table, id), label)
		if err != nil {
			return err
		}
		if err := assertLargeItem(out.Item, item); err != nil {
			return err
		}
	}
	fmt.Println("PASS: the large binary item matched byte for byte on the miss and the hit.")
	return nil
}

func (t *e2e) batchReadsAndScan(ctx context.Context) error {
	fmt.Println("== BatchGetItem and Scan through ElastiCache Integrations")
	prefix := t.s.runID + "-batch-"
	items := make([]map[string]types.AttributeValue, 3)
	keys := make([]map[string]types.AttributeValue, len(items))
	for i := range items {
		item, err := t.seed(ctx, prefix+strconv.Itoa(i), true)
		if err != nil {
			return err
		}
		items[i], keys[i] = item, map[string]types.AttributeValue{"id": item["id"]}
	}
	if err := t.settle(ctx); err != nil {
		return err
	}
	batch, err := t.integrations.BatchGetItem(ctx, &dynamodb.BatchGetItemInput{
		RequestItems: map[string]types.KeysAndAttributes{t.s.table: {Keys: keys}},
	})
	if err != nil {
		return err
	}
	if len(batch.UnprocessedKeys) > 0 {
		return fmt.Errorf("BatchGetItem left %d tables with unprocessed keys", len(batch.UnprocessedKeys))
	}
	if err := assertItems(batch.Responses[t.s.table], items); err != nil {
		return err
	}
	scan, err := t.integrations.Scan(ctx, &dynamodb.ScanInput{
		TableName:                 aws.String(t.s.table),
		FilterExpression:          aws.String("begins_with(id, :prefix)"),
		ExpressionAttributeValues: map[string]types.AttributeValue{":prefix": str(prefix)},
	})
	if err != nil {
		return err
	}
	if err := assertItems(scan.Items, items); err != nil {
		return err
	}
	fmt.Println("PASS: BatchGetItem and Scan returned every seeded item.")
	return nil
}

func (t *e2e) deletePassThrough(ctx context.Context) error {
	fmt.Println("== DeleteItem and BatchWriteItem through ElastiCache Integrations")
	deleted, err := t.seed(ctx, t.s.runID+"-delete", false)
	if err != nil {
		return err
	}
	if _, err := t.integrations.DeleteItem(ctx, t.deleteInput(idOf(deleted))); err != nil {
		return err
	}
	if err := t.assertAbsent(ctx, idOf(deleted)); err != nil {
		return err
	}

	batchDeleted, err := t.seed(ctx, t.s.runID+"-batch-delete", false)
	if err != nil {
		return err
	}
	batchPut := newItem(t.s.runID + "-batch-put")
	t.track(idOf(batchPut))
	_, err = t.integrations.BatchWriteItem(ctx, &dynamodb.BatchWriteItemInput{
		RequestItems: map[string][]types.WriteRequest{t.s.table: {
			{PutRequest: &types.PutRequest{Item: batchPut}},
			{DeleteRequest: &types.DeleteRequest{Key: map[string]types.AttributeValue{"id": batchDeleted["id"]}}},
		}},
	})
	if err != nil {
		return err
	}
	stored, err := t.consistentGet(ctx, idOf(batchPut))
	if err != nil {
		return err
	}
	if err := assertItem(stored, batchPut); err != nil {
		return err
	}
	if err := t.assertAbsent(ctx, idOf(batchDeleted)); err != nil {
		return err
	}
	fmt.Println("PASS: consistent DynamoDB reads reflected the deletes and the put written through ElastiCache Integrations.")
	return nil
}

// settleTime is how long a scenario waits after seeding before it reads the seeded items
// through the cache: a miss reads DynamoDB with an eventually consistent read.
const settleTime = time.Second

// settle waits settleTime, or until ctx is done, to give the seeded writes time to reach
// every DynamoDB replica.
func (t *e2e) settle(ctx context.Context) error {
	fmt.Printf("Waiting %s for DynamoDB to settle...\n", settleTime)
	timer := time.NewTimer(settleTime)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}

func (t *e2e) timedGet(ctx context.Context, in *dynamodb.GetItemInput, label string) (*dynamodb.GetItemOutput, error) {
	started := time.Now()
	out, err := t.integrations.GetItem(ctx, in)
	if err != nil {
		return nil, fmt.Errorf("%s: %w", label, err)
	}
	elapsed := time.Since(started)
	status := "unknown"
	if raw, ok := awsmiddleware.GetRawResponse(out.ResultMetadata).(*smithyhttp.Response); ok {
		status = strconv.Itoa(raw.StatusCode)
	}
	fmt.Printf("%s: HTTP %s in %s\n", label, status, ms(elapsed))
	return out, nil
}

func (t *e2e) seed(ctx context.Context, id string, quiet bool) (map[string]types.AttributeValue, error) {
	item := newItem(id)
	if !quiet {
		fmt.Printf("Seeding %s/%s directly in DynamoDB...\n", t.s.table, id)
	}
	if _, err := t.origin.PutItem(ctx, &dynamodb.PutItemInput{TableName: aws.String(t.s.table), Item: item}); err != nil {
		return nil, err
	}
	t.track(id)
	return item, nil
}

func (t *e2e) track(id string) {
	t.mu.Lock()
	t.seeded = append(t.seeded, id)
	t.mu.Unlock()
}

func (t *e2e) deleteFromOrigin(ctx context.Context, id string, quiet bool) error {
	if !quiet {
		fmt.Printf("Deleting %s/%s directly from DynamoDB...\n", t.s.table, id)
	}
	if _, err := t.origin.DeleteItem(ctx, t.deleteInput(id)); err != nil {
		return err
	}
	if err := t.assertAbsent(ctx, id); err != nil {
		return err
	}
	if !quiet {
		fmt.Println("Confirmed the item no longer exists in DynamoDB.")
	}
	return nil
}

func (t *e2e) consistentGet(ctx context.Context, id string) (map[string]types.AttributeValue, error) {
	in := t.getInput(t.s.table, id)
	in.ConsistentRead = aws.Bool(true)
	out, err := t.origin.GetItem(ctx, in)
	if err != nil {
		return nil, err
	}
	return out.Item, nil
}

func (t *e2e) assertAbsent(ctx context.Context, id string) error {
	item, err := t.consistentGet(ctx, id)
	if err != nil {
		return err
	}
	if item != nil {
		return fmt.Errorf("DynamoDB still contains %s", id)
	}
	return nil
}

// cleanup deletes every seeded item from DynamoDB, also after a failed scenario.
func (t *e2e) cleanup(ctx context.Context) error {
	var errs []error
	for _, id := range t.seeded {
		if _, err := t.origin.DeleteItem(ctx, t.deleteInput(id)); err != nil {
			errs = append(errs, fmt.Errorf("deleting %s: %w", id, err))
		}
	}
	return errors.Join(errs...)
}

func (t *e2e) getInput(table, id string) *dynamodb.GetItemInput {
	return &dynamodb.GetItemInput{TableName: aws.String(table), Key: map[string]types.AttributeValue{"id": str(id)}}
}

func (t *e2e) deleteInput(id string) *dynamodb.DeleteItemInput {
	return &dynamodb.DeleteItemInput{TableName: aws.String(t.s.table), Key: map[string]types.AttributeValue{"id": str(id)}}
}

func newItem(id string) map[string]types.AttributeValue {
	return map[string]types.AttributeValue{
		"id":          str(id),
		"name":        str("elasticache-integrations-widget"),
		"description": str("e2e test item for ElastiCache Integrations caching"),
	}
}

func str(v string) types.AttributeValue { return &types.AttributeValueMemberS{Value: v} }

func strAttr(item map[string]types.AttributeValue, name string) (string, bool) {
	v, ok := item[name].(*types.AttributeValueMemberS)
	if !ok {
		return "", false
	}
	return v.Value, true
}

func idOf(item map[string]types.AttributeValue) string {
	id, _ := strAttr(item, "id")
	return id
}

func assertItem(got, want map[string]types.AttributeValue) error {
	if got == nil {
		return fmt.Errorf("item %s is missing", idOf(want))
	}
	for _, name := range []string{"id", "name", "description"} {
		g, ok := strAttr(got, name)
		w, _ := strAttr(want, name)
		if !ok || g != w {
			return fmt.Errorf("unexpected item for %s: attribute %q is %q, want %q", idOf(want), name, g, w)
		}
	}
	return nil
}

func assertLargeItem(got, want map[string]types.AttributeValue) error {
	if got == nil {
		return fmt.Errorf("large item %s is missing", idOf(want))
	}
	g, ok := got["payload"].(*types.AttributeValueMemberB)
	w := want["payload"].(*types.AttributeValueMemberB)
	gt, _ := strAttr(got, "text")
	wt, _ := strAttr(want, "text")
	if idOf(got) != idOf(want) || gt != wt || !ok || !bytes.Equal(g.Value, w.Value) {
		return fmt.Errorf("unexpected large item for %s", idOf(want))
	}
	return nil
}

func assertItems(got, want []map[string]types.AttributeValue) error {
	if len(got) != len(want) {
		return fmt.Errorf("got %d items, want %d", len(got), len(want))
	}
	byID := make(map[string]map[string]types.AttributeValue, len(got))
	for _, item := range got {
		byID[idOf(item)] = item
	}
	for _, item := range want {
		if err := assertItem(byID[idOf(item)], item); err != nil {
			return err
		}
	}
	return nil
}

// checkTunnel fails unless the cache host already resolves to ELASTICACHE_TUNNEL_HOST. The
// client dials the endpoint and checks its TLS certificate itself, so the example cannot
// redirect the host the way the Java and Node examples do.
func checkTunnel(s settings) error {
	if s.tunnelHost == "" {
		return nil
	}
	if resolvesOnlyTo(s.cacheHost, s.tunnelHost) {
		return nil
	}
	return fmt.Errorf("ELASTICACHE_TUNNEL_HOST is %s, but %s does not resolve to it; add the line "+
		"\"%s %s\" to /etc/hosts, or unset ELASTICACHE_TUNNEL_HOST", s.tunnelHost, s.cacheHost, s.tunnelHost, s.cacheHost)
}

// resolvesOnlyTo reports whether every address of host is an address of tunnel, so the
// client cannot dial around the tunnel.
func resolvesOnlyTo(host, tunnel string) bool {
	hostAddrs, err := net.LookupHost(host)
	if err != nil || len(hostAddrs) == 0 {
		return false
	}
	tunnelAddrs, err := net.LookupHost(tunnel)
	if err != nil {
		return false
	}
	for _, h := range hostAddrs {
		if !slices.ContainsFunc(tunnelAddrs, func(a string) bool { return net.ParseIP(a).Equal(net.ParseIP(h)) }) {
			return false
		}
	}
	return true
}

// loadDotEnv reads the repository-root .env, next to .env.example, if there is one. A
// variable that is already set keeps its value.
func loadDotEnv() error {
	dir, err := os.Getwd()
	if err != nil {
		return err
	}
	for {
		if _, err := os.Stat(filepath.Join(dir, ".env.example")); err == nil {
			break
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			return nil
		}
		dir = parent
	}
	f, err := os.Open(filepath.Join(dir, ".env"))
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	defer f.Close()
	lines := bufio.NewScanner(f)
	for lines.Scan() {
		line := strings.TrimSpace(lines.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		name, value, ok := strings.Cut(strings.TrimPrefix(line, "export "), "=")
		if !ok {
			continue
		}
		name, value = strings.TrimSpace(name), dotEnvValue(value)
		if _, set := os.LookupEnv(name); !set {
			if err := os.Setenv(name, value); err != nil {
				return err
			}
		}
	}
	return lines.Err()
}

// dotEnvValue parses a .env value as Node's loadEnvFile does: a value in matching quotes is
// the text between them; otherwise a # starts a comment.
func dotEnvValue(raw string) string {
	v := strings.TrimSpace(raw)
	if v != "" && strings.ContainsRune("\"'`", rune(v[0])) {
		if end := strings.IndexByte(v[1:], v[0]); end >= 0 {
			return v[1 : 1+end]
		}
	}
	if i := strings.IndexByte(v, '#'); i >= 0 {
		v = v[:i]
	}
	return strings.TrimSpace(v)
}

func env(name, def string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return def
}

func positiveInt(name string, def int) (int, error) {
	raw := env(name, strconv.Itoa(def))
	v, err := strconv.Atoi(raw)
	if err != nil || v <= 0 {
		return 0, fmt.Errorf("%s must be a positive integer, got %q", name, raw)
	}
	return v, nil
}

func positiveFloat(name string, def float64) (float64, error) {
	raw := env(name, strconv.FormatFloat(def, 'g', -1, 64))
	v, err := strconv.ParseFloat(raw, 64)
	if err != nil || math.IsInf(v, 0) || math.IsNaN(v) || v <= 0 {
		return 0, fmt.Errorf("%s must be a positive number, got %q", name, raw)
	}
	return v, nil
}

// requestTimeout is ELASTICACHE_REQUEST_TIMEOUT_SECONDS rounded to whole milliseconds, within
// the client's 1 ms to 2^31-1 ms range.
func requestTimeout() (time.Duration, error) {
	seconds, err := positiveFloat("ELASTICACHE_REQUEST_TIMEOUT_SECONDS", 5)
	if err != nil {
		return 0, err
	}
	millis := math.Round(seconds * 1000)
	if millis < 1 || millis > math.MaxInt32 {
		return 0, fmt.Errorf("ELASTICACHE_REQUEST_TIMEOUT_SECONDS must be from 0.001 to 2147483.647, got %v", seconds)
	}
	return time.Duration(millis) * time.Millisecond, nil
}

func randomID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func ms(d time.Duration) string {
	return strconv.FormatFloat(float64(d)/float64(time.Millisecond), 'f', 1, 64) + " ms"
}
