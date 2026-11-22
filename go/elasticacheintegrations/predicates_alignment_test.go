// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"bytes"
	"encoding/json"
	"testing"

	"github.com/awslabs/elasticache-integration-client/go/internal/testutil"
)

// sdkRequestCases is shared/sdk-request-cases.json. go-sdk-integration reads every field;
// predicates read the config and each case's service, operation and params.
type sdkRequestCases struct {
	Description     string          `json:"description"`
	Config          json.RawMessage `json:"config"`
	ExcludedHeaders []string        `json:"excluded_headers"`
	Cases           []struct {
		Name      string          `json:"name"`
		Service   string          `json:"service"`
		Operation string          `json:"operation"`
		Params    json.RawMessage `json:"params"`
		Expected  json.RawMessage `json:"expected"`
	} `json:"cases"`
}

func TestAlignmentSharedPredicates(t *testing.T) {
	var fixture sdkRequestCases
	testutil.DecodeStrict(t, testutil.SharedFixture(t, "sdk-request-cases.json"), &fixture)
	dec := json.NewDecoder(bytes.NewReader(fixture.Config))
	dec.UseNumber()
	var raw map[string]any
	if err := dec.Decode(&raw); err != nil {
		t.Fatal(err)
	}
	p := mustCompilePredicates(t, raw)
	if len(fixture.Cases) == 0 {
		t.Fatal("no cases")
	}
	for _, c := range fixture.Cases {
		if !p.shouldRoute(predReq(c.Operation, c.Service, string(c.Params))) {
			t.Errorf("%s: %s does not route", c.Name, c.Operation)
		}
	}
	for _, op := range []string{"DescribeTable", "Scan", "TransactGetItems"} {
		if p.shouldRoute(predReq(op, "dynamodb", "{}")) {
			t.Errorf("%s routes; the fixture's ops do not list it", op)
		}
	}
	if p.shouldRoute(predReq("GetItem", "s3", "{}")) {
		t.Error("a non-dynamodb service routes")
	}
	for _, name := range fixture.ExcludedHeaders {
		if !excludedHeaders[name] {
			t.Errorf("excluded header %q is visible to predicates", name)
		}
	}
	if len(excludedHeaders) != len(fixture.ExcludedHeaders) {
		t.Errorf("excludedHeaders has %d names, the fixture %d", len(excludedHeaders), len(fixture.ExcludedHeaders))
	}
}
