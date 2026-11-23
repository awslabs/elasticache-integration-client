// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/aws/retry"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
	"github.com/aws/smithy-go"
	"github.com/aws/smithy-go/middleware"
	smithyhttp "github.com/aws/smithy-go/transport/http"

	"github.com/awslabs/elasticache-integration-client/go/internal/mockcache"
	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
	"github.com/awslabs/elasticache-integration-client/go/internal/testutil"
)

type noBackoff struct{}

func (noBackoff) BackoffDelay(int, error) (time.Duration, error) { return 0, nil }

// withRetries makes the SDK's standard retryer try n times with no backoff.
func withRetries(n int) func(*dynamodb.Options) {
	return func(o *dynamodb.Options) {
		o.Retryer = retry.NewStandard(func(so *retry.StandardOptions) {
			so.MaxAttempts = n
			so.Backoff = noBackoff{}
		})
	}
}

func getMusic(ctx context.Context, ddb *dynamodb.Client) (*dynamodb.GetItemOutput, error) {
	return ddb.GetItem(ctx, &dynamodb.GetItemInput{TableName: aws.String("Music"), Key: musicKey()})
}

// replySequence answers the nth aws.request with replies[n], and the last reply after that.
func replySequence(replies ...mockcache.Reply) func(mockcache.Command) mockcache.Reply {
	var n atomic.Int64
	return func(mockcache.Command) mockcache.Reply {
		i := min(int(n.Add(1))-1, len(replies)-1)
		return replies[i]
	}
}

const musicItem = `{"Item":{"Artist":{"S":"Acme"}}}`

func TestSDKRetriesRetryableOutcome(t *testing.T) {
	testutil.ClearAWSEnv(t)
	first := map[string]mockcache.Reply{
		"ORIGIN_ERROR":   mockcache.Now(resp.ErrorValue("ORIGIN_ERROR", "try again")),
		"INTERNAL_ERROR": mockcache.Now(resp.ErrorValue("INTERNAL_ERROR", "try again")),
		"MOVED":          mockcache.Now(resp.ErrorValue("MOVED", "1 10.0.0.1:6379")),
		"dropped":        mockcache.Drop(),
	}
	for name, reply := range first {
		t.Run(name, func(t *testing.T) {
			s, addr := sdkMock(t)
			s.SetOnRequest(replySequence(reply, servedJSON(200, musicItem)))
			origin := &fakeOrigin{}
			ddb, _ := startSDK(t, sdkConfig(t, s, addr), origin, withRetries(3))
			out, err := getMusic(context.Background(), ddb)
			if err != nil || attrS(t, out.Item, "Artist") != "Acme" {
				t.Fatalf("GetItem = %v, %v", out, err)
			}
			if n := len(s.Requests()); n != 2 || origin.count() != 0 {
				t.Errorf("cache requests %d, origin calls %d; want 2, 0", n, origin.count())
			}
		})
	}
}

func TestSDKRetriesTimeout(t *testing.T) {
	testutil.ClearAWSEnv(t)
	s, addr := sdkMock(t)
	s.SetOnRequest(replySequence(mockcache.Hang(), servedJSON(200, musicItem)))
	c := sdkConfig(t, s, addr)
	c.cacheRequestTimeout = 200 * time.Millisecond
	ddb, _ := startSDK(t, c, &fakeOrigin{}, withRetries(2))
	out, err := getMusic(context.Background(), ddb)
	if err != nil || attrS(t, out.Item, "Artist") != "Acme" {
		t.Fatalf("GetItem = %v, %v", out, err)
	}
	if n := len(s.Requests()); n != 2 {
		t.Errorf("cache requests = %d, want 2", n)
	}
}

func TestSDKDoesNotRetryTerminalOutcome(t *testing.T) {
	testutil.ClearAWSEnv(t)
	s, addr := sdkMock(t)
	s.SetOnRequest(func(mockcache.Command) mockcache.Reply {
		return mockcache.Now(resp.ErrorValue("PARSE_ERROR", "bad request"))
	})
	origin := &fakeOrigin{}
	ddb, _ := startSDK(t, sdkConfig(t, s, addr), origin, withRetries(3))
	_, err := getMusic(context.Background(), ddb)
	var op *smithy.OperationError
	var rejected *RejectedError
	if !errors.As(err, &op) || !errors.As(err, &rejected) || rejected.Code != "PARSE_ERROR" || !errors.Is(err, ErrIntegrations) {
		t.Fatalf("err = %v", err)
	}
	if n := len(s.Requests()); n != 1 || origin.count() != 0 {
		t.Errorf("cache requests %d, origin calls %d; want 1, 0", n, origin.count())
	}
}

func TestSDKRetriesExhausted(t *testing.T) {
	testutil.ClearAWSEnv(t)
	s, addr := sdkMock(t)
	s.SetOnRequest(func(mockcache.Command) mockcache.Reply {
		return mockcache.Now(resp.ErrorValue("ORIGIN_ERROR", "throttled"))
	})
	ddb, _ := startSDK(t, sdkConfig(t, s, addr), &fakeOrigin{}, withRetries(3))
	_, err := getMusic(context.Background(), ddb)
	var oe *OriginError
	var max *retry.MaxAttemptsError
	if !errors.As(err, &oe) || !errors.As(err, &max) || max.Attempt != 3 {
		t.Fatalf("err = %v", err)
	}
	if n := len(s.Requests()); n != 3 {
		t.Errorf("cache requests = %d, want 3", n)
	}
}

func TestSDKAuthErrorGoesToOrigin(t *testing.T) {
	testutil.ClearAWSEnv(t)
	s, addr := sdkMock(t)
	s.SetOnRequest(replySequence(mockcache.Now(resp.ErrorValue("AUTH_ERROR", "no FAS")), servedJSON(200, musicItem)))
	origin := &fakeOrigin{respond: func(req *http.Request) (*http.Response, error) {
		return jsonResponse(req, 200, `{"Item":{"Artist":{"S":"FromDynamoDB"}}}`), nil
	}}
	ddb, _ := startSDK(t, sdkConfig(t, s, addr), origin, withRetries(1))
	out, err := getMusic(context.Background(), ddb)
	if err != nil || attrS(t, out.Item, "Artist") != "FromDynamoDB" {
		t.Fatalf("GetItem = %v, %v", out, err)
	}
	if origin.count() != 1 || origin.last().body != `{"Key":{"Artist":{"S":"Acme"}},"TableName":"Music"}` {
		t.Errorf("origin calls = %+v", origin.calls)
	}
}

func TestCancellation(t *testing.T) {
	testutil.ClearAWSEnv(t)
	s, addr := sdkMock(t)
	ctx, stop := context.WithCancel(context.Background())
	var once sync.Once
	// Cancel once the cache holds the request, which it never answers.
	s.SetOnRequest(func(mockcache.Command) mockcache.Reply { once.Do(stop); return mockcache.Hang() })
	origin := &fakeOrigin{}
	ddb, _ := startSDK(t, sdkConfig(t, s, addr), origin, withRetries(3))

	begin := time.Now()
	_, err := getMusic(ctx, ddb)
	if !errors.Is(err, context.Canceled) || time.Since(begin) > 2*time.Second {
		t.Fatalf("err = %v after %v; want context.Canceled at once", err, time.Since(begin))
	}
	if n := len(s.Requests()); n != 1 || origin.count() != 0 {
		t.Errorf("cache requests %d, origin calls %d; want 1, 0 (no retry)", n, origin.count())
	}

	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	_, err = getMusic(ctx, ddb)
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("err = %v; want context.DeadlineExceeded", err)
	}
	if origin.count() != 0 {
		t.Errorf("origin calls = %d, want 0", origin.count())
	}
}

// signedAt is the X-Amz-Date the SDK signed an origin call with.
func signedAt(t *testing.T, call originCall) time.Time {
	t.Helper()
	at, err := time.Parse("20060102T150405Z", call.header.Get("X-Amz-Date"))
	if err != nil {
		t.Fatal(err)
	}
	return at
}

func TestServedHitDoesNotSkewSigningClock(t *testing.T) {
	testutil.ClearAWSEnv(t)
	stale := time.Now().Add(-time.Hour).UTC().Format(http.TimeFormat)
	listTables := func(ddb *dynamodb.Client) {
		if _, err := ddb.ListTables(context.Background(), &dynamodb.ListTablesInput{}, func(o *dynamodb.Options) {
			o.RetryMaxAttempts = 1
		}); err != nil {
			t.Fatal(err)
		}
	}

	t.Run("served", func(t *testing.T) {
		s, addr := sdkMock(t)
		s.SetOnRequest(func(mockcache.Command) mockcache.Reply {
			return mockcache.Now(mockcache.Served(200, map[string]string{
				"content-type": "application/x-amz-json-1.0", "x-amz-crc32": crc(musicItem), "date": stale,
			}, []byte(musicItem)))
		})
		origin := &fakeOrigin{}
		ddb, _ := startSDK(t, sdkConfig(t, s, addr), origin)
		if _, err := getMusic(context.Background(), ddb); err != nil {
			t.Fatal(err)
		}
		listTables(ddb)
		if d := time.Since(signedAt(t, origin.last())); d > time.Minute || d < -time.Minute {
			t.Errorf("the origin call was signed %v off the clock", d)
		}
	})

	// The same stale Date from DynamoDB itself, with no Age, does skew the clock: the SDK
	// still corrects skew from responses, so the test above can fail.
	t.Run("control", func(t *testing.T) {
		s, addr := sdkMock(t)
		var calls atomic.Int64
		origin := &fakeOrigin{respond: func(req *http.Request) (*http.Response, error) {
			r := jsonResponse(req, 200, `{"TableNames":[]}`)
			if calls.Add(1) == 1 {
				r.Header.Set("Date", stale)
			}
			return r, nil
		}}
		ddb, _ := startSDK(t, sdkConfig(t, s, addr), origin)
		listTables(ddb)
		listTables(ddb)
		if d := time.Since(signedAt(t, origin.last())); d < 50*time.Minute {
			t.Errorf("the SDK did not correct skew from a stale Date (%v); the served test proves nothing", d)
		}
	})
}

func TestServedHeadersReachSDK(t *testing.T) {
	testutil.ClearAWSEnv(t)
	t.Run("crc32 mismatch", func(t *testing.T) {
		s, addr := sdkMock(t)
		s.SetOnRequest(func(mockcache.Command) mockcache.Reply {
			return mockcache.Now(mockcache.Served(200, map[string]string{
				"content-type": "application/x-amz-json-1.0", "x-amz-crc32": "1",
			}, []byte(musicItem)))
		})
		ddb, _ := startSDK(t, sdkConfig(t, s, addr), &fakeOrigin{}, withRetries(1))
		_, err := getMusic(context.Background(), ddb)
		if err == nil || !strings.Contains(err.Error(), "checksum") {
			t.Fatalf("err = %v; want a checksum mismatch", err)
		}
	})
	t.Run("error type header", func(t *testing.T) {
		s, addr := sdkMock(t)
		body := `{"message":"Requested resource not found"}`
		s.SetOnRequest(func(mockcache.Command) mockcache.Reply {
			return mockcache.Now(mockcache.Served(400, map[string]string{
				"content-type":     "application/x-amz-json-1.0",
				"x-amz-crc32":      crc(body),
				"x-amzn-errortype": "ResourceNotFoundException:http://internal.amazon.com/coral/com.amazonaws.dynamodb.v20120810/",
			}, []byte(body)))
		})
		ddb, _ := startSDK(t, sdkConfig(t, s, addr), &fakeOrigin{}, withRetries(1))
		_, err := getMusic(context.Background(), ddb)
		var rnf *types.ResourceNotFoundException
		if !errors.As(err, &rnf) || rnf.ErrorMessage() != "Requested resource not found" {
			t.Fatalf("err = %v; want ResourceNotFoundException", err)
		}
	})
}

func TestPerCallCredentialsGoToOrigin(t *testing.T) {
	testutil.ClearAWSEnv(t)
	s, addr := sdkMock(t)
	var key atomic.Value
	key.Store(testutil.AccessKeyID)
	rotating := aws.CredentialsProviderFunc(func(context.Context) (aws.Credentials, error) {
		return aws.Credentials{AccessKeyID: key.Load().(string), SecretAccessKey: testutil.SecretAccessKey, SessionToken: testutil.SessionToken}, nil
	})
	other := aws.CredentialsProviderFunc(func(context.Context) (aws.Credentials, error) {
		return aws.Credentials{AccessKeyID: "AKIDOTHERPRINCIPAL01", SecretAccessKey: "other"}, nil
	})
	origin := &fakeOrigin{}
	ddb, _ := startSDKWith(t, aws.Config{Region: testRegion, Credentials: rotating}, sdkConfig(t, s, addr), origin)
	ctx := context.Background()
	in := &dynamodb.GetItemInput{TableName: aws.String("Music"), Key: musicKey()}

	if _, err := ddb.GetItem(ctx, in, func(o *dynamodb.Options) { o.Credentials = other }); err != nil {
		t.Fatal(err)
	}
	if _, err := ddb.GetItem(ctx, in, func(o *dynamodb.Options) { o.Region = "us-west-2" }); err != nil {
		t.Fatal(err)
	}
	if origin.count() != 2 || len(s.Requests()) != 0 {
		t.Fatalf("origin calls %d, cache requests %d; want 2, 0", origin.count(), len(s.Requests()))
	}
	if !strings.Contains(origin.calls[0].header.Get("Authorization"), "Credential=AKIDOTHERPRINCIPAL01/") ||
		!strings.Contains(origin.calls[1].header.Get("Authorization"), "/us-west-2/dynamodb/") {
		t.Error("the origin did not get the request as the SDK signed it")
	}

	key.Store("AKIDROTATEDKEY000001")
	if _, err := ddb.GetItem(ctx, in); err != nil {
		t.Fatal(err)
	}
	if origin.count() != 2 || len(s.Requests()) != 1 {
		t.Errorf("a rotated key of the client's own provider did not route: origin %d, cache %d", origin.count(), len(s.Requests()))
	}
}

func TestConcurrentHitsKeepTheirBodies(t *testing.T) {
	testutil.ClearAWSEnv(t)
	s, addr := sdkMock(t)
	s.SetOnRequest(func(cmd mockcache.Command) mockcache.Reply {
		var in struct {
			Key struct {
				Artist struct{ S string }
			}
		}
		if err := json.Unmarshal(cmd.Args[4], &in); err != nil {
			return mockcache.Now(resp.ErrorValue("PARSE_ERROR", "bad body"))
		}
		id := in.Key.Artist.S
		size := 100
		if strings.HasSuffix(id, "7") {
			size = 200 << 10 // larger than the 64 KiB read buffer
		}
		body := fmt.Sprintf(`{"Item":{"Artist":{"S":%q},"Data":{"S":%q}}}`, id, strings.Repeat(id[len(id)-1:], size))
		return servedJSON(200, body)
	})
	ddb, _ := startSDK(t, sdkConfig(t, s, addr), &fakeOrigin{})
	var wg sync.WaitGroup
	for g := range 32 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			id := fmt.Sprintf("artist-%d", g)
			for range 8 {
				out, err := ddb.GetItem(context.Background(), &dynamodb.GetItemInput{
					TableName: aws.String("Music"),
					Key:       map[string]types.AttributeValue{"Artist": &types.AttributeValueMemberS{Value: id}},
				})
				if err != nil {
					t.Error(err)
					return
				}
				artist, _ := out.Item["Artist"].(*types.AttributeValueMemberS)
				data, _ := out.Item["Data"].(*types.AttributeValueMemberS)
				if artist == nil || artist.Value != id || data == nil || strings.Trim(data.Value, id[len(id)-1:]) != "" {
					t.Errorf("goroutine %d got another item", g)
					return
				}
			}
		}()
	}
	wg.Wait()
}

// signatures records the SigV4 signature of every request the SDK signs.
type signatures struct {
	mu   sync.Mutex
	seen []string
}

func (sg *signatures) option(o *dynamodb.Options) {
	o.APIOptions = append(o.APIOptions, func(st *middleware.Stack) error {
		return st.Finalize.Add(middleware.FinalizeMiddlewareFunc("recordSignature",
			func(ctx context.Context, in middleware.FinalizeInput, next middleware.FinalizeHandler) (middleware.FinalizeOutput, middleware.Metadata, error) {
				if req, ok := in.Request.(*smithyhttp.Request); ok {
					if _, sig, ok := strings.Cut(req.Header.Get("Authorization"), "Signature="); ok {
						sg.mu.Lock()
						sg.seen = append(sg.seen, sig)
						sg.mu.Unlock()
					}
				}
				return next.HandleFinalize(ctx, in)
			}), middleware.After)
	})
}

func (sg *signatures) all() []string {
	sg.mu.Lock()
	defer sg.mu.Unlock()
	return slices.Clone(sg.seen)
}

// authCount is the number of AUTH commands s received.
func authCount(s *mockcache.Server) int {
	n := 0
	for _, cmd := range s.Commands() {
		if cmd.Name() == "AUTH" {
			n++
		}
	}
	return n
}

func TestSDKNoSecretsInLogsOrErrors(t *testing.T) {
	testutil.ClearAWSEnv(t)
	capture, lg := testutil.NewLogCapture()
	s, addr := sdkMock(t)
	responseItem := `{"Item":{"Artist":{"S":"` + testutil.ResponseMarker + `"}}}`
	s.SetOnRequest(replySequence(
		servedJSON(200, responseItem),
		mockcache.Now(resp.ErrorValue("PARSE_ERROR", "bad request")),
		mockcache.Drop(),
		mockcache.Now(resp.ErrorValue("AUTH_ERROR", "no FAS")),
		mockcache.Now(resp.ErrorValue("ORIGIN_ERROR", "throttled")),
	))
	c := sdkConfig(t, s, addr)
	c.logger = newLogger(lg)
	c.reauthInterval, c.jitter = 100*time.Millisecond, 0
	var sigs signatures
	ddb, _ := startSDK(t, c, &fakeOrigin{}, withRetries(1), sigs.option)
	in := &dynamodb.GetItemInput{TableName: aws.String("Music"), Key: map[string]types.AttributeValue{
		"Artist": &types.AttributeValueMemberS{Value: testutil.BodyMarker}}}
	var errs []error
	for range 5 {
		if _, err := ddb.GetItem(context.Background(), in); err != nil {
			errs = append(errs, err)
		}
	}
	if len(errs) != 3 {
		t.Errorf("errors = %v; want PARSE_ERROR, a lost connection and ORIGIN_ERROR", errs)
	}
	if len(sigs.all()) != 5 {
		t.Errorf("signatures = %d, want 5", len(sigs.all()))
	}

	// A re-AUTH the cache refuses with the token echoed back, then one it accepts.
	var refused atomic.Bool
	s.SetIntercept(func(cmd mockcache.Command) *mockcache.Reply {
		if cmd.Name() != "AUTH" || refused.Swap(true) {
			return nil
		}
		r := mockcache.Now(resp.ErrorValue("WRONGPASS", cmd.Arg(len(cmd.Args)-1)))
		return &r
	})
	auths := authCount(s)
	for deadline := time.Now().Add(10 * time.Second); authCount(s) < auths+2; {
		if time.Now().After(deadline) {
			t.Fatalf("re-AUTH did not run: %d AUTH commands", authCount(s))
		}
		time.Sleep(10 * time.Millisecond)
	}
	if !strings.Contains(capture.Text(), "re-AUTH") {
		t.Errorf("no re-AUTH record:\n%s", capture.Text())
	}

	// A start-up whose HELLO is refused with the token echoed back. The first backoff outlasts the connection timeout, so
	// the refusal is the only attempt and the error it returns.
	bad, badAddr := sdkMock(t)
	bad.SetIntercept(func(cmd mockcache.Command) *mockcache.Reply {
		if cmd.Name() != "HELLO" {
			return nil
		}
		r := mockcache.Now(resp.ErrorValue("WRONGPASS", cmd.Arg(len(cmd.Args)-1)))
		return &r
	})
	bc := sdkConfig(t, bad, badAddr)
	bc.logger = newLogger(lg)
	bc.connectionTimeout = time.Second
	bc.backoffMin, bc.backoffMax, bc.jitter = 2*time.Second, 2*time.Second, 0
	_, _, err := start(context.Background(), bc, func(fns ...func(*dynamodb.Options)) *dynamodb.Client {
		return dynamodb.NewFromConfig(awsConfig(), fns...)
	}, nil)
	if !errors.As(err, new(*AuthError)) {
		t.Errorf("start = %v; want an AuthError", err)
	}
	errs = append(errs, err)

	var text strings.Builder
	text.WriteString(capture.Text())
	for _, err := range errs {
		fmt.Fprintf(&text, "%v\n%+v\n", err, err)
	}
	secrets := append(testutil.Secrets(), sigs.all()...)
	for _, srv := range []*mockcache.Server{s, bad} {
		for _, cmd := range srv.Commands() {
			if cmd.Name() == "HELLO" || cmd.Name() == "AUTH" {
				secrets = append(secrets, testutil.TokenSecrets(cmd.Arg(len(cmd.Args)-1))...)
			}
		}
	}
	testutil.AssertNoSecrets(t, text.String(), secrets...)
}
