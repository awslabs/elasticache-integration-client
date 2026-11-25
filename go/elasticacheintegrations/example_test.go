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
		log.Print(err) // not log.Fatal, which would skip the deferred Close
		return
	}
	fmt.Println(len(out.Item))
}

// When NewFromConfig fails, a *ConfigError means the options, region or credentials need
// fixing. Any other error that matches ErrIntegrations means the cache is not usable now:
// the application can run on plain DynamoDB.
func ExampleNewFromConfig_errors() {
	ctx := context.Background()
	var cfg aws.Config // for example from config.LoadDefaultConfig(ctx)

	ddb, cache, err := elasticacheintegrations.NewFromConfig(ctx, cfg, elasticacheintegrations.Options{
		Endpoint:  "my-cache-abc123.serverless.use1.cache.amazonaws.com:6379",
		CacheName: "my-cache",
	})
	var configErr *elasticacheintegrations.ConfigError
	switch {
	case errors.As(err, &configErr):
		log.Fatal(err) // a bad option, or no region or credentials
	case errors.Is(err, elasticacheintegrations.ErrIntegrations):
		log.Print("starting without the cache: ", err)
		ddb = dynamodb.NewFromConfig(cfg)
	case err != nil:
		log.Fatal(err) // ctx ended
	default:
		defer cache.Close()
	}

	if _, err := ddb.GetItem(ctx, &dynamodb.GetItemInput{
		TableName: aws.String("Music"),
		Key:       map[string]types.AttributeValue{"Artist": &types.AttributeValueMemberS{Value: "Acme"}},
	}); err != nil {
		log.Print(err)
	}
}

// New takes the dynamodb.Options a caller of dynamodb.New builds.
func ExampleNew() {
	ctx := context.Background()
	var provider aws.CredentialsProvider // for example from config.LoadDefaultConfig(ctx)

	ddb, cache, err := elasticacheintegrations.New(ctx, dynamodb.Options{
		Region:      "us-east-1",
		Credentials: aws.NewCredentialsCache(provider),
	}, elasticacheintegrations.Options{
		Endpoint:  "my-cache-abc123.serverless.use1.cache.amazonaws.com:6379",
		CacheName: "my-cache",
	})
	if err != nil {
		log.Fatal(err)
	}
	defer cache.Close()

	if _, err := ddb.GetItem(ctx, &dynamodb.GetItemInput{
		TableName: aws.String("Music"),
		Key:       map[string]types.AttributeValue{"Artist": &types.AttributeValueMemberS{Value: "Acme"}},
	}); err != nil {
		log.Print(err)
	}
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
		log.Print("the cache is unavailable: ", unavailable.Msg)
	}
}
