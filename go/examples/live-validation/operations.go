// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"

	"github.com/awslabs/elasticache-integration-client/go/elasticacheintegrations"
)

// settle is how long a miss waits after a write, because the proxy's miss read is eventually
// consistent.
const settle = time.Second

// runOperations runs checks 1-5 and 9.
func runOperations(ctx context.Context, l *live) error {
	started := time.Now()
	c, err := l.open(ctx, nil, nil)
	if err != nil {
		return fmt.Errorf("check 1: start-up: %w", err)
	}
	l.check("1 IAM token accepted", true, "bring-up in %s", ms(time.Since(started)))
	started = time.Now()
	upper, err := l.open(ctx, nil, func(o *elasticacheintegrations.Options) { o.CacheName = strings.ToUpper(o.CacheName) })
	l.check("1 upper-case cache name accepted", err == nil, "bring-up in %s, err %v", ms(time.Since(started)), err)
	if err == nil {
		_ = upper.cache.Close()
	}
	l.check("9 config applied and usable", l.events.since(0, 1301, "applied") > 0 && l.events.since(0, 1307, "") == 0,
		"1301 seen %d, 1307 seen %d", l.events.since(0, 1301, "applied"), l.events.since(0, 1307, ""))

	o := &ops{live: l, c: c}
	for _, step := range []func(context.Context) error{o.hitAndMiss, o.reads, o.writes, o.passThrough} {
		if err := step(ctx); err != nil {
			return err
		}
	}
	return nil
}

type ops struct {
	*live
	c *cacheClient
}

func (o *ops) item(name string, v int) map[string]types.AttributeValue {
	item := o.key(name)
	item["v"] = num(v)
	item["tags"] = &types.AttributeValueMemberSS{Value: []string{"b", "a"}}
	item["ns"] = &types.AttributeValueMemberNS{Value: []string{"2", "1.5"}}
	item["b"] = &types.AttributeValueMemberB{Value: []byte{0, 1, 0xff}}
	item["m"] = &types.AttributeValueMemberM{Value: map[string]types.AttributeValue{
		"n": num(v * 10),
		"l": &types.AttributeValueMemberL{Value: []types.AttributeValue{
			str("z"), &types.AttributeValueMemberBOOL{Value: true}, &types.AttributeValueMemberNULL{Value: true},
		}},
	}}
	return item
}

// probe changes each named item with the plain client, behind the cache's back, then waits 2 s
// so that a cache read still without the probe can only be a hit.
func (o *ops) probe(ctx context.Context, names ...string) error {
	for _, name := range names {
		_, err := o.plain.UpdateItem(ctx, &dynamodb.UpdateItemInput{
			TableName: aws.String(o.s.table), Key: o.key(name), UpdateExpression: aws.String("SET probe = :p"),
			ExpressionAttributeValues: map[string]types.AttributeValue{":p": num(1)},
		})
		if err != nil {
			return fmt.Errorf("probing %s: %w", name, err)
		}
	}
	return pause(ctx, 2*time.Second)
}

// same checks got against the plain client's consistent read of key.
func (o *ops) same(ctx context.Context, name string, got map[string]types.AttributeValue, key map[string]types.AttributeValue) error {
	want, err := o.direct(ctx, key)
	if err != nil {
		return err
	}
	o.check(name, canon(got) == canon(want), "cache %s, DynamoDB %s", canon(got), canon(want))
	return nil
}

// hitAndMiss is check 4: a miss fills, then a stale read proves the hit.
func (o *ops) hitAndMiss(ctx context.Context) error {
	key := o.key("hit")
	if err := o.put(ctx, o.item("hit", 1)); err != nil {
		return err
	}
	if err := pause(ctx, settle); err != nil {
		return err
	}
	started := time.Now()
	miss, err := o.get(ctx, o.c.ddb, key)
	if err != nil {
		return fmt.Errorf("check 4: miss: %w", err)
	}
	missTime := time.Since(started)
	if err := o.same(ctx, "4 miss returns the DynamoDB item", miss, key); err != nil {
		return err
	}
	_, err = o.plain.UpdateItem(ctx, &dynamodb.UpdateItemInput{
		TableName: aws.String(o.s.table), Key: key, UpdateExpression: aws.String("SET v = :v"),
		ExpressionAttributeValues: map[string]types.AttributeValue{":v": num(2)},
	})
	if err != nil {
		return err
	}
	direct, err := o.direct(ctx, key)
	if err != nil {
		return err
	}
	if err := pause(ctx, 2*time.Second); err != nil {
		return err
	}
	started = time.Now()
	hit, err := o.get(ctx, o.c.ddb, key)
	if err != nil {
		return fmt.Errorf("check 4: hit: %w", err)
	}
	o.check("4 a read after a direct write is served from the cache", value(direct, "v") == "2" && value(hit, "v") == "1",
		"DynamoDB v=%s, cache v=%s; miss %s, hit %s", value(direct, "v"), value(hit, "v"), ms(missTime), ms(time.Since(started)))
	return nil
}

func value(item map[string]types.AttributeValue, name string) string {
	if n, ok := item[name].(*types.AttributeValueMemberN); ok {
		return n.Value
	}
	return "<none>"
}

// reads is the read half of check 2 and of check 4: the five read operations against
// DynamoDB, then the same reads after a probe: the cached ones must return their first answer,
// TransactGetItems (never served from the cache) the probed items.
func (o *ops) reads(ctx context.Context) error {
	names := []string{"r-get", "r-batch-0", "r-batch-1", "r-query", "r-scan-0", "r-scan-1", "r-tx-0", "r-tx-1"}
	for i, name := range names {
		if err := o.put(ctx, o.item(name, i)); err != nil {
			return err
		}
	}
	if err := pause(ctx, settle); err != nil {
		return err
	}
	table := aws.String(o.s.table)
	type read struct {
		op     string
		cached bool
		names  []string
		call   func(context.Context) ([]map[string]types.AttributeValue, error)
	}
	all := []read{
		{"GetItem", true, []string{"r-get"}, func(ctx context.Context) ([]map[string]types.AttributeValue, error) {
			item, err := o.get(ctx, o.c.ddb, o.key("r-get"))
			return []map[string]types.AttributeValue{item}, err
		}},
		{"BatchGetItem", true, []string{"r-batch-0", "r-batch-1"}, func(ctx context.Context) ([]map[string]types.AttributeValue, error) {
			out, err := o.c.ddb.BatchGetItem(ctx, &dynamodb.BatchGetItemInput{RequestItems: map[string]types.KeysAndAttributes{
				o.s.table: {Keys: []map[string]types.AttributeValue{o.key("r-batch-0"), o.key("r-batch-1")}},
			}})
			if err != nil {
				return nil, err
			}
			if len(out.UnprocessedKeys) > 0 {
				return nil, errors.New("unprocessed keys left")
			}
			return out.Responses[o.s.table], nil
		}},
		{"Query", true, []string{"r-query"}, func(ctx context.Context) ([]map[string]types.AttributeValue, error) {
			out, err := o.c.ddb.Query(ctx, &dynamodb.QueryInput{
				TableName: table, KeyConditionExpression: aws.String("id = :id"),
				ExpressionAttributeValues: map[string]types.AttributeValue{":id": o.key("r-query")["id"]},
			})
			if err != nil {
				return nil, err
			}
			return out.Items, nil
		}},
		{"Scan", true, []string{"r-scan-0", "r-scan-1"}, func(ctx context.Context) ([]map[string]types.AttributeValue, error) {
			pages := dynamodb.NewScanPaginator(o.c.ddb, &dynamodb.ScanInput{
				TableName: table, FilterExpression: aws.String("begins_with(id, :p)"),
				ExpressionAttributeValues: map[string]types.AttributeValue{":p": str(o.run + "-r-scan-")},
			})
			var items []map[string]types.AttributeValue
			for pages.HasMorePages() {
				page, err := pages.NextPage(ctx)
				if err != nil {
					return nil, err
				}
				items = append(items, page.Items...)
			}
			return items, nil
		}},
		{"TransactGetItems", false, []string{"r-tx-0", "r-tx-1"}, func(ctx context.Context) ([]map[string]types.AttributeValue, error) {
			out, err := o.c.ddb.TransactGetItems(ctx, &dynamodb.TransactGetItemsInput{TransactItems: []types.TransactGetItem{
				{Get: &types.Get{TableName: table, Key: o.key("r-tx-0")}},
				{Get: &types.Get{TableName: table, Key: o.key("r-tx-1")}},
			}})
			if err != nil {
				return nil, err
			}
			items := make([]map[string]types.AttributeValue, 0, len(out.Responses))
			for _, r := range out.Responses {
				items = append(items, r.Item)
			}
			return items, nil
		}},
	}
	first := make([][]map[string]types.AttributeValue, len(all))
	for i, r := range all {
		got, err := r.call(ctx)
		if err != nil {
			return fmt.Errorf("calling %s: %w", r.op, err)
		}
		if err := o.sameAll(ctx, "2 "+r.op, got, r.names...); err != nil {
			return err
		}
		first[i] = got
	}
	if err := o.probe(ctx, names...); err != nil {
		return err
	}
	for i, r := range all {
		got, err := r.call(ctx)
		if err != nil {
			return fmt.Errorf("calling %s again: %w", r.op, err)
		}
		if !r.cached {
			if err := o.sameAll(ctx, "4 "+r.op+" is not served from the cache", got, r.names...); err != nil {
				return err
			}
			continue
		}
		o.check("4 "+r.op+" after a direct write is served from the cache", canonAll(got) == canonAll(first[i]),
			"cache %s, first answer %s", canonAll(got), canonAll(first[i]))
	}
	return nil
}

// sameAll checks that got holds exactly the named items, as DynamoDB has them.
func (o *ops) sameAll(ctx context.Context, name string, got []map[string]types.AttributeValue, names ...string) error {
	want := make([]map[string]types.AttributeValue, len(names))
	for i, n := range names {
		item, err := o.direct(ctx, o.key(n))
		if err != nil {
			return err
		}
		if item == nil {
			return fmt.Errorf("%s: %s is not in DynamoDB", name, n)
		}
		want[i] = item
	}
	o.check(name, canonAll(got) == canonAll(want), "%d items, cache %s, DynamoDB %s", len(got), canonAll(got), canonAll(want))
	return nil
}

// writes is the write half of check 2, and check 5: each write lands in DynamoDB, and a cache
// read of the item, cached before the write, matches DynamoDB after it.
func (o *ops) writes(ctx context.Context) error {
	names := []string{"w-update", "w-put", "w-delete", "w-batch-put", "w-batch-delete", "w-tx-update", "w-tx-put"}
	for i, name := range names {
		if err := o.put(ctx, o.item(name, i)); err != nil {
			return err
		}
	}
	if err := pause(ctx, settle); err != nil {
		return err
	}
	filled := map[string]string{}
	for _, name := range names {
		item, err := o.get(ctx, o.c.ddb, o.key(name))
		if err != nil {
			return fmt.Errorf("filling %s: %w", name, err)
		}
		filled[name] = canon(item)
	}
	if err := o.probe(ctx, names...); err != nil {
		return err
	}
	for _, name := range names {
		item, err := o.get(ctx, o.c.ddb, o.key(name))
		if err != nil {
			return fmt.Errorf("reading %s before the write: %w", name, err)
		}
		o.check("5 cached before the write: "+name, canon(item) == filled[name], "cache %s", canon(item))
	}
	table := aws.String(o.s.table)
	set := func(v int) (*string, map[string]types.AttributeValue) {
		return aws.String("SET v = :v"), map[string]types.AttributeValue{":v": num(v)}
	}

	expr, vals := set(100)
	if _, err := o.c.ddb.UpdateItem(ctx, &dynamodb.UpdateItemInput{
		TableName: table, Key: o.key("w-update"), UpdateExpression: expr, ExpressionAttributeValues: vals,
	}); err != nil {
		return fmt.Errorf("calling UpdateItem: %w", err)
	}
	if _, err := o.c.ddb.PutItem(ctx, &dynamodb.PutItemInput{TableName: table, Item: o.item("w-put", 101)}); err != nil {
		return fmt.Errorf("calling PutItem: %w", err)
	}
	if _, err := o.c.ddb.DeleteItem(ctx, &dynamodb.DeleteItemInput{TableName: table, Key: o.key("w-delete")}); err != nil {
		return fmt.Errorf("calling DeleteItem: %w", err)
	}
	batch, err := o.c.ddb.BatchWriteItem(ctx, &dynamodb.BatchWriteItemInput{RequestItems: map[string][]types.WriteRequest{
		o.s.table: {
			{PutRequest: &types.PutRequest{Item: o.item("w-batch-put", 102)}},
			{DeleteRequest: &types.DeleteRequest{Key: o.key("w-batch-delete")}},
		},
	}})
	if err != nil {
		return fmt.Errorf("calling BatchWriteItem: %w", err)
	}
	if len(batch.UnprocessedItems) > 0 {
		return errors.New("calling BatchWriteItem: unprocessed items left")
	}
	expr, vals = set(103)
	if _, err := o.c.ddb.TransactWriteItems(ctx, &dynamodb.TransactWriteItemsInput{TransactItems: []types.TransactWriteItem{
		{Update: &types.Update{TableName: table, Key: o.key("w-tx-update"), UpdateExpression: expr, ExpressionAttributeValues: vals}},
		{Put: &types.Put{TableName: table, Item: o.item("w-tx-put", 104)}},
	}}); err != nil {
		return fmt.Errorf("calling TransactWriteItems: %w", err)
	}

	want := map[string]string{
		"w-update": "100", "w-put": "101", "w-delete": "<none>", "w-batch-put": "102",
		"w-batch-delete": "<none>", "w-tx-update": "103", "w-tx-put": "104",
	}
	for _, name := range names {
		item, err := o.direct(ctx, o.key(name))
		if err != nil {
			return err
		}
		o.check("2 write lands in DynamoDB: "+name, value(item, "v") == want[name], "v=%s, want %s", value(item, "v"), want[name])
	}
	if err := pause(ctx, settle); err != nil {
		return err
	}
	for _, name := range names {
		got, err := o.get(ctx, o.c.ddb, o.key(name))
		if err != nil {
			return fmt.Errorf("reading %s after the write: %w", name, err)
		}
		if err := o.same(ctx, "5 cache read after the write: "+name, got, o.key(name)); err != nil {
			return err
		}
	}
	calls := o.c.origin.calls.Load()
	o.check("2 ten operations routed through the cache", calls == 0, "origin calls %d", calls)
	return nil
}

// passThrough is check 3: an operation the cache does not route goes to DynamoDB.
func (o *ops) passThrough(ctx context.Context) error {
	before := o.c.origin.calls.Load()
	through, err := o.c.ddb.DescribeTable(ctx, &dynamodb.DescribeTableInput{TableName: aws.String(o.s.table)})
	if err != nil {
		return fmt.Errorf("calling DescribeTable: %w", err)
	}
	plain, err := o.plain.DescribeTable(ctx, &dynamodb.DescribeTableInput{TableName: aws.String(o.s.table)})
	if err != nil {
		return err
	}
	calls := o.c.origin.calls.Load() - before
	o.check("3 DescribeTable goes to DynamoDB", calls == 1 && aws.ToString(through.Table.TableArn) == aws.ToString(plain.Table.TableArn),
		"origin calls %d, ARN %s", calls, aws.ToString(through.Table.TableArn))
	return nil
}
