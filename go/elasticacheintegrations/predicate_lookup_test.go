// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"bytes"
	"testing"
)

// lookupKeyOracle is lookupKey written without shortcuts: decode the key, then index.
func lookupKeyOracle(pr *predicateRequest, c, key fastValue) (fastValue, bool) {
	k := key.s
	if key.kind == fkJSONStr {
		s, ok := decodeString(rawString(pr.doc, key))
		if !ok {
			return fastDeferred, false
		}
		k = s
	}
	switch c.kind {
	case fkMap:
		x, ok := c.ref.(map[string]any)[k]
		return valueIf(nativeValue(x), ok)
	case fkHeaders:
		h, ok := c.ref.(map[string]string)[k]
		return valueIf(fastValue{kind: fkString, s: h}, ok)
	case fkJSONObj:
		var v fastValue
		found := false
		it := pr.doc.iterAt(c.start(), int(c.ci))
		for kr, val, more := it.next(); more; kr, val, more = it.next() {
			if ks, ok := decodeString(kr); ok && ks == k {
				v, found = jsonValue(pr.doc, val, it.valEnd, it.valCI), true
			}
		}
		return v, found
	}
	return fastDeferred, false
}

// lookupKeyBody holds the keys the test looks up, plain and escaped, and an object with a
// repeated key and an escaped key.
const lookupKeyBody = `{"keys":["a","b","\u0061","a\"q","","\u00e9","z\\"],` +
	`"obj":{"a":1,"b":"x","a":"last","a\"q":true,"\u00e9":null,"z\\":"s"}}`

// TestLookupKeyMatchesOracle: every container kind and key kind, present, absent, escaped and
// repeated, gives the oracle's answer.
func TestLookupKeyMatchesOracle(t *testing.T) {
	pr := predicateRequest{req: predReq("GetItem", "dynamodb", lookupKeyBody)}
	defer pr.release()
	if !pr.loadBody() {
		t.Fatal("the body does not load")
	}
	root := jsonValue(pr.doc, pr.doc.root, int(pr.doc.ends[0]), 0)
	keysList, _ := fastLookup(&pr, root, "keys")
	obj, ok := fastLookup(&pr, root, "obj")
	if !ok || obj.kind != fkJSONObj || keysList.kind != fkJSONArr {
		t.Fatalf("body shape: keys %v, obj %v", keysList.kind, obj.kind)
	}
	var keys []fastValue
	it := pr.doc.iterAt(keysList.start(), int(keysList.ci))
	for _, val, more := it.next(); more; _, val, more = it.next() {
		keys = append(keys, jsonValue(pr.doc, val, it.valEnd, it.valCI))
	}
	for _, s := range []string{"a", "b", "a\"q", "", "é", "z\\", "missing"} {
		keys = append(keys, fastValue{kind: fkString, s: s})
	}
	containers := map[string]fastValue{
		"map": {kind: fkMap, ref: map[string]any{"a": "va", "a\"q": int64(7), "é": true, "": "empty",
			"z\\": nil}},
		"headers": {kind: fkHeaders, ref: map[string]string{"a": "ha", "b": "hb", "é": "he", "z\\": "hz"}},
		"json":    obj,
		"other":   {kind: fkString, s: "a"},
	}
	for name, c := range containers {
		for i, k := range keys {
			got, gotOK := lookupKey(&pr, c, k)
			want, wantOK := lookupKeyOracle(&pr, c, k)
			if gotOK != wantOK || !sameFastValue(&pr, got, want) {
				t.Errorf("lookupKey(%s, key %d %q) = %+v, %v; want %+v, %v",
					name, i, keyText(&pr, k), got, gotOK, want, wantOK)
			}
		}
	}
}

func keyText(pr *predicateRequest, k fastValue) string {
	if k.kind == fkJSONStr {
		return string(rawString(pr.doc, k))
	}
	return k.s
}

// sameFastValue compares two lookup results by kind and content.
func sameFastValue(pr *predicateRequest, a, b fastValue) bool {
	if a.kind != b.kind || a.n != b.n || a.s != b.s || a.ci != b.ci {
		return false
	}
	if a.kind == fkJSONStr {
		return bytes.Equal(rawString(pr.doc, a), rawString(pr.doc, b))
	}
	return true
}

// TestLookupKeyBodyKeyDoesNotAllocate: a plain body string used as a map or headers key is
// not copied.
func TestLookupKeyBodyKeyDoesNotAllocate(t *testing.T) {
	pr := predicateRequest{req: predReq("GetItem", "dynamodb", `{"k":"Users"}`)}
	defer pr.release()
	if !pr.loadBody() {
		t.Fatal("the body does not load")
	}
	root := jsonValue(pr.doc, pr.doc.root, int(pr.doc.ends[0]), 0)
	key, _ := fastLookup(&pr, root, "k")
	m := fastValue{kind: fkMap, ref: map[string]any{"Users": "x"}}
	h := fastValue{kind: fkHeaders, ref: map[string]string{"Users": "y"}}
	if n := testing.AllocsPerRun(100, func() {
		if _, ok := lookupKey(&pr, m, key); !ok {
			t.Fatal("not found in the map")
		}
		if _, ok := lookupKey(&pr, h, key); !ok {
			t.Fatal("not found in the headers")
		}
	}); n != 0 {
		t.Fatalf("lookupKey allocates %v times per call, want 0", n)
	}
}
