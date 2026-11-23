// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package e2e

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"hash/crc32"
	"io"
	"net/http"
	"net/http/httptest"
	"regexp"
	"slices"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
	"github.com/aws/smithy-go"
)

// callerHeader marks the calls the mock proxy makes, so the fake tells them from the client's.
const callerHeader = "X-E2e-Caller"

// dynamo is a fake DynamoDB: the JSON protocol for the ten advertised operations plus
// DescribeTable and ListTables, over in-memory tables. It supports what the tests send and
// answers anything else with a ValidationException.
type dynamo struct {
	srv *httptest.Server

	mu     sync.Mutex
	tables map[string]*table
	calls  []dynamoCall
	ids    atomic.Uint64
}

type dynamoCall struct {
	op        string
	fromCache bool
	header    http.Header
	body      []byte
}

type item = map[string]json.RawMessage

type table struct {
	hash, rng string
	items     map[string]item
}

// newDynamo starts a fake with the tables Music (Artist, Song) and Other (Id).
func newDynamo(t *testing.T) *dynamo {
	t.Helper()
	d := &dynamo{tables: map[string]*table{
		"Music": {hash: "Artist", rng: "Song", items: map[string]item{}},
		"Other": {hash: "Id", items: map[string]item{}},
	}}
	d.srv = httptest.NewServer(http.HandlerFunc(d.serve))
	t.Cleanup(d.srv.Close)
	return d
}

func (d *dynamo) url() string { return d.srv.URL }

// put stores it directly, as another writer would.
func (d *dynamo) put(t *testing.T, tableName string, it item) {
	t.Helper()
	d.mu.Lock()
	defer d.mu.Unlock()
	tb := d.tables[tableName]
	k, err := tb.key(it)
	if err != nil {
		t.Fatal(err)
	}
	tb.items[k] = it
}

// remove deletes the item with key directly.
func (d *dynamo) remove(t *testing.T, tableName string, key item) {
	t.Helper()
	d.mu.Lock()
	defer d.mu.Unlock()
	tb := d.tables[tableName]
	k, err := tb.key(key)
	if err != nil {
		t.Fatal(err)
	}
	delete(tb.items, k)
}

// fromClient counts the calls the Go client sent to DynamoDB itself.
func (d *dynamo) fromClient() int { return d.count(false) }

// fromCache counts the calls the mock proxy made.
func (d *dynamo) fromCache() int { return d.count(true) }

func (d *dynamo) count(fromCache bool) int {
	d.mu.Lock()
	defer d.mu.Unlock()
	n := 0
	for _, c := range d.calls {
		if c.fromCache == fromCache {
			n++
		}
	}
	return n
}

// clientCalls returns the calls the Go client sent to DynamoDB itself.
func (d *dynamo) clientCalls() []dynamoCall {
	d.mu.Lock()
	defer d.mu.Unlock()
	var out []dynamoCall
	for _, c := range d.calls {
		if !c.fromCache {
			out = append(out, c)
		}
	}
	return out
}

// cacheCalls returns the calls the mock proxy made.
func (d *dynamo) cacheCalls() []dynamoCall {
	d.mu.Lock()
	defer d.mu.Unlock()
	var out []dynamoCall
	for _, c := range d.calls {
		if c.fromCache {
			out = append(out, c)
		}
	}
	return out
}

func (d *dynamo) serve(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	target := r.Header.Get("X-Amz-Target")
	op := target[strings.LastIndexByte(target, '.')+1:]
	d.mu.Lock()
	d.calls = append(d.calls, dynamoCall{op: op, fromCache: r.Header.Get(callerHeader) == "cache", header: r.Header.Clone(), body: body})
	status, out := d.handle(op, body)
	d.mu.Unlock()
	w.Header().Set("Content-Type", "application/x-amz-json-1.0")
	w.Header().Set("X-Amz-Crc32", strconv.FormatUint(uint64(crc32.ChecksumIEEE(out)), 10))
	w.Header().Set("X-Amzn-Requestid", "E2E"+strconv.FormatUint(d.ids.Add(1), 10))
	w.WriteHeader(status)
	_, _ = w.Write(out)
}

// dynamoError is a DynamoDB error reply.
type dynamoError struct {
	status  int
	code    string
	message string
	extra   map[string]any
}

func (e *dynamoError) Error() string { return e.code + ": " + e.message }

func validation(format string, args ...any) *dynamoError {
	return &dynamoError{status: 400, code: "ValidationException", message: fmt.Sprintf(format, args...)}
}

// supported are the request members the fake implements, per operation. Any other member is a
// ValidationException, so a test cannot pass with a parameter both clients would ignore.
var supported = map[string][]string{
	"GetItem":            {"TableName", "Key"},
	"PutItem":            {"TableName", "Item", "ConditionExpression", "ExpressionAttributeNames", "ExpressionAttributeValues", "ReturnValues", "ReturnValuesOnConditionCheckFailure"},
	"UpdateItem":         {"TableName", "Key", "UpdateExpression", "ConditionExpression", "ExpressionAttributeNames", "ExpressionAttributeValues", "ReturnValues", "ReturnValuesOnConditionCheckFailure"},
	"DeleteItem":         {"TableName", "Key", "ConditionExpression", "ExpressionAttributeNames", "ExpressionAttributeValues", "ReturnValues", "ReturnValuesOnConditionCheckFailure"},
	"BatchGetItem":       {"RequestItems"},
	"BatchWriteItem":     {"RequestItems"},
	"Query":              {"TableName", "KeyConditionExpression", "ExpressionAttributeNames", "ExpressionAttributeValues"},
	"Scan":               {"TableName"},
	"TransactGetItems":   {"TransactItems"},
	"TransactWriteItems": {"TransactItems", "ClientRequestToken"},
	"DescribeTable":      {"TableName"},
	"ListTables":         {},
	// Members of the actions inside TransactGetItems and TransactWriteItems.
	"Get":            {"TableName", "Key"},
	"Put":            {"TableName", "Item", "ConditionExpression", "ExpressionAttributeNames", "ExpressionAttributeValues"},
	"Update":         {"TableName", "Key", "UpdateExpression", "ConditionExpression", "ExpressionAttributeNames", "ExpressionAttributeValues"},
	"Delete":         {"TableName", "Key", "ConditionExpression", "ExpressionAttributeNames", "ExpressionAttributeValues"},
	"ConditionCheck": {"TableName", "Key", "ConditionExpression", "ExpressionAttributeNames", "ExpressionAttributeValues"},
}

// onlySupported fails when in has a member that what does not implement.
func onlySupported(what string, in map[string]json.RawMessage) error {
	for name := range in {
		if !slices.Contains(supported[what], name) {
			return validation("the fake does not support %s in %s", name, what)
		}
	}
	return nil
}

// handle runs op on body under d.mu and returns the status and the JSON reply.
func (d *dynamo) handle(op string, body []byte) (int, []byte) {
	var in map[string]json.RawMessage
	if err := json.Unmarshal(body, &in); err != nil {
		return d.fail(validation("the body is not a JSON object"))
	}
	if _, known := supported[op]; known {
		if err := onlySupported(op, in); err != nil {
			return d.fail(err)
		}
	}
	var out any
	var err error
	switch op {
	case "GetItem":
		out, err = d.getItem(in)
	case "PutItem":
		out, err = d.putItem(in)
	case "UpdateItem":
		out, err = d.updateItem(in)
	case "DeleteItem":
		out, err = d.deleteItem(in)
	case "BatchGetItem":
		out, err = d.batchGetItem(in)
	case "BatchWriteItem":
		out, err = d.batchWriteItem(in)
	case "Query":
		out, err = d.query(in)
	case "Scan":
		out, err = d.scan(in)
	case "TransactGetItems":
		out, err = d.transactGetItems(in)
	case "TransactWriteItems":
		out, err = d.transactWriteItems(in)
	case "DescribeTable":
		out, err = d.describeTable(in)
	case "ListTables":
		out, err = d.listTables()
	default:
		err = &dynamoError{status: 400, code: "UnknownOperationException", message: "unknown operation " + op}
	}
	if err != nil {
		return d.fail(err)
	}
	b, merr := json.Marshal(out)
	if merr != nil {
		return d.fail(validation("cannot encode the reply: %v", merr))
	}
	return 200, b
}

func (d *dynamo) fail(err error) (int, []byte) {
	e, ok := err.(*dynamoError)
	if !ok {
		e = &dynamoError{status: 500, code: "InternalServerError", message: err.Error()}
	}
	out := map[string]any{"__type": "com.amazonaws.dynamodb.v20120810#" + e.code, "message": e.message}
	for k, v := range e.extra {
		out[k] = v
	}
	b, _ := json.Marshal(out)
	return e.status, b
}

// field decodes in[name] into v; a missing field leaves v unchanged.
func field(in map[string]json.RawMessage, name string, v any) error {
	raw, ok := in[name]
	if !ok {
		return nil
	}
	if err := json.Unmarshal(raw, v); err != nil {
		return validation("bad %s: %v", name, err)
	}
	return nil
}

func (d *dynamo) table(in map[string]json.RawMessage) (*table, error) {
	var name string
	if err := field(in, "TableName", &name); err != nil {
		return nil, err
	}
	tb, ok := d.tables[name]
	if !ok {
		return nil, &dynamoError{status: 400, code: "ResourceNotFoundException", message: "Requested resource not found"}
	}
	return tb, nil
}

// key is the canonical key of it: its key attributes, compacted.
func (tb *table) key(it item) (string, error) {
	var b strings.Builder
	for _, name := range []string{tb.hash, tb.rng} {
		if name == "" {
			continue
		}
		v, ok := it[name]
		if !ok {
			return "", validation("the key attribute %s is missing", name)
		}
		var c bytes.Buffer
		if err := json.Compact(&c, v); err != nil {
			return "", validation("bad key attribute %s", name)
		}
		b.WriteString(name + "=" + c.String() + "|")
	}
	return b.String(), nil
}

// sorted returns tb's items in key order.
func (tb *table) sorted() []item {
	keys := make([]string, 0, len(tb.items))
	for k := range tb.items {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	out := make([]item, 0, len(keys))
	for _, k := range keys {
		out = append(out, tb.items[k])
	}
	return out
}

// expression is the parsed form of the expressions a request carries.
type expression struct {
	names  map[string]string
	values map[string]json.RawMessage
}

func expressions(in map[string]json.RawMessage) (expression, error) {
	var e expression
	if err := field(in, "ExpressionAttributeNames", &e.names); err != nil {
		return e, err
	}
	err := field(in, "ExpressionAttributeValues", &e.values)
	return e, err
}

func (e expression) name(s string) string {
	if strings.HasPrefix(s, "#") {
		return e.names[s]
	}
	return s
}

func (e expression) value(s string) (json.RawMessage, error) {
	v, ok := e.values[s]
	if !ok {
		return nil, validation("the value %s is not defined", s)
	}
	return v, nil
}

var conditionRE = regexp.MustCompile(`^\s*(attribute_not_exists|attribute_exists)\(\s*(#?\w+)\s*\)\s*$`)

// condition checks the request's ConditionExpression against current (nil when absent).
func condition(in map[string]json.RawMessage, current item) (bool, error) {
	var cond string
	if err := field(in, "ConditionExpression", &cond); err != nil || cond == "" {
		return true, err
	}
	m := conditionRE.FindStringSubmatch(cond)
	if m == nil {
		return false, validation("unsupported condition %q", cond)
	}
	e, err := expressions(in)
	if err != nil {
		return false, err
	}
	_, exists := current[e.name(m[2])]
	return exists == (m[1] == "attribute_exists"), nil
}

func conditionFailed(in map[string]json.RawMessage, current item) error {
	e := &dynamoError{status: 400, code: "ConditionalCheckFailedException", message: "The conditional request failed"}
	var rv string
	_ = field(in, "ReturnValuesOnConditionCheckFailure", &rv)
	if rv == "ALL_OLD" && current != nil {
		e.extra = map[string]any{"Item": current}
	}
	return e
}

// returned is the Attributes of a write for ReturnValues.
func returned(in map[string]json.RawMessage, old, updated item) (map[string]any, error) {
	var rv string
	if err := field(in, "ReturnValues", &rv); err != nil {
		return nil, err
	}
	switch rv {
	case "", "NONE":
		return map[string]any{}, nil
	case "ALL_OLD":
		if old == nil {
			return map[string]any{}, nil
		}
		return map[string]any{"Attributes": old}, nil
	case "ALL_NEW":
		return map[string]any{"Attributes": updated}, nil
	default:
		return nil, validation("unsupported ReturnValues %s", rv)
	}
}

func (d *dynamo) getItem(in map[string]json.RawMessage) (any, error) {
	tb, err := d.table(in)
	if err != nil {
		return nil, err
	}
	var key item
	if err := field(in, "Key", &key); err != nil {
		return nil, err
	}
	k, err := tb.key(key)
	if err != nil {
		return nil, err
	}
	if it, ok := tb.items[k]; ok {
		return map[string]any{"Item": it}, nil
	}
	return map[string]any{}, nil
}

func (d *dynamo) putItem(in map[string]json.RawMessage) (any, error) {
	tb, err := d.table(in)
	if err != nil {
		return nil, err
	}
	var it item
	if err := field(in, "Item", &it); err != nil {
		return nil, err
	}
	k, err := tb.key(it)
	if err != nil {
		return nil, err
	}
	old := tb.items[k]
	if ok, err := condition(in, old); err != nil {
		return nil, err
	} else if !ok {
		return nil, conditionFailed(in, old)
	}
	tb.items[k] = it
	return returned(in, old, it)
}

var setRE = regexp.MustCompile(`^\s*(#?\w+)\s*=\s*(:\w+)\s*$`)

// update applies a "SET a = :v, #b = :w" expression to a copy of current.
func update(in map[string]json.RawMessage, key, current item) (item, error) {
	var expr string
	if err := field(in, "UpdateExpression", &expr); err != nil {
		return nil, err
	}
	rest, ok := strings.CutPrefix(strings.TrimSpace(expr), "SET ")
	if !ok {
		return nil, validation("unsupported update %q", expr)
	}
	e, err := expressions(in)
	if err != nil {
		return nil, err
	}
	next := item{}
	for k, v := range current {
		next[k] = v
	}
	for k, v := range key {
		next[k] = v
	}
	for _, part := range strings.Split(rest, ",") {
		m := setRE.FindStringSubmatch(part)
		if m == nil {
			return nil, validation("unsupported update %q", expr)
		}
		v, err := e.value(m[2])
		if err != nil {
			return nil, err
		}
		next[e.name(m[1])] = v
	}
	return next, nil
}

func (d *dynamo) updateItem(in map[string]json.RawMessage) (any, error) {
	tb, err := d.table(in)
	if err != nil {
		return nil, err
	}
	var key item
	if err := field(in, "Key", &key); err != nil {
		return nil, err
	}
	k, err := tb.key(key)
	if err != nil {
		return nil, err
	}
	old := tb.items[k]
	if ok, err := condition(in, old); err != nil {
		return nil, err
	} else if !ok {
		return nil, conditionFailed(in, old)
	}
	next, err := update(in, key, old)
	if err != nil {
		return nil, err
	}
	tb.items[k] = next
	return returned(in, old, next)
}

func (d *dynamo) deleteItem(in map[string]json.RawMessage) (any, error) {
	tb, err := d.table(in)
	if err != nil {
		return nil, err
	}
	var key item
	if err := field(in, "Key", &key); err != nil {
		return nil, err
	}
	k, err := tb.key(key)
	if err != nil {
		return nil, err
	}
	old := tb.items[k]
	if ok, err := condition(in, old); err != nil {
		return nil, err
	} else if !ok {
		return nil, conditionFailed(in, old)
	}
	delete(tb.items, k)
	return returned(in, old, nil)
}

func (d *dynamo) batchGetItem(in map[string]json.RawMessage) (any, error) {
	var req map[string]map[string]json.RawMessage
	if err := field(in, "RequestItems", &req); err != nil {
		return nil, err
	}
	responses := map[string][]item{}
	for name, ka := range req {
		var keys []item
		if err := onlyKeys(ka); err != nil {
			return nil, err
		}
		if err := field(ka, "Keys", &keys); err != nil {
			return nil, err
		}
		tb, err := d.table(map[string]json.RawMessage{"TableName": mustJSON(name)})
		if err != nil {
			return nil, err
		}
		responses[name] = []item{}
		for _, key := range keys {
			k, err := tb.key(key)
			if err != nil {
				return nil, err
			}
			if it, ok := tb.items[k]; ok {
				responses[name] = append(responses[name], it)
			}
		}
	}
	return map[string]any{"Responses": responses, "UnprocessedKeys": map[string]any{}}, nil
}

// onlyKeys fails when a BatchGetItem table entry has a member other than Keys.
func onlyKeys(ka map[string]json.RawMessage) error {
	for name := range ka {
		if name != "Keys" {
			return validation("the fake does not support %s in BatchGetItem", name)
		}
	}
	return nil
}

func (d *dynamo) batchWriteItem(in map[string]json.RawMessage) (any, error) {
	var req map[string][]struct {
		PutRequest    *struct{ Item item }
		DeleteRequest *struct{ Key item }
	}
	if err := field(in, "RequestItems", &req); err != nil {
		return nil, err
	}
	for name, writes := range req {
		tb, err := d.table(map[string]json.RawMessage{"TableName": mustJSON(name)})
		if err != nil {
			return nil, err
		}
		for _, w := range writes {
			switch {
			case w.PutRequest != nil:
				k, err := tb.key(w.PutRequest.Item)
				if err != nil {
					return nil, err
				}
				tb.items[k] = w.PutRequest.Item
			case w.DeleteRequest != nil:
				k, err := tb.key(w.DeleteRequest.Key)
				if err != nil {
					return nil, err
				}
				delete(tb.items, k)
			default:
				return nil, validation("a write request has no PutRequest or DeleteRequest")
			}
		}
	}
	return map[string]any{"UnprocessedItems": map[string]any{}}, nil
}

var keyConditionRE = regexp.MustCompile(`^\s*(#?\w+)\s*=\s*(:\w+)\s*$`)

func (d *dynamo) query(in map[string]json.RawMessage) (any, error) {
	tb, err := d.table(in)
	if err != nil {
		return nil, err
	}
	var kc string
	if err := field(in, "KeyConditionExpression", &kc); err != nil {
		return nil, err
	}
	m := keyConditionRE.FindStringSubmatch(kc)
	if m == nil {
		return nil, validation("unsupported key condition %q", kc)
	}
	e, err := expressions(in)
	if err != nil {
		return nil, err
	}
	if e.name(m[1]) != tb.hash {
		return nil, validation("the key condition must name the hash key %s", tb.hash)
	}
	want, err := e.value(m[2])
	if err != nil {
		return nil, err
	}
	items := []item{}
	for _, it := range tb.sorted() {
		if jsonEqual(it[tb.hash], want) {
			items = append(items, it)
		}
	}
	return map[string]any{"Count": len(items), "Items": items, "ScannedCount": len(items)}, nil
}

func (d *dynamo) scan(in map[string]json.RawMessage) (any, error) {
	tb, err := d.table(in)
	if err != nil {
		return nil, err
	}
	items := tb.sorted()
	return map[string]any{"Count": len(items), "Items": items, "ScannedCount": len(items)}, nil
}

func (d *dynamo) transactGetItems(in map[string]json.RawMessage) (any, error) {
	var req []struct{ Get map[string]json.RawMessage }
	if err := field(in, "TransactItems", &req); err != nil {
		return nil, err
	}
	out := []any{}
	for _, r := range req {
		if err := onlySupported("Get", r.Get); err != nil {
			return nil, err
		}
		got, err := d.getItem(r.Get)
		if err != nil {
			return nil, err
		}
		out = append(out, got)
	}
	return map[string]any{"Responses": out}, nil
}

func (d *dynamo) transactWriteItems(in map[string]json.RawMessage) (any, error) {
	var req []struct {
		Put, Delete, Update, ConditionCheck map[string]json.RawMessage
	}
	if err := field(in, "TransactItems", &req); err != nil {
		return nil, err
	}
	// Check every condition first; apply only when all pass.
	reasons := make([]map[string]string, len(req))
	failed := false
	for i, r := range req {
		action, kind, keyField := r.Put, "Put", "Key"
		switch {
		case r.Put != nil:
			keyField = "Item"
		case r.Delete != nil:
			action, kind = r.Delete, "Delete"
		case r.Update != nil:
			action, kind = r.Update, "Update"
		case r.ConditionCheck != nil:
			action, kind = r.ConditionCheck, "ConditionCheck"
		default:
			return nil, validation("a transaction item has no action")
		}
		if err := onlySupported(kind, action); err != nil {
			return nil, err
		}
		tb, err := d.table(action)
		if err != nil {
			return nil, err
		}
		var key item
		if err := field(action, keyField, &key); err != nil {
			return nil, err
		}
		k, err := tb.key(key)
		if err != nil {
			return nil, err
		}
		ok, err := condition(action, tb.items[k])
		if err != nil {
			return nil, err
		}
		reasons[i] = map[string]string{"Code": "None"}
		if !ok {
			failed = true
			reasons[i] = map[string]string{"Code": "ConditionalCheckFailed", "Message": "The conditional request failed"}
		}
	}
	if failed {
		codes := make([]string, len(reasons))
		for i, r := range reasons {
			codes[i] = r["Code"]
		}
		return nil, &dynamoError{status: 400, code: "TransactionCanceledException",
			message: "Transaction cancelled, please refer cancellation reasons for specific reasons [" + strings.Join(codes, ", ") + "]",
			extra:   map[string]any{"CancellationReasons": reasons}}
	}
	for _, r := range req {
		var err error
		switch {
		case r.Put != nil:
			_, err = d.putItem(r.Put)
		case r.Delete != nil:
			_, err = d.deleteItem(r.Delete)
		case r.Update != nil:
			_, err = d.updateItem(r.Update)
		}
		if err != nil {
			return nil, err
		}
	}
	return map[string]any{}, nil
}

func (d *dynamo) describeTable(in map[string]json.RawMessage) (any, error) {
	tb, err := d.table(in)
	if err != nil {
		return nil, err
	}
	var name string
	_ = field(in, "TableName", &name)
	schema := []map[string]string{{"AttributeName": tb.hash, "KeyType": "HASH"}}
	if tb.rng != "" {
		schema = append(schema, map[string]string{"AttributeName": tb.rng, "KeyType": "RANGE"})
	}
	return map[string]any{"Table": map[string]any{
		"TableName": name, "TableStatus": "ACTIVE", "KeySchema": schema, "ItemCount": len(tb.items),
	}}, nil
}

func (d *dynamo) listTables() (any, error) {
	names := make([]string, 0, len(d.tables))
	for name := range d.tables {
		names = append(names, name)
	}
	sort.Strings(names)
	return map[string]any{"TableNames": names}, nil
}

func mustJSON(v any) json.RawMessage {
	b, err := json.Marshal(v)
	if err != nil {
		panic(err)
	}
	return b
}

func jsonEqual(a, b json.RawMessage) bool {
	var ca, cb bytes.Buffer
	return json.Compact(&ca, a) == nil && json.Compact(&cb, b) == nil && bytes.Equal(ca.Bytes(), cb.Bytes())
}

// TestFakeRejectsWhatItDoesNotImplement checks that a parameter the fake does not implement
// fails the call, so a test cannot pass because the fake ignored it for both clients.
func TestFakeRejectsWhatItDoesNotImplement(t *testing.T) {
	t.Parallel()
	d := newDynamo(t)
	ddb := plainClient(d)
	ctx := context.Background()
	key := map[string]types.AttributeValue{"Id": &types.AttributeValueMemberS{Value: "1"}}
	for name, run := range map[string]func() error{
		"projection": func() error {
			_, err := ddb.GetItem(ctx, &dynamodb.GetItemInput{TableName: aws.String("Other"), Key: key, ProjectionExpression: aws.String("Id")})
			return err
		},
		"consistent_read": func() error {
			_, err := ddb.Scan(ctx, &dynamodb.ScanInput{TableName: aws.String("Other"), ConsistentRead: aws.Bool(true)})
			return err
		},
		"batch_get_projection": func() error {
			_, err := ddb.BatchGetItem(ctx, &dynamodb.BatchGetItemInput{RequestItems: map[string]types.KeysAndAttributes{
				"Other": {Keys: []map[string]types.AttributeValue{key}, ProjectionExpression: aws.String("Id")}}})
			return err
		},
		"transact_put_return_values": func() error {
			_, err := ddb.TransactWriteItems(ctx, &dynamodb.TransactWriteItemsInput{TransactItems: []types.TransactWriteItem{
				{Put: &types.Put{TableName: aws.String("Other"), Item: key,
					ReturnValuesOnConditionCheckFailure: types.ReturnValuesOnConditionCheckFailureAllOld}}}})
			return err
		},
	} {
		var ve *smithy.GenericAPIError
		if err := run(); !errors.As(err, &ve) || ve.Code != "ValidationException" {
			t.Errorf("%s: error %v, want a ValidationException", name, err)
		}
	}
}
