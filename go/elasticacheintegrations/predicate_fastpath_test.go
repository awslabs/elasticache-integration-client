// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"math/rand"
	"strings"
	"testing"

	"github.com/google/cel-go/common/types"
	"github.com/google/cel-go/interpreter"
)

// fastProbeVars are the vars every probe expression sees.
var fastProbeVars = map[string]any{
	"ops":     []any{"GetItem", "Query"},
	"allowed": []any{"Users", "Sessions", "x"},
	"mixed":   []any{"Users", int64(1), nil, true},
	"n":       int64(2),
	"f":       1.5,
	"m":       map[string]any{"Users": true, "k": "x"},
}

// fastProbes cover every construct of the fast path, and the defer cases next to them.
var fastProbes = []string{
	"body.TableName == 'Users'",
	"body.TableName != 'Users'",
	"has(body.TableName)",
	"has(body.a.b)",
	"has(body.a.k)",
	"body.TableName in allowed",
	"!(body.TableName in allowed)",
	"body.a in allowed",
	"body.a in mixed",
	"'Users' in body.a",
	"'x' in body.a",
	"1 in body.a",
	"'k' in body",
	"'TableName' in body.a",
	"body.a == body.b",
	"body.a != body.b",
	"body.a < 'M'",
	"body.a >= body.b",
	"body['TableName'] == 'Users'",
	"body.a['k'] == 'x'",
	"body.a[0] == 'x'",
	"body.a[1] == true",
	"body.a[n] == 'x'",
	"body.a == null",
	"body.a == true",
	"body.a.k == 'x'",
	"body.Key.id == 'x'",
	"!(body.a == 'x')",
	"!body.a",
	"body.a ? true : false",
	"has(body.a) ? body.a == 'x' : operation == 'GetItem'",
	"body.a || body.b",
	"body.a && body.b",
	"body.a == 'x' || body.b == 'x'",
	"body.a == 'x' && body.b == 'x'",
	"operation in ops && has(body.TableName) && body.TableName in allowed",
	"body.TableName in m",
	"m[body.TableName] == true",
	"m.k == body.a",
	"size(body.a) > 0",
	"size(body.a) == 2",
	"body.a.size() == n",
	"size(body) >= 2",
	"size(body.TableName) == 5",
	"size(body.a) > 0",
	"size(body.a) != 0",
	"size(body.a) == 0",
	"size(body.TableName) == 0",
	"body.a.all(x, x == 'x')",
	"body.a.all(x, x in allowed)",
	"body.a.all(x, has(x.k))",
	"body.RequestItems.all(t, t in allowed)",
	"size(body.RequestItems) > 0 && body.RequestItems.all(t, !(t in allowed))",
	"body.TransactItems.all(i, i.all(k, \"TableName\" in i[k] && i[k].TableName in allowed))",
	"body.TransactItems.all(i, i.all(k, has(i[k].TableName) && i[k].TableName in allowed))",
	"body.a.all(x, body.a.all(y, x == y))",
	"body.all(k, k != '')",
	"ops.all(o, o != body.a)",
	"m.all(k, k in allowed)",
}

// fastProbesNoBody run on every request without a body.
var fastProbesNoBody = []string{
	"operation in ops",
	"service == 'dynamodb' && operation in ops",
	"headers['x-a'] == 'y'",
	"'x-a' in headers",
	"has(headers.x)",
	"method == 'POST' && url == '" + predURL + "'",
	"ops[0] == operation",
	"n < 3 && n >= 2 && n <= 2 && n > 1",
	"'a' < 'b' && 'b' > 'a'",
	"operation == 'GetItem' ? 'yes' : 'no'",
	"operation",
	"null == null && true != false",
	"size(ops) == 2 && ops.size() == 2 && size(m) == 2 && size(headers) >= 0",
	"ops.all(o, o != '')",
	"headers.all(h, h != 'authorization')",
	"size(operation) == 7",
	"ops.all(a, ops.all(b, a == b || a != b))",
}

// genBody writes a random JSON object from a small vocabulary: missing keys, nulls, numbers,
// escaped strings, repeated keys, nested arrays and objects.
func genBody(r *rand.Rand) string {
	var b strings.Builder
	genObject(r, &b, 0)
	return b.String()
}

var genKeys = []string{"TableName", "a", "b", "k", "Key", "id", "RequestItems", "TransactItems", "Put", "Get", "Users", ""}

func genValue(r *rand.Rand, b *strings.Builder, depth int) {
	switch n := r.Intn(12); {
	case n == 0 && depth < 4:
		genObject(r, b, depth+1)
	case n == 1 && depth < 4:
		b.WriteByte('[')
		for i, count := 0, r.Intn(4); i < count; i++ {
			if i > 0 {
				b.WriteByte(',')
			}
			genValue(r, b, depth+1)
		}
		b.WriteByte(']')
	case n == 2:
		b.WriteString([]string{"null", "true", "false"}[r.Intn(3)])
	case n == 3:
		b.WriteString([]string{"0", "1", "-1", "2", "1.5", "1e2", "12345678901234567890"}[r.Intn(7)])
	default:
		b.WriteString([]string{`"Users"`, `"U\u0073ers"`, `"Orders"`, `"x"`, `"k"`, `""`, `"\ud800"`,
			`"a\"b"`, `"Sessions"`, `"M"`, `"é"`, `"TableName"`}[r.Intn(12)])
	}
}

func genObject(r *rand.Rand, b *strings.Builder, depth int) {
	b.WriteByte('{')
	for i, count := 0, r.Intn(5); i < count; i++ {
		if i > 0 {
			b.WriteByte(',')
		}
		key := genKeys[r.Intn(len(genKeys))]
		if r.Intn(8) == 0 {
			key = strings.Replace(key, "a", `\u0061`, 1)
		}
		b.WriteString(`"` + key + `":`)
		genValue(r, b, depth)
	}
	b.WriteByte('}')
}

// celAnswer is the predicate's answer from cel-go alone.
func celAnswer(pred *predicate, pr *predicateRequest) bool {
	var act interpreter.Activation = pr.activation()
	if pred.vars != nil {
		act = interpreter.NewHierarchicalActivation(act, pred.vars)
	}
	out, _, err := pred.program.Eval(act)
	return err == nil && out == types.True
}

func compileProbe(t testing.TB, expr string) *predicate {
	t.Helper()
	p := mustCompilePredicates(t, predConfig(predEntry(expr, fastProbeVars)))
	pred := p.list[0]
	if pred.fast == nil {
		t.Fatalf("%s has no fast form", expr)
	}
	return pred
}

// checkFast evaluates the fast form against cel-go and reports whether it answered.
func checkFast(t testing.TB, pred *predicate, expr string, r *request) bool {
	t.Helper()
	pr := predicateRequest{req: r}
	defer pr.release()
	if pred.readsBody && !pr.loadBody() {
		return false
	}
	got := pred.fast.eval(&pr)
	if got == fastDefer {
		return false
	}
	if want := celAnswer(pred, &pr); (got == fastTrue) != want {
		t.Fatalf("%s\nbody %s\nfast %v, cel-go %v", expr, r.body, got == fastTrue, want)
	}
	return true
}

func TestFastPathAgreesWithCEL(t *testing.T) {
	r := rand.New(rand.NewSource(1))
	bodies := make([]string, 400)
	for i := range bodies {
		bodies[i] = genBody(r)
	}
	bodies = append(bodies, `{"TableName":"Users"}`, `{"a":["x",true],"b":"x"}`, `{"a":{"k":"x","b":1}}`,
		`{"a":"x","a":"y"}`, `{"a":["Users"],"b":"M"}`, `{"Key":{"id":"x"}}`, `{"a":null}`, `{"a":true,"b":false}`)
	for _, expr := range fastProbes {
		pred := compileProbe(t, expr)
		answered := 0
		for _, body := range bodies {
			for _, op := range []string{"GetItem", "PutItem"} {
				if checkFast(t, pred, expr, predReq(op, "dynamodb", body)) {
					answered++
				}
			}
		}
		if answered == 0 {
			t.Errorf("%s: the fast path never answered", expr)
		}
	}
}

func TestFastPathWithoutBody(t *testing.T) {
	for _, expr := range fastProbesNoBody {
		pred := compileProbe(t, expr)
		if pred.readsBody {
			t.Fatalf("%s reads the body", expr)
		}
		answered := 0
		for _, op := range []string{"GetItem", "Scan"} {
			for _, h := range []map[string]string{{}, {"x-a": "y", "x": "1", "authorization": "secret"}} {
				r := predReq(op, "dynamodb", "")
				r.headers = h
				if checkFast(t, pred, expr, r) {
					answered++
				}
			}
		}
		if answered == 0 {
			t.Errorf("%s: the fast path never answered", expr)
		}
	}
}

func TestFastPathDefers(t *testing.T) {
	cases := []struct{ expr, body string }{
		{"body.Missing == 'x'", `{}`},                   // missing key
		{"body.a[3] == 'x'", `{"a":["x"]}`},             // index out of range
		{"body.a[-1] == 'x'", `{"a":["x"]}`},            // negative index
		{"body.a == 1", `{"a":1}`},                      // number operand
		{"body.a == 'x'", `{"a":true}`},                 // mixed types
		{"body.a && true", `{"a":"x"}`},                 // non-bool operand
		{"body.a.b == 'x'", `{"a":null}`},               // select on null
		{"body.a in allowed", `{"a":1}`},                // number needle
		{"'x' in body.a", `{"a":[1]}`},                  // number element
		{"body.a == f", `{"a":"x"}`},                    // double var
		{"body.a < body.b", `{"a":"x","b":true}`},       // mixed ordering
		{"!body.a", `{"a":"x"}`},                        // non-bool not
		{"body.a ? true : false", `{"a":null}`},         // non-bool condition
		{"has(body.a.b)", `{"a":"x"}`},                  // has on a string
		{"body.a['k'] == 'x'", `{"a":["k"]}`},           // string index on a list
		{"body.a[0] == 'x'", `{"a":{"0":"x"}}`},         // int index on a map
		{"1 in body.a", `{"a":{"1":true}}`},             // int key on a map
		{"body.a in mixed", `{"a":"Orders"}`},           // a non-string element is reached
		{"body.TableName in m", `{"TableName":true}`},   // bool key
		{"headers.authorization == 'secret'", `{}`},     // excluded header
		{"ops[5] == operation", `{}`},                   // index out of range
		{"body.a == 'x' || body.b == 'x'", `{"b":"x"}`}, // missing key on the left of ||
		{"size(body.a) > 0", `{"a":1}`},                 // size of a number
		{"body.a.all(x, x == 'y')", `{"a":"x"}`},        // all over a string
		{"body.a.all(x, x == 'y')", `{"a":[1]}`},        // defer inside the loop
		{"body.a.all(x, x == 'y')", `{"a":["y",1]}`},    // a number after a true element
		{"body.a.all(x, x)", `{"a":["x"]}`},             // non-bool element
	}
	for _, c := range cases {
		pred := compileProbe(t, c.expr)
		pr := predicateRequest{req: predReq("GetItem", "dynamodb", c.body)}
		if !pr.loadBody() {
			t.Fatalf("%s: body refused", c.body)
		}
		if got := pred.fast.eval(&pr); got != fastDefer {
			t.Errorf("%s over %s: got %v, want a defer", c.expr, c.body, got)
		}
		pr.release()
	}
}

func TestFastPathAnswersWithoutCEL(t *testing.T) {
	cases := []struct {
		expr, body string
		want       bool
	}{
		{"body.TableName == 'Users'", `{"TableName":"Users"}`, true},
		{"body.TableName == 'Users'", `{"TableName":"U\u0073ers"}`, true},
		{"body.TableName == 'Users'", `{"TableName":"Users","TableName":"Orders"}`, false},
		{"has(body.TableName)", `{"TableName":null}`, true},
		{"has(body.TableName)", `{"TableName":""}`, true},
		{"has(body.TableName)", `{"Key":{}}`, false},
		{"'k' in body", `{"\u006b":1}`, true},
		{"body.a in mixed", `{"a":"Users"}`, true},
		{"body.a == 'x' && body.b == 'x'", `{"a":"y"}`, false},
		{"body.a[1] == true", `{"a":["x",true]}`, true},
		{"body.a < 'M'", `{"a":"é"}`, false},
		{"body.a == null", `{"a":null}`, true},
		{"size(body.a) == 2", `{"a":{"x":1,"\u0078":2,"y":3}}`, true}, // a repeated key counts once
		{"size(body) == 0", `{}`, true},
		{"size(body.a) == 2", `{"a":{"x":1,"y":2,"x":3}}`, true},
		{"size(body.a) == 17", `{"a":{"k0":0,"k1":1,"k2":2,"k3":3,"k4":4,"k5":5,"k6":6,"k7":7,"k8":8,"k9":9,"k10":10,"k11":11,"k12":12,"k13":13,"k14":14,"k15":15,"k16":16,"k0":0}}`, true},
		{"size(body.a) == 3", `{"a":[1,"x",null]}`, true},
		{"size(body.a) == 2", `{"a":"é😀"}`, true},
		{"body.a.all(x, x == 'y')", `{"a":[]}`, true},
		{"body.a.all(x, x == 'y')", `{"a":["z",1]}`, false}, // false before the defer
		{"body.a.all(k, k == 'y')", `{"a":{"y":1,"\u0079":2}}`, true},
		{"size(body.a) > 0", `{"a":"\u0000"}`, true},
		{"size(body.a) == 0", `{"a":""}`, true},
		{"size(body.a) != 0", `{"a":{"x":1,"x":2}}`, true},
		{"size(body.a) == 0", `{"a":[ ]}`, true},
		{"size(body.a) > 0", `{"a":[[]]}`, true},
	}
	for _, c := range cases {
		pred := compileProbe(t, c.expr)
		pr := predicateRequest{req: predReq("GetItem", "dynamodb", c.body)}
		pr.loadBody()
		got := pred.fast.eval(&pr)
		pr.release()
		if got == fastDefer || (got == fastTrue) != c.want {
			t.Errorf("%s over %s: got %v, want %v", c.expr, c.body, got, c.want)
		}
	}
}

func TestNoFastFormFallsBackToCEL(t *testing.T) {
	for _, expr := range []string{
		"body.a.startsWith('x')", "size(body.a + 'y') > 1", "body.a == 1.5", "body.a in ['x']",
		"body.a == b'x'", "body.a == 1u", "body.a.exists(x, x == 'y')", "-n < 0",
	} {
		p := mustCompilePredicates(t, predConfig(predEntry(expr, fastProbeVars)))
		if p.list[0].fast != nil {
			t.Errorf("%s has a fast form", expr)
		}
	}
	p := mustCompilePredicates(t, predConfig(predEntry("body.a.startsWith('x')", nil)))
	if !p.shouldRoute(predReq("GetItem", "dynamodb", `{"a":"xy"}`)) {
		t.Fatal("cel-go does not evaluate a predicate without a fast form")
	}
}

func FuzzFastPath(f *testing.F) {
	r := rand.New(rand.NewSource(2))
	for i := 0; i < 50; i++ {
		f.Add(genBody(r), uint8(i))
	}
	preds := make([]*predicate, len(fastProbes))
	for i, expr := range fastProbes {
		preds[i] = compileProbe(f, expr)
	}
	f.Fuzz(func(t *testing.T, body string, probe uint8) {
		i := int(probe) % len(preds)
		checkFast(t, preds[i], fastProbes[i], predReq("GetItem", "dynamodb", body))
	})
}

// genVarSets are the vars of generated expressions: dotted names that the checker reads as one
// variable, and names that hide a request variable.
var genVarSets = []map[string]any{
	{
		"ops":            []any{"GetItem", "Query"},
		"allowed":        []any{"Users", "Sessions", "x"},
		"mixed":          []any{"Users", int64(1), nil, true},
		"n":              int64(2),
		"f":              1.5,
		"m":              map[string]any{"Users": true, "k": "x", "a": map[string]any{"b": "x"}},
		"s":              "x",
		"nul":            nil,
		"t":              true,
		"big":            int64(9007199254740993),
		"body.TableName": "Users",
		"m.k":            "y",
		"q.r":            map[string]any{"s": "Users"},
	},
	{
		"operation": "Query",
		"headers":   map[string]any{"x-a": int64(1), "x": "x"},
		"body":      map[string]any{"a": "x", "TableName": "Users", "k": nil},
		"s":         "x",
		"n":         int64(2),
		"m":         map[string]any{"k": "x"},
	},
}

var genAtoms = []string{
	"body", "body.a", "body.b", "body.k", "body.TableName", "body.a.b", "body.a.k", "body.Key.id",
	"body['a']", `body['\u0061']`, "body['']", `body['\ufffd']`, "body['é']", "body.a[0]", "body.a[1]", "body.a[n]",
	"ops", "allowed", "mixed", "n", "f", "m", "s", "nul", "t", "big", "m.k", "m.a", "m.a.b", "m['k']", "q.r", "q.r.s",
	"'Users'", "'x'", "''", `'\u00e9'`, `'\ufffd'`, `'U\u0073ers'`, "0", "1", "-1", "2", "9007199254740993",
	"true", "false", "null", "headers", "headers['x-a']", "headers.x", "operation", "service", "method",
	".body.a", ".s", ".operation",
}

// genExpr writes a random expression over the constructs the fast path covers. Many do not
// type-check; the caller skips those.
func genExpr(r *rand.Rand, depth int) string {
	if depth <= 0 || r.Intn(4) == 0 {
		return genAtoms[r.Intn(len(genAtoms))]
	}
	a := func() string { return genExpr(r, depth-1) }
	field := func() string { return []string{"a", "b", "k", "x", "s", "r", "TableName"}[r.Intn(7)] }
	// Iteration variables, some of which hide a vars key, a dotted vars prefix or a request
	// variable.
	iterVar := func() string {
		return []string{"v", "w", "v", "w", "m", "s", "q", "body", "operation", "headers"}[r.Intn(10)]
	}
	switch r.Intn(14) {
	case 11:
		if r.Intn(2) == 0 {
			return "(size(" + a() + ")" + []string{" == ", " != ", " > "}[r.Intn(3)] + "0)"
		}
		return "size(" + a() + ")"
	case 12:
		op := []string{" == ", " != ", " in ", " < "}[r.Intn(4)]
		v := iterVar()
		return "(" + a() + ").all(" + v + ", (" + v + op + a() + "))"
	case 13:
		v, w := iterVar(), iterVar()
		return "(" + a() + ").all(" + v + ", (" + a() + ").all(" + w + ", (" + v + " == " + w + ") || has(" + v + "." + field() + ")))"
	case 0:
		return "(" + a() + " == " + a() + ")"
	case 1:
		return "(" + a() + " != " + a() + ")"
	case 2:
		return "(" + a() + " " + []string{"<", "<=", ">", ">="}[r.Intn(4)] + " " + a() + ")"
	case 3:
		return "(" + a() + " in " + a() + ")"
	case 4:
		operand := []string{"body", "m", "headers", "body.a", "m.a", "q", "nul", "s", "body.a.b", "(" + a() + ")"}
		return "has(" + operand[r.Intn(len(operand))] + "." + field() + ")"
	case 5:
		return "!(" + a() + ")"
	case 6:
		return "(" + a() + " && " + a() + ")"
	case 7:
		return "(" + a() + " || " + a() + ")"
	case 8:
		return "(" + a() + " ? " + a() + " : " + a() + ")"
	case 9:
		return "(" + a() + ")[" + a() + "]"
	default:
		return "(" + a() + ")." + field()
	}
}

// genBodies are hand-written bodies for generated expressions: repeated and escaped keys, lone
// surrogates, nulls, numbers that equal an int or a double, and insignificant whitespace.
var genBodies = []string{
	`{}`, `{"a":"x"}`, `{"a":"x","a":1}`, `{"a":1,"a":"x"}`, `{"\u0061":"Users","a":"x"}`, `{"a":"x","\u0061":"Users"}`,
	`{"a":null}`, `{"a":true}`, `{"a":false,"b":true}`, `{"a":["x",true,null]}`, `{"a":[1,"x"]}`,
	`{"a":{"b":"x","k":"x"}}`, `{"a":{"b":null}}`, `{"a":"\ud800"}`, `{"\ud800":"x","a":"\ufffd"}`, `{"\ufffd":"x"}`,
	`{"é":"é","a":"\u00e9"}`, `{"TableName":"Users"}`, `{"TableName":"U\u0073ers"}`, `{"TableName":"Orders"}`,
	`{"a":"Users","b":"Users"}`, `{"a":"x","b":"x"}`, `{"a":"M","b":"m"}`, `{"a":"","b":""}`, `{"":"x","a":""}`,
	`{"Key":{"id":"x"}}`, `{"a":2,"b":2.0}`, `{"a":9007199254740993}`, `{"a":"a\"b","b":"a\\\"b"}`,
	`{"a":"\/","b":"/"}`, `{"a":{"a":{"b":"x"}},"b":["Users"]}`, `{"a":[["x"]],"b":{"x":1}}`, `{"k":"x"}`,
	` { "a" : [ "x" , true ] , "b" : "x" } `, `{"a":"\ud83d\ude00","b":"😀"}`, `{"a":"\uD800\u0041","b":"\ufffdA"}`,
	`{"a":"x","b":{"a":"x"}}`,
	`{"a":[false,1],"b":[1,false]}`, `{"a":["x",false,"y"],"b":[true,"x"]}`, `{"a":[[],{},"x"],"b":[{"a":"x"},{"a":1},{}]}`,
	`{"a":{"x":"x","x":1},"b":{"\u0078":true,"x":false}}`, `{"a":[[[]],[{"b":[{}]}],{"k":[[],"x"]}],"b":[]}`,
}

// checkGenerated compiles expr with each vars set and checks every fast answer against cel-go.
// It returns how many expressions have a fast form and how many answers the fast path gave.
func checkGenerated(t *testing.T, expr string, bodies []string) (fast, answered int) {
	t.Helper()
	for _, vars := range genVarSets {
		p, err := compilePredicates(predConfig(predEntry(expr, vars)))
		if err != nil {
			continue
		}
		pred := p.list[0]
		if pred.fast == nil {
			continue
		}
		fast++
		for _, body := range bodies {
			for _, h := range []map[string]string{{}, {"x-a": "Users", "x": "x"}} {
				r := predReq("GetItem", "dynamodb", body)
				r.headers = h
				if checkFast(t, pred, expr, r) {
					answered++
				}
			}
		}
	}
	return fast, answered
}

func TestFastPathAgreesWithCELOnGeneratedExpressions(t *testing.T) {
	r := rand.New(rand.NewSource(3))
	fast, answered := 0, 0
	for range 3000 {
		f, a := checkGenerated(t, genExpr(r, 4), genBodies)
		fast, answered = fast+f, answered+a
	}
	if fast < 1000 || answered < 10000 {
		t.Fatalf("only %d expressions have a fast form and %d answers", fast, answered)
	}
}

func FuzzFastPathExpressions(f *testing.F) {
	for i := range 20 {
		f.Add(int64(i), genBodies[i%len(genBodies)])
	}
	f.Fuzz(func(t *testing.T, seed int64, body string) {
		r := rand.New(rand.NewSource(seed))
		checkGenerated(t, genExpr(r, 4), []string{body})
	})
}

func TestFastPathDoesNotAllocate(t *testing.T) {
	pred := compileProbe(t, "operation in ops && has(body.TableName) && body.TableName in allowed")
	pr := predicateRequest{req: predReq("GetItem", "dynamodb", `{"TableName":"Users"}`)}
	defer pr.release()
	pr.loadBody()
	if n := testing.AllocsPerRun(100, func() {
		if pred.fast.eval(&pr) != fastTrue {
			t.Fatal("no match")
		}
	}); n != 0 {
		t.Fatalf("%v allocations per evaluation", n)
	}
}

// SDK-shaped bodies for the seven expressions, routed or not.
var sevenBodies = []struct{ op, body string }{
	{"GetItem", `{"TableName":"Users","Key":{"id":{"S":"1"}}}`},
	{"GetItem", `{"TableName":"Secrets","Key":{"id":{"S":"1"}}}`},
	{"GetItem", `{"Key":{"id":{"S":"1"}}}`},
	{"PutItem", `{"TableName":"Users","Item":{"id":{"S":"1"},"n":{"N":"5"}}}`},
	{"BatchGetItem", `{"RequestItems":{"Users":{"Keys":[{"id":{"S":"1"}}]},"Sessions":{"Keys":[]}}}`},
	{"BatchGetItem", `{"RequestItems":{"Users":{"Keys":[]},"Secrets":{"Keys":[]}}}`},
	{"BatchWriteItem", `{"RequestItems":{}}`},
	{"TransactWriteItems", `{"TransactItems":[{"Put":{"TableName":"Users","Item":{}}},{"Delete":{"TableName":"Sessions","Key":{}}}]}`},
	{"TransactWriteItems", `{"TransactItems":[{"Put":{"TableName":"Users","Item":{}}},{"Update":{"TableName":"Secrets","Key":{}}}]}`},
	{"TransactWriteItems", `{"TransactItems":[{"ConditionCheck":{"Key":{}}}]}`},
	{"TransactGetItems", `{"TransactItems":[]}`},
	{"Scan", `{}`},
}

func TestSevenExpressionsGoFast(t *testing.T) {
	vars := map[string]any{"ops": allOperations, "allowed": []string{"Users", "Sessions"}, "excluded": []string{"Secrets"}}
	cases := append([]struct{ op, body string }{}, sevenBodies...)
	for _, c := range predBenchCases() {
		cases = append(cases, struct{ op, body string }{c.operation, string(c.body)})
	}
	for _, expr := range []string{exprAllOps, exprTableName, exprRequestItems, exprTransactItems,
		exprTransactItemsProxy, exprTableNameDeny, exprRequestItemsDeny, exprTransactItemsDeny} {
		pred := mustCompilePredicates(t, predConfig(predEntry(expr, vars))).list[0]
		if pred.fast == nil {
			t.Fatalf("%s has no fast form", expr)
		}
		for _, c := range cases {
			if !checkFast(t, pred, expr, predReq(c.op, "dynamodb", c.body)) {
				t.Errorf("%s defers on %s %.80s", expr, c.op, c.body)
			}
		}
	}
}

func TestSizeZeroDoesNotCount(t *testing.T) {
	for _, expr := range []string{"size(body.a) > 0", "size(body.a) != 0", "size(body.a) == 0"} {
		if _, ok := compileProbe(t, expr).fast.root.(emptyNode); !ok {
			t.Errorf("%s does not compile to an emptiness test", expr)
		}
	}
	if _, ok := compileProbe(t, "size(body.a) >= 0").fast.root.(emptyNode); ok {
		t.Error("size(body.a) >= 0 compiles to an emptiness test")
	}
}

// TestRouteAllocations: routing a benchmark case allocates only the predicateRequest.
func TestRouteAllocations(t *testing.T) {
	if raceEnabled {
		t.Skip("the race detector drops sync.Pool entries")
	}
	for _, c := range predBenchCases() {
		p, r := c.compile(t), c.request()
		if n := testing.AllocsPerRun(20, func() {
			if !p.shouldRoute(r) {
				t.Fatal("the case does not route")
			}
		}); n > 1 {
			t.Errorf("%s: %v allocations per route", c.name, n)
		}
	}
}

// TestComprehensionScope: an iteration variable hides a vars key and a request variable, and an
// inner variable hides an outer one of the same name.
func TestComprehensionScope(t *testing.T) {
	vars := map[string]any{"ops": []any{"GetItem", "Query"}, "s": "outer",
		"m": map[string]any{"k": "z"}, "allowed": []any{"x"}}
	cases := []struct {
		expr, body string
		want       bool
	}{
		{"ops.all(s, s != 'outer')", `{}`, true},
		{"ops.all(s, s == 'outer')", `{}`, false},
		{"ops.all(m, m in ops)", `{}`, true},
		{"ops.all(operation, operation != 'PutItem')", `{}`, true},
		{"body.a.all(body, body == 'x')", `{"a":["x"]}`, true},
		{"body.a.all(headers, headers in allowed)", `{"a":["x","y"]}`, false},
		{"body.a.all(s, body.b.all(s, s == 'y'))", `{"a":["x"],"b":["y"]}`, true},
		{"body.a.all(s, body.b.all(s, s == 'x'))", `{"a":["x"],"b":["y"]}`, false},
		{"body.a.all(s, body.b.all(w, s == 'x') && s == 'x')", `{"a":["x"],"b":["y"]}`, true},
	}
	for _, c := range cases {
		pred := mustCompilePredicates(t, predConfig(predEntry(c.expr, vars))).list[0]
		if pred.fast == nil {
			t.Fatalf("%s has no fast form", c.expr)
		}
		r := predReq("GetItem", "dynamodb", c.body)
		if !checkFast(t, pred, c.expr, r) {
			t.Fatalf("%s defers on %s", c.expr, c.body)
		}
		pr := predicateRequest{req: r}
		pr.loadBody()
		if got := pred.fast.eval(&pr) == fastTrue; got != c.want {
			t.Errorf("%s on %s: %v, want %v", c.expr, c.body, got, c.want)
		}
		pr.release()
	}
}
