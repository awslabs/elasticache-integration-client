# ElastiCache integration clients

This repository contains client libraries for Amazon ElastiCache integration.
Currently, cache integration with Amazon DynamoDB is supported.

These integration client libraries enable applications to transparently route DynamoDB requests to an
ElastiCache Serverless for Valkey cache with DynamoDB caching enabled.
DynamoDB table data is automatically cached and returned according to the client configuration.
Each language-specific integration client directory contains instructions for configuration and
includes a set of test examples.

## Clients

| Language | Package | Requires | Documentation |
|---|---|---|---|
| Java | [`software.amazon.elasticache:aws-elasticache-integration-client`](https://central.sonatype.com/artifact/software.amazon.elasticache/aws-elasticache-integration-client) | JDK 8, AWS SDK for Java v2 2.21.0 | [java/README.md](java/README.md) |
| Python | [`aws-elasticache-integration-client`](https://pypi.org/project/aws-elasticache-integration-client/) | Python 3.10, boto3 1.43 | [python/README.md](python/README.md) |
| Node.js | [`@aws-sdk/aws-elasticache-integration-client`](https://www.npmjs.com/package/@aws-sdk/aws-elasticache-integration-client) | Node.js 22, AWS SDK for JavaScript v3 | [node/README.md](node/README.md) |

## Getting started

You need an ElastiCache Serverless for Valkey cache with DynamoDB caching enabled. See the [Amazon ElastiCache User Guide](https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/WhatIs.html).

Then install the client for your language and add one call:

**Java**

```java
import java.util.Collections;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.elasticache.integrations.session.ElastiCacheIntegrationsClient;

DynamoDbClient ddb = ElastiCacheIntegrationsClient.builder()
        .endpoint("my-cache-abc123.serverless.use1.cache.amazonaws.com:6379")
        .cacheName("my-cache")
        .install(DynamoDbClient.builder().region(Region.US_EAST_1));

ddb.getItem(r -> r.tableName("Users").key(Collections.singletonMap("id", AttributeValue.fromS("42")))); // unchanged
```

**Python**

```python
import boto3
import aws_elasticache_integration_client

ddb = boto3.client("dynamodb", region_name="us-east-1")
aws_elasticache_integration_client.install(
    client=ddb,
    endpoint="my-cache-abc123.serverless.use1.cache.amazonaws.com:6379",
    cache_name="my-cache",
)

ddb.get_item(TableName="Users", Key={"id": {"S": "42"}})  # unchanged
```

**Node.js**

```ts
import { DynamoDBClient, GetItemCommand } from "@aws-sdk/client-dynamodb";
import { install } from "@aws-sdk/aws-elasticache-integration-client";

const dynamodb = new DynamoDBClient({ region: "us-east-1" });
await install({
  client: dynamodb,
  endpoint: "my-cache-abc123.serverless.use1.cache.amazonaws.com:6379",
  cacheName: "my-cache",
});

await dynamodb.send(new GetItemCommand({ TableName: "Users", Key: { id: { S: "42" } } })); // unchanged
```

Each client's README covers configuration, what happens when the cache is unreachable, errors and authentication.

## Getting help

Open a [GitHub issue](https://github.com/awslabs/elasticache-integration-client/issues). Include the client and its version, the AWS SDK version, what you expected and what happened, and any error with its cause.

## Contributing

GitHub is a platform for collaborative coding. If you're interested in writing code, we encourage you to contribute by submitting pull requests from forked copies of this repository. Additionally, please consider creating GitHub issues for reporting bugs and suggesting new features. Feel free to comment on issues that interest you. For more info see [CONTRIBUTING.md](CONTRIBUTING.md).

## Security

See [CONTRIBUTING.md](CONTRIBUTING.md#security-issue-notifications) for more information.

## License

This project is licensed under the [Apache-2.0 License](LICENSE).
