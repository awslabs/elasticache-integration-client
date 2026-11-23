// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package e2e

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/aws/ratelimit"
	"github.com/aws/aws-sdk-go-v2/aws/retry"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"

	"github.com/awslabs/elasticache-integration-client/go/elasticacheintegrations"
	"github.com/awslabs/elasticache-integration-client/go/internal/mockcache"
	"github.com/awslabs/elasticache-integration-client/go/internal/testutil"
)

// TestMain makes every test hermetic: no AWS_* variable and no shared config or credentials
// file can supply a region, credentials or an endpoint. testutil.ClearAWSEnv does this per
// test, which parallel tests cannot use.
func TestMain(m *testing.M) {
	for _, kv := range os.Environ() {
		if name, _, _ := strings.Cut(kv, "="); strings.HasPrefix(name, "AWS_") {
			_ = os.Unsetenv(name)
		}
	}
	dir, err := os.MkdirTemp("", "elasticache-integrations-e2e-")
	if err != nil {
		panic(err)
	}
	missing := filepath.Join(dir, "missing")
	_ = os.Setenv("AWS_CONFIG_FILE", missing)
	_ = os.Setenv("AWS_SHARED_CREDENTIALS_FILE", missing)
	_ = os.Setenv("AWS_EC2_METADATA_DISABLED", "true")
	code := m.Run()
	_ = os.RemoveAll(dir)
	os.Exit(code)
}

// harnessConfig is what a test changes before the client is built.
type harnessConfig struct {
	options      elasticacheintegrations.Options
	configJSON   string
	retries      int
	relay        bool
	credentials  aws.CredentialsProvider
	region       string
	setupCache   func(*mockcache.Server)
	startMayFail bool
	systemRoots  bool
	sdkOptions   []func(*dynamodb.Options)
}

func withOptions(f func(*elasticacheintegrations.Options)) func(*harnessConfig) {
	return func(hc *harnessConfig) { f(&hc.options) }
}

// withSDKOptions adds f to the DynamoDB options NewFromConfig gets, after the retry settings.
func withSDKOptions(f func(*dynamodb.Options)) func(*harnessConfig) {
	return func(hc *harnessConfig) { hc.sdkOptions = append(hc.sdkOptions, f) }
}

// withConfig makes the cache answer the first aws.config with configJSON.
func withConfig(configJSON string) func(*harnessConfig) {
	return func(hc *harnessConfig) { hc.configJSON = configJSON }
}

// withCache runs f on the cache before the client connects, to set hooks for start-up.
func withCache(f func(*mockcache.Server)) func(*harnessConfig) {
	return func(hc *harnessConfig) { hc.setupCache = f }
}

// systemRoots leaves CACertsFile empty, so the client trusts only the system roots.
func systemRoots() func(*harnessConfig) { return func(hc *harnessConfig) { hc.systemRoots = true } }

// startMayFail keeps a start-up error for the test (harness.startErr) instead of failing it.
func startMayFail() func(*harnessConfig) { return func(hc *harnessConfig) { hc.startMayFail = true } }

// harness is one client under test with everything around it.
type harness struct {
	t        *testing.T
	cache    *mockcache.Server
	addr     string // the cache's own address
	caFile   string
	proxy    *proxy
	dynamo   *dynamo
	relay    *mockcache.Relay // nil without withRelay
	ddb      *dynamodb.Client
	client   *elasticacheintegrations.Client
	startErr error
	logs     *testutil.LogCapture
	started  time.Duration // how long NewFromConfig took

	mu   sync.Mutex
	errs []error // every error a test checked, for the no-secrets check
}

// newHarness starts a TLS mock cache whose aws.request handler is the caching proxy, a fake
// DynamoDB behind it, and a client built with NewFromConfig whose origin is the SDK's default
// HTTP client talking to the same fake.
func newHarness(t *testing.T, setup ...func(*harnessConfig)) *harness {
	t.Helper()
	hc := harnessConfig{retries: 3, region: "us-east-1", credentials: testutil.StaticCredentials()}
	for _, f := range setup {
		f(&hc)
	}
	h := &harness{t: t, dynamo: newDynamo(t)}
	cache, err := mockcache.New(mockcache.Config{TLS: true, ConfigJSON: hc.configJSON})
	if err != nil {
		t.Fatal(err)
	}
	h.cache = cache
	if h.addr, err = cache.Listen(); err != nil {
		t.Fatal(err)
	}
	h.proxy = newProxy(h.dynamo)
	cache.SetOnRequest(h.proxy.handle)
	if hc.setupCache != nil {
		hc.setupCache(cache)
	}
	h.caFile = filepath.Join(t.TempDir(), "ca.pem")
	if err := os.WriteFile(h.caFile, cache.CertPEM(), 0o600); err != nil {
		t.Fatal(err)
	}
	endpoint := h.addr
	if hc.relay {
		if h.relay, err = mockcache.NewRelay(h.addr); err != nil {
			t.Fatal(err)
		}
		endpoint = h.relay.Addr()
	}
	capture, logger := testutil.NewLogCapture()
	h.logs = capture
	opts := hc.options
	opts.Endpoint, opts.CacheName = endpoint, "my-cache"
	if opts.CACertsFile == "" && !opts.InsecureSkipVerify && !hc.systemRoots {
		opts.CACertsFile = h.caFile
	}
	if opts.Logger == nil {
		opts.Logger = logger
	}
	t.Cleanup(h.cleanup)
	begin := time.Now()
	h.ddb, h.client, h.startErr = elasticacheintegrations.NewFromConfig(context.Background(),
		h.awsConfig(hc.region, hc.credentials), opts, append([]func(*dynamodb.Options){sdkRetries(hc.retries)}, hc.sdkOptions...)...)
	h.started = time.Since(begin)
	if h.seen(h.startErr) != nil {
		if !hc.startMayFail {
			t.Fatalf("NewFromConfig: %v", h.startErr)
		}
	}
	return h
}

// awsConfig is the application's aws.Config: its own credentials and region, and the fake
// DynamoDB as the endpoint.
func (h *harness) awsConfig(region string, creds aws.CredentialsProvider) aws.Config {
	return aws.Config{Region: region, Credentials: creds, BaseEndpoint: aws.String(h.dynamo.url())}
}

// sdkRetries gives the SDK n attempts with no backoff and no retry quota, so a retry costs no
// time and many failures cannot empty the retry bucket.
func sdkRetries(n int) func(*dynamodb.Options) {
	return func(o *dynamodb.Options) {
		o.Retryer = retry.NewStandard(func(so *retry.StandardOptions) {
			so.MaxAttempts = n
			so.Backoff = noBackoff{}
			so.RateLimiter = ratelimit.None
		})
	}
}

type noBackoff struct{}

func (noBackoff) BackoffDelay(int, error) (time.Duration, error) { return 0, nil }

// plainClient is an SDK client without the cache, on d.
func plainClient(d *dynamo) *dynamodb.Client {
	return dynamodb.NewFromConfig(aws.Config{Region: "us-east-1", Credentials: testutil.StaticCredentials(),
		BaseEndpoint: aws.String(d.url())}, sdkRetries(1))
}

func (h *harness) cleanup() {
	t := h.t
	if h.client != nil {
		_ = h.client.Close()
	}
	if h.relay != nil {
		_ = h.relay.Close()
	}
	if err := h.cache.Close(); err != nil {
		t.Errorf("mock cache: %v", err)
	}
	var secrets []string
	secrets = append(secrets, testutil.Secrets()...)
	for _, cmd := range h.cache.Commands() {
		switch cmd.Name() {
		case "HELLO":
			if len(cmd.Args) == 5 {
				secrets = append(secrets, testutil.TokenSecrets(cmd.Arg(4))...)
			}
		case "AUTH":
			if len(cmd.Args) == 3 {
				secrets = append(secrets, testutil.TokenSecrets(cmd.Arg(2))...)
			}
		}
	}
	// What the client sent the cache besides the body: no credential, signature or token.
	var wire strings.Builder
	for _, cmd := range h.cache.Requests() {
		for _, i := range []int{1, 2, 3, 5} {
			wire.WriteString(cmd.Arg(i) + "\n")
		}
	}
	testutil.AssertNoSecrets(t, wire.String(), testutil.AccessKeyID, testutil.SecretAccessKey, testutil.SessionToken)
	var text strings.Builder
	text.WriteString(h.logs.Text())
	h.mu.Lock()
	for _, err := range h.errs {
		text.WriteString(err.Error() + "\n")
	}
	h.mu.Unlock()
	testutil.AssertNoSecrets(t, text.String(), secrets...)
}

// seen records err for the no-secrets check and returns it.
func (h *harness) seen(err error) error {
	if err != nil {
		h.mu.Lock()
		h.errs = append(h.errs, err)
		h.mu.Unlock()
	}
	return err
}

// eventually polls cond every 5 ms for up to 10 s.
func eventually(t *testing.T, what string, cond func() bool) {
	t.Helper()
	for end := time.Now().Add(10 * time.Second); !cond(); {
		if time.Now().After(end) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(5 * time.Millisecond)
	}
}

// requests counts the valid aws.requests the cache received.
func (h *harness) requests() int { return len(h.cache.Requests()) }

// errorAs finds a T in err's chain, or fails the test.
func errorAs[T error](t *testing.T, err error) T {
	t.Helper()
	var target T
	if !errors.As(err, &target) {
		t.Fatalf("error %v (%T) has no %T", err, err, target)
	}
	return target
}
