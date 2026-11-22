// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

// Package elasticacheintegrations serves Amazon DynamoDB calls made with the AWS SDK for
// Go v2 from an ElastiCache Serverless cache.
//
// NewFromConfig builds a [github.com/aws/aws-sdk-go-v2/service/dynamodb.Client] whose HTTP
// client sends the operations the cache supports to the cache and every other call to
// DynamoDB. Call sites, paginators and waiters stay unchanged:
//
//	ddb, cache, err := elasticacheintegrations.NewFromConfig(ctx, cfg, elasticacheintegrations.Options{
//		Endpoint:  "my-cache-abc123.serverless.use1.cache.amazonaws.com:6379",
//		CacheName: "my-cache",
//	})
//	if err != nil {
//		return err
//	}
//	defer cache.Close()
//
// Every error the package returns matches [ErrIntegrations] with [errors.Is]; use
// [errors.As] to reach a specific type such as [*ConfigError] or [*CacheUnavailableError].
package elasticacheintegrations
