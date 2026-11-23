// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations_test

import (
	"context"
	"errors"
	"fmt"
	"log"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"

	"github.com/awslabs/elasticache-integration-client/go/elasticacheintegrations"
)

// The one change: build the DynamoDB client with elasticacheintegrations.NewFromConfig
// instead of dynamodb.NewFromConfig. Every call site stays the same.
func ExampleNewFromConfig() {
	ctx := context.Background()
	var cfg aws.Config // for example from config.LoadDefaultConfig(ctx)

	ddb, cache, err := elasticacheintegrations.NewFromConfig(ctx, cfg, elasticacheintegrations.Options{
		Endpoint:  "my-cache-abc123.serverless.use1.cache.amazonaws.com:6379",
		CacheName: "my-cache",
	})
	if err != nil {
		log.Fatal(err)
	}
	defer cache.Close()

	out, err := ddb.GetItem(ctx, &dynamodb.GetItemInput{
		TableName: aws.String("Music"),
		Key:       map[string]types.AttributeValue{"Artist": &types.AttributeValueMemberS{Value: "Acme"}},
	})
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println(len(out.Item))
}

// With FallbackDuration 0 a routed call never goes to DynamoDB. While the cache is down it
// fails with a retryable *CacheUnavailableError, which the SDK's retryer retries.
func ExampleOptions_strict() {
	ctx := context.Background()
	var cfg aws.Config // for example from config.LoadDefaultConfig(ctx)

	ddb, cache, err := elasticacheintegrations.NewFromConfig(ctx, cfg, elasticacheintegrations.Options{
		Endpoint:         "my-cache-abc123.serverless.use1.cache.amazonaws.com:6379",
		CacheName:        "my-cache",
		FallbackDuration: aws.Duration(0),
	})
	if err != nil {
		log.Fatal(err)
	}
	defer cache.Close()

	_, err = ddb.GetItem(ctx, &dynamodb.GetItemInput{
		TableName: aws.String("Music"),
		Key:       map[string]types.AttributeValue{"Artist": &types.AttributeValueMemberS{Value: "Acme"}},
	})
	var unavailable *elasticacheintegrations.CacheUnavailableError
	if errors.As(err, &unavailable) {
		log.Print("the cache is unavailable: ", unavailable.Reason)
	}
}
