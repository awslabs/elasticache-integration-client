# ElastiCache Integrations for Java

ElastiCache Integrations provides a caching layer for AWS SDK for Java v2 DynamoDB requests. It connects your DynamoDB client to an ElastiCache Serverless cache, sends supported requests through the cache, and serves cacheable responses without contacting DynamoDB.

## Requirements

- JDK 8 or later. **JDK 11 or later is recommended.**
- AWS SDK for Java v2 version 2.21.0 or later.

## Installation

The client is published to Maven Central as [`software.amazon.elasticache:aws-elasticache-integration-client`](https://central.sonatype.com/artifact/software.amazon.elasticache/aws-elasticache-integration-client).

Maven:

```xml
<dependency>
  <groupId>software.amazon.elasticache</groupId>
  <artifactId>aws-elasticache-integration-client</artifactId>
  <version>VERSION</version>
</dependency>
```

Gradle:

```kotlin
implementation("software.amazon.elasticache:aws-elasticache-integration-client:VERSION")
```

## Java modules

The jar is an automatic module named `software.amazon.elasticache.integrations`:

```java
module my.app {
    requires software.amazon.elasticache.integrations;
    requires software.amazon.awssdk.services.dynamodb;
}
```

## Basic Example

Before:

```java
import java.util.Collections;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class Main {
    public static void main(String[] args) {
        DynamoDbClient ddb = DynamoDbClient.builder()
                .region(Region.US_EAST_1)
                .build();

        ddb.getItem(r -> r.tableName("Users").key(Collections.singletonMap("id", AttributeValue.fromS("42"))));
        ddb.close();
    }
}
```

After:

```java
import java.util.Collections;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.elasticache.integrations.session.ElastiCacheIntegrationsClient;

public class Main {
    public static void main(String[] args) {
        DynamoDbClient ddb = ElastiCacheIntegrationsClient.builder()
                .endpoint("my-cache-abc123.serverless.use1.cache.amazonaws.com:6379")
                .cacheName("my-cache")
                .install(DynamoDbClient.builder()
                        .region(Region.US_EAST_1));

        ddb.getItem(r -> r.tableName("Users").key(Collections.singletonMap("id", AttributeValue.fromS("42")))); // unchanged
        ddb.close(); // also disconnects the cache
    }
}
```

The only change to your application is building the client through `install(...)`; see [Configuration](#configuration) for its options. Everything else stays the same: you call DynamoDB exactly as before, and the ElastiCache Integrations layer in front of it serves what it can from the cache.

`install` connects to the cache before it returns. If it cannot connect or [authenticate](#authentication), it throws instead of returning a client that never caches.

## How it works

- **The cache decides what it serves.** When the client connects, the cache tells it which requests it wants. Those are sent to the cache; everything else goes to DynamoDB as it would without ElastiCache Integrations. See [Request routing](#request-routing).
- **The cache going down does not take your application down.** If the connection to the cache is lost, the client reconnects in the background, and meanwhile requests go to DynamoDB as they would without ElastiCache Integrations in the middle. Once the connection is back, so is the caching layer. See [Reconnection and fallback](#reconnection-and-fallback) for the full details, and [Configuration](#configuration) to change how long requests wait and fall back.
- **Closing the client also disconnects the cache.**

## Examples

### Asynchronous client

Use `installAsync` with an asynchronous builder. Its own options are in [Configuration](#configuration):

```java
DynamoDbAsyncClient ddb = ElastiCacheIntegrationsClient.builder()
        .endpoint("my-cache-abc123.serverless.use1.cache.amazonaws.com:6379")
        .cacheName("my-cache")
        .installAsync(DynamoDbAsyncClient.builder().region(Region.US_EAST_1));

CompletableFuture<GetItemResponse> item = ddb.getItem(r -> r.tableName("Users").key(Collections.singletonMap("id", AttributeValue.fromS("42"))));
```

### Your own HTTP settings for DynamoDB

Requests that do not go to the cache use an HTTP client to reach DynamoDB. To set its timeouts or connection pool, build it yourself and pass it as [`originHttpClient`](#configuration), not on the DynamoDB builder. This needs the `software.amazon.awssdk:apache-client` dependency:

```java
SdkHttpClient http = ApacheHttpClient.builder()
        .connectionTimeout(Duration.ofSeconds(1))
        .socketTimeout(Duration.ofSeconds(2))
        .build();

DynamoDbClient ddb = ElastiCacheIntegrationsClient.builder()
        .endpoint("my-cache-abc123.serverless.use1.cache.amazonaws.com:6379")
        .cacheName("my-cache")
        .originHttpClient(http)
        .install(DynamoDbClient.builder().region(Region.US_EAST_1));
```

## Configuration

Every option has a default, so set only what you change. The Javadoc of each builder method has the full details.

Options on `ElastiCacheIntegrationsClient.builder()`:

| Option | Default | Meaning |
|---|---|---|
| `endpoint(String)` | required | The cache endpoint, as `host:port`. |
| `cacheName(String)` | required | The ElastiCache Serverless cache name. |
| `userId(String)` | `default.iam-user` | The ElastiCache IAM user. See [Authentication](#authentication). |
| `verify(boolean)` | `true` | Verify the cache's TLS certificate. `false` is for local testing only. |
| `caCerts(Path)` | system trust store | A CA bundle to verify the cache's certificate with. |
| `originHttpClient(SdkHttpClient)` | SDK default | The HTTP client for requests that go to DynamoDB. |
| `originAsyncHttpClient(SdkAsyncHttpClient)` | SDK default | The same, for `installAsync`. |
| `asyncExecutor(Executor)` | 100 daemon threads | Runs the cache requests of `installAsync`, which block, off the SDK's event-loop threads. |
| `asyncMaxBufferedRequestBytes(int)` | 16 MiB | The largest request body `installAsync` buffers for the cache. A larger one goes to DynamoDB. |
| `asyncMaxConcurrentBodyCollectors(int)` | 8 | How many request bodies `installAsync` buffers at once. |
| `clientConfiguration(...)` | all defaults | The options below. |

Options on `ElastiCacheIntegrationsClientConfiguration.builder()`:

| Option | Default | Meaning |
|---|---|---|
| `cacheRequestTimeoutMs(long)` | 5000 | How long to wait for the cache to answer a request. See [Errors](#errors) for what a timeout looks like. |
| `connectionTimeoutMs(long)` | 5000 | How long to try connecting at startup, and how long a request waits for a lost connection to come back. See [Reconnection and fallback](#reconnection-and-fallback). |
| `fallbackDurationSeconds(long)` | no limit | How long requests may go to DynamoDB while the cache is unreachable. `0` means never. See [Reconnection and fallback](#reconnection-and-fallback). |
| `cachePolicyOverrides(Map)` | none | What to cache and for how long. See [Cache policy overrides](#cache-policy-overrides). |
| `cachePolicyTimeoutSeconds(long)` | 3600 | How long the client keeps using a cache policy the cache has not confirmed. See [Cache policy timeout](#cache-policy-timeout). |
| `iamTokenRefreshDelaySeconds(long)` | 300 | How long after a token is issued a replacement is signed in the background. 1 to 879. See [Authentication](#authentication). |

### Cache policy overrides

These are sent to the cache as a map, so they have no builder methods:

| Key | Meaning |
|---|---|
| `cached_tables` | The tables to cache. Empty or absent means all tables. |
| `cached_operations` | The operations to cache, such as `GetItem` or `Query`. Empty or absent means all of them. |
| `item_ttl`, `item_negative_ttl`, `query_ttl`, `scan_ttl` | How long each kind of entry lives. `item_negative_ttl` covers "this item does not exist". |
| `item_write_behavior` | How a write updates the cached item. |
| `table_overrides` | The settings above for one table, keyed by table name. |

### Example

```java
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsError;
import software.amazon.elasticache.integrations.session.ElastiCacheIntegrationsClient;
import software.amazon.elasticache.integrations.session.ElastiCacheIntegrationsClientConfiguration;

public class Main {
    public static void main(String[] args) {
        // Your DynamoDB client, configured as usual. install keeps all of it.
        DynamoDbClientBuilder dynamoDb = DynamoDbClient.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(ProfileCredentialsProvider.create("prod"))
                .overrideConfiguration(o -> o.apiCallTimeout(Duration.ofSeconds(3)));

        // Cache only the Users and Sessions tables, and set their item lifetime.
        Map<String, Object> overrides = new HashMap<>();
        overrides.put("cached_tables", Arrays.asList("Users", "Sessions"));
        overrides.put("item_ttl", 60);

        DynamoDbClient ddb;
        try {
            ddb = ElastiCacheIntegrationsClient.builder()
                    .endpoint("my-cache-abc123.serverless.use1.cache.amazonaws.com:6379")
                    .cacheName("my-cache")
                    .clientConfiguration(ElastiCacheIntegrationsClientConfiguration.builder()
                            .cacheRequestTimeoutMs(2_000)   // wait at most 2 s for the cache
                            .fallbackDurationSeconds(60)    // requests may go to DynamoDB for 60 s while the cache is unreachable
                            .cachePolicyOverrides(overrides)
                            .build())
                    .install(dynamoDb);
        } catch (ElastiCacheIntegrationsError e) {
            // The cache could not be reached or rejected the credentials. Run without it.
            System.err.println("Starting without the cache: " + e.getMessage());
            ddb = dynamoDb.build();
        }

        ddb.getItem(r -> r.tableName("Users").key(Collections.singletonMap("id", AttributeValue.fromS("42"))));
        ddb.close();
    }
}
```

## Request routing

When the client connects, the cache sends it a set of routing rules. The client checks each request against them itself: a request that matches any rule is sent to the cache, and every other request goes straight to DynamoDB, with no round trip to the cache.

A rule can look at the request's service, operation, method, URL, headers and JSON body. A rule that fails to evaluate, or a body that is not a JSON object, counts as no match. A rule the client cannot understand at all rejects the whole set, because using only the rest would route requests differently from what the cache intended: connecting fails and is retried, and a refresh keeps the previous rules.

The client asks the cache for its current rules and [cache policy](#cache-policy-overrides) periodically, at an interval the cache chooses, and applies an update all at once, so a request never sees half of one.

## Cache policy timeout

If those periodic [refreshes](#request-routing) fail, the client keeps using the last policy the cache confirmed. [`cachePolicyTimeoutSeconds`](#configuration) limits how long: once a refresh fails after that much time without a confirmation, the client drops the connection and reconnects, which fetches a fresh policy. Meanwhile requests follow [Reconnection and fallback](#reconnection-and-fallback). A successful refresh restarts the window, so a healthy connection is never dropped.

## Reconnection and fallback

**When the connection is lost.** The client starts reconnecting at once, in the background, even if the application is idle. Each attempt opens a new TLS connection, signs in with a fresh [IAM token](#authentication) and gets the current [cache policy](#request-routing). Attempts back off and continue until one succeeds or the client is closed.

**Requests during an outage.** A request the cache would serve first waits for the connection to come back, up to [`connectionTimeoutMs`](#configuration). This wait is per outage, not per request: a request that arrives later in the same outage waits only for what is left of it. If the connection comes back in time, the request is served from the cache.

**After the wait.** If the connection is still down, the request goes to DynamoDB for as long as [`fallbackDurationSeconds`](#configuration) allows, counted from the end of the wait. The default has no limit, a positive value allows that many seconds, and `0` never allows it. Once the window ends, the request fails with a [retryable error](#errors) for the SDK to handle. A write sent to DynamoDB this way can leave a stale entry in the cache until its [TTL](#cache-policy-overrides) expires.

**A request already sent when the connection drops.** It fails with a [retryable error](#errors), whatever its operation, the same as when the connection to DynamoDB drops without the cache. Your SDK retry settings decide whether it is sent again, and the retry goes through the same wait and fallback window as any other request. As without the cache, a retried write may be applied twice if the first attempt reached DynamoDB.

**At startup.** `install` keeps trying to connect until [`connectionTimeoutMs`](#configuration) has passed, then throws the last attempt's failure: `ElastiCacheIntegrationsConnectionError` if it could not connect, `ElastiCacheIntegrationsAuthError` if the cache rejected its credentials, or `ElastiCacheIntegrationsOverridesRejected` if the cache rejected the [cache policy overrides](#cache-policy-overrides). It never returns a client that is not connected to the cache.

## Errors

A request sent to the cache that times out or loses its connection fails with an error the SDK retries, whatever its operation, the same as that failure on a connection to DynamoDB. An error returned by the cache is retried only for `ORIGIN_ERROR` and `INTERNAL_ERROR_RETRY`, the codes the cache sends when DynamoDB applied nothing or the outcome is unknown. Every other code is not retried, including `INTERNAL_ERROR_FAIL` for a write DynamoDB applied whose response was lost. Your SDK retry settings decide how many times a retryable failure is sent again.

The client's exceptions all extend `ElastiCacheIntegrationsError`, but a request's failure is often wrapped before it reaches you. A failure the SDK may retry, such as a request the cache could not answer in time, arrives after the SDK's retries as an `SdkClientException`. On an asynchronous client, `join()` wraps the failure in a `CompletionException` too. To find the client's exception, walk the cause chain. On a synchronous client, an error returned by the cache, such as a rejected request, is thrown as the client's exception itself.

## Authentication

The client signs in to the cache with an IAM authentication token, using the region and credentials of the DynamoDB client you pass to `install`. See the [Configuration example](#example) for a client with its own credentials provider. The cache is accessed as the same identity as your DynamoDB requests, and credential rotation (SSO, assumed roles, container and instance credentials) keeps working.

That identity needs the `elasticache:Connect` permission on the cache and on the ElastiCache user ([`userId`](#configuration), `default.iam-user` by default).

Tokens are valid for 15 minutes. The client signs a replacement in the background before the current one expires, and authenticates each live connection again with it. If the cache rejects the new token, the connection stays open with its current authentication, and the client signs another token and tries again, with backoff. If the cache does not answer the `AUTH`, or its reply cannot be read, the client drops the connection and [reconnects](#reconnection-and-fallback). Set [`iamTokenRefreshDelaySeconds`](#configuration) to change when the replacement is signed.

Token signing and refresh come from the [ElastiCache developer toolkit](https://central.sonatype.com/artifact/software.amazon.elasticache/developer-toolkit-elasticache).

## Logging

The client logs through `java.util.logging`, under `software.amazon.elasticache.integrations`.

## Development

See [DEVELOPER.md](DEVELOPER.md) for building, testing and releasing the client.
