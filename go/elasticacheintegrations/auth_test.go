// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/awslabs/elasticache-integration-client/go/internal/testutil"
)

type tokenVectors struct {
	Source      string        `json:"source"`
	SigningTime time.Time     `json:"signing_time"`
	Vectors     []tokenVector `json:"vectors"`
}

type tokenVector struct {
	CacheName       string `json:"cache_name"`
	User            string `json:"user"`
	Region          string `json:"region"`
	AccessKeyID     string `json:"access_key_id"`
	SecretAccessKey string `json:"secret_access_key"`
	SessionToken    string `json:"session_token"`
	Token           string `json:"token"`
}

func loadTokenVectors(t *testing.T) tokenVectors {
	t.Helper()
	var v tokenVectors
	testutil.DecodeStrict(t, "testdata/iam-token-vectors.json", &v)
	if len(v.Vectors) == 0 || v.SigningTime.IsZero() {
		t.Fatal("no vectors")
	}
	return v
}

// splitToken returns the host and the query of a token, failing on any other shape.
func splitToken(t *testing.T, token string) (string, url.Values) {
	t.Helper()
	host, rawQuery, ok := strings.Cut(token, "/?")
	if !ok {
		t.Fatalf("token has no /?: %q", token)
	}
	q, err := url.ParseQuery(rawQuery)
	if err != nil {
		t.Fatal(err)
	}
	return host, q
}

func signVector(t *testing.T, v tokenVector, cache string, now time.Time) string {
	t.Helper()
	creds := aws.Credentials{AccessKeyID: v.AccessKeyID, SecretAccessKey: v.SecretAccessKey, SessionToken: v.SessionToken}
	tok, err := signToken(context.Background(), creds, cache, v.User, v.Region, now)
	if err != nil {
		t.Fatal(err)
	}
	return tok.reveal()
}

func TestSignTokenMatchesBotocoreVectors(t *testing.T) {
	vs := loadTokenVectors(t)
	for _, v := range vs.Vectors {
		t.Run(v.User, func(t *testing.T) {
			got := signVector(t, v, v.CacheName, vs.SigningTime)
			gotHost, gotQuery := splitToken(t, got)
			wantHost, wantQuery := splitToken(t, v.Token)
			if gotHost != wantHost {
				t.Errorf("host %q, want %q", gotHost, wantHost)
			}
			if fmt.Sprint(gotQuery) != fmt.Sprint(wantQuery) {
				t.Errorf("query\n got %v\nwant %v", gotQuery, wantQuery)
			}
			if gotQuery.Get("X-Amz-Signature") != wantQuery.Get("X-Amz-Signature") {
				t.Errorf("signature %q, want %q", gotQuery.Get("X-Amz-Signature"), wantQuery.Get("X-Amz-Signature"))
			}
			if _, ok := gotQuery["X-Amz-Security-Token"]; ok != (v.SessionToken != "") {
				t.Errorf("X-Amz-Security-Token present = %v with session token %q", ok, v.SessionToken)
			}
		})
	}
}

func TestSignTokenQueryIsCanonical(t *testing.T) {
	vs := loadTokenVectors(t)
	got := signVector(t, vs.Vectors[0], vs.Vectors[0].CacheName, vs.SigningTime)
	_, rawQuery, _ := strings.Cut(got, "/?")
	var keys []string
	for _, kv := range strings.Split(rawQuery, "&") {
		k, _, _ := strings.Cut(kv, "=")
		keys = append(keys, k)
	}
	want := "Action ResourceType User X-Amz-Algorithm X-Amz-Credential X-Amz-Date X-Amz-Expires " +
		"X-Amz-Security-Token X-Amz-SignedHeaders X-Amz-Signature"
	if strings.Join(keys, " ") != want {
		t.Errorf("query keys %v", keys)
	}
	if strings.Contains(rawQuery, "+") {
		t.Errorf("query has a +: %q", rawQuery)
	}
}

func TestSignTokenLowercasesHostAndUsesUTC(t *testing.T) {
	vs := loadTokenVectors(t)
	v := vs.Vectors[1]
	_, want := splitToken(t, v.Token)
	got := signVector(t, v, "My-Cache", vs.SigningTime.In(time.FixedZone("x", -9*3600)))
	if !strings.HasPrefix(got, "my-cache/?") {
		t.Errorf("token %q does not start with my-cache/?", got)
	}
	_, q := splitToken(t, got)
	if q.Get("X-Amz-Signature") != want.Get("X-Amz-Signature") || q.Get("X-Amz-Date") != want.Get("X-Amz-Date") {
		t.Errorf("got %v, want %v", q, want)
	}
}

func TestSignTokenHasNoScheme(t *testing.T) {
	tok, err := signToken(context.Background(), testCreds(), "c", "u", "us-east-1", time.Now())
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(tok.reveal(), "c/?Action=connect&") {
		t.Errorf("token %q", tok.reveal())
	}
}

func testCreds() aws.Credentials {
	return aws.Credentials{AccessKeyID: testutil.AccessKeyID, SecretAccessKey: testutil.SecretAccessKey, SessionToken: testutil.SessionToken}
}

func credsFunc(creds aws.Credentials, err error) aws.CredentialsProvider {
	return aws.CredentialsProviderFunc(func(context.Context) (aws.Credentials, error) { return creds, err })
}

func TestResolveIdentity(t *testing.T) {
	errProvider := errors.New("provider failed")
	cases := []struct {
		name     string
		region   string
		provider aws.CredentialsProvider
		option   string
		cause    error
	}{
		{"no region", "", testutil.StaticCredentials(), "Region", nil},
		{"nil provider", "us-east-1", nil, "Credentials", nil},
		{"anonymous", "us-east-1", aws.AnonymousCredentials{}, "Credentials", nil},
		{"anonymous in cache", "us-east-1", aws.NewCredentialsCache(aws.AnonymousCredentials{}), "Credentials", nil},
		{"retrieve fails", "us-east-1", credsFunc(aws.Credentials{}, errProvider), "Credentials", errProvider},
		{"no access key", "us-east-1", credsFunc(aws.Credentials{SecretAccessKey: "s"}, nil), "Credentials", nil},
		{"no secret key", "us-east-1", credsFunc(aws.Credentials{AccessKeyID: "a"}, nil), "Credentials", nil},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			err := resolveIdentity(context.Background(), tc.region, tc.provider)
			var ce *ConfigError
			if !errors.As(err, &ce) || ce.Option != tc.option {
				t.Fatalf("err = %v, want *ConfigError for %s", err, tc.option)
			}
			if !errors.Is(err, ErrIntegrations) {
				t.Error("does not match ErrIntegrations")
			}
			if tc.cause != nil && !errors.Is(err, tc.cause) {
				t.Errorf("err = %v does not wrap the provider error", err)
			}
			if tc.cause == nil && ce.Err != nil {
				t.Errorf("err = %v has a cause, want none", err)
			}
		})
	}
	if err := resolveIdentity(context.Background(), "us-east-1", aws.NewCredentialsCache(testutil.StaticCredentials())); err != nil {
		t.Errorf("good credentials: %v", err)
	}
}

func TestResolveIdentityCancelled(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	providers := map[string]aws.CredentialsProvider{
		"provider fails": aws.CredentialsProviderFunc(func(ctx context.Context) (aws.Credentials, error) { return aws.Credentials{}, ctx.Err() }),
		"provider works": aws.NewCredentialsCache(testutil.StaticCredentials()),
	}
	for name, p := range providers {
		t.Run(name, func(t *testing.T) {
			if err := resolveIdentity(ctx, "us-east-1", p); err != context.Canceled {
				t.Errorf("err = %v, want context.Canceled itself", err)
			}
		})
	}
	calls := 0
	counting := aws.CredentialsProviderFunc(func(context.Context) (aws.Credentials, error) {
		calls++
		return testCreds(), nil
	})
	if err := resolveIdentity(ctx, "us-east-1", counting); err != context.Canceled || calls != 0 {
		t.Errorf("err = %v after %d Retrieve calls, want context.Canceled and none", err, calls)
	}
}

func TestMatchesIdentity(t *testing.T) {
	key := "AKIDCURRENT"
	provider := newSignerProvider(aws.CredentialsProviderFunc(func(context.Context) (aws.Credentials, error) {
		return aws.Credentials{AccessKeyID: key, SecretAccessKey: "s"}, nil
	}))
	// call is one SDK operation: a slot, then the signer's Retrieve.
	call := func() context.Context {
		ctx := context.WithValue(context.Background(), signerSlotKey{}, &signerSlot{})
		if _, err := provider.Retrieve(ctx); err != nil {
			t.Fatal(err)
		}
		return ctx
	}
	ctx := call()
	if !matchesIdentity(ctx, "us-east-1", "AKIDCURRENT", "us-east-1") {
		t.Error("same key and region do not match")
	}
	if matchesIdentity(ctx, "us-east-1", "AKIDCURRENT", "eu-west-1") {
		t.Error("another region matches")
	}
	if matchesIdentity(ctx, "us-east-1", "AKIDOTHER", "us-east-1") {
		t.Error("another key matches")
	}
	if matchesIdentity(ctx, "us-east-1", "", "us-east-1") {
		t.Error("an empty key matches")
	}
	if matchesIdentity(context.Background(), "us-east-1", "AKIDCURRENT", "us-east-1") {
		t.Error("a call the client's provider did not sign matches")
	}
	failing := newSignerProvider(credsFunc(aws.Credentials{AccessKeyID: "AKIDCURRENT"}, errors.New("x")))
	failed := context.WithValue(context.Background(), signerSlotKey{}, &signerSlot{})
	_, _ = failing.Retrieve(failed)
	if matchesIdentity(failed, "us-east-1", "AKIDCURRENT", "us-east-1") {
		t.Error("a provider error matches")
	}
	key = "AKIDROTATED"
	rotated := call()
	if !matchesIdentity(rotated, "us-east-1", "AKIDROTATED", "us-east-1") || matchesIdentity(rotated, "us-east-1", "AKIDCURRENT", "us-east-1") {
		t.Error("a call matches a key other than the one its signer retrieved")
	}
	if !matchesIdentity(ctx, "us-east-1", "AKIDCURRENT", "us-east-1") {
		t.Error("an earlier call no longer matches the key it was signed with")
	}
}

func TestSignTokenLeavesNoSecrets(t *testing.T) {
	tok, err := signToken(context.Background(), testCreds(), "my-cache", "default.iam-user", "us-east-1", time.Now())
	if err != nil {
		t.Fatal(err)
	}
	secrets := append(testutil.TokenSecrets(tok.reveal()), testutil.Secrets()...)
	out := fmt.Sprintf("%v %+v %#v %s %q", tok, tok, tok, tok, tok)
	testutil.AssertNoSecrets(t, out, secrets...)

	_, err = signToken(context.Background(), testCreds(), "bad host\x7f", "u", "us-east-1", time.Now())
	if err == nil {
		t.Fatal("no error for an invalid host")
	}
	testutil.AssertNoSecrets(t, err.Error())

	failing := credsFunc(aws.Credentials{}, errors.New("provider failed"))
	testutil.AssertNoSecrets(t, resolveIdentity(context.Background(), "us-east-1", failing).Error())
}
