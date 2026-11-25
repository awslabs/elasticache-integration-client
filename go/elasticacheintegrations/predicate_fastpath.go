// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"bytes"
	"slices"
	"unicode/utf8"

	celast "github.com/google/cel-go/common/ast"
	"github.com/google/cel-go/common/types"
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
	fkJSONObj // an object in the body; n holds its start and end offsets
	fkJSONArr // an array in the body, as fkJSONObj
	fkJSONStr // a string in the body, with its quotes, as fkJSONObj
)

// fastValue is one value during evaluation.
type fastValue struct {
	kind fastKind
	ci   int32 // fkJSONObj, fkJSONArr: the container index in the scanned body
	n    int64
	s    string
	ref  any
}

// fastDeferred is the zero fastValue (fkDefer): the fast path cannot answer. Never modified.
var fastDeferred = fastValue{}

func jsonSpan(kind fastKind, start, end int) fastValue {
	return fastValue{kind: kind, n: int64(start) | int64(end)<<32}
}

func (v fastValue) start() int { return int(uint32(v.n)) }
func (v fastValue) end() int   { return int(v.n >> 32) }

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
	return fastDeferred // float64: the fast path does not compare doubles
}

// jsonValue wraps the body value at b[pos:end]. A number defers: the fast path does not
// model it.
func jsonValue(d *jsonDoc, pos, end, ci int) fastValue {
	switch d.b[pos] {
	case '{':
		v := jsonSpan(fkJSONObj, pos, end)
		v.ci = int32(ci)
		return v
	case '[':
		v := jsonSpan(fkJSONArr, pos, end)
		v.ci = int32(ci)
		return v
	case '"':
		return jsonSpan(fkJSONStr, pos, end)
	case 't':
		return boolValue(true)
	case 'f':
		return boolValue(false)
	case 'n':
		return fastValue{kind: fkNull}
	}
	return fastDeferred
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
	return d.b[v.start():v.end()]
}

// fastEqual compares two values of the same CEL type; any other pair defers.
func fastEqual(pr *predicateRequest, a, b fastValue) fastValue {
	switch {
	case a.kind == fkDefer || b.kind == fkDefer:
		return fastDeferred
	case a.kind == fkString && b.kind == fkJSONStr:
		return boolValue(equalString(rawString(pr.doc, b), a.s))
	case a.kind == fkJSONStr && b.kind == fkString:
		return boolValue(equalString(rawString(pr.doc, a), b.s))
	case isString(a.kind) && isString(b.kind):
		x, ok1 := stringOf(pr, a)
		y, ok2 := stringOf(pr, b)
		if !ok1 || !ok2 {
			return fastDeferred
		}
		return boolValue(x == y)
	case a.kind != b.kind:
		return fastDeferred
	case a.kind == fkInt || a.kind == fkBool:
		return boolValue(a.n == b.n)
	case a.kind == fkNull:
		return boolValue(true)
	}
	return fastDeferred
}

func isString(k fastKind) bool { return k == fkString || k == fkJSONStr }

// fastLookup returns the value of key in a map, headers or JSON object; ok is false when the key
// is absent. A JSON object keeps the last value of a repeated key, as the decoded view does.
func fastLookup(pr *predicateRequest, c fastValue, key string) (v fastValue, ok bool) {
	if c.kind != fkJSONObj {
		return lookupKey(pr, c, fastValue{kind: fkString, s: key})
	}
	it := pr.doc.iterAt(c.start(), int(c.ci))
	for k, val, more := it.next(); more; k, val, more = it.next() {
		if equalString(k, key) {
			v, ok = jsonValue(pr.doc, val, it.valEnd, it.valCI), true
		}
	}
	return v, ok
}

// lookupKey is fastLookup with a string value as the key, so a key read from the body is not
// copied.
func lookupKey(pr *predicateRequest, c, key fastValue) (fastValue, bool) {
	switch c.kind {
	case fkMap, fkHeaders:
		if key.kind != fkJSONStr {
			return mapEntry(c, key.s)
		}
		raw := rawString(pr.doc, key)
		if bytes.IndexByte(raw, '\\') < 0 {
			return mapEntryBytes(c, raw[1:len(raw)-1])
		}
		k, ok := decodeString(raw)
		if !ok {
			return fastDeferred, false
		}
		return mapEntry(c, k)
	case fkJSONObj:
		var v fastValue
		found := false
		it := pr.doc.iterAt(c.start(), int(c.ci))
		for k, val, more := it.next(); more; k, val, more = it.next() {
			if keyEquals(pr, k, key) {
				v, found = jsonValue(pr.doc, val, it.valEnd, it.valCI), true
			}
		}
		return v, found
	}
	return fastDeferred, false
}

// mapEntry looks k up in a map or headers value.
func mapEntry(c fastValue, k string) (fastValue, bool) {
	if c.kind == fkMap {
		x, ok := c.ref.(map[string]any)[k]
		return valueIf(nativeValue(x), ok)
	}
	h, ok := c.ref.(map[string]string)[k]
	return valueIf(fastValue{kind: fkString, s: h}, ok)
}

// mapEntryBytes is mapEntry for a key in the body, which the lookup does not copy.
func mapEntryBytes(c fastValue, k []byte) (fastValue, bool) {
	if c.kind == fkMap {
		x, ok := c.ref.(map[string]any)[string(k)]
		return valueIf(nativeValue(x), ok)
	}
	h, ok := c.ref.(map[string]string)[string(k)]
	return valueIf(fastValue{kind: fkString, s: h}, ok)
}

func valueIf(v fastValue, ok bool) (fastValue, bool) {
	if !ok {
		return fastDeferred, false
	}
	return v, true
}

// keyEquals reports whether a raw JSON key equals a string value.
func keyEquals(pr *predicateRequest, raw []byte, key fastValue) bool {
	if key.kind == fkString {
		return equalString(raw, key.s)
	}
	other := rawString(pr.doc, key)
	if bytes.Equal(raw, other) {
		return true // the same text decodes the same
	}
	if bytes.IndexByte(raw, '\\') < 0 && bytes.IndexByte(other, '\\') < 0 {
		return false
	}
	a, ok1 := decodeString(raw)
	b, ok2 := decodeString(other)
	return ok1 && ok2 && a == b
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
			return jsonValue(pr.doc, pr.doc.root, int(pr.doc.ends[0]), 0)
		}
	}
	return fastDeferred
}

type selectNode struct {
	operand fastNode
	field   string
	test    bool // has(operand.field)
}

func (n selectNode) eval(pr *predicateRequest, locals []fastValue) fastValue {
	c := n.operand.eval(pr, locals)
	if !isMap(c.kind) {
		return fastDeferred
	}
	v, ok := fastLookup(pr, c, n.field)
	if n.test {
		return boolValue(ok)
	}
	return v // a missing key is a cel-go error: fastDeferred
}

type indexNode struct{ container, key fastNode }

func (n indexNode) eval(pr *predicateRequest, locals []fastValue) fastValue {
	c := n.container.eval(pr, locals)
	k := n.key.eval(pr, locals)
	switch {
	case isMap(c.kind) && isString(k.kind):
		v, _ := lookupKey(pr, c, k)
		return v
	case c.kind == fkList && k.kind == fkInt:
		list := c.ref.([]any)
		if k.n < 0 || k.n >= int64(len(list)) {
			return fastDeferred
		}
		return nativeValue(list[k.n])
	case c.kind == fkJSONArr && k.kind == fkInt:
		it := pr.doc.iterAt(c.start(), int(c.ci))
		for i := int64(0); ; i++ {
			_, val, more := it.next()
			if !more || k.n < 0 {
				return fastDeferred
			}
			if i == k.n {
				return jsonValue(pr.doc, val, it.valEnd, it.valCI)
			}
		}
	}
	return fastDeferred
}

type andNode struct{ left, right fastNode }

func (n andNode) eval(pr *predicateRequest, locals []fastValue) fastValue {
	l := n.left.eval(pr, locals)
	if l.kind != fkBool {
		return fastDeferred
	}
	if l.n == 0 {
		return l
	}
	if r := n.right.eval(pr, locals); r.kind == fkBool {
		return r
	}
	return fastDeferred
}

type orNode struct{ left, right fastNode }

func (n orNode) eval(pr *predicateRequest, locals []fastValue) fastValue {
	l := n.left.eval(pr, locals)
	if l.kind != fkBool {
		return fastDeferred
	}
	if l.n == 1 {
		return l
	}
	if r := n.right.eval(pr, locals); r.kind == fkBool {
		return r
	}
	return fastDeferred
}

type notNode struct{ operand fastNode }

func (n notNode) eval(pr *predicateRequest, locals []fastValue) fastValue {
	if v := n.operand.eval(pr, locals); v.kind == fkBool {
		return boolValue(v.n == 0)
	}
	return fastDeferred
}

type condNode struct{ cond, then, otherwise fastNode }

func (n condNode) eval(pr *predicateRequest, locals []fastValue) fastValue {
	c := n.cond.eval(pr, locals)
	switch {
	case c.kind != fkBool:
		return fastDeferred
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
	v := fastEqual(pr, n.left.eval(pr, locals), n.right.eval(pr, locals))
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
			return fastDeferred
		}
		c = cmpString(x, y)
	default:
		return fastDeferred
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
		return fastDeferred
	}
	switch c.kind {
	case fkList:
		for _, el := range c.ref.([]any) {
			if eq := fastEqual(pr, x, nativeValue(el)); eq.kind != fkBool || eq.n == 1 {
				return eq
			}
		}
		return boolValue(false)
	case fkJSONArr:
		it := pr.doc.iterAt(c.start(), int(c.ci))
		for _, val, more := it.next(); more; _, val, more = it.next() {
			if eq := fastEqual(pr, x, jsonValue(pr.doc, val, it.valEnd, it.valCI)); eq.kind != fkBool || eq.n == 1 {
				return eq
			}
		}
		return boolValue(false)
	case fkMap, fkHeaders, fkJSONObj:
		if !isString(x.kind) {
			return fastDeferred
		}
		_, found := lookupKey(pr, c, x)
		return boolValue(found)
	}
	return fastDeferred
}

// setInNode is x in a constant list of strings, answered from a set.
type setInNode struct {
	needle  fastNode
	set     map[string]struct{}
	general inNode
}

func (n setInNode) eval(pr *predicateRequest, locals []fastValue) fastValue {
	x := n.needle.eval(pr, locals)
	var found bool
	switch x.kind {
	case fkString:
		_, found = n.set[x.s]
	case fkJSONStr:
		raw := rawString(pr.doc, x)
		if bytes.IndexByte(raw, '\\') >= 0 {
			s, ok := decodeString(raw)
			if !ok {
				return fastDeferred
			}
			_, found = n.set[s]
		} else {
			_, found = n.set[string(raw[1:len(raw)-1])]
		}
	default:
		return n.general.eval(pr, locals) // the element by element rules
	}
	return boolValue(found)
}

// stringSet returns the set of a constant list whose elements are all strings.
func stringSet(n fastNode) (map[string]struct{}, bool) {
	c, ok := n.(constNode)
	if !ok || c.v.kind != fkList {
		return nil, false
	}
	list := c.v.ref.([]any)
	set := make(map[string]struct{}, len(list))
	for _, el := range list {
		s, ok := el.(string)
		if !ok {
			return nil, false
		}
		set[s] = struct{}{}
	}
	return set, true
}

// fastPredicate is the fast form of one predicate. Immutable.
type fastPredicate struct {
	root  fastNode
	slots int // comprehension variables
}

func (f *fastPredicate) eval(pr *predicateRequest) fastResult {
	if cap(pr.locals) < f.slots {
		if pr.locals = pr.localBuf[:]; f.slots > len(pr.localBuf) {
			pr.locals = make([]fastValue, f.slots)
		}
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
	case len(nodes) == 2 && (fn == "_==_" || fn == "_!=_" || fn == "_>_") && isSizeZero(nodes):
		return emptyNode{operand: nodes[0].(sizeNode).operand, empty: fn == "_==_"}, true
	case len(nodes) == 2 && (fn == "_==_" || fn == "_!=_"):
		return equalNode{left: nodes[0], right: nodes[1], negate: fn == "_!=_"}, true
	case len(nodes) == 2 && (fn == "_<_" || fn == "_<=_" || fn == "_>_" || fn == "_>=_"):
		return compareNode{left: nodes[0], right: nodes[1], op: fn}, true
	case len(nodes) == 2 && fn == "@in":
		general := inNode{nodes[0], nodes[1]}
		if set, ok := stringSet(nodes[1]); ok {
			return setInNode{needle: nodes[0], set: set, general: general}, true
		}
		return general, true
	case len(nodes) == 2 && fn == "_[_]":
		return indexNode{nodes[0], nodes[1]}, true
	}
	return c.global(call.FunctionName(), nodes)
}

// global compiles the global functions the fast path covers beyond the operators.
func (c *fastCompiler) global(fn string, args []fastNode) (fastNode, bool) {
	if fn == "size" && len(args) == 1 {
		return sizeNode{args[0]}, true
	}
	return nil, false
}

// member compiles the member functions the fast path covers.
func (c *fastCompiler) member(call celast.CallExpr, args []fastNode) (fastNode, bool) {
	if call.FunctionName() != "size" || len(args) != 0 {
		return nil, false
	}
	target, ok := c.compile(call.Target())
	return sizeNode{target}, ok
}

// comprehension compiles the all() macro, which cel-go expands into a comprehension with the
// accumulator initialised to true, loop condition @not_strictly_false(@result), step
// @result && p and result @result. Every other comprehension has no fast form.
func (c *fastCompiler) comprehension(comp celast.ComprehensionExpr) (fastNode, bool) {
	accu := comp.AccuVar()
	if comp.IterVar2() != "" || !isIdent(comp.Result(), accu) {
		return nil, false
	}
	if init := comp.AccuInit(); init.Kind() != celast.LiteralKind || init.AsLiteral() != types.True {
		return nil, false
	}
	cond, step := comp.LoopCondition(), comp.LoopStep()
	if cond.Kind() != celast.CallKind || step.Kind() != celast.CallKind {
		return nil, false
	}
	cc, sc := cond.AsCall(), step.AsCall()
	if cc.FunctionName() != "@not_strictly_false" || len(cc.Args()) != 1 || !isIdent(cc.Args()[0], accu) ||
		sc.FunctionName() != "_&&_" || len(sc.Args()) != 2 || !isIdent(sc.Args()[0], accu) {
		return nil, false
	}
	rng, ok := c.compile(comp.IterRange())
	if !ok {
		return nil, false
	}
	slot := len(c.scope)
	c.scope = append(c.scope, comp.IterVar())
	c.slots = max(c.slots, slot+1)
	pred, ok := c.compile(sc.Args()[1])
	c.scope = c.scope[:slot]
	return allNode{rng: rng, slot: slot, pred: pred}, ok
}

func isIdent(e celast.Expr, name string) bool {
	return e.Kind() == celast.IdentKind && e.AsIdent() == name
}

type sizeNode struct{ operand fastNode }

func (n sizeNode) eval(pr *predicateRequest, locals []fastValue) fastValue {
	v := n.operand.eval(pr, locals)
	switch v.kind {
	case fkList:
		return fastValue{kind: fkInt, n: int64(len(v.ref.([]any)))}
	case fkMap:
		return fastValue{kind: fkInt, n: int64(len(v.ref.(map[string]any)))}
	case fkHeaders:
		return fastValue{kind: fkInt, n: int64(len(v.ref.(map[string]string)))}
	case fkString, fkJSONStr:
		s, ok := stringOf(pr, v)
		if !ok {
			return fastDeferred
		}
		return fastValue{kind: fkInt, n: int64(utf8.RuneCountInString(s))} // code points
	case fkJSONArr:
		count := int64(0)
		it := pr.doc.iterAt(v.start(), int(v.ci))
		for _, _, more := it.next(); more; _, _, more = it.next() {
			count++
		}
		return fastValue{kind: fkInt, n: count}
	case fkJSONObj:
		return distinctKeys(pr.doc, v)
	}
	return fastDeferred
}

// isSizeZero reports whether the operands are size(x) and the literal 0.
func isSizeZero(nodes []fastNode) bool {
	_, isSize := nodes[0].(sizeNode)
	c, isConst := nodes[1].(constNode)
	return isSize && isConst && c.v.kind == fkInt && c.v.n == 0
}

// emptyNode is size(x) == 0 when empty is set, else size(x) != 0 or size(x) > 0. It answers as
// sizeNode would, without counting.
type emptyNode struct {
	operand fastNode
	empty   bool
}

func (n emptyNode) eval(pr *predicateRequest, locals []fastValue) fastValue {
	v := n.operand.eval(pr, locals)
	var none bool
	switch v.kind {
	case fkList:
		none = len(v.ref.([]any)) == 0
	case fkMap:
		none = len(v.ref.(map[string]any)) == 0
	case fkHeaders:
		none = len(v.ref.(map[string]string)) == 0
	case fkString:
		none = v.s == ""
	case fkJSONStr:
		none = v.end()-v.start() == 2 // an escape decodes to at least one code point
	case fkJSONArr, fkJSONObj:
		it := pr.doc.iterAt(v.start(), int(v.ci))
		_, _, more := it.next()
		none = !more
	default:
		return fastDeferred
	}
	return boolValue(none == n.empty)
}

// distinctKeys counts the keys of a JSON object once each, as its decoded map holds them.
func distinctKeys(d *jsonDoc, obj fastValue) fastValue {
	var small [16][]byte
	keys := small[:0]
	escaped := false
	it := d.iterAt(obj.start(), int(obj.ci))
	for key, _, more := it.next(); more; key, _, more = it.next() {
		keys = append(keys, key)
		escaped = escaped || bytes.IndexByte(key, '\\') >= 0
	}
	if len(keys) <= 1 {
		return fastValue{kind: fkInt, n: int64(len(keys))}
	}
	if escaped {
		seen := make(map[string]struct{}, len(keys))
		for _, k := range keys {
			s, ok := decodeString(k)
			if !ok {
				return fastDeferred
			}
			seen[s] = struct{}{}
		}
		return fastValue{kind: fkInt, n: int64(len(seen))}
	}
	slices.SortFunc(keys, bytes.Compare)
	count := int64(1)
	for i := 1; i < len(keys); i++ {
		if !bytes.Equal(keys[i], keys[i-1]) {
			count++
		}
	}
	return fastValue{kind: fkInt, n: count}
}

type allNode struct {
	rng  fastNode
	slot int
	pred fastNode
}

// eval answers false at the first false element, which is CEL's answer whatever the other
// elements give; a defer or a non-bool element defers.
func (n allNode) eval(pr *predicateRequest, locals []fastValue) fastValue {
	step := func(v fastValue) (fastValue, bool) {
		locals[n.slot] = v
		r := n.pred.eval(pr, locals)
		if r.kind != fkBool {
			return fastDeferred, false
		}
		return r, r.n == 1
	}
	r := n.rng.eval(pr, locals)
	switch r.kind {
	case fkList:
		for _, el := range r.ref.([]any) {
			if v, more := step(nativeValue(el)); !more {
				return v
			}
		}
	case fkMap:
		for k := range r.ref.(map[string]any) {
			if v, more := step(fastValue{kind: fkString, s: k}); !more {
				return v
			}
		}
	case fkHeaders:
		for k := range r.ref.(map[string]string) {
			if v, more := step(fastValue{kind: fkString, s: k}); !more {
				return v
			}
		}
	case fkJSONArr, fkJSONObj:
		it := pr.doc.iterAt(r.start(), int(r.ci))
		for key, val, more := it.next(); more; key, val, more = it.next() {
			el := jsonValue(pr.doc, val, it.valEnd, it.valCI)
			if key != nil {
				el = jsonSpan(fkJSONStr, it.keyPos, it.keyPos+len(key))
			}
			if v, more := step(el); !more {
				return v
			}
		}
	default:
		return fastDeferred
	}
	return boolValue(true)
}
