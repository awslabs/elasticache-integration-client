// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

// Package e2e holds the client's end-to-end tests and nothing else. Each test builds a real
// DynamoDB client with elasticacheintegrations.NewFromConfig against the in-process mock cache
// over TLS, with a fake DynamoDB behind the cache and as the client's origin. The tests are
// hermetic: loopback only, no AWS configuration, no network.
package e2e
