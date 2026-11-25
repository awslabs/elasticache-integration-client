// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"context"
	"encoding/json"
	"reflect"
	"sort"
	"strings"
	"testing"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"

	"github.com/awslabs/elasticache-integration-client/go/internal/mockcache"
	"github.com/awslabs/elasticache-integration-client/go/internal/testutil"
)

// sdkCaseFile is shared/sdk-request-cases.json with every field typed.
type sdkCaseFile struct {
	Description     string          `json:"description"`
	Config          json.RawMessage `json:"config"`
	ExcludedHeaders []string        `json:"excluded_headers"`
	Cases           []struct {
		Name      string          `json:"name"`
		Service   string          `json:"service"`
		Operation string          `json:"operation"`
		Params    json.RawMessage `json:"params"`
		Expected  struct {
			Method   string             `json:"method"`
			URL      string             `json:"url"`
			Metadata string             `json:"metadata"`
			Headers  map[string]*string `json:"headers"`
		} `json:"expected"`
	} `json:"cases"`
}

// Header names the Go SDK sends or omits compared with expected.headers, which lists what
// Python sends. The check is exact both ways, so a new difference fails, and so does an entry
// the SDK no longer adds or omits.
var sdkHeaderDifferences = struct{ extra, missing []string }{
	// The Go SDK asks for an unencoded body (aws-sdk-go-v2 service/internal/accept-encoding);
	// botocore sends no Accept-Encoding.
	extra: []string{"accept-encoding"},
}

// sdkCaseCalls is the SDK call for each case. The Go SDK cannot build a call from JSON, so each
// is written out; the body check compares it with the shared params.
var sdkCaseCalls = map[string]func(context.Context, *dynamodb.Client) error{
	"dynamodb_get_item": func(ctx context.Context, ddb *dynamodb.Client) error {
		_, err := ddb.GetItem(ctx, &dynamodb.GetItemInput{TableName: aws.String("Music"), Key: musicKey()})
		return err
	},
	"dynamodb_query_with_expression_values": func(ctx context.Context, ddb *dynamodb.Client) error {
		_, err := ddb.Query(ctx, &dynamodb.QueryInput{
			TableName:                 aws.String("Music"),
			KeyConditionExpression:    aws.String("Artist = :a"),
			ExpressionAttributeValues: map[string]types.AttributeValue{":a": &types.AttributeValueMemberS{Value: "Acme"}},
			Limit:                     aws.Int32(10),
		})
		return err
	},
	"dynamodb_batch_get_item": func(ctx context.Context, ddb *dynamodb.Client) error {
		_, err := ddb.BatchGetItem(ctx, &dynamodb.BatchGetItemInput{RequestItems: map[string]types.KeysAndAttributes{
			"Music": {Keys: []map[string]types.AttributeValue{musicKey()}},
		}})
		return err
	},
	"dynamodb_put_item": func(ctx context.Context, ddb *dynamodb.Client) error {
		item := musicKey()
		item["Year"] = &types.AttributeValueMemberN{Value: "2020"}
		_, err := ddb.PutItem(ctx, &dynamodb.PutItemInput{TableName: aws.String("Music"), Item: item})
		return err
	},
}

func loadSDKRequestCases(t *testing.T) sdkCaseFile {
	t.Helper()
	var f sdkCaseFile
	testutil.DecodeStrict(t, testutil.SharedFixture(t, "sdk-request-cases.json"), &f)
	return f
}

func TestAlignmentSDKRequestCases(t *testing.T) {
	testutil.ClearAWSEnv(t)
	f := loadSDKRequestCases(t)
	if len(f.Cases) == 0 {
		t.Fatal("no cases")
	}
	for _, tc := range f.Cases {
		t.Run(tc.Name, func(t *testing.T) {
			call := sdkCaseCalls[tc.Name]
			if call == nil {
				t.Fatalf("no SDK call for %s", tc.Name)
			}
			if tc.Service != sdkService {
				t.Fatalf("service %q: only dynamodb has an SDK integration", tc.Service)
			}
			s, addr := sdkMockConfig(t, string(f.Config))
			s.SetOnRequest(func(mockcache.Command) mockcache.Reply { return servedJSON(200, `{}`) })
			origin := &fakeOrigin{}
			ddb, _ := startSDK(t, sdkConfig(t, s, addr), origin)
			if err := call(context.Background(), ddb); err != nil {
				t.Fatal(err)
			}
			reqs := s.Requests()
			if len(reqs) != 1 || origin.count() != 0 {
				t.Fatalf("cache requests %d, origin calls %d; want 1, 0", len(reqs), origin.count())
			}
			got := reqs[0]
			if got.Arg(1) != tc.Expected.Method || got.Arg(2) != tc.Expected.URL || got.Arg(5) != tc.Expected.Metadata {
				t.Errorf("method, url, metadata = %q, %q, %q; want %q, %q, %q",
					got.Arg(1), got.Arg(2), got.Arg(5), tc.Expected.Method, tc.Expected.URL, tc.Expected.Metadata)
			}
			if tc.Expected.Metadata != "service="+tc.Service+"&op="+tc.Operation {
				t.Errorf("case metadata %q does not name %s %s", tc.Expected.Metadata, tc.Service, tc.Operation)
			}
			var gotBody, wantBody any
			if err := json.Unmarshal(got.Args[4], &gotBody); err != nil {
				t.Fatalf("body %q: %v", got.Args[4], err)
			}
			if err := json.Unmarshal(tc.Params, &wantBody); err != nil {
				t.Fatal(err)
			}
			if !reflect.DeepEqual(gotBody, wantBody) {
				t.Errorf("body = %s, want %s", got.Args[4], tc.Params)
			}
			checkCaseHeaders(t, got.Arg(3), tc.Expected.Headers, f.ExcludedHeaders)
		})
	}
}

// checkCaseHeaders compares the normalized header block of the aws.request with the case.
func checkCaseHeaders(t *testing.T, block string, expected map[string]*string, excluded []string) {
	t.Helper()
	sent := map[string]string{}
	for _, line := range strings.Split(block, "\n") {
		name, value, ok := strings.Cut(line, ":")
		if !ok {
			t.Fatalf("header line %q has no ':'", line)
		}
		sent[name] = value
	}
	for _, name := range excluded {
		if _, ok := sent[name]; ok {
			t.Errorf("excluded header %q was sent", name)
		}
	}
	want := map[string]bool{}
	for name := range expected {
		want[name] = true
	}
	for _, name := range sdkHeaderDifferences.missing {
		if !want[name] {
			t.Errorf("accepted missing header %q is not in the case", name)
		}
		delete(want, name)
	}
	for _, name := range sdkHeaderDifferences.extra {
		if want[name] {
			t.Errorf("accepted extra header %q is in the case", name)
		}
		want[name] = true
	}
	if got, exp := sortedKeys(sent), sortedKeys(want); !reflect.DeepEqual(got, exp) {
		t.Errorf("header names = %v, want %v", got, exp)
	}
	for name, value := range expected {
		if value != nil && sent[name] != *value {
			t.Errorf("header %q = %q, want %q", name, sent[name], *value)
		}
	}
}

func sortedKeys[V any](m map[string]V) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}

func TestAlignmentSDKRequestCasesHaveCalls(t *testing.T) {
	names := map[string]bool{}
	for _, tc := range loadSDKRequestCases(t).Cases {
		names[tc.Name] = true
	}
	if got, want := sortedKeys(sdkCaseCalls), sortedKeys(names); !reflect.DeepEqual(got, want) {
		t.Errorf("SDK calls for %v, cases %v", got, want)
	}
}
