# ElastiCache integration client for Go

ElastiCache Integrations provides a caching layer for AWS SDK for Go v2 DynamoDB requests. It connects your DynamoDB client to an ElastiCache Serverless cache, sends supported requests through the cache, and serves cacheable responses.

## Requirements

- Go 1.24 or later.
- AWS SDK for Go v2: `github.com/aws/aws-sdk-go-v2` v1.48.0 and `github.com/aws/aws-sdk-go-v2/service/dynamodb` v1.74.1 or later.
- An ElastiCache Serverless for Valkey cache with DynamoDB caching enabled. See [Getting started with DynamoDB caching](https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/ddb-caching-getting-started.html).

## Installation

```sh
go get github.com/awslabs/elasticache-integration-client/go
```

The module is `github.com/awslabs/elasticache-integration-client/go` and the package is `elasticacheintegrations`:

```go
import "github.com/awslabs/elasticache-integration-client/go/elasticacheintegrations"
```

## Basic Example

Before:

```go
package main

import (
	"context"
	"log"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

func main() {
	ctx := context.Background()
	cfg, err := config.LoadDefaultConfig(ctx, config.WithRegion("us-east-1"))
	if err != nil {
		log.Fatal(err)
	}

	ddb := dynamodb.NewFromConfig(cfg)

	_, err = ddb.GetItem(ctx, &dynamodb.GetItemInput{
		TableName: aws.String("Users"),
		Key:       map[string]types.AttributeValue{"id": &types.AttributeValueMemberS{Value: "42"}},
	})
	if err != nil {
		log.Fatal(err)
	}
}
```

After:

```go
package main

import (
	"context"
	"log"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"

	"github.com/awslabs/elasticache-integration-client/go/elasticacheintegrations"
)

func main() {
	ctx := context.Background()
	cfg, err := config.LoadDefaultConfig(ctx, config.WithRegion("us-east-1"))
	if err != nil {
		log.Fatal(err)
	}

	ddb, cache, err := elasticacheintegrations.NewFromConfig(ctx, cfg, elasticacheintegrations.Options{
		Endpoint:  "my-cache-abc123.serverless.use1.cache.amazonaws.com:6379",
		CacheName: "my-cache",
	})
	if err != nil {
		log.Fatal(err)
	}
	defer cache.Close() // disconnects the cache

	_, err = ddb.GetItem(ctx, &dynamodb.GetItemInput{ // unchanged
		TableName: aws.String("Users"),
		Key:       map[string]types.AttributeValue{"id": &types.AttributeValueMemberS{Value: "42"}},
	})
	if err != nil {
		log.Fatal(err)
	}
}
```

The only change to your application is building the client with `elasticacheintegrations.NewFromConfig` instead of `dynamodb.NewFromConfig`; see [Configuration](#configuration) for its options. `ddb` is a plain `*dynamodb.Client`, so every call, paginator, waiter and `attributevalue` use stays the same, and the ElastiCache Integrations layer in front of it serves what it can from the cache. Optional `func(*dynamodb.Options)` arguments go last, as with `dynamodb.NewFromConfig`.

`NewFromConfig` connects to the cache before it returns. If it cannot connect or [authenticate](#authentication), it returns an error instead of a client that never caches.

## How it works

- **You choose what is cached.** Requests for the tables and operations you choose go through the cache; everything else goes to DynamoDB as it would without ElastiCache Integrations. See [Request routing](#request-routing).
- **The cache going down does not take your application down.** If the connection to the cache is lost, the client reconnects in the background, and meanwhile requests go to DynamoDB as they would without ElastiCache Integrations in the middle. Once the connection is back, so is the caching layer. See [Reconnection and fallback](#reconnection-and-fallback) for the full details, and [Configuration](#configuration) to change how long requests wait and fall back.
- **`cache.Close()` disconnects the cache.** The DynamoDB client keeps working without it. See [Closing the client](#closing-the-client).

The client installs itself as the DynamoDB client's `HTTPClient`. It sees each request after the SDK has built and signed it, inside the SDK's retry loop, so the SDK's retries, unmarshalling, checksum check and errors work as before.

## Examples

### Your own HTTP settings for DynamoDB

Requests that do not go to the cache use an HTTP client to reach DynamoDB. The client keeps the one your DynamoDB client would use, so set its timeouts or transport as you do today, on `aws.Config.HTTPClient` or in an option function:

```go
httpClient := awshttp.NewBuildableClient().WithTimeout(2 * time.Second)

ddb, cache, err := elasticacheintegrations.NewFromConfig(ctx, cfg, elasticacheintegrations.Options{
	Endpoint:  "my-cache-abc123.serverless.use1.cache.amazonaws.com:6379",
	CacheName: "my-cache",
}, func(o *dynamodb.Options) {
	o.HTTPClient = httpClient
})
```

`awshttp` is `github.com/aws/aws-sdk-go-v2/aws/transport/http`. An `HTTPClient` that is already an ElastiCache Integrations client is refused with a `*ConfigError`.

### Building from `dynamodb.Options`

If you build the DynamoDB client with `dynamodb.New`, use `elasticacheintegrations.New`:

```go
ddb, cache, err := elasticacheintegrations.New(ctx, dynamodb.Options{
	Region:      "us-east-1",
	Credentials: aws.NewCredentialsCache(provider),
}, elasticacheintegrations.Options{
	Endpoint:  "my-cache-abc123.serverless.use1.cache.amazonaws.com:6379",
	CacheName: "my-cache",
})
```

### Strict mode

With `FallbackDuration: aws.Duration(0)` a request the cache would serve never goes to DynamoDB. While the cache is unreachable it fails with a retryable `*CacheUnavailableError`:

```go
ddb, cache, err := elasticacheintegrations.NewFromConfig(ctx, cfg, elasticacheintegrations.Options{
	Endpoint:         "my-cache-abc123.serverless.use1.cache.amazonaws.com:6379",
	CacheName:        "my-cache",
	FallbackDuration: aws.Duration(0),
})
```

## Configuration

`elasticacheintegrations.Options`. The zero value of every optional field means its default. Every option is checked before any network I/O, and a bad value returns a `*ConfigError` naming it. The doc comment of each field has the full details.

| Option | Default | Meaning |
|---|---|---|
| `Endpoint` | required | The cache endpoint, as `host:port`. An IPv6 host is bracketed. |
| `CacheName` | required | The ElastiCache Serverless cache name. |
| `UserID` | `default.iam-user` | The ElastiCache IAM user. See [Authentication](#authentication). |
| `CACertsFile` | system roots | A PEM bundle to verify the cache's certificate with, read once by `NewFromConfig`. See [TLS](#tls). |
| `InsecureSkipVerify` | `false` | Skip the certificate check. For local and test endpoints only. Cannot be combined with `CACertsFile`. |
| `CacheRequestTimeout` | 5s | How long to wait for the cache to answer a request. Whole milliseconds. See [Errors and retries](#errors-and-retries) for what a timeout looks like. |
| `ConnectionTimeout` | 5s | How long `NewFromConfig` tries to connect, and how long a request waits for a lost connection to come back. Whole milliseconds. See [Reconnection and fallback](#reconnection-and-fallback). |
| `FallbackDuration` | `nil`, no limit | How long requests may go to DynamoDB while the cache is unreachable. `aws.Duration(0)` means never ([strict mode](#strict-mode)). Whole seconds; negative is refused. See [Reconnection and fallback](#reconnection-and-fallback). |
| `CachePolicyOverrides` | none | What to cache and for how long. See [Cache policy overrides](#cache-policy-overrides). |
| `CachePolicyTimeout` | 1h | How long the client keeps using a cache policy the cache has not confirmed. Whole seconds. See [Cache policy timeout](#cache-policy-timeout). |
| `IAMTokenRefreshDelay` | 300s | How long after a token is signed a replacement is signed in the background. Whole seconds, 1s to 879s. See [Authentication](#authentication). |
| `Connections` | 1 | How many cache connections the client opens, 1 to 16. See [Connections](#connections). |
| `Logger` | Info and above, as text, to stderr | Where the client logs. See [Logging](#logging). |

Timeouts are `time.Duration` values, but they must be whole milliseconds and the longer periods whole seconds, so every value is one the other clients can express too.

### Connections

By default the client opens one connection to the cache, as the other clients do. The cache serves each connection on one proxy core, about 35,000 requests per second, whatever the client does. A client with 64 or more calls in flight, or more than about 20,000 calls per second, gains from more connections. Measured from one c7g.4xlarge in us-east-1 with 256 calls in flight and 1 KiB hits (VPC endpoint):

| `Connections` | Requests per second | p50 / p99 |
|---|---|---|
| 1 | 33,200 | 7.7 / 8.9 ms |
| 2 | 60,200 | 4.1 / 8.6 ms |
| 4 | 85,200 | 2.7 / 7.2 ms |

Each connection is opened, authenticated and re-authenticated, refreshes its cache policy, and reconnects on its own; they share one IAM token. Calls go in turn to the connections that are up, and never wait for one that is reconnecting while another is up. Only when every connection is down does a call wait and fall back as described in [Reconnection and fallback](#reconnection-and-fallback), counted from when the last connection dropped, and strict mode applies the same way. `NewFromConfig` returns when every connection is up, and fails if one cannot connect. `Close` closes them all. Each connection counts toward the cache's connection limit and sends its own `AUTH` and `aws.config`.

### Cache policy overrides

`CachePolicyOverrides` is a `map[string]any` sent with the first `aws.config` of each connection. The cache checks it; the client only checks that it is JSON. It is copied by `NewFromConfig`, so a later change to the map is not sent. Give lifetimes as whole seconds: a `time.Duration` is refused, because it would be sent in nanoseconds.

| Key | Meaning |
|---|---|
| `cached_tables` | The tables to cache. Empty or absent means all tables. |
| `cached_operations` | The operations to cache, such as `GetItem` or `Query`. Empty or absent means all of them. |
| `item_ttl`, `item_negative_ttl`, `query_ttl`, `scan_ttl` | How long each kind of entry lives, in seconds. `item_negative_ttl` covers "this item does not exist". |
| `item_write_behavior` | How a write updates the cached item. |
| `table_overrides` | The settings above for one table, keyed by table name. |

If the cache rejects the overrides, `NewFromConfig` returns an `*OverridesRejectedError` with the cache's message.

### Example

```go
package main

import (
	"context"
	"errors"
	"log"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"

	"github.com/awslabs/elasticache-integration-client/go/elasticacheintegrations"
)

func main() {
	ctx := context.Background()
	// Your AWS config, as usual. The DynamoDB client keeps all of it.
	cfg, err := config.LoadDefaultConfig(ctx,
		config.WithRegion("us-east-1"),
		config.WithSharedConfigProfile("prod"),
	)
	if err != nil {
		log.Fatal(err)
	}

	ddb, cache, err := elasticacheintegrations.NewFromConfig(ctx, cfg, elasticacheintegrations.Options{
		Endpoint:            "my-cache-abc123.serverless.use1.cache.amazonaws.com:6379",
		CacheName:           "my-cache",
		CacheRequestTimeout: 2 * time.Second,           // wait at most 2 s for the cache
		FallbackDuration:    aws.Duration(time.Minute), // requests may go to DynamoDB for 60 s while the cache is unreachable
		// Cache only the Users and Sessions tables, and set their item lifetime.
		CachePolicyOverrides: map[string]any{
			"cached_tables": []string{"Users", "Sessions"},
			"item_ttl":      60,
		},
	})
	var configErr *elasticacheintegrations.ConfigError
	switch {
	case errors.As(err, &configErr):
		log.Fatal(err) // a bad option, or no region or credentials: fix the configuration
	case errors.Is(err, elasticacheintegrations.ErrIntegrations):
		// The cache could not be reached, rejected the credentials or the overrides, or sent
		// rules the client cannot use. Run without it.
		log.Print("starting without the cache: ", err)
		ddb = dynamodb.NewFromConfig(cfg)
	case err != nil:
		log.Fatal(err) // ctx ended
	default:
		defer cache.Close()
	}

	_, err = ddb.GetItem(ctx, &dynamodb.GetItemInput{
		TableName: aws.String("Users"),
		Key:       map[string]types.AttributeValue{"id": &types.AttributeValueMemberS{Value: "42"}},
	})
	if err != nil {
		log.Fatal(err)
	}
}
```

## Request routing

Requests for the tables and operations you choose in [`CachePolicyOverrides`](#cache-policy-overrides) go through the cache. Without overrides, all of them do. Every other request goes straight to DynamoDB, with no round trip to the cache.

The cache sends the client its routing rules as CEL predicates with each config. A request goes to the cache when any predicate is true; an evaluation error counts as no match. The cache supports ten operations: `GetItem`, `Query`, `Scan`, `PutItem`, `UpdateItem`, `DeleteItem`, `BatchGetItem`, `BatchWriteItem`, `TransactGetItems` and `TransactWriteItems`. Everything else, such as `DescribeTable`, goes to DynamoDB. A DynamoDB error in a reply from the cache, such as `ResourceNotFoundException` or `ConditionalCheckFailedException`, is returned by the SDK exactly as for a direct call.

A predicate sees the request's `service`, `operation`, `method`, `url`, `headers` (lowercase names, without `authorization`, `x-amz-date`, `x-amz-security-token`, `amz-sdk-request` and `x-amz-request-id`) and its JSON `body`, plus the predicate's own `vars`. The body is parsed only for the predicates that read it, so a body that is not a JSON object fails those predicates and no others. A config with no predicates routes nothing. A config whose predicates are malformed or do not compile, a `vars` that is not an object included, is not used: at startup it is a `*ConfigUnevaluableError`, and on a refresh the client keeps the config it has.

These requests always go to DynamoDB, untouched, whatever the predicates say, also in [strict mode](#strict-mode). After `Close` in strict mode only the call with its own `HTTPClient` still does; the others fail like every other call ([Closing the client](#closing-the-client)):

- **A call with per-call options that change who signs it.** The cache connection is authenticated as the DynamoDB client's own identity. A call whose option function sets other `Credentials` or another `Region` is signed differently, so serving it from that connection would answer for the wrong principal. The client compares the access key id and region in the signed request with the region of the DynamoDB client and the key its own provider returned while the SDK signed the call, and sends any mismatch to DynamoDB. A rotated key of that provider still matches; a per-call provider never does, even one that returns the same key.
- **A call whose option function replaces `HTTPClient`.** It no longer goes through the client at all.
- A request body of unknown length or over 16 MiB, and a request without a SigV4 signature.

## Cache policy timeout

The client confirms its cache policy with the cache on the interval the cache sends with it (`refresh_interval`). If those confirmations fail, the client keeps using the last policy the cache confirmed. [`CachePolicyTimeout`](#configuration) limits how long: once a refresh fails after that much time without a confirmation, the client drops the connection and reconnects, which fetches a fresh policy. Meanwhile requests follow [Reconnection and fallback](#reconnection-and-fallback). A successful refresh restarts the window, so a healthy connection is never dropped.

## Reconnection and fallback

**When the connection is lost.** The client starts reconnecting at once, in the background, even if the application is idle. Each attempt opens a new TLS connection, signs in with the current [IAM token](#authentication) and gets the current [cache policy](#request-routing). Each attempt has 5 s to complete, whatever `ConnectionTimeout` is, so a short `ConnectionTimeout` still lets a slow network reconnect. Attempts back off from 100 ms to 3.2 s and continue until one succeeds or the client is closed.

**Requests during an outage.** A request the cache would serve first waits for the connection to come back, up to [`ConnectionTimeout`](#configuration). This wait is per outage, not per request: a request that arrives later in the same outage waits only for what is left of it. If the connection comes back in time, the request is served from the cache, with its whole `CacheRequestTimeout`.

**After the wait.** If the connection is still down, the request goes to DynamoDB for as long as [`FallbackDuration`](#configuration) allows, counted from the end of the wait. The default has no limit, a positive value allows that long, and `aws.Duration(0)` never allows it. Once the window ends, the request fails with a retryable `*CacheUnavailableError` for the SDK to handle. A write sent to DynamoDB this way can leave a stale entry in the cache until its [TTL](#cache-policy-overrides) expires.

**Strict mode** (`FallbackDuration: aws.Duration(0)`). A request the cache would serve never goes to DynamoDB: not during an outage, not when the connection is too busy to take it, not when the cache replies `AUTH_ERROR`, and not after [`Close`](#closing-the-client). Requests the predicates send to DynamoDB still go there, and so do the calls that [always go there](#request-routing), such as a per-call `Credentials` or `Region`, until `Close`. After `Close` only a call with its own `HTTPClient` reaches DynamoDB.

**Too many requests at once.** One connection carries up to 100 requests in flight, and the cache serves one connection at about 35,000 requests per second. For more, raise [`Connections`](#connections). A request that finds no room within 75% of `CacheRequestTimeout` is not sent to the cache and goes to DynamoDB, or fails with a retryable `*CacheUnavailableError` in strict mode.

**A request already sent when the connection drops.** It fails with a retryable `*ConnectionLostError`, whatever its operation, the same as when the connection to DynamoDB drops without the cache. Your SDK retry settings decide whether it is sent again, and the retry goes through the same wait and fallback window as any other request. All the requests one drop fails cost the SDK's [retry quota](#errors-and-retries) one retry. As without the cache, a retried write may be applied twice if the first attempt reached DynamoDB.

**At startup.** `NewFromConfig` keeps trying to connect until [`ConnectionTimeout`](#configuration) has passed, then returns the last attempt's failure: a `*ConnectionError` if it could not connect, or an `*AuthError` if the cache rejected its credentials. It stops at once with an `*OverridesRejectedError` when the cache rejects the [cache policy overrides](#cache-policy-overrides), and with a `*ConfigUnevaluableError` when the cache sends routing rules the client cannot compile, because a retry would get the same answer. It never returns a client that is not connected to the cache. Only the last attempt's failure is returned, as in the other clients, so a last attempt that `ConnectionTimeout` cuts short reports a `*ConnectionError` even when the earlier attempts got an `*AuthError`; Debug logging shows each attempt's failure (event 1302).

## Closing the client

`cache.Close()` closes the connections, fails the requests still waiting on them, and waits for every goroutine the client started. It never waits for the cache to answer, but it does wait for a credentials `Retrieve` in flight, so a provider that ignores its context can hold it up. It is safe to call more than once and from any goroutine, and always returns `nil`.

- A request in flight when you call `Close` fails with a retryable `*ConnectionLostError`. The SDK's retry then goes to DynamoDB. In strict mode it is not retryable.
- After `Close`, every call on the DynamoDB client goes straight to DynamoDB. In strict mode every call fails instead, routed or not, with a `*CacheUnavailableError` that is not retryable. Only a call whose option function sets its own `HTTPClient` still reaches DynamoDB, because it no longer goes through the client.

The DynamoDB client itself has no `Close`; stop using it when you are done.

## Errors and retries

Retries work as they do without the cache: your SDK retry settings decide whether, and how many times, a request is retried. A request that times out or fails because the cache could not complete it can be retried, for any operation. As without the cache, a retried write may be applied twice if the first attempt reached DynamoDB.

Your application handles these failures as it does without the cache: once the SDK's retries run out, the call returns the SDK's `*smithy.OperationError`. The client's error is inside it, so `errors.As` reaches it:

```go
_, err := ddb.GetItem(ctx, input)
var unavailable *elasticacheintegrations.CacheUnavailableError
if errors.As(err, &unavailable) {
	log.Print("the cache is unavailable: ", unavailable.Reason)
}
```

Every error the client creates matches `elasticacheintegrations.ErrIntegrations` with `errors.Is`. "At startup" means from `NewFromConfig` or `New`.

| Error | When | Retryable |
|---|---|---|
| `*ConfigError` | A bad option, no region, no or anonymous credentials, credentials that cannot be retrieved, an unreadable `CACertsFile`, or an `HTTPClient` that is already an ElastiCache Integrations client. At startup. `Option` names the option. | — |
| `*ConnectionError` | Startup could not connect in `ConnectionTimeout`. It wraps the last failure. | — |
| `*AuthError` | The cache rejected the IAM token (`WRONGPASS`, `invalid-iam-credentials`). At startup. | — |
| `*OverridesRejectedError` | The cache rejected `CachePolicyOverrides` (`CONFIG_INVALID`). At startup. | — |
| `*ConfigUnevaluableError` | The cache sent routing rules the client cannot compile. At startup. | — |
| `*TimeoutError` | The cache did not answer within `CacheRequestTimeout`. `Timeout()` is true. | yes |
| `*ConnectionLostError` | The connection closed while the request was in flight, also when `Close` closed it. The cache answering `MOVED` or `NOAUTH`, a reply stream the client cannot decode, and a reply over 16 MiB also close the connection; the last two wrap a `*ProtocolError`. | yes; strict mode after `Close`: no |
| `*OriginError` | The cache could not complete its call to DynamoDB (`ORIGIN_ERROR`, `INTERNAL_ERROR`). | yes |
| `*CacheUnavailableError` | No connection and the fallback window is spent, or strict mode. `Reason` says which. In strict mode it may wrap a `*RequestNotSentError`: the connection had no room for the request in time. | yes; after `Close` in strict mode: no |
| `*RejectedError` | The cache refused the request (`PARSE_ERROR`, `UNSUPPORTED`, `UNIMPLEMENTED`, `REGION_MISMATCH` or an unknown code). `Code` holds the code. It never goes to DynamoDB. | no |
| `*ProtocolError` | The cache sent a reply of the wrong shape, such as a header block over 64 KiB or 1024 lines. The connection stays open. | no |

In strict mode, every error after `Close` is not retryable.

A `*ProtocolError` can also sit inside a retryable `*ConnectionLostError`, when the stream cannot be decoded at all. Check for `*ConnectionLostError` first when the difference matters.

A cancelled or expired `ctx` ends the call with a `*smithy.CanceledError` that wraps `ctx.Err()`, as the SDK returns it, so `errors.Is(err, context.Canceled)` or `context.DeadlineExceeded` holds. It is never retried.

**How the SDK learns what to retry.** Each error the client hands the SDK has a `RetryableError() bool` method with the decision in the table above. The SDK's standard retryer (`retry.NewStandard`, the default) and the adaptive retryer (`retry.NewAdaptiveMode`) check it right after the cancellation check, before their other rules. If you replace the retryer, yours must honour `RetryableError` too. One that ignores it treats every cache error as a connection error and retries even a `*RejectedError` or the strict-mode error after `Close`.

**The retry quota.** Each retry spends 14 tokens of the standard retryer's 500-token bucket (5 for a throttling error). One connection carries up to 100 requests in flight, so when it drops, every one of them fails at the same moment. The client wraps the DynamoDB client's retryer so that one drop costs the bucket one retry, however many requests it failed, as one dropped HTTP connection costs one retry without the cache: the first of them to retry pays, and the others retry without spending tokens, or are refused if it was. This applies to the retryer the DynamoDB client has after its option functions, the default included; a `Retryer` set on a single call is used as it is. Your retryer still decides whether to retry and how many attempts a request gets. In a long outage with strict mode, or a spent fallback window, every request fails with a retryable `*CacheUnavailableError`, which pays the full cost, so the bucket can empty. While it is empty the SDK does not retry, including throttling errors from DynamoDB. Successful calls refill it slowly.

## Authentication

The client signs in to the cache with an IAM authentication token, using the region and the `Credentials` provider of the DynamoDB client it builds. The cache is accessed as the same identity as your DynamoDB requests, and credential rotation (SSO, assumed roles, container and instance credentials) keeps working. `NewFromConfig` retrieves the credentials once, so a provider that cannot resolve fails at startup with a `*ConfigError`.

The client routes a call only when the SDK signed it with a key from the DynamoDB client's own provider ([Request routing](#request-routing)): it records the access key id that provider returns while the SDK signs each attempt, and compares it with the signed request. So a provider that returns a new key on every call still routes, and no extra `Retrieve` runs. The client does not add a credentials cache: use the provider you would use without it (`config.LoadDefaultConfig` gives you a cached one).

Tokens are valid for 15 minutes. The client signs a replacement in the background before the current one expires, and signs a new one before its next use when the credentials it signed with expire within a minute. About every 15 minutes, it also authenticates the live connection again with the current token. If the cache rejects the new token, the connection stays open with its current authentication, and the client signs another token and tries again, with backoff. If the cache does not answer the `AUTH` in time, or replies with neither `OK` nor an error, the client drops the connection and [reconnects](#reconnection-and-fallback). Set [`IAMTokenRefreshDelay`](#configuration) to change when the replacement is signed.

When the cache answers a request `AUTH_ERROR`, it holds no valid AWS credentials for that connection. The client drops the connection and reconnects with a fresh token, and that request goes to DynamoDB, whatever the fallback window; in strict mode it fails with a retryable `*CacheUnavailableError`.

Token signing uses the SDK's own SigV4 signer (`aws/signer/v4`). There is no ElastiCache developer toolkit for Go.

### IAM permissions

The identity of your DynamoDB client needs:

| Permission | On | Why |
|---|---|---|
| `elasticache:Connect` | The cache and the ElastiCache user ([`UserID`](#configuration), `default.iam-user` by default) | To sign in to the cache. |
| DynamoDB read actions (`dynamodb:GetItem`, `Query`, `Scan`, `BatchGetItem`, `TransactGetItems`, ...) | The cached tables | On a cache miss the cache calls DynamoDB for you. |
| DynamoDB write actions (`dynamodb:PutItem`, `UpdateItem`, `DeleteItem`, ...) | The tables you write through the cache | The cache sends each write to DynamoDB for you. |

The cache does not forward your signed request. When you sign in, ElastiCache gets a forward access session (FAS) credential for your identity, and the cache signs every miss and write with it. DynamoDB checks your permissions on each of those calls, and `aws:ViaAWSService` is true for them.

A read served from the cache is authorized by the cache connection alone; DynamoDB permissions are checked when the item is first read from DynamoDB. So **cached DynamoDB data is readable by anyone who can connect to the cache.** Give a cache only to principals that may read the same tables. Attribute- and item-level conditions (`dynamodb:LeadingKeys`, `dynamodb:Attributes`) are evaluated by DynamoDB on a miss and not again on a hit. To keep such a table out of the cache, deny it in IAM for calls made through an AWS service (`aws:ViaAWSService`), so a miss fails and no entry is created. Also leave it out of `cached_tables`, so its requests go straight to DynamoDB instead of failing. `cached_tables` only routes this client's requests; it does not stop another client of the same cache. Entries cached before the deny stay readable until they expire.

## TLS

The connection always uses TLS 1.2 or later. The cache's certificate and host name are checked against the system roots, or against `CACertsFile` when set. The name checked is the `Endpoint` host, also when it is an IP address. `InsecureSkipVerify` keeps TLS but skips the check, and logs a warning once per process; it is not a production option.

## Logging

The client logs through `log/slog`. Without `Options.Logger` it writes Info and above, as text, to stderr, so warnings are never lost. Pass `slog.New(slog.DiscardHandler)` to silence it. Every record has `component=elasticacheintegrations` and an `event` id. Messages never hold tokens, signatures, credentials, request or response bodies. A logger that panics cannot stop the client.

A failure that repeats logs at Warn the first time and every 100th time, so an outage does not flood the log.

| Event | Meaning | Level |
|---|---|---|
| 1001 | `InsecureSkipVerify` is on | Warn, once per process |
| 1101 | A re-AUTH or config refresh failed | Warn on the first and every 100th failure, else Debug |
| 1102 | A re-AUTH or config refresh succeeded after failures | Info |
| 1103 | The cache replied `AUTH_ERROR`; reconnecting | Warn, once per dropped connection |
| 1201 | The connection was lost | Info; 1303 warns when reconnecting keeps failing |
| 1202 | An in-order reply did not arrive in time; the connection is closed | Warn |
| 1203 | A reply arrived for a request that no longer waits for it | Debug |
| 1301 | A config was applied | Debug |
| 1302 | A startup attempt failed; retrying | Debug |
| 1303 | A reconnect attempt failed | Warn on the first and every 100th failure, else Debug |
| 1304 | Reconnected | Info, or Debug when the first attempt worked |
| 1305 | The cache is unreachable and requests may not go to DynamoDB | Warn, once per outage |
| 1306 | The config was not confirmed within `CachePolicyTimeout`; reconnecting | Warn |
| 1307 | The cache rejected the overrides, or sent a config the client cannot use | Error |
| 1308 | A request found no room on the connection in time | Warn once per burst; a burst ends after 1 s with none |

## Limits

- **Request body.** A request body over 16 MiB, or of unknown length, goes to DynamoDB.
- **Reply size.** A reply over 16 MiB closes the connection, so every request in flight on it fails with a retryable `*ConnectionLostError`. A retry of the same request meets the same reply. DynamoDB caps a `Query` or `Scan` page at 1 MB and a `BatchGetItem` response at 16 MB, so only a `BatchGetItem` close to its limit can reach this.
- **Reply headers.** A served reply whose header block is over 64 KiB or 1024 lines fails that request with a `*ProtocolError`. A DynamoDB response has about ten headers.
- **Requests in flight.** 100 per connection; see [Too many requests at once](#reconnection-and-fallback).
- **One connection per client.** Each `NewFromConfig` opens one connection. Requests share it, and replies may arrive out of order, so a slow request does not hold up the ones behind it.
- **RESP3 only.** The cache must answer `HELLO 3`.
- **Only the DynamoDB client** is supported.

## Troubleshooting

**`NewFromConfig` fails.** Check the error with `errors.As`:

| Error | Likely cause |
|---|---|
| `*ConfigError` with `Option` `Region` or `Credentials` | The AWS config has no region, or no credentials resolve. The client needs the same ones the DynamoDB client uses. |
| `*ConnectionError` wrapping a dial or TLS error | The endpoint is not reachable from this host (a cache in a VPC needs a route and a security group that allow port 6379), or the certificate does not match the host name. |
| `*ConnectionError` wrapping a `*RejectedError` with `Code` `ERR` and `unknown command 'aws.config'` | DynamoDB caching is not enabled on the cache. |
| `*AuthError` | The identity lacks `elasticache:Connect` on the cache or the user, the user does not exist, or `CacheName` is not the cache's name. |
| `*OverridesRejectedError` | A key or value in `CachePolicyOverrides` the cache does not accept. The message says which. |

**Requests always go to DynamoDB.** Check that the operation is one of the ten in [Request routing](#request-routing), that the table is in `cached_tables` when you set it, and that the call does not set its own `Credentials`, `Region` or `HTTPClient`. Turn on Debug logging to see the applied config (event 1301).

**The cache is behind an SSH tunnel.** The client dials `Endpoint` and checks the certificate against its host, so pointing `Endpoint` at the tunnel's address fails the TLS check. Map the cache host name to the tunnel address in `/etc/hosts` and keep the cache host in `Endpoint`.

**Timeouts under load.** `*TimeoutError` means the cache did not answer within `CacheRequestTimeout`. A miss includes the cache's call to DynamoDB, so a slow table or large item can need more than the 5 s default. A timeout closes the connection when it is for a reply that must arrive in order, and fails the other requests on it with retryable errors.

## Development

See [DEVELOPER.md](DEVELOPER.md) for building, testing, the live example and releasing the client.
