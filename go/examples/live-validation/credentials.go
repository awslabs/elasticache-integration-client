// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"context"
	"sync/atomic"

	"github.com/aws/aws-sdk-go-v2/aws"
)

// switchableCredentials returns the wrapped credentials, or the same credentials with a wrong
// secret key while broken, so a token signed from them is refused by the cache.
type switchableCredentials struct {
	next   aws.CredentialsProvider
	broken atomic.Bool
}

func (s *switchableCredentials) Retrieve(ctx context.Context) (aws.Credentials, error) {
	creds, err := s.next.Retrieve(ctx)
	if err != nil || !s.broken.Load() {
		return creds, err
	}
	creds.SecretAccessKey = "x" + creds.SecretAccessKey
	return creds, nil
}
