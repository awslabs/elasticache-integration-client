# ElastiCache Integrations for Python

ElastiCache Integrations provides a caching layer for AWS SDK for Python (Boto3) DynamoDB requests. It connects your DynamoDB client to an ElastiCache Serverless cache, sends supported requests through the cache, and serves cacheable responses without contacting DynamoDB.

## Requirements

- Python 3.10 or later.
- boto3 and botocore 1.43 or later.

## Installation

The client is published to PyPI as [`aws-elasticache-integration-client`](https://pypi.org/project/aws-elasticache-integration-client/). The import package is `aws_elasticache_integration_client`.

```bash
pip install aws-elasticache-integration-client
```

It installs its dependencies with it, including the [ElastiCache developer toolkit](https://pypi.org/project/developer-toolkit-elasticache/) for IAM authentication. It has no native dependencies.

## Basic Example

Before:

```python
import boto3

ddb = boto3.client("dynamodb", region_name="us-east-1")

ddb.get_item(TableName="Users", Key={"id": {"S": "42"}})
ddb.close()
```

After:

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
ddb.close()  # also disconnects the cache
```

The only change to your application is the call to `install(...)`; see [Configuration](#configuration) for its options. It changes the client you pass in place and returns nothing. Everything else stays the same: you call DynamoDB exactly as before, and the ElastiCache Integrations layer in front of it serves what it can from the cache.

`install` connects to the cache before it returns. If it cannot connect or [authenticate](#authentication), it raises instead of leaving a client that never caches. Your client is then left as it was, so you can keep using it without the cache.

## How it works

- **The cache decides what it serves.** When the client connects, the cache tells it which requests it wants. Those are sent to the cache; everything else goes to DynamoDB as it would without ElastiCache Integrations. See [Request routing](#request-routing).
- **The cache going down does not take your application down.** If the connection to the cache is lost, the client reconnects in the background, and meanwhile requests go to DynamoDB as they would without ElastiCache Integrations in the middle. Once the connection is back, so is the caching layer. See [Reconnection and fallback](#reconnection-and-fallback) for the full details, and [Configuration](#configuration) to change how long requests wait and fall back.
- **Closing the client also disconnects the cache.**

## Examples

### Your own session and settings for DynamoDB

`install` keeps everything about the client you pass: its profile, credentials, endpoint and `botocore.config.Config`, including timeouts, retries and connection pool. Set those on the client as usual:

```python
import boto3
from botocore.config import Config
import aws_elasticache_integration_client

session = boto3.Session(profile_name="prod", region_name="us-east-1")
ddb = session.client(
    "dynamodb",
    config=Config(connect_timeout=1, read_timeout=2, max_pool_connections=50),
)

aws_elasticache_integration_client.install(
    client=ddb,
    endpoint="my-cache-abc123.serverless.use1.cache.amazonaws.com:6379",
    cache_name="my-cache",
)
```

These settings apply to the requests that go to DynamoDB. The connection to the cache has its own options, in [Configuration](#configuration).

The client is synchronous. Install into each boto3 client you create; a client can have the cache installed only once.

## Configuration

Every option has a default, so set only what you change. The docstrings of `install` and `ElastiCacheIntegrationsClientConfiguration` have the full details.

Arguments of `install(...)`, all keyword-only:

| Argument | Default | Meaning |
|---|---|---|
| `client` | required | The boto3 client to route through the cache. |
| `endpoint` | required | The cache endpoint, as `host:port`. |
| `cache_name` | required | The ElastiCache Serverless cache name. |
| `user_id` | `default.iam-user` | The ElastiCache IAM user. See [Authentication](#authentication). |
| `verify` | `True` | Verify the cache's TLS certificate. `False` is for local testing only. |
| `ca_certs` | system trust store | The path to a CA bundle to verify the cache's certificate with. |
| `client_configuration` | all defaults | An `ElastiCacheIntegrationsClientConfiguration` with the options below. |

These describe the connection to the cache, which the boto3 client knows nothing about. The connection to DynamoDB stays the client's own configuration.

Fields of `aws_elasticache_integration_client.ElastiCacheIntegrationsClientConfiguration(...)`, all keyword-only:

| Field | Default | Meaning |
|---|---|---|
| `cache_request_timeout` | 5000 | How long to wait for the cache to answer a request, in whole milliseconds. See [Errors](#errors) for what a timeout looks like. |
| `connection_timeout` | 5000 | How long to try connecting at startup, and how long a request waits for a lost connection to come back, in whole milliseconds. See [Reconnection and fallback](#reconnection-and-fallback). |
| `fallback_duration` | `None` (no limit) | How long requests may go to DynamoDB while the cache is unreachable, in whole seconds. `0` means never. A negative value means no limit. See [Reconnection and fallback](#reconnection-and-fallback). |
| `cache_policy_overrides` | `None` | What to cache and for how long, as a `dict`. See [Cache policy overrides](#cache-policy-overrides). |
| `cache_policy_timeout` | 3600 | How long the client keeps using a cache policy the cache has not confirmed, in whole seconds. See [Cache policy timeout](#cache-policy-timeout). |
| `iam_token_refresh_delay` | `None` (300) | How long after a token is issued a replacement is signed in the background, in whole seconds. 1 to 879. `None` uses the toolkit's default of 300. See [Authentication](#authentication). |

Creating `ElastiCacheIntegrationsClientConfiguration` with a value it cannot use, such as a timeout that is not a positive `int`, raises `ElastiCacheIntegrationsConfigError`. So does `install` for a bad endpoint, a client with no region, or a client with no credentials.

### Cache policy overrides

These are sent to the cache as a `dict`:

| Key | Meaning |
|---|---|
| `cached_tables` | The tables to cache. Empty or absent means all tables. |
| `cached_operations` | The operations to cache, such as `GetItem` or `Query`. Empty or absent means all of them. |
| `item_ttl`, `item_negative_ttl`, `query_ttl`, `scan_ttl` | How long each kind of entry lives. `item_negative_ttl` covers "this item does not exist". |
| `item_write_behavior` | How a write updates the cached item. |
| `table_overrides` | The settings above for one table, keyed by table name. |

`cached_tables` and `cached_operations` are allowlists, and they combine: only those operations, on only those tables. There is no way to exclude a table; to keep one out of the cache, list the tables that should be in it. A `table_overrides` entry only changes settings; it does not add its table to `cached_tables`.

The cache checks the overrides when the client connects. If it does not allow a value, it rejects the overrides with `ElastiCacheIntegrationsOverridesRejected` rather than adjusting them. A key it does not recognize is ignored, so check the spelling of each key.

### Example

```python
import boto3
from botocore.config import Config
import aws_elasticache_integration_client
from aws_elasticache_integration_client import (
    ElastiCacheIntegrationsClientConfiguration,
    ElastiCacheIntegrationsError,
)

# Your DynamoDB client, configured as usual. install keeps all of it.
session = boto3.Session(profile_name="prod", region_name="us-east-1")
ddb = session.client("dynamodb", config=Config(read_timeout=3))

try:
    aws_elasticache_integration_client.install(
        client=ddb,
        endpoint="my-cache-abc123.serverless.use1.cache.amazonaws.com:6379",
        cache_name="my-cache",
        client_configuration=ElastiCacheIntegrationsClientConfiguration(
            cache_request_timeout=2000,  # wait at most 2 s for the cache
            fallback_duration=60,  # requests may go to DynamoDB for 60 s while the cache is unreachable
            # Cache only the Users and Sessions tables, and set their item lifetime.
            cache_policy_overrides={"cached_tables": ["Users", "Sessions"], "item_ttl": 60},
        ),
    )
except ElastiCacheIntegrationsError as e:
    # The cache could not be reached or rejected the credentials. ddb is unchanged: run without it.
    print(f"Starting without the cache: {e}")

ddb.get_item(TableName="Users", Key={"id": {"S": "42"}})
ddb.close()
```

## Request routing

When the client connects, the cache sends it a set of routing rules. The client checks each request against them itself: a request that matches any rule is sent to the cache, and every other request goes straight to DynamoDB, with no round trip to the cache.

A rule can look at the request's service, operation, method, URL, headers and JSON body. A rule that fails to evaluate, or a body that is not a JSON object, counts as no match. A rule the client cannot understand at all rejects the whole set, because using only the rest would route requests differently from what the cache intended: connecting fails and is retried, and a refresh keeps the previous rules.

A request with a streaming body always goes to DynamoDB, because reading it for the cache would consume it.

The client asks the cache for its current rules and [cache policy](#cache-policy-overrides) periodically, at an interval the cache chooses, and replaces the rules all at once, so a request never sees half of an update.

## Cache policy timeout

If those periodic [refreshes](#request-routing) fail, the client keeps using the last policy the cache confirmed. [`cache_policy_timeout`](#configuration) limits how long: once a refresh fails after that much time without a confirmation, the client drops the connection and reconnects, which fetches a fresh policy. Meanwhile requests follow [Reconnection and fallback](#reconnection-and-fallback). A successful refresh restarts the window, so a healthy connection is never dropped.

## Reconnection and fallback

**When the connection is lost.** The client starts reconnecting at once, in the background, even if the application is idle. Each attempt opens a new TLS connection, signs in with the current [IAM token](#authentication) and gets the current [cache policy](#request-routing). Attempts back off and continue until one succeeds or the client is closed.

**Requests during an outage.** A request the cache would serve first waits for the connection to come back, up to [`connection_timeout`](#configuration). This wait is per outage, not per request: a request that arrives later in the same outage waits only for what is left of it. If the connection comes back in time, the request is served from the cache.

**After the wait.** If the connection is still down, the request goes to DynamoDB for as long as [`fallback_duration`](#configuration) allows, counted from the end of the wait. The default has no limit, a positive value allows that many seconds, and `0` never allows it. Once the window ends, the request fails with `ElastiCacheIntegrationsCacheUnavailable`, a [retryable error](#errors) for the SDK to handle. A write sent to DynamoDB this way can leave a stale entry in the cache until its [TTL](#cache-policy-overrides) expires.

The window is per outage, not a total budget: with `fallback_duration=60`, each outage gets 60 seconds of fallback, and a connection that keeps dropping gets a fresh 60 seconds after every successful reconnect.

**A request already sent when the connection drops.** It fails with a [retryable error](#errors), whatever its operation, the same as when the connection to DynamoDB drops without the cache. Your SDK retry settings decide whether it is sent again, and the retry goes through the same wait and fallback window as any other request. As without the cache, a retried write may be applied twice if the first attempt reached DynamoDB.

**When the cache rejects the connection's authentication.** If the cache answers a request with `AUTH_ERROR`, the client drops the connection and reconnects with a fresh token. The rejected request goes to DynamoDB if the fallback window allows it, and fails with `ElastiCacheIntegrationsCacheUnavailable` at once with `fallback_duration=0`.

**When the connection is busy.** One connection has at most 100 requests waiting for the cache at a time. Further requests wait for a free slot, in arrival order, for up to three quarters of [`cache_request_timeout`](#configuration). A request that does not get a slot in time is not sent to the cache and goes to DynamoDB, whatever its operation and whatever `fallback_duration` is.

**At startup.** `install` keeps trying to connect until [`connection_timeout`](#configuration) has passed, then raises the last attempt's failure: `ElastiCacheIntegrationsConnectionError` if it could not connect (with the last connection failure as its `__cause__`), `ElastiCacheIntegrationsAuthError` if the cache rejected its credentials, or `ElastiCacheIntegrationsOverridesRejected` if the cache rejected the [cache policy overrides](#cache-policy-overrides). It never leaves a client that is not connected to the cache.

## Errors

The client's exceptions are in `aws_elasticache_integration_client.exceptions`. Most extend `ElastiCacheIntegrationsError`, which is also exported from the top-level package with `ElastiCacheIntegrationsConfigError` and `ElastiCacheIntegrationsCacheUnavailable`.

A failure the SDK may retry is raised as a botocore `ConnectionClosedError` or `ReadTimeoutError`, so your retry configuration applies to it as it does to a request to DynamoDB, and the `except` blocks you already have for those catch it. After the SDK's retries, the last one reaches you:

| Error | When |
|---|---|
| `ConnectionClosedError` | The connection to the cache was lost after the request was sent, for any operation. |
| `ReadTimeoutError` | The cache did not answer a read within `cache_request_timeout`. |
| `ElastiCacheIntegrationsOriginError` | The cache could not get a read's response from DynamoDB (`ORIGIN_ERROR`). |
| `ElastiCacheIntegrationsCacheUnavailable` | There is no connection to the cache and the [fallback window](#reconnection-and-fallback) has closed. |

Reads here are the operations in [`shared/idempotent-operations.json`](../shared/idempotent-operations.json), such as `GetItem`, `Query` and `Scan`, which are safe to send again. `ElastiCacheIntegrationsOriginError` and `ElastiCacheIntegrationsCacheUnavailable` subclass `ConnectionClosedError`, not `ElastiCacheIntegrationsError`. For the first two, the client's own exception, such as `ElastiCacheIntegrationsConnectionLost` or `ElastiCacheIntegrationsTimeoutError`, is the `__cause__`.

Any other failure is raised as the client's own `ElastiCacheIntegrationsError`, without retries. For example, an error returned by the cache, such as a rejected request, is an `ElastiCacheIntegrationsRejectedError` with the cache's error code in `code`. A write the cache did not answer in time is an `ElastiCacheIntegrationsTimeoutError`, because it is not safe to send again.

## Authentication

The client signs in to the cache with an IAM authentication token, using the region and credentials of the boto3 client you pass to `install`. See the [Configuration example](#example) for a client with its own profile. The cache is accessed as the same identity as your DynamoDB requests, and credential rotation (SSO, assumed roles, container and instance credentials) keeps working, because the client reads the credentials again for every token it signs. A client created with `signature_version=UNSIGNED` has no credentials, and `install` rejects it.

That identity needs the `elasticache:Connect` permission on the cache and on the ElastiCache user ([`user_id`](#configuration), `default.iam-user` by default).

Tokens are valid for 15 minutes. The client signs a replacement in the background before the current one expires; set [`iam_token_refresh_delay`](#configuration) to change when. The token is sent only to the cache, over TLS.

About every 15 minutes, the client authenticates each live connection again with the current token. If the cache rejects it, the connection stays open with its current authentication, and the client signs another token and tries again, with backoff. If the cache does not answer the `AUTH` in time, or its reply is not the expected `OK`, the client drops the connection and [reconnects](#reconnection-and-fallback).

Token signing and refresh come from the [ElastiCache developer toolkit](https://pypi.org/project/developer-toolkit-elasticache/), which also validates the cache name and user ID and converts them to lowercase.

## Logging

The client logs through the standard `logging` module, under the `aws_elasticache_integration_client` logger. It adds only a `NullHandler`, so nothing is printed until your application configures logging. It logs connection loss and reconnection, and failed background refreshes. It never logs credentials or tokens.

## Development

See [DEVELOPER.md](DEVELOPER.md) for building, testing and releasing the client.
