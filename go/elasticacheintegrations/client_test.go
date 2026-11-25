// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"hash/crc32"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
	"github.com/aws/smithy-go/middleware"

	"github.com/awslabs/elasticache-integration-client/go/internal/mockcache"
	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
	"github.com/awslabs/elasticache-integration-client/go/internal/testutil"
)

// fakeOrigin is the user's HTTP client: it records every call and answers from memory.
type fakeOrigin struct {
	mu      sync.Mutex
	calls   []originCall
	respond func(*http.Request) (*http.Response, error) // nil: 200 {}
}

type originCall struct {
	operation string // from X-Amz-Target
	body      string
	header    http.Header
}

func (o *fakeOrigin) Do(req *http.Request) (*http.Response, error) {
	var body []byte
	if req.Body != nil {
		var err error
		if body, err = io.ReadAll(req.Body); err != nil {
			return nil, err
		}
	}
	target := req.Header.Get("X-Amz-Target")
	o.mu.Lock()
	o.calls = append(o.calls, originCall{operation: target[strings.LastIndexByte(target, '.')+1:], body: string(body), header: req.Header.Clone()})
	respond := o.respond
	o.mu.Unlock()
	if respond != nil {
		return respond(req)
	}
	return jsonResponse(req, 200, `{}`), nil
}

func (o *fakeOrigin) count() int {
	o.mu.Lock()
	defer o.mu.Unlock()
	return len(o.calls)
}

func (o *fakeOrigin) last() originCall {
	o.mu.Lock()
	defer o.mu.Unlock()
	return o.calls[len(o.calls)-1]
}

// jsonResponse is a DynamoDB response as net/http returns it.
func jsonResponse(req *http.Request, status int, body string) *http.Response {
	return &http.Response{
		StatusCode:    status,
		Status:        statusLine(status),
		Proto:         "HTTP/1.1",
		ProtoMajor:    1,
		ProtoMinor:    1,
		Header:        http.Header{"Content-Type": {"application/x-amz-json-1.0"}, "X-Amz-Crc32": {crc(body)}},
		Body:          io.NopCloser(strings.NewReader(body)),
		ContentLength: int64(len(body)),
		Request:       req,
	}
}

func crc(body string) string { return strconv.FormatUint(uint64(crc32.ChecksumIEEE([]byte(body))), 10) }

// servedJSON is a served aws.request reply with a correct x-amz-crc32.
func servedJSON(status int, body string) mockcache.Reply {
	return mockcache.Now(mockcache.Served(status, map[string]string{
		"content-type": "application/x-amz-json-1.0",
		"x-amz-crc32":  crc(body),
	}, []byte(body)))
}

// requestOp is the operation of a received aws.request, from its metadata.
func requestOp(cmd mockcache.Command) string {
	_, op, _ := strings.Cut(cmd.Arg(5), "&op=")
	return op
}

// sdkMock starts a TLS mock cache that closes after the test.
func sdkMock(t testing.TB) (*mockcache.Server, string) {
	t.Helper()
	return sdkMockConfig(t, "")
}

// sdkMockConfig is sdkMock serving configJSON, or the ten operations when it is empty.
func sdkMockConfig(t testing.TB, configJSON string) (*mockcache.Server, string) {
	t.Helper()
	s, err := mockcache.New(mockcache.Config{TLS: true, AWSConfigReply: configJSON})
	if err != nil {
		t.Fatal(err)
	}
	addr, err := s.Listen()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := s.Close(); err != nil {
			t.Errorf("mock cache: %v", err)
		}
	})
	return s, addr
}

// sdkConfig is the validated config for the mock cache at addr.
func sdkConfig(t testing.TB, s *mockcache.Server, addr string) *config {
	t.Helper()
	c, err := Options{Endpoint: addr, CacheName: "my-cache", Logger: slog.New(slog.DiscardHandler)}.validate()
	if err != nil {
		t.Fatal(err)
	}
	c.rootCAs = s.CertPool()
	return c
}

// awsConfig is the application's aws.Config: static credentials, us-east-1.
func awsConfig() aws.Config {
	return aws.Config{Region: testRegion, Credentials: testutil.StaticCredentials()}
}

// startSDK builds a DynamoDB client on the mock cache with origin as the user's HTTP client,
// and closes it after the test.
func startSDK(t testing.TB, c *config, origin aws.HTTPClient, optFns ...func(*dynamodb.Options)) (*dynamodb.Client, *Client) {
	t.Helper()
	return startSDKWith(t, awsConfig(), c, origin, optFns...)
}

// startSDKWith is startSDK with the application's aws.Config ac.
func startSDKWith(t testing.TB, ac aws.Config, c *config, origin aws.HTTPClient, optFns ...func(*dynamodb.Options)) (*dynamodb.Client, *Client) {
	t.Helper()
	fns := append([]func(*dynamodb.Options){func(o *dynamodb.Options) { o.HTTPClient = origin }}, optFns...)
	ddb, cache, err := startClient(context.Background(), c, func(fns ...func(*dynamodb.Options)) *dynamodb.Client {
		return dynamodb.NewFromConfig(ac, fns...)
	}, fns)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = cache.Close() })
	return ddb, cache
}

func musicKey() map[string]types.AttributeValue {
	return map[string]types.AttributeValue{"Artist": &types.AttributeValueMemberS{Value: "Acme"}}
}

func attrS(t *testing.T, item map[string]types.AttributeValue, name string) string {
	t.Helper()
	v, ok := item[name].(*types.AttributeValueMemberS)
	if !ok {
		t.Fatalf("attribute %q = %#v", name, item[name])
	}
	return v.Value
}

func TestNewFromConfigServes(t *testing.T) {
	testutil.ClearAWSEnv(t)
	s, addr := sdkMock(t)
	s.SetOnRequest(func(mockcache.Command) mockcache.Reply {
		return servedJSON(200, `{"Item":{"Artist":{"S":"Acme"}}}`)
	})
	ca := filepath.Join(t.TempDir(), "ca.pem")
	if err := os.WriteFile(ca, s.CertPEM(), 0o600); err != nil {
		t.Fatal(err)
	}
	origin := &fakeOrigin{}
	ctx := context.Background()
	ddb, cache, err := NewFromConfig(ctx, awsConfig(), Options{
		Endpoint:    addr,
		CacheName:   "My-Cache",
		CACertsFile: ca,
		Logger:      slog.New(slog.DiscardHandler),
	}, func(o *dynamodb.Options) { o.HTTPClient = origin })
	if err != nil {
		t.Fatal(err)
	}
	defer cache.Close()

	out, err := ddb.GetItem(ctx, &dynamodb.GetItemInput{TableName: aws.String("Music"), Key: musicKey()})
	if err != nil {
		t.Fatal(err)
	}
	if attrS(t, out.Item, "Artist") != "Acme" {
		t.Errorf("Item = %v", out.Item)
	}
	if _, err := ddb.ListTables(ctx, &dynamodb.ListTablesInput{}); err != nil {
		t.Fatal(err)
	}
	if origin.count() != 1 || origin.last().operation != "ListTables" {
		t.Errorf("origin calls = %+v; want one ListTables", origin.calls)
	}
	if n := len(s.Requests()); n != 1 {
		t.Errorf("cache requests = %d, want 1", n)
	}
	hello := s.Commands()[0]
	if hello.Name() != "HELLO" || hello.Arg(3) != "default.iam-user" || !strings.HasPrefix(hello.Arg(4), "my-cache/?") {
		t.Errorf("HELLO user, token prefix = %q, %.10q", hello.Arg(3), hello.Arg(4))
	}
}

func TestNewServes(t *testing.T) {
	testutil.ClearAWSEnv(t)
	s, addr := sdkMock(t)
	ca := filepath.Join(t.TempDir(), "ca.pem")
	if err := os.WriteFile(ca, s.CertPEM(), 0o600); err != nil {
		t.Fatal(err)
	}
	origin := &fakeOrigin{}
	ctx := context.Background()
	ddb, cache, err := New(ctx, dynamodb.Options{Region: testRegion, Credentials: testutil.StaticCredentials(), HTTPClient: origin},
		Options{Endpoint: addr, CacheName: "my-cache", CACertsFile: ca, Logger: slog.New(slog.DiscardHandler)})
	if err != nil {
		t.Fatal(err)
	}
	defer cache.Close()
	if _, err := ddb.GetItem(ctx, &dynamodb.GetItemInput{TableName: aws.String("Music"), Key: musicKey()}); err != nil {
		t.Fatal(err)
	}
	if len(s.Requests()) != 1 || origin.count() != 0 {
		t.Errorf("cache requests %d, origin calls %d; want 1, 0", len(s.Requests()), origin.count())
	}
}

// tenOperations calls each advertised operation and checks its output came from the body.
var tenOperations = []struct {
	op   string
	body string
	call func(context.Context, *testing.T, *dynamodb.Client)
}{
	{"GetItem", `{"Item":{"Artist":{"S":"Acme"}}}`, func(ctx context.Context, t *testing.T, ddb *dynamodb.Client) {
		out, err := ddb.GetItem(ctx, &dynamodb.GetItemInput{TableName: aws.String("Music"), Key: musicKey()})
		if err != nil || attrS(t, out.Item, "Artist") != "Acme" {
			t.Fatalf("GetItem = %v, %v", out, err)
		}
	}},
	{"BatchGetItem", `{"Responses":{"Music":[{"Artist":{"S":"Acme"}}]}}`, func(ctx context.Context, t *testing.T, ddb *dynamodb.Client) {
		out, err := ddb.BatchGetItem(ctx, &dynamodb.BatchGetItemInput{RequestItems: map[string]types.KeysAndAttributes{
			"Music": {Keys: []map[string]types.AttributeValue{musicKey()}}}})
		if err != nil || len(out.Responses["Music"]) != 1 || attrS(t, out.Responses["Music"][0], "Artist") != "Acme" {
			t.Fatalf("BatchGetItem = %v, %v", out, err)
		}
	}},
	{"Query", `{"Count":1,"Items":[{"Artist":{"S":"Acme"}}],"ScannedCount":1}`, func(ctx context.Context, t *testing.T, ddb *dynamodb.Client) {
		out, err := ddb.Query(ctx, &dynamodb.QueryInput{TableName: aws.String("Music"), KeyConditionExpression: aws.String("Artist = :a"),
			ExpressionAttributeValues: map[string]types.AttributeValue{":a": &types.AttributeValueMemberS{Value: "Acme"}}})
		if err != nil || out.Count != 1 || attrS(t, out.Items[0], "Artist") != "Acme" {
			t.Fatalf("Query = %v, %v", out, err)
		}
	}},
	{"Scan", `{"Count":2,"Items":[{"Artist":{"S":"A"}},{"Artist":{"S":"B"}}],"ScannedCount":2}`, func(ctx context.Context, t *testing.T, ddb *dynamodb.Client) {
		out, err := ddb.Scan(ctx, &dynamodb.ScanInput{TableName: aws.String("Music")})
		if err != nil || out.ScannedCount != 2 || attrS(t, out.Items[1], "Artist") != "B" {
			t.Fatalf("Scan = %v, %v", out, err)
		}
	}},
	{"PutItem", `{"Attributes":{"Artist":{"S":"Old"}}}`, func(ctx context.Context, t *testing.T, ddb *dynamodb.Client) {
		out, err := ddb.PutItem(ctx, &dynamodb.PutItemInput{TableName: aws.String("Music"), Item: musicKey(), ReturnValues: types.ReturnValueAllOld})
		if err != nil || attrS(t, out.Attributes, "Artist") != "Old" {
			t.Fatalf("PutItem = %v, %v", out, err)
		}
	}},
	{"UpdateItem", `{"Attributes":{"Year":{"N":"2020"}}}`, func(ctx context.Context, t *testing.T, ddb *dynamodb.Client) {
		out, err := ddb.UpdateItem(ctx, &dynamodb.UpdateItemInput{TableName: aws.String("Music"), Key: musicKey(),
			UpdateExpression: aws.String("SET #y = :y"), ExpressionAttributeNames: map[string]string{"#y": "Year"},
			ExpressionAttributeValues: map[string]types.AttributeValue{":y": &types.AttributeValueMemberN{Value: "2020"}},
			ReturnValues:              types.ReturnValueUpdatedNew})
		if err != nil || out.Attributes["Year"].(*types.AttributeValueMemberN).Value != "2020" {
			t.Fatalf("UpdateItem = %v, %v", out, err)
		}
	}},
	{"DeleteItem", `{"Attributes":{"Artist":{"S":"Gone"}}}`, func(ctx context.Context, t *testing.T, ddb *dynamodb.Client) {
		out, err := ddb.DeleteItem(ctx, &dynamodb.DeleteItemInput{TableName: aws.String("Music"), Key: musicKey(), ReturnValues: types.ReturnValueAllOld})
		if err != nil || attrS(t, out.Attributes, "Artist") != "Gone" {
			t.Fatalf("DeleteItem = %v, %v", out, err)
		}
	}},
	{"BatchWriteItem", `{"UnprocessedItems":{},"ConsumedCapacity":[{"TableName":"Music","CapacityUnits":1}]}`, func(ctx context.Context, t *testing.T, ddb *dynamodb.Client) {
		out, err := ddb.BatchWriteItem(ctx, &dynamodb.BatchWriteItemInput{RequestItems: map[string][]types.WriteRequest{
			"Music": {{PutRequest: &types.PutRequest{Item: musicKey()}}}}, ReturnConsumedCapacity: types.ReturnConsumedCapacityTotal})
		if err != nil || len(out.ConsumedCapacity) != 1 || *out.ConsumedCapacity[0].CapacityUnits != 1 {
			t.Fatalf("BatchWriteItem = %v, %v", out, err)
		}
	}},
	{"TransactGetItems", `{"Responses":[{"Item":{"Artist":{"S":"Acme"}}}]}`, func(ctx context.Context, t *testing.T, ddb *dynamodb.Client) {
		out, err := ddb.TransactGetItems(ctx, &dynamodb.TransactGetItemsInput{TransactItems: []types.TransactGetItem{
			{Get: &types.Get{TableName: aws.String("Music"), Key: musicKey()}}}})
		if err != nil || len(out.Responses) != 1 || attrS(t, out.Responses[0].Item, "Artist") != "Acme" {
			t.Fatalf("TransactGetItems = %v, %v", out, err)
		}
	}},
	{"TransactWriteItems", `{"ConsumedCapacity":[{"TableName":"Music","CapacityUnits":2}]}`, func(ctx context.Context, t *testing.T, ddb *dynamodb.Client) {
		out, err := ddb.TransactWriteItems(ctx, &dynamodb.TransactWriteItemsInput{TransactItems: []types.TransactWriteItem{
			{Put: &types.Put{TableName: aws.String("Music"), Item: musicKey()}}}, ReturnConsumedCapacity: types.ReturnConsumedCapacityTotal})
		if err != nil || len(out.ConsumedCapacity) != 1 || *out.ConsumedCapacity[0].CapacityUnits != 2 {
			t.Fatalf("TransactWriteItems = %v, %v", out, err)
		}
	}},
}

func TestTenOperationsServed(t *testing.T) {
	testutil.ClearAWSEnv(t)
	s, addr := sdkMock(t)
	bodies := map[string]string{}
	for _, tc := range tenOperations {
		bodies[tc.op] = tc.body
	}
	s.SetOnRequest(func(cmd mockcache.Command) mockcache.Reply {
		return servedJSON(200, bodies[requestOp(cmd)])
	})
	origin := &fakeOrigin{}
	ddb, _ := startSDK(t, sdkConfig(t, s, addr), origin)
	for i, tc := range tenOperations {
		t.Run(tc.op, func(t *testing.T) {
			tc.call(context.Background(), t, ddb)
			reqs := s.Requests()
			if len(reqs) != i+1 {
				t.Fatalf("cache requests = %d, want %d", len(reqs), i+1)
			}
			got := reqs[i]
			if got.Arg(5) != "service=dynamodb&op="+tc.op || got.Arg(1) != "POST" ||
				!strings.Contains(got.Arg(3), "x-amz-target:DynamoDB_20120810."+tc.op) {
				t.Errorf("aws.request metadata %q, method %q, headers %q", got.Arg(5), got.Arg(1), got.Arg(3))
			}
		})
	}
	if origin.count() != 0 {
		t.Errorf("origin calls = %d, want 0", origin.count())
	}
}

func TestPassThroughOperation(t *testing.T) {
	testutil.ClearAWSEnv(t)
	s, addr := sdkMock(t)
	origin := &fakeOrigin{respond: func(req *http.Request) (*http.Response, error) {
		return jsonResponse(req, 200, `{"Table":{"TableName":"Music","ItemCount":7}}`), nil
	}}
	ddb, _ := startSDK(t, sdkConfig(t, s, addr), origin)
	out, err := ddb.DescribeTable(context.Background(), &dynamodb.DescribeTableInput{TableName: aws.String("Music")})
	if err != nil || *out.Table.ItemCount != 7 {
		t.Fatalf("DescribeTable = %v, %v", out, err)
	}
	if len(s.Requests()) != 0 || origin.count() != 1 {
		t.Fatalf("cache requests %d, origin calls %d; want 0, 1", len(s.Requests()), origin.count())
	}
	if call := origin.last(); call.operation != "DescribeTable" || call.body != `{"TableName":"Music"}` ||
		!strings.HasPrefix(call.header.Get("Authorization"), sigV4Prefix) {
		t.Errorf("origin call = %+v", call)
	}
}

func TestRoutedRequestDeclinedGoesToOriginWithBody(t *testing.T) {
	testutil.ClearAWSEnv(t)
	s, addr := sdkMockConfig(t, `{"version":"1.0","refresh_interval":60,"predicates":[]}`)
	origin := &fakeOrigin{}
	ddb, _ := startSDK(t, sdkConfig(t, s, addr), origin)
	if _, err := ddb.GetItem(context.Background(), &dynamodb.GetItemInput{TableName: aws.String("Music"), Key: musicKey()}); err != nil {
		t.Fatal(err)
	}
	if origin.count() != 1 || origin.last().body != `{"Key":{"Artist":{"S":"Acme"}},"TableName":"Music"}` {
		t.Fatalf("origin calls = %+v", origin.calls)
	}
}

func TestStartErrors(t *testing.T) {
	testutil.ClearAWSEnv(t)
	s, addr := sdkMock(t)
	closed, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	deadAddr := closed.Addr().String()
	closed.Close()

	other, _ := startSDK(t, sdkConfig(t, s, addr), &fakeOrigin{})

	cases := []struct {
		name   string
		cfg    func() *config
		aws    func(*aws.Config)
		optFns []func(*dynamodb.Options)
		check  func(error) bool
	}{
		{"no region", nil, func(c *aws.Config) { c.Region = "" }, nil, isConfigError("Region")},
		{"anonymous credentials", nil, func(c *aws.Config) { c.Credentials = aws.AnonymousCredentials{} }, nil, isConfigError("Credentials")},
		{"no credentials", nil, func(c *aws.Config) { c.Credentials = nil }, nil, isConfigError("Credentials")},
		{"credentials fail", nil, func(c *aws.Config) {
			c.Credentials = aws.CredentialsProviderFunc(func(context.Context) (aws.Credentials, error) {
				return aws.Credentials{}, errors.New("no profile")
			})
		}, nil, isConfigError("Credentials")},
		{"already installed", nil, nil, []func(*dynamodb.Options){func(o *dynamodb.Options) {
			o.HTTPClient = other.Options().HTTPClient
		}}, isConfigError("HTTPClient")},
		{"cache unreachable", func() *config {
			c := sdkConfig(t, s, deadAddr)
			c.connectionTimeout = 300 * time.Millisecond
			return c
		}, nil, nil, func(err error) bool { return errors.As(err, new(*ConnectionError)) }},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			c := sdkConfig(t, s, addr)
			if tc.cfg != nil {
				c = tc.cfg()
			}
			ac := awsConfig()
			if tc.aws != nil {
				tc.aws(&ac)
			}
			before := s.Connections()
			ddb, cache, err := startClient(context.Background(), c, func(fns ...func(*dynamodb.Options)) *dynamodb.Client {
				return dynamodb.NewFromConfig(ac, fns...)
			}, tc.optFns)
			if ddb != nil || cache != nil || err == nil || !tc.check(err) || !errors.Is(err, ErrIntegrations) {
				t.Fatalf("start = %v, %v, %v", ddb, cache, err)
			}
			if tc.name != "cache unreachable" && s.Connections() != before {
				t.Error("the cache was contacted")
			}
		})
	}

	if _, _, err := NewFromConfig(context.Background(), awsConfig(), Options{CacheName: "c"}); !isConfigError("Endpoint")(err) {
		t.Errorf("NewFromConfig without Endpoint = %v", err)
	}
	if _, _, err := New(context.Background(), dynamodb.Options{}, Options{Endpoint: addr}); !isConfigError("CacheName")(err) {
		t.Errorf("New without CacheName = %v", err)
	}
}

func isConfigError(option string) func(error) bool {
	return func(err error) bool {
		var ce *ConfigError
		return errors.As(err, &ce) && ce.Option == option
	}
}

func TestStartCallerCancels(t *testing.T) {
	testutil.ClearAWSEnv(t)
	s, addr := sdkMock(t)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	ddb, cache, err := startClient(ctx, sdkConfig(t, s, addr), func(fns ...func(*dynamodb.Options)) *dynamodb.Client {
		return dynamodb.NewFromConfig(awsConfig(), fns...)
	}, nil)
	if ddb != nil || cache != nil || !errors.Is(err, context.Canceled) {
		t.Fatalf("start = %v, %v, %v", ddb, cache, err)
	}
}

func TestOptFnsNotMutated(t *testing.T) {
	testutil.ClearAWSEnv(t)
	s, addr := sdkMock(t)
	origin := &fakeOrigin{}
	fns := make([]func(*dynamodb.Options), 1, 4)
	fns[0] = func(o *dynamodb.Options) { o.HTTPClient = origin }
	ddb, cache, err := startClient(context.Background(), sdkConfig(t, s, addr), func(fns ...func(*dynamodb.Options)) *dynamodb.Client {
		return dynamodb.NewFromConfig(awsConfig(), fns...)
	}, fns)
	if err != nil {
		t.Fatal(err)
	}
	defer cache.Close()
	if fns[:2][1] != nil {
		t.Fatal("start wrote into the caller's optFns array")
	}
	if _, ok := ddb.Options().HTTPClient.(*httpClient); !ok {
		t.Fatal("the cache is not installed")
	}
}

// TestConfigHTTPClientIsOrigin: the aws.Config HTTP client, and one a ServiceOptions function
// sets, is what install wraps.
func TestConfigHTTPClientIsOrigin(t *testing.T) {
	testutil.ClearAWSEnv(t)
	s, addr := sdkMock(t)
	fromConfig, fromService := &fakeOrigin{}, &fakeOrigin{}
	for _, tc := range []struct {
		name   string
		cfg    func(*aws.Config)
		origin *fakeOrigin
	}{
		{"aws.Config.HTTPClient", func(c *aws.Config) { c.HTTPClient = fromConfig }, fromConfig},
		{"ServiceOptions", func(c *aws.Config) {
			c.HTTPClient = fromConfig
			c.ServiceOptions = []func(string, any){func(_ string, o any) {
				o.(*dynamodb.Options).HTTPClient = fromService
			}}
		}, fromService},
	} {
		t.Run(tc.name, func(t *testing.T) {
			ac := awsConfig()
			tc.cfg(&ac)
			ddb, cache, err := startClient(context.Background(), sdkConfig(t, s, addr), func(fns ...func(*dynamodb.Options)) *dynamodb.Client {
				return dynamodb.NewFromConfig(ac, fns...)
			}, nil)
			if err != nil {
				t.Fatal(err)
			}
			defer cache.Close()
			before := tc.origin.count()
			if _, err := ddb.ListTables(context.Background(), &dynamodb.ListTablesInput{}); err != nil {
				t.Fatal(err)
			}
			if tc.origin.count() != before+1 {
				t.Errorf("origin calls = %d, want %d", tc.origin.count(), before+1)
			}
		})
	}
}

// attempts counts the SDK attempts of one call: a per-call option wraps the installed client.
type attempts struct {
	n    int
	next aws.HTTPClient
}

func (a *attempts) Do(req *http.Request) (*http.Response, error) {
	a.n++
	return a.next.Do(req)
}

func (a *attempts) option(o *dynamodb.Options) {
	a.next = o.HTTPClient
	o.HTTPClient = a
}

// TestMintingProviderRoutes: a provider that mints a new key on every Retrieve, assigned
// straight to aws.Config.Credentials, still routes: each call is checked against the key its
// own signer retrieved. The SDK still retrieves once per call, as without the cache.
func TestMintingProviderRoutes(t *testing.T) {
	testutil.ClearAWSEnv(t)
	s, addr := sdkMock(t)
	var minted atomic.Int64
	minting := aws.CredentialsProviderFunc(func(context.Context) (aws.Credentials, error) {
		return aws.Credentials{AccessKeyID: fmt.Sprintf("ASIAMINTED%010d", minted.Add(1)),
			SecretAccessKey: testutil.SecretAccessKey, SessionToken: testutil.SessionToken}, nil
	})
	origin := &fakeOrigin{}
	ddb, _ := startSDKWith(t, aws.Config{Region: testRegion, Credentials: minting}, sdkConfig(t, s, addr), origin)
	before := minted.Load()
	for range 3 {
		if _, err := getMusic(context.Background(), ddb); err != nil {
			t.Fatal(err)
		}
	}
	if origin.count() != 0 || len(s.Requests()) != 3 {
		t.Fatalf("origin calls %d, cache requests %d; want 0, 3", origin.count(), len(s.Requests()))
	}
	if n := minted.Load() - before; n != 3 {
		t.Errorf("Retrieve ran %d times for three calls, want 3", n)
	}
}

// sourcedProvider reports a credential source, as the SDK's own providers do.
type sourcedProvider struct{ aws.CredentialsProvider }

func (sourcedProvider) ProviderSources() []aws.CredentialSource {
	return []aws.CredentialSource{aws.CredentialSourceSTSAssumeRole}
}

func TestInstallWrapsCredentials(t *testing.T) {
	static := testutil.StaticCredentials()
	cache := aws.NewCredentialsCache(static)
	sourced := sourcedProvider{static}
	wraps := func(inner aws.CredentialsProvider) func(aws.CredentialsProvider) bool {
		return func(p aws.CredentialsProvider) bool {
			w, ok := p.(*signerProvider)
			return ok && aws.IsCredentialsProvider(w.provider, inner) && aws.IsCredentialsProvider(p, inner)
		}
	}
	cases := []struct {
		name string
		in   aws.CredentialsProvider
		want func(aws.CredentialsProvider) bool
	}{
		{"plain provider", static, wraps(static)},
		{"cache", cache, func(p aws.CredentialsProvider) bool {
			w, ok := p.(*signerProvider)
			return ok && w.provider == aws.CredentialsProvider(cache)
		}},
		{"provider sources", sourced, func(p aws.CredentialsProvider) bool {
			s, ok := p.(aws.CredentialProviderSource)
			return wraps(sourced)(p) && ok && slices.Equal(s.ProviderSources(), sourced.ProviderSources())
		}},
		{"anonymous is kept", aws.AnonymousCredentials{}, func(p aws.CredentialsProvider) bool { return p == aws.AnonymousCredentials{} }},
		{"nil is kept", nil, func(p aws.CredentialsProvider) bool { return p == nil }},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			o := dynamodb.Options{Credentials: tc.in}
			(&Client{}).install(&o)
			if !tc.want(o.Credentials) {
				t.Errorf("Credentials = %T", o.Credentials)
			}
		})
	}
	t.Run("caller's APIOptions array", func(t *testing.T) {
		callers := make([]func(*middleware.Stack) error, 1, 2)
		o := dynamodb.Options{Credentials: static, APIOptions: callers}
		(&Client{}).install(&o)
		if len(o.APIOptions) != 2 || callers[:2][1] != nil {
			t.Error("install wrote into the caller's APIOptions array")
		}
	})
}

// valueProvider is a provider of the shape of credentials.StaticCredentialsProvider: a value
// that holds the secrets in exported fields.
type valueProvider struct{ Value aws.Credentials }

func (p valueProvider) Retrieve(context.Context) (aws.Credentials, error) { return p.Value, nil }

// TestClientPrintsNoCredentials: fmt and slog of a Client never print the secrets of a
// value-type provider such as credentials.StaticCredentialsProvider.
func TestClientPrintsNoCredentials(t *testing.T) {
	testutil.ClearAWSEnv(t)
	s, addr := sdkMock(t)
	creds := aws.Credentials{AccessKeyID: testutil.AccessKeyID, SecretAccessKey: testutil.SecretAccessKey, SessionToken: testutil.SessionToken}
	ddb, cache := startSDKWith(t, aws.Config{Region: testRegion, Credentials: valueProvider{creds}}, sdkConfig(t, s, addr), &fakeOrigin{})
	if _, err := getMusic(context.Background(), ddb); err != nil {
		t.Fatal(err)
	}
	var out bytes.Buffer
	for _, verb := range []string{"%v", "%+v", "%#v", "%s", "%q", "%x", "%d"} {
		fmt.Fprintf(&out, verb+"\n", cache)
	}
	slog.New(slog.NewTextHandler(&out, nil)).Info("client", "client", cache)
	slog.New(slog.NewJSONHandler(&out, nil)).Info("client", "client", cache)
	testutil.AssertNoSecrets(t, out.String(), testutil.SecretAccessKey, testutil.SessionToken)
	if !strings.Contains(out.String(), clientString) {
		t.Errorf("a Client prints as %q", out.String())
	}
}

func TestClose(t *testing.T) {
	testutil.ClearAWSEnv(t)
	s, addr := sdkMock(t)
	origin := &fakeOrigin{}
	ddb, cache := startSDK(t, sdkConfig(t, s, addr), origin)
	ctx := context.Background()
	in := &dynamodb.GetItemInput{TableName: aws.String("Music"), Key: musicKey()}
	if _, err := ddb.GetItem(ctx, in); err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	for range 4 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if err := cache.Close(); err != nil {
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	if !cache.sessions[0].closed() {
		t.Fatal("session not closed")
	}
	if _, err := ddb.GetItem(ctx, in); err != nil {
		t.Fatal(err)
	}
	if len(s.Requests()) != 1 || origin.count() != 1 {
		t.Fatalf("cache requests %d, origin calls %d; want 1, 1", len(s.Requests()), origin.count())
	}
	if body := origin.last().body; body != `{"Key":{"Artist":{"S":"Acme"}},"TableName":"Music"}` {
		t.Errorf("origin body after Close = %q", body)
	}
	if err := cache.Close(); err != nil {
		t.Error(err)
	}
	var none *Client
	if err := none.Close(); err != nil {
		t.Error(err)
	}
}

// TestCloseStrict: after Close a strict client's calls go to DynamoDB, routed or not, as the
// Python client's do once close() removes its handler.
func TestCloseStrict(t *testing.T) {
	testutil.ClearAWSEnv(t)
	s, addr := sdkMock(t)
	c := sdkConfig(t, s, addr)
	c.fallbackUnlimited, c.fallbackDuration = false, 0
	origin := &fakeOrigin{}
	ddb, cache := startSDK(t, c, origin)
	cache.Close()
	ctx := context.Background()
	get, desc := &attempts{}, &attempts{}
	_, getErr := ddb.GetItem(ctx, &dynamodb.GetItemInput{TableName: aws.String("Music"), Key: musicKey()}, get.option)
	_, descErr := ddb.DescribeTable(ctx, &dynamodb.DescribeTableInput{TableName: aws.String("Music")}, desc.option)
	if getErr != nil || descErr != nil {
		t.Fatalf("calls after Close = %v, %v; want DynamoDB's answers", getErr, descErr)
	}
	if get.n != 1 || desc.n != 1 {
		t.Errorf("attempts = %d, %d; want 1, 1", get.n, desc.n)
	}
	if origin.count() != 2 || len(s.Requests()) != 0 {
		t.Errorf("origin calls %d, cache requests %d; want 2, 0", origin.count(), len(s.Requests()))
	}
}

func TestBodyReadErrorFailsCall(t *testing.T) {
	testutil.ClearAWSEnv(t)
	s, addr := sdkMock(t)
	origin := &fakeOrigin{}
	ddb, _ := startSDK(t, sdkConfig(t, s, addr), origin)
	h := ddb.Options().HTTPClient.(*httpClient)
	req := getItemRequest(t)
	req.Body = io.NopCloser(io.MultiReader(strings.NewReader("{"), errReader{}))
	res, err := h.Do(req)
	var se *sdkError
	if res != nil || !errors.As(err, &se) || se.RetryableError() || !errors.Is(err, ErrIntegrations) {
		t.Fatalf("Do = %v, %v", res, err)
	}
	if origin.count() != 0 || len(s.Requests()) != 0 {
		t.Errorf("origin calls %d, cache requests %d; want 0, 0", origin.count(), len(s.Requests()))
	}
}

type errReader struct{}

func (errReader) Read([]byte) (int, error) { return 0, errors.New("read failed") }

func TestDoOutcomes(t *testing.T) {
	testutil.ClearAWSEnv(t)
	s, addr := sdkMock(t)
	origin := &fakeOrigin{}
	ddb, _ := startSDK(t, sdkConfig(t, s, addr), origin)
	h := ddb.Options().HTTPClient.(*httpClient)

	s.SetOnRequest(func(mockcache.Command) mockcache.Reply {
		return mockcache.Now(mockcache.Served(404, map[string]string{"x-a": "1"}, []byte("hit")))
	})
	res, err := h.Do(getItemRequest(t))
	if err != nil || res.StatusCode != 404 || res.Header.Get("X-A") != "1" || res.Header.Get("Age") != "0" {
		t.Fatalf("served Do = %+v, %v", res, err)
	}
	if b, _ := io.ReadAll(res.Body); !bytes.Equal(b, []byte("hit")) {
		t.Errorf("served body = %q", b)
	}

	s.SetOnRequest(func(mockcache.Command) mockcache.Reply {
		return mockcache.Now(errorReply("ORIGIN_ERROR", "throttled"))
	})
	_, err = h.Do(getItemRequest(t))
	var se *sdkError
	if !errors.As(err, &se) || !se.RetryableError() || !errors.As(err, new(*OriginError)) {
		t.Fatalf("failed Do = %v", err)
	}

	s.SetOnRequest(func(mockcache.Command) mockcache.Reply {
		return mockcache.Now(errorReply("PARSE_ERROR", "bad"))
	})
	_, err = h.Do(getItemRequest(t))
	if !errors.As(err, &se) || se.RetryableError() || !errors.As(err, new(*RejectedError)) {
		t.Fatalf("rejected Do = %v", err)
	}

	unsigned := getItemRequest(t)
	unsigned.Header.Del("Authorization")
	if _, err := h.Do(unsigned); err != nil || origin.count() != 1 {
		t.Fatalf("not routable Do = %v, origin calls %d", err, origin.count())
	}
	if origin.last().body != `{"TableName":"Music"}` {
		t.Errorf("origin body = %q", origin.last().body)
	}
}

func errorReply(code, msg string) resp.Value { return resp.ErrorValue(code, msg) }

// A client built from a bare aws.Config or dynamodb.Options, with no retryer of its own,
// gets the SDK's default retryer wrapped: the calls of one drop cost one retry. Without the
// wrap the default bucket grants 35 of them.
func TestInstallWrapsTheSDKDefaultRetryer(t *testing.T) {
	builds := map[string]func(...func(*dynamodb.Options)) *dynamodb.Client{
		"NewFromConfig": func(fns ...func(*dynamodb.Options)) *dynamodb.Client {
			return dynamodb.NewFromConfig(aws.Config{Region: testRegion, Credentials: testutil.StaticCredentials()}, fns...)
		},
		"New": func(fns ...func(*dynamodb.Options)) *dynamodb.Client {
			return dynamodb.New(dynamodb.Options{Region: testRegion, Credentials: testutil.StaticCredentials()}, fns...)
		},
		"NewFromConfig with RetryMaxAttempts": func(fns ...func(*dynamodb.Options)) *dynamodb.Client {
			return dynamodb.NewFromConfig(aws.Config{Region: testRegion, Credentials: testutil.StaticCredentials(),
				RetryMaxAttempts: 2}, fns...)
		},
	}
	for name, build := range builds {
		t.Run(name, func(t *testing.T) {
			c := &Client{}
			ddb := build(c.install)
			if c.installErr != nil {
				t.Fatal(c.installErr)
			}
			r, ok := ddb.Options().Retryer.(aws.RetryerV2)
			if !ok {
				t.Fatalf("Retryer is %T, not an aws.RetryerV2", ddb.Options().Retryer)
			}
			d := &dropCharge{}
			if n := tokens(r, repeat(100, func() error { return lostErr(d) })...); n != 100 {
				t.Fatalf("%d of 100 calls of one drop got a retry token, want 100", n)
			}
		})
	}
}
