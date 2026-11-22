// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"context"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	v4 "github.com/aws/aws-sdk-go-v2/aws/signer/v4"
)

const (
	tokenExpirySeconds = 900
	tokenService       = "elasticache"
	emptySHA256        = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
)

// signToken returns the IAM token: the presigned ElastiCache connect URL without its scheme.
// The SDK writes the query in SigV4 canonical order.
func signToken(ctx context.Context, creds aws.Credentials, cache, user, region string, now time.Time) (secret, error) {
	q := url.Values{
		"Action":        {"connect"},
		"User":          {user},
		"ResourceType":  {"ServerlessCache"},
		"X-Amz-Expires": {strconv.Itoa(tokenExpirySeconds)},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, "https://"+strings.ToLower(cache)+"/?"+q.Encode(), nil)
	if err != nil {
		return secret{}, &ConnectionError{Msg: "cannot build the IAM token request", Err: err}
	}
	uri, _, err := v4.NewSigner().PresignHTTP(ctx, creds, req, emptySHA256, tokenService, region, now)
	if err != nil {
		return secret{}, &ConnectionError{Msg: "cannot sign the IAM token", Err: err}
	}
	token, ok := strings.CutPrefix(uri, "https://")
	if !ok {
		return secret{}, &ConnectionError{Msg: "the IAM token signer returned an unexpected scheme"}
	}
	return newSecret(token), nil
}

// resolveIdentity checks the DynamoDB client's region and credentials before the cache is
// contacted. It retrieves the credentials once, so a provider that cannot resolve fails here.
func resolveIdentity(ctx context.Context, region string, provider aws.CredentialsProvider) error {
	if region == "" {
		return &ConfigError{Option: "Region", Msg: "is required: the DynamoDB client has no region"}
	}
	if provider == nil || aws.IsCredentialsProvider(provider, aws.AnonymousCredentials{}) {
		return &ConfigError{Option: "Credentials", Msg: "are required: the DynamoDB client has no credentials or anonymous credentials"}
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	creds, err := provider.Retrieve(ctx)
	if ctxErr := ctx.Err(); ctxErr != nil {
		return ctxErr
	}
	if err != nil {
		return &ConfigError{Option: "Credentials", Msg: "cannot be retrieved", Err: err}
	}
	if !creds.HasKeys() {
		return &ConfigError{Option: "Credentials", Msg: "have no access key id or secret access key"}
	}
	return nil
}

// matchesIdentity reports whether a request signed with signedKeyID in signedRegion may use
// the client's cache connection: the region is the client's and the key id is the one the
// client's provider returns now.
func matchesIdentity(ctx context.Context, provider aws.CredentialsProvider, region, signedKeyID, signedRegion string) bool {
	if signedRegion != region || signedKeyID == "" {
		return false
	}
	creds, err := provider.Retrieve(ctx)
	return err == nil && creds.AccessKeyID == signedKeyID
}
