// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"bytes"
	"encoding/json"
	"fmt"
	"slices"
	"strings"
	"testing"

	"github.com/google/cel-go/common/types"
	"github.com/google/cel-go/interpreter"
)

// Cost of one routing decision per expression and body. Run from go/:
//
//	go test -run '^$' -bench BenchmarkPredicates ./elasticacheintegrations/
//
// Sub-benchmarks per case:
//
//	route  shouldRoute on the request, body work included
//	cel    the cel-go program alone over an already decoded body
//	parse  decodeBody alone
type predBenchCase struct {
	name      string
	expr      string
	vars      map[string]any
	operation string
	body      []byte
}

func predBenchCases() []predBenchCase {
	allow := map[string]any{"ops": allOperations, "allowed": []string{"Users", "Sessions"}}
	deny := map[string]any{"ops": allOperations, "excluded": []string{"Secrets"}}
	cases := []predBenchCase{
		{"AllOps/GetItem", exprAllOps, map[string]any{"ops": allOperations}, "GetItem", benchGetItem()},
		{"AllOps/PutItem-390KiB-many", exprAllOps, map[string]any{"ops": allOperations}, "PutItem", benchPutItem(390<<10, true)},
		{"TableName/GetItem", exprTableName, allow, "GetItem", benchGetItem()},
		{"TableNameDeny/GetItem", exprTableNameDeny, deny, "GetItem", benchGetItem()},
	}
	for _, size := range []int{1 << 10, 64 << 10, 390 << 10} {
		for _, many := range []bool{false, true} {
			shape := map[bool]string{false: "big", true: "many"}[many]
			name := fmt.Sprintf("PutItem-%dKiB-%s", size>>10, shape)
			cases = append(cases,
				predBenchCase{"TableName/" + name, exprTableName, allow, "PutItem", benchPutItem(size, many)},
				predBenchCase{"TableNameDeny/" + name, exprTableNameDeny, deny, "PutItem", benchPutItem(size, many)})
		}
	}
	for _, n := range []int{1, 10, 100} {
		tables := make([]string, n)
		for i := range tables {
			tables[i] = benchTable(i)
		}
		cases = append(cases,
			predBenchCase{fmt.Sprintf("RequestItems/x%d", n), exprRequestItems,
				map[string]any{"ops": allOperations, "allowed": tables}, "BatchGetItem", benchBatch(n)},
			predBenchCase{fmt.Sprintf("RequestItemsDeny/x%d", n), exprRequestItemsDeny, deny, "BatchGetItem", benchBatch(n)})
	}
	for _, n := range []int{1, 10, 100} {
		body := benchTransact(n, 0)
		cases = append(cases,
			predBenchCase{fmt.Sprintf("TransactItems/x%d", n), exprTransactItems, allow, "TransactWriteItems", body},
			predBenchCase{fmt.Sprintf("TransactItemsProxy/x%d", n), exprTransactItemsProxy, allow, "TransactWriteItems", body},
			predBenchCase{fmt.Sprintf("TransactItemsDeny/x%d", n), exprTransactItemsDeny, deny, "TransactWriteItems", body})
	}
	cases = append(cases, predBenchCase{"TransactItemsProxy/x100-4KiB", exprTransactItemsProxy, allow,
		"TransactWriteItems", benchTransact(100, 4<<10)})
	return cases
}

func benchTable(i int) string {
	if i == 0 {
		return "Users"
	}
	return fmt.Sprintf("Table%d", i)
}

// The bodies follow aws-sdk-go-v2's serializer: structure members in name order, so TableName
// comes after Key and Item, and TransactWriteItems leads with the generated ClientRequestToken.

func benchGetItem() []byte {
	return []byte(`{"ConsistentRead":false,"Key":{"id":{"S":"user-1"}},"TableName":"Users"}`)
}

// benchPutItem is a PutItem body of about size bytes: one large string attribute, or many
// small nested attributes.
func benchPutItem(size int, many bool) []byte {
	const tail = `},"TableName":"Users"}`
	var b bytes.Buffer
	b.WriteString(`{"Item":{"id":{"S":"user-1"}`)
	if !many {
		pad := size - b.Len() - len(`,"blob":{"S":""}`) - len(tail)
		b.WriteString(`,"blob":{"S":"` + strings.Repeat("x", max(pad, 0)) + `"}` + tail)
		return b.Bytes()
	}
	for i := 0; b.Len() < size-len(tail); i++ {
		fmt.Fprintf(&b, `,"a%05d":{"M":{"n":{"N":"%d"},"s":{"S":"value-%d"},"l":{"L":[{"BOOL":true},{"NULL":true}]}}}`, i, i, i)
	}
	b.WriteString(tail)
	return b.Bytes()
}

func benchBatch(n int) []byte {
	var b bytes.Buffer
	b.WriteString(`{"RequestItems":{`)
	for i := 0; i < n; i++ {
		if i > 0 {
			b.WriteByte(',')
		}
		fmt.Fprintf(&b, `"%s":{"Keys":[{"id":{"S":"k%d"}}]}`, benchTable(i), i)
	}
	b.WriteString(`}}`)
	return b.Bytes()
}

// benchTransact is a TransactWriteItems body of n Put actions, each with a pad-byte attribute.
func benchTransact(n, pad int) []byte {
	var b bytes.Buffer
	b.WriteString(`{"ClientRequestToken":"5f0c7a52-3d1e-4b8a-9c6f-2e7d8a1b4c90","TransactItems":[`)
	for i := 0; i < n; i++ {
		if i > 0 {
			b.WriteByte(',')
		}
		table := []string{"Users", "Sessions"}[i%2]
		fmt.Fprintf(&b, `{"Put":{"Item":{"id":{"S":"k%d"},"v":{"S":"value-%d%s"}},"TableName":"%s"}}`,
			i, i, strings.Repeat("x", pad), table)
	}
	b.WriteString(`]}`)
	return b.Bytes()
}

func (c predBenchCase) request() *request {
	return &request{method: "POST", url: predURL, headers: map[string]string{}, body: c.body,
		metadata: newRequestMetadata("dynamodb", c.operation)}
}

func (c predBenchCase) compile(tb testing.TB) *predicates {
	return mustCompilePredicates(tb, predConfig(predEntry(c.expr, c.vars)))
}

func TestBenchmarkCasesRoute(t *testing.T) {
	for _, c := range predBenchCases() {
		if !c.compile(t).shouldRoute(c.request()) {
			t.Errorf("%s does not route", c.name)
		}
	}
}

// jsonMembers returns the member names of a JSON object in body order, and their values.
func jsonMembers(t *testing.T, raw []byte) ([]string, map[string]json.RawMessage) {
	t.Helper()
	dec := json.NewDecoder(bytes.NewReader(raw))
	if tok, err := dec.Token(); err != nil || tok != json.Delim('{') {
		t.Fatalf("not an object: %.60s", raw)
	}
	var names []string
	values := map[string]json.RawMessage{}
	for dec.More() {
		tok, err := dec.Token()
		if err != nil {
			t.Fatal(err)
		}
		var v json.RawMessage
		if err := dec.Decode(&v); err != nil {
			t.Fatal(err)
		}
		names = append(names, tok.(string))
		values[tok.(string)] = v
	}
	return names, values
}

func TestBenchmarkBodiesAreSDKShaped(t *testing.T) {
	if names, _ := jsonMembers(t, benchGetItem()); !slices.Equal(names, []string{"ConsistentRead", "Key", "TableName"}) {
		t.Errorf("GetItem members %v", names)
	}
	for _, size := range []int{1 << 10, 64 << 10, 390 << 10} {
		for _, many := range []bool{false, true} {
			body := benchPutItem(size, many)
			if len(body) < size || len(body) > size+128 {
				t.Errorf("PutItem %d many=%v is %d bytes", size, many, len(body))
			}
			if names, _ := jsonMembers(t, body); !slices.Equal(names, []string{"Item", "TableName"}) {
				t.Errorf("PutItem members %v", names)
			}
		}
	}
	for _, pad := range []int{0, 4 << 10} {
		names, values := jsonMembers(t, benchTransact(100, pad))
		if !slices.Equal(names, []string{"ClientRequestToken", "TransactItems"}) {
			t.Fatalf("TransactWriteItems members %v", names)
		}
		var actions []json.RawMessage
		if err := json.Unmarshal(values["TransactItems"], &actions); err != nil || len(actions) != 100 {
			t.Fatalf("TransactItems: %d actions, %v", len(actions), err)
		}
		for _, a := range actions {
			kinds, put := jsonMembers(t, a)
			if names, _ := jsonMembers(t, put["Put"]); !slices.Equal(kinds, []string{"Put"}) ||
				!slices.Equal(names, []string{"Item", "TableName"}) {
				t.Fatalf("action members %v %v", kinds, names)
			}
			if n := len(a) - pad; n < 60 || n > 140 {
				t.Fatalf("action of %d bytes beyond its %d-byte pad", len(a), pad)
			}
		}
	}
}

func BenchmarkPredicates(b *testing.B) {
	for _, c := range predBenchCases() {
		p := c.compile(b)
		r := c.request()
		b.Run(c.name+"/route", func(b *testing.B) {
			if p.list[0].readsBody {
				b.SetBytes(int64(len(c.body)))
			}
			b.ReportAllocs()
			for b.Loop() {
				if !p.shouldRoute(r) {
					b.Fatal("the case does not route")
				}
			}
		})
		b.Run(c.name+"/cel", func(b *testing.B) {
			pr := predicateRequest{req: r}
			if !pr.loadBody() {
				b.Fatal("the body does not load")
			}
			pred := p.list[0]
			var act interpreter.Activation = pr.activation()
			if pred.vars != nil {
				act = interpreter.NewHierarchicalActivation(act, pred.vars)
			}
			b.ReportAllocs()
			for b.Loop() {
				if out, _, err := pred.program.Eval(act); err != nil || out != types.True {
					b.Fatal("the case does not route")
				}
			}
		})
		b.Run(c.name+"/parse", func(b *testing.B) {
			b.SetBytes(int64(len(c.body)))
			b.ReportAllocs()
			for b.Loop() {
				if _, ok := decodeBody(c.body); !ok {
					b.Fatal("the body does not decode")
				}
			}
		})
	}
}
