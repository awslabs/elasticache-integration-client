// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"bytes"
	"encoding/json"
	"errors"
	"reflect"
	"strings"
	"sync"
	"testing"
)

const (
	predURL = "https://dynamodb.us-east-1.amazonaws.com/"

	exprAllOps        = "service == 'dynamodb' && operation in ops"
	exprTableName     = "service == 'dynamodb' && operation in ops && has(body.TableName) && body.TableName in allowed"
	exprRequestItems  = "service == 'dynamodb' && operation in ops && has(body.RequestItems) && size(body.RequestItems) > 0 && body.RequestItems.all(t, t in allowed)"
	exprTransactItems = "service == 'dynamodb' && operation in ops && has(body.TransactItems) && size(body.TransactItems) > 0 && body.TransactItems.all(i, i.all(k, has(i[k].TableName) && i[k].TableName in allowed))"
	// The proxy's transaction expression.
	exprTransactItemsProxy = "service == 'dynamodb' && operation in ops && has(body.TransactItems) && size(body.TransactItems) > 0 && body.TransactItems.all(i, i.all(k, \"TableName\" in i[k] && i[k].TableName in allowed))"
	exprTableNameDeny      = "service == 'dynamodb' && operation in ops && has(body.TableName) && !(body.TableName in excluded)"
	exprRequestItemsDeny   = "service == 'dynamodb' && operation in ops && has(body.RequestItems) && size(body.RequestItems) > 0 && body.RequestItems.all(t, !(t in excluded))"
	exprTransactItemsDeny  = "service == 'dynamodb' && operation in ops && has(body.TransactItems) && size(body.TransactItems) > 0 && body.TransactItems.all(i, i.all(k, has(i[k].TableName) && !(i[k].TableName in excluded)))"
)

var (
	allOperations = []string{"GetItem", "BatchGetItem", "Query", "Scan", "PutItem", "UpdateItem",
		"DeleteItem", "BatchWriteItem", "TransactGetItems", "TransactWriteItems"}
	singleTableOperations = []string{"GetItem", "Query", "Scan", "PutItem", "UpdateItem", "DeleteItem"}
	batchOperations       = []string{"BatchGetItem", "BatchWriteItem"}
	transactOperations    = []string{"TransactGetItems", "TransactWriteItems"}
)

// predJSONConfig decodes a config document as go-protocol does: with UseNumber.
func predJSONConfig(t testing.TB, doc string) map[string]any {
	t.Helper()
	dec := json.NewDecoder(strings.NewReader(doc))
	dec.UseNumber()
	var raw map[string]any
	if err := dec.Decode(&raw); err != nil {
		t.Fatalf("config %s: %v", doc, err)
	}
	return raw
}

// predEntry is one predicate; nil vars leaves the key out.
func predEntry(expr string, vars map[string]any) map[string]any {
	if vars == nil {
		return map[string]any{"expr": expr}
	}
	return map[string]any{"expr": expr, "vars": vars}
}

func predConfig(entries ...map[string]any) map[string]any {
	list := make([]any, len(entries))
	for i, e := range entries {
		list[i] = e
	}
	// A JSON round trip gives the shapes go-protocol hands over ([]any, json.Number).
	doc, err := json.Marshal(map[string]any{"version": "1.0", "predicates": list})
	if err != nil {
		panic(err)
	}
	dec := json.NewDecoder(bytes.NewReader(doc))
	dec.UseNumber()
	var raw map[string]any
	if err := dec.Decode(&raw); err != nil {
		panic(err)
	}
	return raw
}

func mustCompilePredicates(t testing.TB, raw map[string]any) *predicates {
	t.Helper()
	p, err := compilePredicates(raw)
	if err != nil {
		t.Fatalf("compilePredicates: %v", err)
	}
	return p
}

func predDefaultConfig() map[string]any {
	return predConfig(predEntry(exprAllOps, map[string]any{"ops": allOperations}))
}

func predAllowlistConfig(transactExpr string, tables ...string) map[string]any {
	return predConfig(
		predEntry(exprTableName, map[string]any{"ops": singleTableOperations, "allowed": tables}),
		predEntry(exprRequestItems, map[string]any{"ops": batchOperations, "allowed": tables}),
		predEntry(transactExpr, map[string]any{"ops": transactOperations, "allowed": tables}),
	)
}

func predReq(operation, service, body string) *request {
	return &request{
		method:   "POST",
		url:      predURL,
		headers:  map[string]string{},
		body:     []byte(body),
		metadata: newRequestMetadata(service, operation),
	}
}

func TestCompilesTheDefaultCacheConfig(t *testing.T) {
	if n := len(mustCompilePredicates(t, predDefaultConfig()).list); n != 1 {
		t.Fatalf("got %d predicates, want 1", n)
	}
}

func TestMissingPredicatesCompileToEmpty(t *testing.T) {
	for _, raw := range []map[string]any{nil, {}, {"version": "1.0"}} {
		p := mustCompilePredicates(t, raw)
		if len(p.list) != 0 || p.shouldRoute(predReq("GetItem", "dynamodb", "{}")) {
			t.Fatalf("%v: want an empty set that routes nothing", raw)
		}
	}
}

func TestNonListPredicatesRejectTheConfig(t *testing.T) {
	for _, v := range []any{"nope", map[string]any{"expr": "true"}, nil} {
		_, err := compilePredicates(map[string]any{"predicates": v})
		assertUnevaluable(t, err, v)
	}
}

func assertUnevaluable(t *testing.T, err error, what any) {
	t.Helper()
	var unevaluable *ConfigUnevaluableError
	if !errors.As(err, &unevaluable) || !errors.Is(err, ErrIntegrations) {
		t.Fatalf("%v: got %v, want *ConfigUnevaluableError", what, err)
	}
}

// One bad entry taints the whole config.
func TestAnyUnevaluableEntryRejectsTheWholeConfig(t *testing.T) {
	bad := []any{
		"not-an-object",
		map[string]any{"vars": map[string]any{}},
		map[string]any{"expr": 7},
		predEntry("this is not (valid CEL", map[string]any{}),
		predEntry("operation == 1", map[string]any{}),
		predEntry("undeclared == 'x'", map[string]any{}),
		map[string]any{"expr": "true", "vars": "not-an-object"},
		map[string]any{"expr": "true", "vars": []any{"a"}},
		map[string]any{"expr": "true", "vars": nil},
	}
	for _, b := range bad {
		raw := map[string]any{"predicates": []any{predEntry("operation == 'GetItem'", map[string]any{}), b}}
		_, err := compilePredicates(raw)
		assertUnevaluable(t, err, b)
		if !strings.Contains(err.Error(), "predicate 1 ") {
			t.Errorf("%v: %q does not name the entry", b, err)
		}
	}
}

func TestUnevaluableMessageDoesNotEchoVars(t *testing.T) {
	_, err := compilePredicates(predConfig(predEntry("operation in", map[string]any{"allowed": []string{"SecretTable"}})))
	if err == nil || strings.Contains(err.Error(), "SecretTable") {
		t.Fatalf("got %v", err)
	}
}

func TestDefaultConfigRoutesReadsAndWritesButNotUnsupportedOperations(t *testing.T) {
	p := mustCompilePredicates(t, predDefaultConfig())
	for _, op := range allOperations {
		if !p.shouldRoute(predReq(op, "dynamodb", "{}")) {
			t.Errorf("%s does not route", op)
		}
	}
	for _, op := range []string{"CreateTable", "DescribeTable", "ExecuteStatement"} {
		if p.shouldRoute(predReq(op, "dynamodb", "{}")) {
			t.Errorf("%s routes", op)
		}
	}
	if p.shouldRoute(predReq("GetItem", "s3", "{}")) {
		t.Error("s3 routes")
	}
}

func TestTableAllowlistRoutesSingleTableReadsAndWrites(t *testing.T) {
	p := mustCompilePredicates(t, predAllowlistConfig(exprTransactItemsProxy, "Users"))
	for _, op := range singleTableOperations {
		if !p.shouldRoute(predReq(op, "dynamodb", `{"TableName":"Users"}`)) {
			t.Errorf("%s Users does not route", op)
		}
		if p.shouldRoute(predReq(op, "dynamodb", `{"TableName":"Orders"}`)) {
			t.Errorf("%s Orders routes", op)
		}
	}
	if p.shouldRoute(predReq("GetItem", "dynamodb", `{"Key":{}}`)) {
		t.Error("a body without TableName routes")
	}
}

func TestBatchAllowlistRequiresEveryTableAndRejectsEmptyBatch(t *testing.T) {
	p := mustCompilePredicates(t, predAllowlistConfig(exprTransactItemsProxy, "Users", "Sessions"))
	cases := []struct {
		op, body string
		want     bool
	}{
		{"BatchGetItem", `{"RequestItems":{"Users":{},"Sessions":{}}}`, true},
		{"BatchGetItem", `{"RequestItems":{"Users":{},"Orders":{}}}`, false},
		{"BatchWriteItem", `{"RequestItems":{"Users":[]}}`, true},
		{"BatchWriteItem", `{"RequestItems":{"Users":[],"Orders":[]}}`, false},
		{"BatchGetItem", `{"RequestItems":{}}`, false},
	}
	for _, c := range cases {
		if got := p.shouldRoute(predReq(c.op, "dynamodb", c.body)); got != c.want {
			t.Errorf("%s %s: got %v", c.op, c.body, got)
		}
	}
}

func TestTransactionAllowlistRequiresEveryTableAndRejectsEmptyTransaction(t *testing.T) {
	for _, expr := range []string{exprTransactItemsProxy, exprTransactItems} {
		p := mustCompilePredicates(t, predAllowlistConfig(expr, "Users", "Sessions"))
		cases := []struct {
			op, body string
			want     bool
		}{
			{"TransactGetItems", `{"TransactItems":[{"Get":{"TableName":"Users"}},{"Get":{"TableName":"Sessions"}}]}`, true},
			{"TransactGetItems", `{"TransactItems":[{"Get":{"TableName":"Users"}},{"Get":{"TableName":"Orders"}}]}`, false},
			{"TransactWriteItems", `{"TransactItems":[{"Put":{"TableName":"Users"}}]}`, true},
			{"TransactWriteItems", `{"TransactItems":[{"Put":{"TableName":"Orders"}}]}`, false},
			{"TransactGetItems", `{"TransactItems":[]}`, false},
			// The TableName guard: an action carrying no table is a clean non-match.
			{"TransactGetItems", `{"TransactItems":[{"Get":{"Key":{}}}]}`, false},
		}
		for _, c := range cases {
			if got := p.shouldRoute(predReq(c.op, "dynamodb", c.body)); got != c.want {
				t.Errorf("%s\n%s %s: got %v", expr, c.op, c.body, got)
			}
		}
	}
}

func TestDenylistRoutesEveryOtherTableAndFailsClosed(t *testing.T) {
	excluded := map[string]any{"excluded": []string{"Secrets"}}
	with := func(ops []string) map[string]any {
		return map[string]any{"ops": ops, "excluded": excluded["excluded"]}
	}
	p := mustCompilePredicates(t, predConfig(
		predEntry(exprTableNameDeny, with(singleTableOperations)),
		predEntry(exprRequestItemsDeny, with(batchOperations)),
		predEntry(exprTransactItemsDeny, with(transactOperations)),
	))
	cases := []struct {
		op, body string
		want     bool
	}{
		{"GetItem", `{"TableName":"Users"}`, true},
		{"GetItem", `{"TableName":"Secrets"}`, false},
		{"GetItem", `{"Key":{}}`, false},
		{"BatchGetItem", `{"RequestItems":{"Users":{}}}`, true},
		{"BatchGetItem", `{"RequestItems":{"Users":{},"Secrets":{}}}`, false},
		{"BatchGetItem", `{"RequestItems":{}}`, false},
		{"TransactWriteItems", `{"TransactItems":[{"Put":{"TableName":"Users"}}]}`, true},
		{"TransactWriteItems", `{"TransactItems":[{"Put":{"TableName":"Users"}},{"Delete":{"TableName":"Secrets"}}]}`, false},
		{"TransactWriteItems", `{"TransactItems":[{"Put":{"Key":{}}}]}`, false},
	}
	for _, c := range cases {
		if got := p.shouldRoute(predReq(c.op, "dynamodb", c.body)); got != c.want {
			t.Errorf("%s %s: got %v", c.op, c.body, got)
		}
	}
}

func TestUninspectableBodiesDoNotRoute(t *testing.T) {
	p := mustCompilePredicates(t, predAllowlistConfig(exprTransactItemsProxy, "Users"))
	for _, body := range []string{
		"", "not json", `["TableName"]`, "null", `{"TableName":"Users"} trailing`,
		`{"TableName":"Users"}{}`, `{"TableName":"Users"`, "\"Users\"",
		"{\"TableName\":\"Users\",\"x\":\"\xff\"}", // invalid UTF-8
	} {
		if p.shouldRoute(predReq("GetItem", "dynamodb", body)) {
			t.Errorf("%q routes", body)
		}
	}
}

func TestLargeValidBodiesAreEvaluatedByPredicates(t *testing.T) {
	p := mustCompilePredicates(t, predAllowlistConfig(exprTransactItemsProxy, "Users"))
	body := predBodyOfSize(600 << 10)
	if len(body) != 600<<10 || !p.shouldRoute(predReq("GetItem", "dynamodb", body)) {
		t.Fatal("a 600 KiB body does not route")
	}
}

func predBodyOfSize(size int) string {
	prefix, suffix := `{"TableName":"Users","Pad":"`, `"}`
	return prefix + strings.Repeat("x", size-len(prefix)-len(suffix)) + suffix
}

// Java declines every predicate on a malformed body; Go declines only those that read it.
func TestMalformedBodiesDeclineOnlyBodyPredicates(t *testing.T) {
	p := mustCompilePredicates(t, predDefaultConfig())
	for _, body := range []string{"", "not json", "[]", "\xff"} {
		if !p.shouldRoute(predReq("GetItem", "dynamodb", body)) {
			t.Errorf("%q: a body-free predicate does not match", body)
		}
	}
	mixed := mustCompilePredicates(t, predConfig(
		predEntry(exprTableName, map[string]any{"ops": allOperations, "allowed": []string{"Users"}}),
		predEntry("operation == 'Scan'", nil),
	))
	if !mixed.shouldRoute(predReq("Scan", "dynamodb", "not json")) {
		t.Error("the body-free predicate after a body predicate does not match")
	}
	if mixed.shouldRoute(predReq("GetItem", "dynamodb", "not json")) {
		t.Error("a body predicate matches a malformed body")
	}
}

func TestBodyPredicatesAreEvaluatedLast(t *testing.T) {
	p := mustCompilePredicates(t, predConfig(
		predEntry("has(body.TableName)", nil),
		predEntry("operation == 'GetItem'", nil),
		predEntry("method == 'POST' && has(body.Key)", nil),
	))
	if p.list[0].readsBody || !p.list[1].readsBody || !p.list[2].readsBody {
		t.Fatal("body predicates are not last")
	}
	r := predReq("GetItem", "dynamodb", "not json")
	if !p.shouldRoute(r) {
		t.Fatal("does not route")
	}
}

func TestEvaluationErrorsCountAsNoMatchAndSiblingsStillEvaluate(t *testing.T) {
	p := mustCompilePredicates(t, predConfig(
		predEntry("body.Missing == 'value'", nil),
		predEntry("operation == 'GetItem'", nil),
	))
	if !p.shouldRoute(predReq("GetItem", "dynamodb", "{}")) {
		t.Error("GetItem does not route")
	}
	if p.shouldRoute(predReq("PutItem", "dynamodb", "{}")) {
		t.Error("PutItem routes")
	}
}

func TestNonBoolResultIsNoMatch(t *testing.T) {
	p := mustCompilePredicates(t, predConfig(predEntry("operation", nil), predEntry("body.TableName", nil)))
	if p.shouldRoute(predReq("GetItem", "dynamodb", `{"TableName":"Users"}`)) {
		t.Error("a string result routes")
	}
}

func TestFixedRequestContextAndBoundVariablesAreAvailable(t *testing.T) {
	p := mustCompilePredicates(t, predConfig(predEntry(
		"method == 'POST' && url.startsWith(prefix) && headers['x-test'] == expected",
		map[string]any{"prefix": "https://dynamodb.", "expected": "yes"})))
	r := predReq("GetItem", "dynamodb", "")
	r.headers = map[string]string{"x-test": "yes"}
	if !p.shouldRoute(r) {
		t.Fatal("does not route")
	}
}

func TestHeadersExcludeSigningHeaders(t *testing.T) {
	p := mustCompilePredicates(t, predConfig(predEntry(
		"!('authorization' in headers) && !('x-amz-date' in headers) && !('x-amz-security-token' in headers)"+
			" && !('x-amz-request-id' in headers) && !('amz-sdk-request' in headers) && headers['content-type'] == 'application/x-amz-json-1.0'",
		nil)))
	r := predReq("GetItem", "dynamodb", "")
	r.headers = map[string]string{
		"authorization": "AWS4-HMAC-SHA256 Credential=x", "x-amz-date": "d", "x-amz-security-token": "t",
		"x-amz-request-id": "r", "amz-sdk-request": "attempt=1", "content-type": "application/x-amz-json-1.0",
	}
	if !p.shouldRoute(r) {
		t.Fatal("headers include an excluded header")
	}
	if _, ok := r.headers["authorization"]; !ok {
		t.Fatal("the request's own headers changed")
	}
}

func TestVarsWinOverRequestFields(t *testing.T) {
	p := mustCompilePredicates(t, predConfig(predEntry("operation == 'Pinned' && body == 'mine'",
		map[string]any{"operation": "Pinned", "body": "mine"})))
	if p.list[0].readsBody {
		t.Fatal("a vars key body hides the request body")
	}
	// The body is never parsed: a malformed one still matches.
	if !p.shouldRoute(predReq("GetItem", "dynamodb", "not json")) {
		t.Fatal("vars do not win")
	}
}

func TestDottedVarsNameIsOneVariable(t *testing.T) {
	p := mustCompilePredicates(t, predConfig(predEntry("body.TableName == 'x'", map[string]any{"body.TableName": "x"})))
	if !p.shouldRoute(predReq("GetItem", "dynamodb", `{"TableName":"y"}`)) {
		t.Fatal("the dotted var is not used")
	}
}

func TestComprehensionVariableNamedBodyIsNotTheBody(t *testing.T) {
	p := mustCompilePredicates(t, predConfig(predEntry("ops.all(body, body != '')", map[string]any{"ops": []string{"a"}})))
	if p.list[0].readsBody {
		t.Fatal("a macro variable named body counts as the body")
	}
	if !p.shouldRoute(predReq("GetItem", "dynamodb", "not json")) {
		t.Fatal("does not route")
	}
}

func TestVarsNumbersKeepTheirType(t *testing.T) {
	p := mustCompilePredicates(t, predJSONList(t, `[{"expr":"size(body.RequestItems) == n && limit < 1.5 && size(body.RequestItems) < big","vars":{"n":2,"limit":1.25,"big":9007199254740993}}]`))
	if !p.shouldRoute(predReq("BatchGetItem", "dynamodb", `{"RequestItems":{"a":{},"b":{}}}`)) {
		t.Fatal("vars integers do not compare as int")
	}
}

// Compiling never changes the config, so the same config compiles again, also concurrently.
func TestCompileLeavesTheConfigUnchanged(t *testing.T) {
	const doc = `[{"expr":"size(body.RequestItems) in ns && m.k == 1","vars":{"ns":[1,2],"m":{"k":1}}}]`
	raw := predJSONList(t, doc)
	want := predJSONList(t, doc)
	var wg sync.WaitGroup
	for g := 0; g < 4; g++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := compilePredicates(raw); err != nil {
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	if !reflect.DeepEqual(raw, want) {
		t.Fatalf("the config changed: %#v", raw)
	}
	p := mustCompilePredicates(t, raw)
	if !p.shouldRoute(predReq("BatchGetItem", "dynamodb", `{"RequestItems":{"a":{}}}`)) {
		t.Fatal("the recompiled config does not route")
	}
}

func predJSONList(t *testing.T, list string) map[string]any {
	return predJSONConfig(t, `{"version":"1.0","predicates":`+list+`}`)
}

func TestBodyNumbers(t *testing.T) {
	p := mustCompilePredicates(t, predConfig(predEntry("body.i == 3 && body.f == 2.5 && body.n == -0 && body.e == 100.0 && body.big > 1e19", nil)))
	if !p.shouldRoute(predReq("GetItem", "dynamodb", `{"i":3,"f":2.5,"n":-0,"e":1e2,"big":12345678901234567890}`)) {
		t.Fatal("body numbers do not decode as int64 and float64")
	}
}

func TestBodyDepthLimit(t *testing.T) {
	p := mustCompilePredicates(t, predConfig(predEntry("has(body.a)", nil)))
	nest := func(levels int) string { // the top-level object is level 1
		return `{"a":` + strings.Repeat("[", levels-1) + strings.Repeat("]", levels-1) + `}`
	}
	if !p.shouldRoute(predReq("GetItem", "dynamodb", nest(maxBodyDepth))) {
		t.Error("a body nested 1000 levels does not route")
	}
	if p.shouldRoute(predReq("GetItem", "dynamodb", nest(maxBodyDepth+1))) {
		t.Error("a body nested 1001 levels routes")
	}
}

func TestBodyByteOrderMarkAndDuplicateKeys(t *testing.T) {
	p := mustCompilePredicates(t, predConfig(predEntry("body.TableName == 'Users'", nil)))
	if !p.shouldRoute(predReq("GetItem", "dynamodb", "\xef\xbb\xbf"+`{"TableName":"Users"}`)) {
		t.Error("a BOM is not skipped")
	}
	if !p.shouldRoute(predReq("GetItem", "dynamodb", `{"TableName":"Orders","TableName":"Users"}`)) {
		t.Error("a repeated key does not keep its last value")
	}
	if !p.shouldRoute(predReq("GetItem", "dynamodb", ` {"TableName":"U\u0073ers"} `)) {
		t.Error("escapes or whitespace are not read")
	}
}

func TestTheSevenExpressionsCompile(t *testing.T) {
	for _, expr := range []string{exprAllOps, exprTableName, exprRequestItems, exprTransactItems,
		exprTransactItemsProxy, exprTableNameDeny, exprRequestItemsDeny, exprTransactItemsDeny} {
		mustCompilePredicates(t, predConfig(predEntry(expr, map[string]any{"ops": allOperations, "allowed": []string{}, "excluded": []string{}})))
	}
}

func TestEmptyPredicateSetRoutesNothing(t *testing.T) {
	var nilSet *predicates
	if mustCompilePredicates(t, predConfig()).shouldRoute(predReq("GetItem", "dynamodb", "{}")) || nilSet.shouldRoute(predReq("GetItem", "dynamodb", "{}")) {
		t.Fatal("an empty set routes")
	}
}

func TestPredicatesConcurrent(t *testing.T) {
	p := mustCompilePredicates(t, predAllowlistConfig(exprTransactItemsProxy, "Users"))
	var wg sync.WaitGroup
	for g := 0; g < 16; g++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < 200; i++ {
				if !p.shouldRoute(predReq("GetItem", "dynamodb", `{"TableName":"Users"}`)) ||
					p.shouldRoute(predReq("TransactGetItems", "dynamodb", `{"TransactItems":[{"Get":{"TableName":"Orders"}}]}`)) {
					t.Error("wrong answer under concurrency")
					return
				}
			}
		}()
	}
	wg.Wait()
}

// A request that a body-free predicate routes is never scanned. The body has more containers
// than a pooled scan keeps, so a scan always allocates its index.
func TestBodyFreeMatchSkipsTheBodyScan(t *testing.T) {
	if raceEnabled {
		t.Skip("the race detector changes allocation counts")
	}
	r := predReq("GetItem", "dynamodb", `{"a":[`+strings.Repeat(`[],`, maxPooledContainers)+`[]]}`)
	free := mustCompilePredicates(t, predConfig(predEntry("operation == 'GetItem'", nil)))
	reads := mustCompilePredicates(t, predConfig(predEntry("has(body.a) && operation == 'GetItem'", nil)))
	freeB := bytesPerRun(20, func() { free.shouldRoute(r) })
	readsB := bytesPerRun(20, func() { reads.shouldRoute(r) })
	if !free.shouldRoute(r) || !reads.shouldRoute(r) || freeB > 4<<10 || readsB < 64<<10 {
		t.Fatalf("a body-free match allocates %d bytes, a body match %d", freeB, readsB)
	}
}
