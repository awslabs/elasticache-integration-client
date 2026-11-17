# Integration Client for ElastiCache

The Integration Client for ElastiCache adds a cache in front of your application's DynamoDB requests. You install it into the DynamoDB client you already use: supported requests go through an ElastiCache Serverless cache, and the rest of your code stays the same.

## Clients

| Language | Package | Requires | Documentation |
|---|---|---|---|
| Python | [`aws-elasticache-integration-client`](https://pypi.org/project/aws-elasticache-integration-client/) | Python 3.10, boto3 1.43 | [python/README.md](python/README.md) |
| Node.js | [`@aws-sdk/aws-elasticache-integration-client`](https://www.npmjs.com/package/@aws-sdk/aws-elasticache-integration-client) | Node.js 22, AWS SDK for JavaScript v3 | [node/README.md](node/README.md) |
| Java | [`software.amazon.elasticache:aws-elasticache-integration-client`](https://central.sonatype.com/artifact/software.amazon.elasticache/aws-elasticache-integration-client) | JDK 8, AWS SDK for Java v2 2.21.0 | [java/README.md](java/README.md) |

## Getting started

You need an ElastiCache Serverless for Valkey cache with DynamoDB caching enabled. See [Getting started with DynamoDB caching](TODO-AWS-DOCS-LINK).

Then install the client for your language and add one call:

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

**Java**

```java
DynamoDbClient ddb = ElastiCacheIntegrationsClient.builder()
        .endpoint("my-cache-abc123.serverless.use1.cache.amazonaws.com:6379")
        .cacheName("my-cache")
        .install(DynamoDbClient.builder().region(Region.US_EAST_1));

ddb.getItem(r -> r.tableName("Users").key(Collections.singletonMap("id", AttributeValue.fromS("42")))); // unchanged
```

Each client's README covers configuration, what happens when the cache is unreachable, errors and authentication.

## Getting help

Open a [GitHub issue](https://github.com/awslabs/elasticache-integration-client/issues). Include the client and its version, the AWS SDK version, what you expected and what happened, and any error with its cause.

## Contributing

Each client's `DEVELOPER.md` explains how to build, test and release it. [CONTRIBUTING.md](CONTRIBUTING.md) explains the repository layout, the shared test cases, and how to run the examples against a real cache.

## License

This project is licensed under the [Apache-2.0 License](LICENSE).
