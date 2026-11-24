// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package e2e

import (
	"bytes"
	"context"
	"encoding/json"
	"reflect"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"

	"github.com/awslabs/elasticache-integration-client/go/elasticacheintegrations"
	"github.com/awslabs/elasticache-integration-client/go/internal/mockcache"
)

// String and number attribute values, for the SDK and for the fake's JSON.
func sv(v string) *types.AttributeValueMemberS { return &types.AttributeValueMemberS{Value: v} }
func nv(v string) *types.AttributeValueMemberN { return &types.AttributeValueMemberN{Value: v} }
func sj(v string) json.RawMessage              { return mustJSON(map[string]string{"S": v}) }
func nj(v string) json.RawMessage              { return mustJSON(map[string]string{"N": v}) }

func songKey(artist, song string) map[string]types.AttributeValue {
	return map[string]types.AttributeValue{"Artist": sv(artist), "Song": sv(song)}
}

// seed puts the same items into every fake.
func seed(t *testing.T, ds ...*dynamo) {
	t.Helper()
	for _, d := range ds {
		for _, song := range []string{"One", "Two", "Three"} {
			d.put(t, "Music", item{"Artist": sj("Acme"), "Song": sj(song), "Year": nj("2001")})
		}
		d.put(t, "Music", item{"Artist": sj("Zed"), "Song": sj("Solo"), "Year": nj("1999")})
		d.put(t, "Other", item{"Id": sj("1"), "Note": sj("other")})
	}
}

// call is one SDK call; its output has ResultMetadata cleared so two clients compare equal.
type call struct {
	op  string
	run func(context.Context, *dynamodb.Client) (any, error)
}

func (c call) do(ctx context.Context, ddb *dynamodb.Client) (any, error) {
	out, err := c.run(ctx, ddb)
	if err == nil && out != nil {
		v := reflect.ValueOf(out).Elem()
		f := v.FieldByName("ResultMetadata")
		f.Set(reflect.Zero(f.Type()))
	}
	return out, err
}

var readCalls = []call{
	{"GetItem", func(ctx context.Context, ddb *dynamodb.Client) (any, error) {
		return ddb.GetItem(ctx, &dynamodb.GetItemInput{TableName: aws.String("Music"), Key: songKey("Acme", "One")})
	}},
	{"BatchGetItem", func(ctx context.Context, ddb *dynamodb.Client) (any, error) {
		return ddb.BatchGetItem(ctx, &dynamodb.BatchGetItemInput{RequestItems: map[string]types.KeysAndAttributes{
			"Music": {Keys: []map[string]types.AttributeValue{songKey("Acme", "One"), songKey("Zed", "Solo")}},
			"Other": {Keys: []map[string]types.AttributeValue{{"Id": sv("1")}}},
		}})
	}},
	{"Query", func(ctx context.Context, ddb *dynamodb.Client) (any, error) {
		return ddb.Query(ctx, &dynamodb.QueryInput{TableName: aws.String("Music"), KeyConditionExpression: aws.String("#a = :a"),
			ExpressionAttributeNames:  map[string]string{"#a": "Artist"},
			ExpressionAttributeValues: map[string]types.AttributeValue{":a": sv("Acme")}})
	}},
	{"Scan", func(ctx context.Context, ddb *dynamodb.Client) (any, error) {
		return ddb.Scan(ctx, &dynamodb.ScanInput{TableName: aws.String("Music")})
	}},
	{"TransactGetItems", func(ctx context.Context, ddb *dynamodb.Client) (any, error) {
		return ddb.TransactGetItems(ctx, &dynamodb.TransactGetItemsInput{TransactItems: []types.TransactGetItem{
			{Get: &types.Get{TableName: aws.String("Music"), Key: songKey("Acme", "Two")}},
			{Get: &types.Get{TableName: aws.String("Other"), Key: map[string]types.AttributeValue{"Id": sv("1")}}},
		}})
	}},
}

var writeCalls = []call{
	{"PutItem", func(ctx context.Context, ddb *dynamodb.Client) (any, error) {
		return ddb.PutItem(ctx, &dynamodb.PutItemInput{TableName: aws.String("Music"), ReturnValues: types.ReturnValueAllOld,
			Item: map[string]types.AttributeValue{"Artist": sv("Acme"), "Song": sv("One"), "Year": nv("2024")}})
	}},
	{"UpdateItem", func(ctx context.Context, ddb *dynamodb.Client) (any, error) {
		return ddb.UpdateItem(ctx, &dynamodb.UpdateItemInput{TableName: aws.String("Music"), Key: songKey("Acme", "Two"),
			UpdateExpression: aws.String("SET #y = :y"), ExpressionAttributeNames: map[string]string{"#y": "Year"},
			ExpressionAttributeValues: map[string]types.AttributeValue{":y": nv("2025")}, ReturnValues: types.ReturnValueAllNew})
	}},
	{"DeleteItem", func(ctx context.Context, ddb *dynamodb.Client) (any, error) {
		return ddb.DeleteItem(ctx, &dynamodb.DeleteItemInput{TableName: aws.String("Music"), Key: songKey("Zed", "Solo"),
			ReturnValues: types.ReturnValueAllOld})
	}},
	{"BatchWriteItem", func(ctx context.Context, ddb *dynamodb.Client) (any, error) {
		return ddb.BatchWriteItem(ctx, &dynamodb.BatchWriteItemInput{RequestItems: map[string][]types.WriteRequest{
			"Music": {
				{PutRequest: &types.PutRequest{Item: map[string]types.AttributeValue{"Artist": sv("Acme"), "Song": sv("Four"), "Year": nv("2026")}}},
				{DeleteRequest: &types.DeleteRequest{Key: songKey("Acme", "Three")}},
			},
		}})
	}},
	{"TransactWriteItems", func(ctx context.Context, ddb *dynamodb.Client) (any, error) {
		return ddb.TransactWriteItems(ctx, &dynamodb.TransactWriteItemsInput{TransactItems: []types.TransactWriteItem{
			{Put: &types.Put{TableName: aws.String("Other"), Item: map[string]types.AttributeValue{"Id": sv("2"), "Note": sv("new")},
				ConditionExpression: aws.String("attribute_not_exists(Id)")}},
			{Delete: &types.Delete{TableName: aws.String("Other"), Key: map[string]types.AttributeValue{"Id": sv("1")}}},
		}})
	}},
}

// sameRequest checks that an aws.request carries what the plain SDK client sent DynamoDB for
// the same call: the body, with object keys sorted and the SDK's random ClientRequestToken
// equalized, and the content-type and x-amz-target values; content-length is the body length.
func sameRequest(t *testing.T, op string, got mockcache.Command, want dynamoCall) {
	t.Helper()
	if g, w := comparable(t, got.Args[4]), comparable(t, want.body); g != w {
		t.Errorf("%s: the aws.request body differs from the SDK's:\n got  %s\n want %s", op, g, w)
	}
	headers := map[string]string{}
	for _, line := range strings.Split(got.Arg(3), "\n") {
		name, value, _ := strings.Cut(line, ":")
		headers[name] = value
	}
	for _, name := range []string{"content-type", "x-amz-target"} {
		if headers[name] != want.header.Get(name) {
			t.Errorf("%s: aws.request %s %q, the SDK sent %q", op, name, headers[name], want.header.Get(name))
		}
	}
	if headers["content-length"] != strconv.Itoa(len(got.Args[4])) {
		t.Errorf("%s: aws.request content-length %q for a %d-byte body", op, headers["content-length"], len(got.Args[4]))
	}
}

// comparable is a request body with its keys sorted and any ClientRequestToken replaced.
func comparable(t *testing.T, body []byte) string {
	t.Helper()
	d := json.NewDecoder(bytes.NewReader(body))
	d.UseNumber()
	var v map[string]any
	if err := d.Decode(&v); err != nil {
		t.Fatalf("a request body is not a JSON object: %v", err)
	}
	if _, ok := v["ClientRequestToken"]; ok {
		v["ClientRequestToken"] = "token"
	}
	return string(mustJSON(v))
}

func TestTenOperationsMatchPlainClient(t *testing.T) {
	t.Parallel()
	h := newHarness(t)
	twin := newDynamo(t)
	seed(t, h.dynamo, twin)
	plain := plainClient(twin)
	ctx := context.Background()
	var script []call
	script = append(script, readCalls...)
	script = append(script, readCalls...) // hits
	script = append(script, writeCalls...)
	script = append(script, readCalls...) // misses after the writes dropped what was kept
	for i, c := range script {
		got, err := c.do(ctx, h.ddb)
		if h.seen(err) != nil {
			t.Fatalf("%d %s through the cache: %v", i, c.op, err)
		}
		want, err := c.do(ctx, plain)
		if err != nil {
			t.Fatalf("%d %s on the plain client: %v", i, c.op, err)
		}
		if !reflect.DeepEqual(got, want) {
			t.Errorf("%d %s:\n through the cache %#v\n plain client      %#v", i, c.op, got, want)
		}
	}
	reqs, sent := h.cache.Requests(), twin.clientCalls()
	if len(reqs) != len(script) || len(sent) != len(script) {
		t.Fatalf("aws.requests %d, plain client calls %d; want %d each", len(reqs), len(sent), len(script))
	}
	for i, c := range script {
		sameRequest(t, c.op, reqs[i], sent[i])
	}
	if got := h.server.hits.Load(); got != int64(len(readCalls)) {
		t.Errorf("cache hits = %d, want %d", got, len(readCalls))
	}
	if n := h.dynamo.fromClient(); n != 0 {
		t.Errorf("the client sent %d calls to DynamoDB, want 0", n)
	}
}

func TestReadsMissThenHit(t *testing.T) {
	t.Parallel()
	for _, c := range readCalls {
		t.Run(c.op, func(t *testing.T) {
			t.Parallel()
			h := newHarness(t)
			seed(t, h.dynamo)
			ctx := context.Background()
			first, err := c.do(ctx, h.ddb)
			if h.seen(err) != nil {
				t.Fatal(err)
			}
			if n := h.dynamo.fromCache(); n != 1 {
				t.Fatalf("after the miss DynamoDB got %d calls from the cache, want 1", n)
			}
			second, err := c.do(ctx, h.ddb)
			if h.seen(err) != nil {
				t.Fatal(err)
			}
			if n := h.dynamo.fromCache(); n != 1 {
				t.Errorf("after the hit DynamoDB got %d calls from the cache, want 1", n)
			}
			if h.server.hits.Load() != 1 || h.server.misses.Load() != 1 {
				t.Errorf("hits %d, misses %d; want 1 each", h.server.hits.Load(), h.server.misses.Load())
			}
			if !reflect.DeepEqual(first, second) {
				t.Errorf("the hit differs from the miss:\n%#v\n%#v", first, second)
			}
			if n := h.dynamo.fromClient(); n != 0 {
				t.Errorf("the client sent %d calls to DynamoDB", n)
			}
		})
	}
}

func TestWritesInvalidateCachedReads(t *testing.T) {
	t.Parallel()
	// Each write changes what the Scan of its table returns.
	scans := map[string]string{"PutItem": "Music", "UpdateItem": "Music", "DeleteItem": "Music",
		"BatchWriteItem": "Music", "TransactWriteItems": "Other"}
	for _, w := range writeCalls {
		t.Run(w.op, func(t *testing.T) {
			t.Parallel()
			h := newHarness(t)
			twin := newDynamo(t)
			seed(t, h.dynamo, twin)
			ctx := context.Background()
			scan := call{"Scan", func(ctx context.Context, ddb *dynamodb.Client) (any, error) {
				return ddb.Scan(ctx, &dynamodb.ScanInput{TableName: aws.String(scans[w.op])})
			}}
			before, err := scan.do(ctx, h.ddb)
			if h.seen(err) != nil {
				t.Fatal(err)
			}
			if _, err := w.do(ctx, h.ddb); h.seen(err) != nil {
				t.Fatalf("%s: %v", w.op, err)
			}
			after, err := scan.do(ctx, h.ddb)
			if h.seen(err) != nil {
				t.Fatal(err)
			}
			if reflect.DeepEqual(before, after) {
				t.Fatalf("the Scan after %s still returns the cached result", w.op)
			}
			if _, err := w.do(ctx, plainClient(twin)); err != nil {
				t.Fatal(err)
			}
			want, err := scan.do(ctx, plainClient(twin))
			if err != nil {
				t.Fatal(err)
			}
			if !reflect.DeepEqual(after, want) {
				t.Errorf("after %s:\n through the cache %#v\n plain client      %#v", w.op, after, want)
			}
			writes := 0
			for _, c := range h.dynamo.cacheCalls() {
				if c.op == w.op {
					writes++
				}
			}
			if writes != 1 || h.dynamo.fromClient() != 0 {
				t.Errorf("DynamoDB got %s %d times from the cache and %d calls from the client; want 1 and 0",
					w.op, writes, h.dynamo.fromClient())
			}
		})
	}
}

func TestHitIsServedAfterDynamoChanges(t *testing.T) {
	t.Parallel()
	h := newHarness(t)
	seed(t, h.dynamo)
	ctx := context.Background()
	get := func() *dynamodb.GetItemOutput {
		t.Helper()
		out, err := h.ddb.GetItem(ctx, &dynamodb.GetItemInput{TableName: aws.String("Music"), Key: songKey("Acme", "One")})
		if h.seen(err) != nil {
			t.Fatal(err)
		}
		return out
	}
	if get().Item == nil {
		t.Fatal("the item is missing before the delete")
	}
	h.dynamo.remove(t, "Music", item{"Artist": sj("Acme"), "Song": sj("One")})
	if get().Item == nil {
		t.Fatal("the cached item was not served after DynamoDB lost it")
	}
	// A write through the client drops what the cache kept.
	if _, err := h.ddb.PutItem(ctx, &dynamodb.PutItemInput{TableName: aws.String("Other"),
		Item: map[string]types.AttributeValue{"Id": sv("9")}}); h.seen(err) != nil {
		t.Fatal(err)
	}
	if out := get(); out.Item != nil {
		t.Fatalf("after the write the read returned %v, want no item", out.Item)
	}
}

func TestDynamoErrorsReachSDKAsModeledErrors(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	t.Run("missing_table", func(t *testing.T) {
		t.Parallel()
		h := newHarness(t)
		_, err := h.ddb.GetItem(ctx, &dynamodb.GetItemInput{TableName: aws.String("Missing"), Key: songKey("a", "b")})
		errorAs[*types.ResourceNotFoundException](t, h.seen(err))
		if h.requests() != 1 || h.dynamo.fromClient() != 0 {
			t.Errorf("aws.requests %d, client calls %d; want 1 and 0", h.requests(), h.dynamo.fromClient())
		}
	})
	t.Run("condition", func(t *testing.T) {
		t.Parallel()
		h := newHarness(t)
		seed(t, h.dynamo)
		_, err := h.ddb.PutItem(ctx, &dynamodb.PutItemInput{TableName: aws.String("Music"),
			Item:                                map[string]types.AttributeValue{"Artist": sv("Acme"), "Song": sv("One")},
			ConditionExpression:                 aws.String("attribute_not_exists(Artist)"),
			ReturnValuesOnConditionCheckFailure: types.ReturnValuesOnConditionCheckFailureAllOld})
		ccf := errorAs[*types.ConditionalCheckFailedException](t, h.seen(err))
		if y, ok := ccf.Item["Year"].(*types.AttributeValueMemberN); !ok || y.Value != "2001" {
			t.Errorf("the error carries item %v, want the stored one", ccf.Item)
		}
		if h.requests() != 1 || h.dynamo.fromClient() != 0 {
			t.Errorf("aws.requests %d, client calls %d; want 1 and 0", h.requests(), h.dynamo.fromClient())
		}
	})
	t.Run("transaction", func(t *testing.T) {
		t.Parallel()
		h := newHarness(t)
		seed(t, h.dynamo)
		_, err := h.ddb.TransactWriteItems(ctx, &dynamodb.TransactWriteItemsInput{TransactItems: []types.TransactWriteItem{
			{Put: &types.Put{TableName: aws.String("Other"), Item: map[string]types.AttributeValue{"Id": sv("5")}}},
			{Put: &types.Put{TableName: aws.String("Other"), Item: map[string]types.AttributeValue{"Id": sv("1")},
				ConditionExpression: aws.String("attribute_not_exists(Id)")}},
		}})
		tce := errorAs[*types.TransactionCanceledException](t, h.seen(err))
		if len(tce.CancellationReasons) != 2 || aws.ToString(tce.CancellationReasons[0].Code) != "None" ||
			aws.ToString(tce.CancellationReasons[1].Code) != "ConditionalCheckFailed" {
			t.Errorf("cancellation reasons %#v", tce.CancellationReasons)
		}
		if h.requests() != 1 || h.dynamo.fromClient() != 0 {
			t.Errorf("aws.requests %d, client calls %d; want 1 and 0", h.requests(), h.dynamo.fromClient())
		}
	})
}

func TestUnadvertisedOperationGoesToDynamo(t *testing.T) {
	t.Parallel()
	h := newHarness(t)
	seed(t, h.dynamo)
	ctx := context.Background()
	desc, err := h.ddb.DescribeTable(ctx, &dynamodb.DescribeTableInput{TableName: aws.String("Music")})
	if h.seen(err) != nil || aws.ToInt64(desc.Table.ItemCount) != 4 {
		t.Fatalf("DescribeTable = %v, %v", desc, err)
	}
	list, err := h.ddb.ListTables(ctx, &dynamodb.ListTablesInput{})
	if h.seen(err) != nil || !reflect.DeepEqual(list.TableNames, []string{"Music", "Other"}) {
		t.Fatalf("ListTables = %v, %v", list, err)
	}
	if h.requests() != 0 {
		t.Errorf("the cache got %d aws.requests, want 0", h.requests())
	}
	calls := h.dynamo.clientCalls()
	if len(calls) != 2 {
		t.Fatalf("the client sent %d calls to DynamoDB, want 2", len(calls))
	}
	for _, c := range calls {
		if !strings.HasPrefix(c.header.Get("Authorization"), "AWS4-HMAC-SHA256 Credential=") {
			t.Errorf("%s reached DynamoDB unsigned", c.op)
		}
	}
}

func TestRequestOnTheWire(t *testing.T) {
	t.Parallel()
	h := newHarness(t)
	seed(t, h.dynamo)
	ctx := context.Background()
	for range 2 {
		if _, err := readCalls[0].do(ctx, h.ddb); h.seen(err) != nil {
			t.Fatal(err)
		}
	}
	reqs := h.cache.Requests()
	if len(reqs) != 2 {
		t.Fatalf("aws.requests = %d, want 2", len(reqs))
	}
	got := reqs[0]
	if got.Arg(1) != "POST" || got.Arg(2) != h.dynamo.url()+"/" || got.Arg(5) != "service=dynamodb&op=GetItem" {
		t.Errorf("method %q, url %q, metadata %q", got.Arg(1), got.Arg(2), got.Arg(5))
	}
	names := map[string]bool{}
	for _, line := range strings.Split(got.Arg(3), "\n") {
		name, _, _ := strings.Cut(line, ":")
		names[name] = true
	}
	for _, excluded := range []string{"authorization", "x-amz-date", "x-amz-security-token", "x-amz-request-id", "amz-sdk-request"} {
		if names[excluded] {
			t.Errorf("the aws.request carries %s", excluded)
		}
	}
	for _, want := range []string{"content-type", "content-length", "x-amz-target"} {
		if !names[want] {
			t.Errorf("the aws.request has no %s header: %q", want, got.Arg(3))
		}
	}
	twin := newDynamo(t)
	seed(t, twin)
	if _, err := readCalls[0].do(ctx, plainClient(twin)); err != nil {
		t.Fatal(err)
	}
	sameRequest(t, "GetItem", got, twin.clientCalls()[0])
	if reqs[1].Conn != reqs[0].Conn {
		t.Errorf("the two calls came on connections %d and %d", reqs[0].Conn, reqs[1].Conn)
	}
	configs := 0
	for _, cmd := range h.cache.Commands() {
		if cmd.Name() == "AWS.CONFIG" {
			configs++
		}
	}
	if h.cache.Connections() != 1 || configs != 1 {
		t.Errorf("connections %d, aws.config commands %d; want 1 each", h.cache.Connections(), configs)
	}
}

// TestIdleConnectionServes idles a connection for 3x CacheRequestTimeout, then serves a hit
// on it. The timeout is long enough that a loaded host does not time out the calls, and the
// test compares the connection of the last request before the idle time with the first after
// it, so a reconnect during a call is not counted as one during the idle time.
func TestIdleConnectionServes(t *testing.T) {
	t.Parallel()
	h := newHarness(t, withOptions(func(o *elasticacheintegrations.Options) { o.CacheRequestTimeout = 500 * time.Millisecond }))
	seed(t, h.dynamo)
	ctx := context.Background()
	if _, err := readCalls[0].do(ctx, h.ddb); h.seen(err) != nil {
		t.Fatal(err)
	}
	before := h.cache.Requests()
	hits := h.server.hits.Load()
	time.Sleep(1500 * time.Millisecond)
	if _, err := readCalls[0].do(ctx, h.ddb); h.seen(err) != nil {
		t.Fatal(err)
	}
	after := h.cache.Requests()
	if len(after) <= len(before) {
		t.Fatal("the call after the idle time did not reach the cache")
	}
	if last, next := before[len(before)-1].Conn, after[len(before)].Conn; last != next {
		t.Errorf("the request before the idle time came on connection %d, the one after on %d; client log:\n%s",
			last, next, h.logs.Text())

	}
	if h.server.hits.Load() == hits || h.dynamo.fromClient() != 0 {
		t.Errorf("hits %d before and %d after, client calls %d; want a hit and no client call",
			hits, h.server.hits.Load(), h.dynamo.fromClient())
	}
}

func TestLargeItemsCrossUnchanged(t *testing.T) {
	t.Parallel()
	h := newHarness(t)
	ctx := context.Background()
	payload := make([]byte, 395<<10)
	for i := range payload {
		payload[i] = byte(i * 7)
	}
	title := "Ünïcödé 日本語 🎵 \u2028 <&>"
	big := map[string]types.AttributeValue{"Artist": sv("Big"), "Song": sv("Blob"), "Title": sv(title),
		"Payload": &types.AttributeValueMemberB{Value: payload}}
	if _, err := h.ddb.PutItem(ctx, &dynamodb.PutItemInput{TableName: aws.String("Music"), Item: big}); h.seen(err) != nil {
		t.Fatal(err)
	}
	for _, pass := range []string{"miss", "hit"} {
		out, err := h.ddb.GetItem(ctx, &dynamodb.GetItemInput{TableName: aws.String("Music"), Key: songKey("Big", "Blob")})
		if h.seen(err) != nil {
			t.Fatalf("%s: %v", pass, err)
		}
		if !reflect.DeepEqual(out.Item, big) {
			t.Fatalf("%s: the item changed on the way", pass)
		}
	}
	if h.server.hits.Load() != 1 {
		t.Errorf("hits = %d, want 1", h.server.hits.Load())
	}
	// A Query reply of five 350 KiB items, 2.3 MB of JSON.
	parts := make([][]byte, 5)
	for i := range parts {
		parts[i] = make([]byte, 350<<10)
		for j := range parts[i] {
			parts[i][j] = byte(i + j)
		}
		h.dynamo.put(t, "Music", item{"Artist": sj("Many"), "Song": sj(string(rune('a' + i))),
			"Payload": mustJSON(map[string][]byte{"B": parts[i]})})
	}
	for _, pass := range []string{"miss", "hit"} {
		out, err := h.ddb.Query(ctx, &dynamodb.QueryInput{TableName: aws.String("Music"), KeyConditionExpression: aws.String("Artist = :a"),
			ExpressionAttributeValues: map[string]types.AttributeValue{":a": sv("Many")}})
		if h.seen(err) != nil {
			t.Fatalf("%s: %v", pass, err)
		}
		if len(out.Items) != 5 {
			t.Fatalf("%s: %d items, want 5", pass, len(out.Items))
		}
		for i, it := range out.Items {
			if !bytes.Equal(it["Payload"].(*types.AttributeValueMemberB).Value, parts[i]) {
				t.Fatalf("%s: item %d payload changed on the way", pass, i)
			}
		}
	}
	if h.server.hits.Load() != 2 {
		t.Errorf("hits = %d, want 2", h.server.hits.Load())
	}
}
