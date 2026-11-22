// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"github.com/google/cel-go/common/types"

	celast "github.com/google/cel-go/common/ast"
)

// The fast path evaluates the common predicate expressions over the scanned body without
// building it as Go values. Every node returns a value or fkDefer. It defers wherever cel-go
// would raise an error or apply a rule the fast path does not model; a defer reaches the
// top, and cel-go evaluates that predicate instead. So when the fast path answers, it read
// the same operands as cel-go and gives the same answer.

type fastResult uint8

const (
	fastDefer fastResult = iota
	fastFalse
	fastTrue
)

type fastKind uint8

const (
	fkDefer fastKind = iota
	fkNull
	fkBool    // n is 0 or 1
	fkInt     // n
	fkString  // s
	fkList    // ref is []any
	fkMap     // ref is map[string]any
	fkHeaders // ref is map[string]string
	fkJSONObj // n is the offset of an object in the body
	fkJSONArr // n is the offset of an array in the body
	fkJSONStr // n is the offset of a string in the body
)

// fastValue is one value during evaluation.
type fastValue struct {
	kind fastKind
	n    int64
	s    string
	ref  any
}

var deferred = fastValue{}

func boolValue(b bool) fastValue {
	if b {
		return fastValue{kind: fkBool, n: 1}
	}
	return fastValue{kind: fkBool}
}

// nativeValue wraps a vars value. v keeps its interface value, so a list is not boxed again.
func nativeValue(v any) fastValue {
	switch x := v.(type) {
	case string:
		return fastValue{kind: fkString, s: x}
	case bool:
		return boolValue(x)
	case int64:
		return fastValue{kind: fkInt, n: x}
	case nil:
		return fastValue{kind: fkNull}
	case []any:
		return fastValue{kind: fkList, ref: v}
	case map[string]any:
		return fastValue{kind: fkMap, ref: v}
	}
	return deferred // float64: the fast path does not compare doubles
}

// jsonValue wraps the body value at pos. A number defers: the fast path does not model it.
func jsonValue(d *jsonDoc, pos int) fastValue {
	switch d.b[pos] {
	case '{':
		return fastValue{kind: fkJSONObj, n: int64(pos)}
	case '[':
		return fastValue{kind: fkJSONArr, n: int64(pos)}
	case '"':
		return fastValue{kind: fkJSONStr, n: int64(pos)}
	case 't':
		return boolValue(true)
	case 'f':
		return boolValue(false)
	case 'n':
		return fastValue{kind: fkNull}
	}
	return deferred
}

// stringOf returns the text of a string value.
func stringOf(pr *predicateRequest, v fastValue) (string, bool) {
	switch v.kind {
	case fkString:
		return v.s, true
	case fkJSONStr:
		return decodeString(rawString(pr.doc, v))
	}
	return "", false
}

func rawString(d *jsonDoc, v fastValue) []byte {
	return d.b[v.n:d.end(int(v.n))]
}

// equal compares two values of the same CEL type; any other pair defers.
func equal(pr *predicateRequest, a, b fastValue) fastValue {
	switch {
	case a.kind == fkDefer || b.kind == fkDefer:
		return deferred
	case a.kind == fkString && b.kind == fkJSONStr:
		return boolValue(equalString(rawString(pr.doc, b), a.s))
	case a.kind == fkJSONStr && b.kind == fkString:
		return boolValue(equalString(rawString(pr.doc, a), b.s))
	case isString(a.kind) && isString(b.kind):
		x, ok1 := stringOf(pr, a)
		y, ok2 := stringOf(pr, b)
		if !ok1 || !ok2 {
			return deferred
		}
		return boolValue(x == y)
	case a.kind != b.kind:
		return deferred
	case a.kind == fkInt || a.kind == fkBool:
		return boolValue(a.n == b.n)
	case a.kind == fkNull:
		return boolValue(true)
	}
	return deferred
}

func isString(k fastKind) bool { return k == fkString || k == fkJSONStr }

// lookup returns the value of key in a map, headers or JSON object; ok is false when the key
// is absent. A JSON object keeps the last value of a repeated key, as the decoded view does.
func lookup(pr *predicateRequest, c fastValue, key string) (v fastValue, ok bool) {
	switch c.kind {
	case fkMap:
		x, ok := c.ref.(map[string]any)[key]
		if !ok {
			return deferred, false
		}
		return nativeValue(x), true
	case fkHeaders:
		x, ok := c.ref.(map[string]string)[key]
		if !ok {
			return deferred, false
		}
		return fastValue{kind: fkString, s: x}, true
	case fkJSONObj:
		it := pr.doc.iter(int(c.n))
		for k, val, more := it.next(); more; k, val, more = it.next() {
			if equalString(k, key) {
				v, ok = jsonValue(pr.doc, val), true
			}
		}
		return v, ok
	}
	return deferred, false
}

func isMap(k fastKind) bool { return k == fkMap || k == fkHeaders || k == fkJSONObj }

type fastNode interface {
	eval(pr *predicateRequest, locals []fastValue) fastValue
}

type constNode struct{ v fastValue }

func (n constNode) eval(*predicateRequest, []fastValue) fastValue { return n.v }

type localNode struct{ slot int }

func (n localNode) eval(_ *predicateRequest, locals []fastValue) fastValue { return locals[n.slot] }

type requestNode struct{ name string }

func (n requestNode) eval(pr *predicateRequest, _ []fastValue) fastValue {
	r := pr.req
	switch n.name {
	case "service":
		return fastValue{kind: fkString, s: r.metadata.service}
	case "operation":
		return fastValue{kind: fkString, s: r.metadata.operation}
	case "method":
		return fastValue{kind: fkString, s: r.method}
	case "url":
		return fastValue{kind: fkString, s: r.url}
	case "headers":
		return fastValue{kind: fkHeaders, ref: pr.headers()}
	case "body":
		if pr.loadBody() {
			return jsonValue(pr.doc, pr.doc.root)
		}
	}
	return deferred
}

type selectNode struct {
	operand fastNode
	field   string
	test    bool // has(operand.field)
}

func (n selectNode) eval(pr *predicateRequest, locals []fastValue) fastValue {
	c := n.operand.eval(pr, locals)
	if !isMap(c.kind) {
		return deferred
	}
	v, ok := lookup(pr, c, n.field)
	if n.test {
		return boolValue(ok)
	}
	return v // a missing key is a cel-go error: deferred
}

type indexNode struct{ container, key fastNode }

func (n indexNode) eval(pr *predicateRequest, locals []fastValue) fastValue {
	c := n.container.eval(pr, locals)
	k := n.key.eval(pr, locals)
	switch {
	case isMap(c.kind) && isString(k.kind):
		key, ok := stringOf(pr, k)
		if !ok {
			return deferred
		}
		v, _ := lookup(pr, c, key)
		return v
	case c.kind == fkList && k.kind == fkInt:
		list := c.ref.([]any)
		if k.n < 0 || k.n >= int64(len(list)) {
			return deferred
		}
		return nativeValue(list[k.n])
	case c.kind == fkJSONArr && k.kind == fkInt:
		it := pr.doc.iter(int(c.n))
		for i := int64(0); ; i++ {
			_, val, more := it.next()
			if !more || k.n < 0 {
				return deferred
			}
			if i == k.n {
				return jsonValue(pr.doc, val)
			}
		}
	}
	return deferred
}

type andNode struct{ left, right fastNode }

func (n andNode) eval(pr *predicateRequest, locals []fastValue) fastValue {
	l := n.left.eval(pr, locals)
	if l.kind != fkBool {
		return deferred
	}
	if l.n == 0 {
		return l
	}
	if r := n.right.eval(pr, locals); r.kind == fkBool {
		return r
	}
	return deferred
}

type orNode struct{ left, right fastNode }

func (n orNode) eval(pr *predicateRequest, locals []fastValue) fastValue {
	l := n.left.eval(pr, locals)
	if l.kind != fkBool {
		return deferred
	}
	if l.n == 1 {
		return l
	}
	if r := n.right.eval(pr, locals); r.kind == fkBool {
		return r
	}
	return deferred
}

type notNode struct{ operand fastNode }

func (n notNode) eval(pr *predicateRequest, locals []fastValue) fastValue {
	if v := n.operand.eval(pr, locals); v.kind == fkBool {
		return boolValue(v.n == 0)
	}
	return deferred
}

type condNode struct{ cond, then, otherwise fastNode }

func (n condNode) eval(pr *predicateRequest, locals []fastValue) fastValue {
	c := n.cond.eval(pr, locals)
	switch {
	case c.kind != fkBool:
		return deferred
	case c.n == 1:
		return n.then.eval(pr, locals)
	default:
		return n.otherwise.eval(pr, locals)
	}
}

type equalNode struct {
	left, right fastNode
	negate      bool
}

func (n equalNode) eval(pr *predicateRequest, locals []fastValue) fastValue {
	v := equal(pr, n.left.eval(pr, locals), n.right.eval(pr, locals))
	if n.negate && v.kind == fkBool {
		return boolValue(v.n == 0)
	}
	return v
}

type compareNode struct {
	left, right fastNode
	op          string // _<_, _<=_, _>_, _>=_
}

func (n compareNode) eval(pr *predicateRequest, locals []fastValue) fastValue {
	a, b := n.left.eval(pr, locals), n.right.eval(pr, locals)
	var c int
	switch {
	case a.kind == fkInt && b.kind == fkInt:
		c = cmpInt(a.n, b.n)
	case isString(a.kind) && isString(b.kind):
		x, ok1 := stringOf(pr, a)
		y, ok2 := stringOf(pr, b)
		if !ok1 || !ok2 {
			return deferred
		}
		c = cmpString(x, y)
	default:
		return deferred
	}
	switch n.op {
	case "_<_":
		return boolValue(c < 0)
	case "_<=_":
		return boolValue(c <= 0)
	case "_>_":
		return boolValue(c > 0)
	default:
		return boolValue(c >= 0)
	}
}

func cmpInt(a, b int64) int {
	switch {
	case a < b:
		return -1
	case a > b:
		return 1
	}
	return 0
}

// cmpString orders by code point, which is the byte order of UTF-8.
func cmpString(a, b string) int {
	switch {
	case a < b:
		return -1
	case a > b:
		return 1
	}
	return 0
}

type inNode struct{ needle, container fastNode }

func (n inNode) eval(pr *predicateRequest, locals []fastValue) fastValue {
	x := n.needle.eval(pr, locals)
	c := n.container.eval(pr, locals)
	if x.kind == fkDefer {
		return deferred
	}
	switch c.kind {
	case fkList:
		for _, el := range c.ref.([]any) {
			if eq := equal(pr, x, nativeValue(el)); eq.kind != fkBool || eq.n == 1 {
				return eq
			}
		}
		return boolValue(false)
	case fkJSONArr:
		it := pr.doc.iter(int(c.n))
		for _, val, more := it.next(); more; _, val, more = it.next() {
			if eq := equal(pr, x, jsonValue(pr.doc, val)); eq.kind != fkBool || eq.n == 1 {
				return eq
			}
		}
		return boolValue(false)
	case fkMap, fkHeaders, fkJSONObj:
		key, ok := stringOf(pr, x)
		if !ok {
			return deferred
		}
		_, found := lookup(pr, c, key)
		return boolValue(found)
	}
	return deferred
}

// fastPredicate is the fast form of one predicate. Immutable.
type fastPredicate struct {
	root  fastNode
	slots int // comprehension variables
}

func (f *fastPredicate) eval(pr *predicateRequest) fastResult {
	if cap(pr.locals) < f.slots {
		pr.locals = make([]fastValue, f.slots)
	}
	switch v := f.root.eval(pr, pr.locals[:f.slots]); {
	case v.kind == fkDefer:
		return fastDefer
	case v.kind == fkBool && v.n == 1:
		return fastTrue
	default:
		return fastFalse // a non-bool result is no match, as in cel-go
	}
}

// fastCompiler turns a checked AST into fast nodes.
type fastCompiler struct {
	refs  map[int64]*celast.ReferenceInfo
	vars  map[string]any
	scope []string // comprehension variables in scope; the index is the slot
	slots int
}

// compileFast returns nil when the expression uses anything the fast path does not cover.
func compileFast(a *celast.AST, vars map[string]any) *fastPredicate {
	c := fastCompiler{refs: a.ReferenceMap(), vars: vars}
	root, ok := c.compile(a.Expr())
	if !ok {
		return nil
	}
	return &fastPredicate{root: root, slots: c.slots}
}

func (c *fastCompiler) compile(e celast.Expr) (fastNode, bool) {
	switch e.Kind() {
	case celast.LiteralKind:
		switch v := e.AsLiteral().(type) {
		case types.String:
			return constNode{fastValue{kind: fkString, s: string(v)}}, true
		case types.Int:
			return constNode{fastValue{kind: fkInt, n: int64(v)}}, true
		case types.Bool:
			return constNode{boolValue(bool(v))}, true
		case types.Null:
			return constNode{fastValue{kind: fkNull}}, true
		}
	case celast.IdentKind:
		return c.ident(e)
	case celast.SelectKind:
		s := e.AsSelect()
		operand, ok := c.compile(s.Operand())
		return selectNode{operand: operand, field: s.FieldName(), test: s.IsTestOnly()}, ok
	case celast.CallKind:
		return c.call(e.AsCall())
	case celast.ComprehensionKind:
		return c.comprehension(e.AsComprehension())
	}
	return nil, false
}

func (c *fastCompiler) ident(e celast.Expr) (fastNode, bool) {
	name := e.AsIdent()
	for slot := len(c.scope) - 1; slot >= 0; slot-- {
		if c.scope[slot] == name {
			return localNode{slot: slot}, true
		}
	}
	if ref, ok := c.refs[e.ID()]; ok && ref.Value != nil {
		return nil, false // a constant such as an enum value
	}
	if v, ok := c.vars[name]; ok {
		return constNode{nativeValue(v)}, true
	}
	for _, rv := range requestVariables {
		if rv.name == name {
			return requestNode{name: name}, true
		}
	}
	return nil, false
}

func (c *fastCompiler) call(call celast.CallExpr) (fastNode, bool) {
	args := call.Args()
	nodes := make([]fastNode, len(args))
	for i, a := range args {
		n, ok := c.compile(a)
		if !ok {
			return nil, false
		}
		nodes[i] = n
	}
	if call.IsMemberFunction() {
		return c.member(call, nodes)
	}
	switch fn := call.FunctionName(); {
	case len(nodes) == 2 && fn == "_&&_":
		return andNode{nodes[0], nodes[1]}, true
	case len(nodes) == 2 && fn == "_||_":
		return orNode{nodes[0], nodes[1]}, true
	case len(nodes) == 1 && fn == "!_":
		return notNode{nodes[0]}, true
	case len(nodes) == 3 && fn == "_?_:_":
		return condNode{nodes[0], nodes[1], nodes[2]}, true
	case len(nodes) == 2 && (fn == "_==_" || fn == "_!=_"):
		return equalNode{nodes[0], nodes[1], fn == "_!=_"}, true
	case len(nodes) == 2 && (fn == "_<_" || fn == "_<=_" || fn == "_>_" || fn == "_>=_"):
		return compareNode{nodes[0], nodes[1], fn}, true
	case len(nodes) == 2 && fn == "@in":
		return inNode{nodes[0], nodes[1]}, true
	case len(nodes) == 2 && fn == "_[_]":
		return indexNode{nodes[0], nodes[1]}, true
	}
	return c.global(call.FunctionName(), nodes)
}

// global compiles the global functions the fast path covers beyond the operators.
func (c *fastCompiler) global(string, []fastNode) (fastNode, bool) { return nil, false }

// member compiles the member functions the fast path covers.
func (c *fastCompiler) member(celast.CallExpr, []fastNode) (fastNode, bool) { return nil, false }

// comprehension compiles the macros the fast path covers.
func (c *fastCompiler) comprehension(celast.ComprehensionExpr) (fastNode, bool) { return nil, false }
