// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"encoding/json"
	"errors"
	"fmt"
	"strconv"

	"github.com/google/cel-go/cel"
	"github.com/google/cel-go/common/types"
	"github.com/google/cel-go/interpreter"

	celast "github.com/google/cel-go/common/ast"
)

// maxBodyDepth is the deepest nesting a body may have for predicates to read it, as Jackson's
// default read limit.
const maxBodyDepth = 1000

// excludedHeaders are the request headers never sent to the cache. Predicates do not see them.
var excludedHeaders = map[string]bool{
	"authorization":        true,
	"x-amz-date":           true,
	"x-amz-security-token": true,
	"x-amz-request-id":     true,
	"amz-sdk-request":      true,
}

// requestVariables are the names every predicate can read, with their CEL types.
var requestVariables = []struct {
	name string
	typ  *cel.Type
}{
	{"service", cel.StringType},
	{"operation", cel.StringType},
	{"method", cel.StringType},
	{"url", cel.StringType},
	{"headers", cel.MapType(cel.StringType, cel.StringType)},
	{"body", cel.DynType},
}

// predicates is the compiled routing predicates of one resolved config. It is immutable and
// safe for concurrent use.
type predicates struct {
	list []*predicate // predicates that read the body come last
}

type predicate struct {
	program   cel.Program
	vars      interpreter.Activation // nil without vars
	readsBody bool
}

// compilePredicates compiles the predicates of a resolved config, in config order. A config
// without a "predicates" key routes nothing. Any entry that cannot be used makes the whole
// config unusable: caching by the entries that did compile would route a different set of
// requests than the cache resolved. The error is a *ConfigUnevaluableError.
func compilePredicates(raw map[string]any) (*predicates, error) {
	entries, ok := raw["predicates"]
	if !ok {
		return &predicates{}, nil
	}
	list, ok := entries.([]any)
	if !ok {
		return nil, &ConfigUnevaluableError{Msg: "predicates is " + jsonKind(entries) + ", not a list"}
	}
	var head, tail []*predicate
	for i, entry := range list {
		pred, err := compilePredicate(entry)
		if err != nil {
			err.Msg = fmt.Sprintf("predicate %d %s", i, err.Msg)
			return nil, err
		}
		if pred.readsBody {
			tail = append(tail, pred)
		} else {
			head = append(head, pred)
		}
	}
	return &predicates{list: append(head, tail...)}, nil
}

func compilePredicate(entry any) (*predicate, *ConfigUnevaluableError) {
	obj, ok := entry.(map[string]any)
	if !ok {
		return nil, &ConfigUnevaluableError{Msg: "is " + jsonKind(entry) + ", not an object"}
	}
	expr, ok := obj["expr"].(string)
	if !ok {
		return nil, &ConfigUnevaluableError{Msg: "has no string expr"}
	}
	vars := map[string]any{}
	if rawVars, present := obj["vars"]; present {
		m, ok := rawVars.(map[string]any)
		if !ok {
			return nil, &ConfigUnevaluableError{Msg: "has vars that is " + jsonKind(rawVars) + ", not an object"}
		}
		for name, v := range m {
			cv, err := celValue(v, 1)
			if err != nil {
				return nil, &ConfigUnevaluableError{Msg: "has an unusable vars value", Err: err}
			}
			vars[name] = cv
		}
	}

	opts := make([]cel.EnvOption, 0, len(requestVariables)+len(vars))
	for _, rv := range requestVariables {
		if _, shadowed := vars[rv.name]; !shadowed {
			opts = append(opts, cel.Variable(rv.name, rv.typ))
		}
	}
	for name := range vars {
		opts = append(opts, cel.Variable(name, cel.DynType))
	}
	env, err := cel.NewEnv(opts...)
	if err != nil {
		return nil, &ConfigUnevaluableError{Msg: "has vars that cannot be declared", Err: err}
	}
	checked, issues := env.Compile(expr)
	if issues != nil && issues.Err() != nil {
		return nil, &ConfigUnevaluableError{Msg: "does not compile", Err: issues.Err()}
	}
	program, err := env.Program(checked)
	if err != nil {
		return nil, &ConfigUnevaluableError{Msg: "cannot be planned", Err: err}
	}

	pred := &predicate{program: program}
	if len(vars) > 0 {
		if pred.vars, err = interpreter.NewActivation(vars); err != nil {
			return nil, &ConfigUnevaluableError{Msg: "has vars that cannot be bound", Err: err}
		}
	}
	_, bodyShadowed := vars["body"]
	pred.readsBody = !bodyShadowed && namesFree(checked.NativeRep().Expr(), "body", nil)
	return pred, nil
}

// namesFree reports whether e reads the identifier name outside a comprehension that binds it.
func namesFree(e celast.Expr, name string, bound []string) bool {
	switch e.Kind() {
	case celast.IdentKind:
		if e.AsIdent() != name {
			return false
		}
		for _, b := range bound {
			if b == name {
				return false
			}
		}
		return true
	case celast.SelectKind:
		return namesFree(e.AsSelect().Operand(), name, bound)
	case celast.CallKind:
		c := e.AsCall()
		if c.IsMemberFunction() && namesFree(c.Target(), name, bound) {
			return true
		}
		for _, a := range c.Args() {
			if namesFree(a, name, bound) {
				return true
			}
		}
	case celast.ListKind:
		for _, el := range e.AsList().Elements() {
			if namesFree(el, name, bound) {
				return true
			}
		}
	case celast.MapKind:
		for _, en := range e.AsMap().Entries() {
			me := en.AsMapEntry()
			if namesFree(me.Key(), name, bound) || namesFree(me.Value(), name, bound) {
				return true
			}
		}
	case celast.StructKind:
		for _, f := range e.AsStruct().Fields() {
			if namesFree(f.AsStructField().Value(), name, bound) {
				return true
			}
		}
	case celast.ComprehensionKind:
		c := e.AsComprehension()
		if namesFree(c.IterRange(), name, bound) || namesFree(c.AccuInit(), name, bound) {
			return true
		}
		inner := append(append(bound[:len(bound):len(bound)], c.IterVar(), c.AccuVar()), c.IterVar2())
		return namesFree(c.LoopCondition(), name, inner) ||
			namesFree(c.LoopStep(), name, inner) ||
			namesFree(c.Result(), name, append(bound[:len(bound):len(bound)], c.AccuVar()))
	}
	return false
}

// shouldRoute reports whether the request goes to the cache: some predicate is exactly true.
// An empty set routes nothing.
func (p *predicates) shouldRoute(req *request) bool {
	if p == nil || len(p.list) == 0 {
		return false
	}
	pr := predicateRequest{req: req}
	defer pr.release()
	for _, pred := range p.list {
		// A body that predicates cannot read matches none that read it, and only those are left.
		if pred.readsBody && !pr.loadBody() {
			return false
		}
		if pred.matches(&pr) {
			return true
		}
	}
	return false
}

func (pred *predicate) matches(pr *predicateRequest) bool {
	var act interpreter.Activation = pr.activation()
	if pred.vars != nil {
		act = interpreter.NewHierarchicalActivation(act, pred.vars) // a var wins over a request field
	}
	out, _, err := pred.program.Eval(act)
	return err == nil && out == types.True
}

// predicateRequest is one request as the predicates see it. The body is scanned, and the
// headers and the cel-go body are built, on first need. Not safe for concurrent use: one per
// evaluation.
type predicateRequest struct {
	req      *request
	bodyDone bool
	doc      *jsonDoc // nil when the body is not usable
	celDone  bool
	celBody  map[string]any
	act      *requestActivation
}

// loadBody scans the body on the first call. It reports whether predicates may read it.
func (pr *predicateRequest) loadBody() bool {
	if !pr.bodyDone {
		pr.bodyDone = true
		if len(pr.req.body) == 0 {
			pr.doc = emptyDoc
		} else {
			pr.doc, _ = scanJSON(pr.req.body)
		}
	}
	return pr.doc != nil
}

// body returns the body decoded for cel-go, once.
func (pr *predicateRequest) body() (map[string]any, bool) {
	if !pr.celDone {
		pr.celDone = true
		if pr.loadBody() {
			pr.celBody, _ = pr.doc.celBody()
		}
	}
	return pr.celBody, pr.celBody != nil
}

func (pr *predicateRequest) release() {
	pr.doc.release()
	pr.doc = nil
}

func (pr *predicateRequest) activation() *requestActivation {
	if pr.act == nil {
		pr.act = &requestActivation{pr: pr}
	}
	return pr.act
}

// requestActivation resolves the request variables for cel-go. headers is built on first use.
type requestActivation struct {
	pr      *predicateRequest
	headers map[string]string
}

func (a *requestActivation) ResolveName(name string) (any, bool) {
	r := a.pr.req
	switch name {
	case "service":
		return r.metadata.service, true
	case "operation":
		return r.metadata.operation, true
	case "method":
		return r.method, true
	case "url":
		return r.url, true
	case "headers":
		if a.headers == nil {
			a.headers = predicateHeaders(r.headers)
		}
		return a.headers, true
	case "body":
		return a.pr.body()
	}
	return nil, false
}

func (a *requestActivation) Parent() interpreter.Activation { return nil }

// predicateHeaders is the request's header map without the excluded headers.
func predicateHeaders(h map[string]string) map[string]string {
	out := make(map[string]string, len(h))
	for name, v := range h {
		if !excludedHeaders[name] {
			out[name] = v
		}
	}
	return out
}

// decodeBody returns the body as cel-go input. An empty body is {}. It reports false when the
// body is not usable by predicates (see scanJSON).
func decodeBody(body []byte) (map[string]any, bool) {
	pr := predicateRequest{req: &request{body: body}}
	defer pr.release()
	return pr.body()
}

var errTooDeep = errors.New("nested too deep")

// celValue returns a copy of a decoded JSON value for cel-go: a json.Number becomes an int64
// when it is an integer that fits, else a float64. v is never modified. depth is the nesting
// level of v.
func celValue(v any, depth int) (any, error) {
	switch x := v.(type) {
	case map[string]any:
		if depth > maxBodyDepth {
			return nil, errTooDeep
		}
		out := make(map[string]any, len(x))
		for k, el := range x {
			cv, err := celValue(el, depth+1)
			if err != nil {
				return nil, err
			}
			out[k] = cv
		}
		return out, nil
	case []any:
		if depth > maxBodyDepth {
			return nil, errTooDeep
		}
		out := make([]any, len(x))
		for i, el := range x {
			cv, err := celValue(el, depth+1)
			if err != nil {
				return nil, err
			}
			out[i] = cv
		}
		return out, nil
	case json.Number:
		return numberValue(string(x))
	case string, bool, float64, nil:
		return x, nil
	default:
		return nil, fmt.Errorf("%T is not a JSON value", v)
	}
}

func numberValue(s string) (any, error) {
	if i, err := strconv.ParseInt(s, 10, 64); err == nil {
		return i, nil
	}
	f, err := strconv.ParseFloat(s, 64)
	if err != nil && !errors.Is(err, strconv.ErrRange) {
		return nil, err
	}
	return f, nil // an integer beyond int64 or a huge exponent: the nearest float64, or ±Inf
}

// jsonKind names the JSON type of a decoded value, for messages that must not echo it.
func jsonKind(v any) string {
	switch v.(type) {
	case nil:
		return "null"
	case map[string]any:
		return "an object"
	case []any:
		return "a list"
	case string:
		return "a string"
	case bool:
		return "a bool"
	case json.Number, float64:
		return "a number"
	default:
		return fmt.Sprintf("%T", v)
	}
}
