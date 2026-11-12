# ElastiCacheIntegrations (Python client)


## Install & test

```bash
cd python
pip install -e '.[test]'   # runtime deps plus pytest and TLS test fixtures
python -m pytest           # see tests/README.md for details
pip install -e '.[dev]'    # pinned ruff, mypy, build, twine
ruff format --check . && ruff check . && mypy
```

Runtime requires Python ≥ 3.10. See [`tests/README.md`](tests/README.md) for the test layout and
how to run the unit vs. end-to-end tests.

## Releases

Python publication is driven by annotated Git tags. Stable releases use
`vMAJOR.MINOR.PATCH`; release candidates use `vMAJOR.MINOR.PATCH-rc.N`, which maps to the
PEP 440 version `MAJOR.MINOR.PATCHrcN`. A release tag applies to every language client: Node,
Python, and Java release together.

The tag is the release version source of truth. The `Python Release` workflow
converts it to PEP 440 form and stamps `pyproject.toml` only in its temporary
checkout, leaving the checked-in version at its local development baseline. It
runs the reusable Python CI workflow, builds one wheel and one source
distribution, and smoke tests the wheel on Python 3.10, 3.12, and 3.14 before
waiting for approval from the protected `release` environment and publishing
with PyPI trusted publishing and attestations. The workflow verifies the
published version and attaches the distributions plus `python-SHA256SUMS` to
the matching GitHub Release.

The PyPI distribution name is `aws-elasticache-integration-client`, and the import package is
`aws_elasticache_integration_client`. The PyPI project must
configure `.github/workflows/python-release.yml` as its trusted publisher for the `release`
environment. No PyPI token is used by the workflow. A manual workflow run performs the same test,
packaging, artifact validation, and wheel smoke stages for an optional version
without entering the release environment or publishing to PyPI.

## Current state

The library delivers the happy path: intercept → serialize → `aws.config` handshake →
`aws.request` → return the cached response over a real TLS RESP3 connection, with
session-level IAM auth. The transport runs on a **pure-Python RESP3 connector**
(`ElastiCacheIntegrationsConnector`) — no Glide or other native dependency.
Its per-request timeout defaults to 5000 ms and can be changed with the
`cache_request_timeout` field (see below).

Cache requests may complete out of order when the cache returns a request ID and later delivers
the result in a `aws.response` RESP3 push. Deferred requests do not block later FIFO replies. If a
deferred request times out, only that request is abandoned, and a late push for its ID is dropped.
A timeout while a request is still FIFO-correlated closes the connection to prevent reply
misrouting. A RESP3 push with an unrecognized kind is treated as protocol corruption and closes
the connection. One connection sends at most 100 requests awaiting replies at once. Additional
callers wait for a slot within their `cache_request_timeout` instead of failing at the concurrency
boundary. Queue admission uses at most 75% of that timeout, reserving 25% to send and receive a
reply. If that budget is exhausted, the request is not sent to the cache and continues to origin
regardless of operation idempotency.

## Installing into a client

`install()` takes a boto3 client the application already built, so the client keeps its own
profile, `botocore.config.Config`, endpoint and everything else, and its region and credentials
are the ones the cache is authenticated with.

```python
import boto3
import aws_elasticache_integration_client

ddb = boto3.client("dynamodb", region_name="us-east-1")

aws_elasticache_integration_client.install(
    client=ddb, endpoint="my-cache:6379", cache_name="my-cache"
)

ddb.get_item(TableName="my-table", Key={"id": {"S": "1"}})  # unchanged
```

| Argument | Default | Meaning |
|---|---|---|
| `client` | required | The boto3 client to route through the cache. |
| `endpoint` | required | Cache endpoint as `host:port`. |
| `cache_name` | required | The ElastiCache Serverless cache name. |
| `user_id` | `default.iam-user` | ElastiCache IAM user id. |
| `verify` | `True` | Verify the cache's TLS certificate. `False` is for self-signed test rigs only. |
| `ca_certs` | `None` | CA bundle for the cache's certificate, instead of the system trust store. |
| `client_configuration` | all defaults | See [Configuration](#configuration). |

Everything above describes the connection to the **cache**, which the boto3 client knows nothing
about. TLS to AWS, and everything else about reaching the service, stays the client's own
configuration.

Closing the client disconnects the cache first, then closes the client as usual. A client can
have the cache installed once; a second `install()` on the same client raises
`ElastiCacheIntegrationsConfigError`.

## Configuration

Optional ElastiCacheIntegrations settings go in one `aws_elasticache_integration_client.ElastiCacheIntegrationsClientConfiguration`, passed as
`client_configuration`. Every field has a default, so set only what you change. Cache policy
settings are sent to the cache through `aws.config`.

```python
aws_elasticache_integration_client.install(
    client=ddb,
    endpoint="my-cache:6379",
    cache_name="my-cache",
    client_configuration=aws_elasticache_integration_client.ElastiCacheIntegrationsClientConfiguration(
        cache_request_timeout=2000,
        connection_timeout=5000,
        fallback_duration=60,
        cache_policy_overrides={"cached_tables": ["Users", "Sessions"]},
        iam_token_refresh_delay=300,
    ),
)
```

| Field | Default | Meaning |
|---|---|---|
| `cache_request_timeout` | 5000 ms | How long to wait for each ElastiCacheIntegrations request's reply. |
| `connection_timeout` | 5000 ms | How long to try at startup, and how long a request waits for reconnection after a connection is lost. |
| `fallback_duration` | `None` (indefinite) | How long requests may go to the origin service while there is no cache connection (see below). |
| `cache_policy_overrides` | `None` | Settings that change what ElastiCacheIntegrations caches: `cached_tables` and `cached_operations` allowlists, and the cache entry lifetimes. See below. |
| `cache_policy_timeout` | 3600 s | How long ElastiCacheIntegrations may use a cache policy without confirmation from the cache. |
| `iam_token_refresh_delay` | 300 s | When the IAM toolkit signs a replacement token in the background. A whole number of seconds. |

Building `ElastiCacheIntegrationsClientConfiguration` raises `ElastiCacheIntegrationsConfigError` for a value that is not usable.

### Cache policy overrides

`cached_tables` and `cached_operations` are **allowlists**, and they intersect: only those
operations, on only those tables. Each one empty or absent means everything, so the example
above caches `Users` and `Sessions` and nothing else. There is no exclude form — to keep one
table out of the cache, name the tables that should be in it.

Lifetimes are set per category (`item_ttl`, `item_negative_ttl`, `query_ttl`, `scan_ttl`),
connection-wide at the top level or per table under `table_overrides`; a `table_overrides`
entry is settings only and never puts a table in scope.

```python
cache_policy_overrides = {
    "cached_tables": ["Users", "Sessions"],
    "cached_operations": ["GetItem", "Query"],
    "item_ttl": 500,
    "item_negative_ttl": 0,  # do not cache "this item does not exist"
    "table_overrides": {"Users": {"item_ttl": 60}},
}
```

A value the cache does not allow is refused at connect time with
`ElastiCacheIntegrationsOverridesRejected`, rather than being quietly adjusted. A field name
the cache does not recognise is **ignored**, so a typo fails silently — the names above are
the whole set.

## IAM authentication

IAM token generation and refresh come from the Amazon ElastiCache developer toolkit, the
[`developer-toolkit-elasticache`](https://pypi.org/project/developer-toolkit-elasticache/) package on PyPI.
The toolkit validates and lowercases cache names and user IDs, re-reads the credentials for
every newly signed token, and owns the SigV4 signing contract. ElastiCacheIntegrations sends the bearer token
only in the TLS-protected `HELLO 3 AUTH` exchange; origin request signing remains owned by boto3.

The credentials are the ones behind the boto3 client, so the cache is authenticated as the same
principal as the requests, and rotation (SSO, assume-role, container, instance metadata) keeps
working because the credentials object refreshes itself. A client built with
`signature_version=UNSIGNED` carries none and is rejected.

`install()` creates a toolkit token manager. It caches the current token, signs a
replacement in the background after 300 seconds by default, and force-refreshes when a live
connection explicitly rejects the cached token. Set `iam_token_refresh_delay` to customize that
background signing point through `ElastiCacheIntegrationsClientConfiguration`; it must be a whole number greater than
0 and less than 880 seconds, preserving the toolkit's 20-second safety margin on the 15-minute token.

Independently, ElastiCacheIntegrations sends `AUTH` on the live connection every 15 minutes to refresh the proxy's
forward-access session. A rejected background `AUTH` leaves the existing connection alive on its
previous authentication and is retried with backoff. Only a timeout or unexpected reply closes the
connection for FIFO safety. Closing the boto3 client stops both ElastiCacheIntegrations re-authentication and the
toolkit's token refresh.

## Reconnection and fallback

A lost connection is rebuilt in the background and retried until it succeeds. Right after the
loss, a request waits up to `connection_timeout` for the new connection. If the connection is not
back by then, the AWS SDK sends the request to the origin service using the application's own
credentials. The same timeout bounds startup; client creation fails if no connection is ready.

How long requests may fall back, counted from the end of `connection_timeout`, is controlled by
`fallback_duration`:

| `fallback_duration` | Behavior |
|---|---|
| `None` (default), or negative | Serve from the origin service indefinitely, so a cache outage never becomes an application outage. |
| `N > 0` | Serve from the origin service for N seconds, then raise `ElastiCacheIntegrationsCacheUnavailable`. |
| `0` | Never fall back — wait up to `connection_timeout` for a connection, then raise `ElastiCacheIntegrationsCacheUnavailable`. |

The window is **per outage, not a total budget**: `fallback_duration=60` allows 60 seconds of
fallback each time the connection is lost, and a connection that keeps flapping gets a fresh 60
seconds after every successful reconnect.

`aws_elasticache_integration_client.ElastiCacheIntegrationsCacheUnavailable` subclasses botocore's `ConnectionClosedError`, so it is retryable and
an application's existing `except botocore...` blocks already catch it. Retryable cuts both ways:
under the SDK's default retry mode a raised request is retried, which is usually what you want — a
retry after backoff can land once the connection is back. With `fallback_duration=0`, a routed
request waits only for the part of `connection_timeout` left in the current outage. Later retries in
the same outage do not restart that wait.

When there is no live cache connection, the request has not been sent and can safely pass through
for any operation, including routed writes, while the fallback window remains open. After a request
is sent, origin fallback is allowed only when the connection drops and the operation is in the
shared replay-safe catalog.
While the connection remains healthy, `ORIGIN_ERROR` becomes a botocore-retryable error only for a
cataloged operation. `AUTH_ERROR` closes the stale connection and passes the rejected
request through while reconnection obtains fresh authentication. With `fallback_duration=0`, the
rejected request raises `ElastiCacheIntegrationsCacheUnavailable` at once instead. Other RESP
errors, malformed replies, and failures for non-idempotent operations propagate without origin
fallback.

## Cache policy timeout

ElastiCacheIntegrations automatically confirms the resolved cache policy in the background through `aws.config`.
A new policy replaces the current one. An unchanged reply confirms that the current policy is still
valid. Both replies restart the timeout.

If confirmation fails, ElastiCacheIntegrations keeps using the last confirmed policy. That tolerance is bounded by
`cache_policy_timeout`, one hour by default. After the timeout, the next failed confirmation drops
the connection so a replacement can fetch a current policy. Requests follow the `fallback_duration`
rules while the connection is being replaced.

## Next steps:

- **Observability.** Reconnection and config staleness are logged but emit no metrics, so under the
  default `fallback_duration` a cache outage has no symptom other than the origin service's bill.
- **Detecting a silently dead peer.** A peer that vanishes without closing the socket is noticed only
  when the next request times out. Closing that gap needs a periodic health check.
- **IAM authentication validation.** Exercise the toolkit-backed flow against a real IAM-enabled
  cache in addition to the hermetic mock-cache coverage.
- **Public TTL / NTTL controls.** TTL/NTTL policy is resolved server-side from the
  `aws.config` overrides; the public API does not expose per-table or per-request TTL
  overrides yet.
