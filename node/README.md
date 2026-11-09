# ElastiCacheIntegrations Node

ElastiCacheIntegrations implementation done for Node.js.

The npm package name is `@aws-sdk/aws-elasticache-integration-client`.

## Scope

This package currently ports the Python implementation's:

- ElastiCacheIntegrations error taxonomy and cause preservation;
- request, response, metadata, and resolved-config models;
- binary-safe RESP3 command encoding and decoding;
- `aws.config` and `aws.request` serialization and parsing;
- deterministic request-header normalization;
- compiled CEL predicate evaluation for cacheability routing;
- a single-connection TCP/TLS transport with FIFO response correlation,
  request-ID correlation for deferred cache responses delivered through later
  RESP3 pushes,
  bounded response frames, a single `maxPending` bound covering both
  positional/FIFO and deferred requests, bounded send backpressure,
  differentiated connect/request/initialization timeouts, idempotent close,
  and an authenticated `HELLO 3` handshake;
- a session that performs connect, `HELLO 3 AUTH`, and the `aws.config` exchange,
  reconnects failed transports in the background, periodically refreshes the
  config on the interval the cache returns, and controls safe origin fallback
  with a per-outage window;
- AWS SDK v3 middleware that receives the final signed request, strips volatile
  SigV4 headers and the `host`, `x-amz-content-sha256`, and
  `x-amz-user-agent` headers from the ElastiCacheIntegrations copy, routes dropped-connection failures to the
  origin only for operations in the shared replay-safe catalog, and marks a
  healthy-connection `ORIGIN_ERROR` retryable only for those operations;
- an async `install()` that adds ElastiCacheIntegrations to an AWS SDK v3 client
  the application already built.

Automatic ElastiCacheIntegrations failover to another endpoint remains out of scope. Deferred
cache responses are correlated by request ID and later `aws.response`
pushes, while callers still receive ordinary promises. The per-request timeout
defaults to 5000 ms and can be changed with the
`cacheRequestTimeout` field (see [Configuration](#configuration)). A timeout for a deferred request removes only
that request and preserves the connection; a FIFO or write timeout remains
connection-fatal. The middleware sends requests with no matching predicate
directly to origin and has a narrow origin fallback for cataloged idempotent
requests when the connection drops. On a healthy connection, `ORIGIN_ERROR` is
returned to the SDK as retryable for a cataloged operation.
`AUTH_ERROR` closes the stale connection, starts background recovery, and
passes the explicitly rejected request through. With `fallbackDuration` set to
0, the rejected request raises `ElastiCacheIntegrationsCacheUnavailable` at once
instead. Other RESP errors, malformed responses, and failures for
non-idempotent operations propagate without origin fallback.

## Request routing

Opening a ElastiCacheIntegrations session compiles the CEL predicates returned by
`aws.config`. Expressions can use the request's `service`, `operation`,
`method`, `url`, `headers`, and parsed JSON `body`, plus arbitrary values bound
by the predicate's `vars` object.

A request is sent to ElastiCacheIntegrations when any predicate evaluates to `true`. Otherwise,
the original signed SDK request continues to the configured origin transport
without a cache round trip. Evaluation errors and malformed or non-object JSON
bodies count as no match. A predicate that cannot be compiled is different: one
bad entry rejects the whole config rather than caching by the entries that did
compile, because those would route a different set of requests than the proxy
resolved. `install()` fails on such a config, a reconnect retries, and a refresh
keeps the last good config.

The predicates are compiled when the session opens and recompiled whenever a
refresh returns a new config. A refresh sends `aws.config` with no argument,
and a RESP3 null reply means the config is unchanged. See
[Cache policy timeout](#cache-policy-timeout) for what happens when refreshes fail.

## Client

```ts
import { DynamoDBClient, ListTablesCommand } from "@aws-sdk/client-dynamodb";
import { install } from "@aws-sdk/aws-elasticache-integration-client";

const dynamodb = new DynamoDBClient({ region: "us-east-1", maxAttempts: 10 });

await install({
  client: dynamodb,
  endpoint: "cache.example.com:6379",
  cacheName: "my-cache",
});

try {
  const result = await dynamodb.send(new ListTablesCommand({}));
  console.log(result.TableNames);
} finally {
  dynamodb.destroy();
}
```

`install()` completes the authenticated `HELLO 3` and `aws.config` handshakes
before installing middleware into the client, and resolves once it is ready.
The client keeps its identical API, and every SDK setting on it stays as
configured. ElastiCacheIntegrations reads three things
from it: the region, which also signs the cache's IAM token; the credentials,
which sign that token; and the service id, lowercased with spaces replaced by
`-` (`DynamoDB` becomes `dynamodb`) to match the `service` in request
predicates. A value that is not an AWS SDK v3 client, a client without a
region, or a client whose credentials provider cannot resolve credentials
raises `ElastiCacheIntegrationsConfigError`. In the last case, the SDK's error
is the cause. `install()` resolves the credentials once before it connects and
signs the first cache token with them. If installation fails,
ElastiCacheIntegrations releases what it opened, and the client stays open and
usable without the cache.
Wrapped AWS requests are fully serialized and signed by the SDK before
ElastiCacheIntegrations intercepts them. ElastiCacheIntegrations strips volatile
SigV4 headers, plus the `host`, `x-amz-content-sha256`, and `x-amz-user-agent`
headers that the JS SDK adds, only from its copied request model;
the original signed request remains untouched for origin pass-through. When the
ElastiCacheIntegrations connection drops, failures for operations in the shared replay-safe
catalog return to the SDK's normal origin pipeline. While the connection
remains healthy, only an `ORIGIN_ERROR` for a cataloged operation is marked
SDK-retryable. Other RESP errors, non-idempotent failures, and request
validation errors and fallback-window exhaustion propagate. Calling `destroy()`
detaches ElastiCacheIntegrations before SDK teardown, rejects in-flight ElastiCacheIntegrations requests without
passing shutdown failures to the origin, stops reconnect activity, and is safe
to repeat. A client can have the cache installed once; a second `install()` on
the same client raises `ElastiCacheIntegrationsConfigError`. After `destroy()`,
the client can be installed into again.
`userId` defaults to `default.iam-user`. `verify` (default `true`) and
`caCerts`, the path of a PEM CA bundle file that `install()` reads once before
connecting, control TLS certificate verification; see the end of this README.

## Configuration

Optional ElastiCacheIntegrations settings go in one
`ElastiCacheIntegrationsClientConfiguration` object, passed as
`clientConfiguration`. Every field has a default, so set only what you change.
Cache policy settings are sent to the cache through `aws.config`. `install()`
copies `cachePolicyOverrides`, so changing the object afterward has no effect.

```ts
await install({
  client: dynamodb,
  endpoint: "cache.example.com:6379",
  cacheName: "my-cache",
  clientConfiguration: {
    cacheRequestTimeout: 2_000,
    connectionTimeout: 5_000,
    fallbackDuration: 60,
    cachePolicyOverrides: { cached_tables: ["Users", "Sessions"] },
    iamTokenRefreshDelay: 300,
  },
});
```

| Field                  | Default                  | Meaning                                                                                                                                      |
| ---------------------- | ------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------- |
| `cacheRequestTimeout`  | 5000 ms                  | How long to wait for each ElastiCacheIntegrations request's reply.                                                                           |
| `connectionTimeout`    | 5000 ms                  | How long to try at startup, and how long a request waits for reconnection after a connection is lost.                                        |
| `fallbackDuration`     | `undefined` (indefinite) | How long requests may go to the origin service while there is no cache connection (see below).                                               |
| `cachePolicyOverrides` | `undefined`              | Settings that change what ElastiCacheIntegrations caches: `cached_tables` and `cached_operations` allowlists, and the cache entry lifetimes. |
| `cachePolicyTimeout`   | 3600 s                   | How long ElastiCacheIntegrations may use a cache policy without confirmation from the cache.                                                 |
| `iamTokenRefreshDelay` | 300 s (toolkit default)  | When the IAM toolkit signs a replacement token in the background. Greater than 0 and less than 880 s.                                        |

`install()` raises `ElastiCacheIntegrationsConfigError` for a value that is not
usable, or for an install option or `clientConfiguration` field it does not
recognise, before it connects. `cachePolicyTimeout` is described under
[Cache policy timeout](#cache-policy-timeout).

## Reconnection and fallback

A lost connection is rebuilt in the background and retried until it succeeds or
the client is destroyed. Idle socket loss starts recovery without waiting for a
request. Every replacement resolves fresh credentials, repeats `HELLO 3 AUTH`
and `aws.config`, and recompiles the resolved predicates.

Right after the loss, a routed request waits up to `connectionTimeout` for the
new connection and is served from the cache if it arrives. If the connection is
not back by then, the request has not been sent to ElastiCacheIntegrations and
can safely use the origin. The same timeout bounds startup: `install()` keeps
retrying until `connectionTimeout` passes, then rejects if no connection is
ready.

`clientConfiguration.fallbackDuration` controls how long requests may fall
back, counted from the end of `connectionTimeout`:

| `fallbackDuration`                         | Behavior                                                                                                                |
| ------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------- |
| `undefined` (default), `null`, or negative | Fall back indefinitely, so a cache outage does not become an application outage.                                        |
| Positive integer                           | Fall back for that many seconds, then raise `ElastiCacheIntegrationsCacheUnavailable`.                                  |
| `0`                                        | Never fall back; wait up to `connectionTimeout` for a connection, then raise `ElastiCacheIntegrationsCacheUnavailable`. |

The wait and the window apply to each outage, and both reset after every
successful reconnect. A request that arrives after the wait has ended does not
wait again. With `fallbackDuration: 0`, an SDK retry in the same outage
therefore does not restart the wait. Requests that do not match
the resolved predicates continue to origin even after a finite window expires.
`ElastiCacheIntegrationsCacheUnavailable` is exported and marked retryable for the AWS SDK.

## Cache policy timeout

ElastiCacheIntegrations automatically confirms the resolved cache policy in the
background through `aws.config`. A new policy replaces the current one. An
unchanged reply confirms that the current policy is still valid. Both replies
restart the timeout, and each new connection resolving its own policy counts as
confirmation too.

If confirmation fails, ElastiCacheIntegrations keeps using the last confirmed
policy. That tolerance is bounded by `cachePolicyTimeout`, one hour by default,
which must be a positive whole number of seconds. After the timeout, the next
failed confirmation drops the connection so a replacement can fetch a current
policy, and a healthy connection is never dropped. Requests follow the
`fallbackDuration` rules while the connection is being replaced. A request
already in flight when the connection drops falls back only when it is
idempotent.

A `CONFIG_INVALID` reply (the proxy rejected the overrides) and a policy with a
predicate this client cannot compile never fix themselves, so they log at error
level on every occurrence, including each connection attempt. On refresh they
still keep the last confirmed policy until the cache policy timeout drops the
connection. Refresh timeouts and connection loss
start recovery immediately.

Startup attempts back off the same way and stop when `connectionTimeout`
passes. Each background reconnect attempt is capped at 5 seconds. Reconnect delay
starts at 100 ms and doubles up to 3.2 s, with ±20% jitter applied to every
attempt including the capped tail. The same jitter applies to config-refresh
and re-AUTH intervals, as in the other clients. Closing or destroying the SDK
client interrupts reconnect backoff and prevents further attempts.

The `HELLO 3 AUTH` handshake normally uses the
[`@aws/developer-toolkit-elasticache`](https://www.npmjs.com/package/@aws/developer-toolkit-elasticache)
package on npm to cache and
automatically refresh short-lived IAM tokens from the SDK client's credentials.
The toolkit validates cache names and user IDs, normalizes them to lowercase
before signing, and refreshes the cached token in the background. If ElastiCacheIntegrations
rejects either the initial handshake or a background AUTH refresh, it invalidates
that token so the next authentication attempt uses a newly signed one. Destroying
the SDK client stops background
token refresh.

The first token is minted lazily. The toolkit signs a replacement in the
background after 300 seconds by default. Set
`clientConfiguration.iamTokenRefreshDelay` to customize that background signing
point; it must be greater than 0 and less than 880 seconds, preserving the
toolkit's 20-second safety margin on the 15-minute token. Refresh and retry
behavior is described in the
[toolkit token manager documentation](https://github.com/aws/developer-toolkit-elasticache/tree/main/node#caching-and-background-refresh).

`install()` creates the token manager, so destroying the SDK client also
closes the manager and stops its timers.

ElastiCacheIntegrations repeats `AUTH` on each live connection on a jittered 15-minute base
interval by default. A rejected refresh is retried with reconnect backoff while
the connection keeps serving with its last valid authentication state. A
timed-out or malformed AUTH reply drops the connection for FIFO safety and
starts normal recovery.

TLS is on by default and the server's certificate is verified against Node.js's
default CA store — its bundled Mozilla roots, plus the OS trust store only when
Node is run with system-CA support enabled — or against
the CA bundle file named by `caCerts` when supplied. A local or test endpoint whose
certificate cannot be verified is reached with
`verify: false`, which keeps TLS but waives the identity
check and emits a process warning on the first handshake that uses it. It is not
a production option; supply `caCerts` instead wherever the endpoint's CA is
available.

Authentication, transport, protocol, and middleware implementation details are
internal to the package. `cachePolicyOverrides` use standard JSON-compatible values.
`install()` copies them through the same JSON serializer as `aws.config`, so a
`BigInt` or a non-finite number raises `ElastiCacheIntegrationsConfigError`
before connecting instead of being silently converted to `null`.

## Development

```bash
npm install
npm run verify
```

The package builds ESM and CommonJS outputs and emits TypeScript declarations.
Annotated `vMAJOR.MINOR.PATCH` and `vMAJOR.MINOR.PATCH-rc.N` tags start the Node
release workflow. It validates the tag against `package.json`, tests and
inspects one tarball, then publishes that exact artifact through the protected
`release` environment using npm trusted publishing. Release candidates use the
`next` npm distribution tag; stable releases use `latest`. A manual workflow
run performs the same validation and uploads the tarball and checksum without
entering the release environment or publishing to npm.
