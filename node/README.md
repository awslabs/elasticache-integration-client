# ElastiCacheIntegrations Node

ElastiCacheIntegrations implementation done for Node.js.

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
  SigV4 headers from the ElastiCacheIntegrations copy, routes dropped-connection failures to the
  origin only for operations in the shared replay-safe catalog, and marks a
  healthy-connection `ORIGIN_ERROR` retryable only for those operations;
- an async `install()` that adds ElastiCacheIntegrations to an AWS SDK v3 client
  the application already built.

Automatic ElastiCacheIntegrations failover to another endpoint remains out of scope. Deferred
cache responses are correlated by request ID and later `aws.response`
pushes, while callers still receive ordinary promises. The per-request timeout
defaults to 5 seconds and can be overridden with
`connectorOptions.requestTimeout`. A timeout for a deferred request removes only
that request and preserves the connection; a FIFO or write timeout remains
connection-fatal. The middleware sends requests with no matching predicate
directly to origin and has a narrow origin fallback for cataloged idempotent
requests when the connection drops. On a healthy connection, `ORIGIN_ERROR` is
returned to the SDK as retryable for a cataloged operation.
`AUTH_ERROR` closes the stale connection, starts background recovery, and
passes the explicitly rejected request through. Other RESP errors, malformed
responses, and failures for non-idempotent operations propagate without origin
fallback.

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
[Config staleness](#config-staleness) for what happens when refreshes fail.

## Client

```ts
import { DynamoDBClient, ListTablesCommand } from "@aws-sdk/client-dynamodb";
import { install } from "elasticache-integrations";

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
before installing middleware, then returns the same client. Every SDK setting
on the client stays as configured. ElastiCacheIntegrations reads three things
from it: the region, which also signs the cache's IAM token; the credentials,
which sign that token; and the service id, lowercased with spaces replaced by
`-` (`DynamoDB` becomes `dynamodb`) to match the `service` in request
predicates. A client whose region cannot be resolved raises
`ElastiCacheIntegrationsConfigError`. If installation fails,
ElastiCacheIntegrations releases what it opened and leaves the client usable.
Wrapped AWS requests are fully serialized and signed by the SDK before
ElastiCacheIntegrations intercepts them. ElastiCacheIntegrations strips volatile
SigV4 headers only from its copied request model;
the original signed request remains untouched for origin pass-through. When the
ElastiCacheIntegrations connection drops, failures for operations in the shared replay-safe
catalog return to the SDK's normal origin pipeline. While the connection
remains healthy, only an `ORIGIN_ERROR` for a cataloged operation is marked
SDK-retryable. Other RESP errors, non-idempotent failures, and request
validation errors and fallback-window exhaustion propagate. Calling `destroy()`
detaches ElastiCacheIntegrations before SDK teardown, rejects in-flight ElastiCacheIntegrations requests without
passing shutdown failures to the origin, stops reconnect activity, and is safe
to repeat.
`userId` defaults to `default.iam-user`. `connectorOptions` is Node-specific;
all `aws.config` overrides are passed through `overrides`.
`stalenessDuration` is described under [Config staleness](#config-staleness).

## Reconnection and fallback

`install()` retries the initial connection a bounded number of times and
still fails loudly if no attempt completes. After a live connection is lost,
ElastiCacheIntegrations reconnects in the background until it succeeds or the client is
destroyed. Idle socket loss starts recovery without waiting for a request.
Every replacement resolves fresh credentials, repeats `HELLO 3 AUTH` and `aws.config`, and
recompiles the resolved predicates.

While there is no connection, a routed request has not been sent to ElastiCacheIntegrations and
can safely use the origin. `fallbackDuration` controls that per-outage window:

| `fallbackDuration`                         | Behavior                                                                               |
| ------------------------------------------ | -------------------------------------------------------------------------------------- |
| `undefined` (default), `null`, or negative | Fall back indefinitely, so a cache outage does not become an application outage.       |
| Positive integer                           | Fall back for that many seconds, then raise `ElastiCacheIntegrationsCacheUnavailable`. |
| `0`                                        | Never fall back; routed requests raise immediately while disconnected.                 |

The window resets after every successful reconnect. Requests that do not match
the resolved predicates continue to origin even after a finite window expires.
`ElastiCacheIntegrationsCacheUnavailable` is exported and marked retryable for the AWS SDK.

## Config staleness

The resolved config is re-fetched periodically on the live connection. A
refresh that fails changes nothing: the last config the server confirmed stays
in force and the next refresh retries. A config the server sends, an empty
"unchanged" reply, and each new connection resolving its own config all count
as confirmation.

`stalenessDuration` bounds that tolerance. It is a positive whole number of
seconds and defaults to one hour. A refresh that fails past it means the config
can no longer be trusted, so ElastiCacheIntegrations drops the connection, and bringing up the
replacement resolves a fresh config. A refresh that succeeds restarts the
window, so a healthy connection is never dropped. Requests fall back meanwhile
under the `fallbackDuration` rules above. A request already in flight when the connection
drops falls back only when it is idempotent.

A `CONFIG_INVALID` reply (the proxy rejected the overrides) and a config with a
predicate this client cannot compile never fix themselves, so they log at error
level on every occurrence, including each connection attempt. On refresh they
still keep the last good config until the staleness window drops the
connection. Refresh timeouts and connection loss
start recovery immediately.

```ts
await install({
  client: dynamodb,
  endpoint: "cache.example.com:6379",
  cacheName: "my-cache",
  fallbackDuration: 60,
  reconnectPolicy: {
    connectAttempts: 3,
    connectionTimeoutMs: 5_000,
    authRefreshIntervalMs: 900_000,
    baseDelayMs: 100,
    maxDelayMs: 3_200,
    jitter: 0.2,
  },
});
```

Reconnect delay doubles to `maxDelayMs`, with symmetric jitter applied to every
attempt including the capped tail. The same `reconnectPolicy.jitter` is applied
to config-refresh and re-AUTH intervals for parity with the other clients;
setting it to `0` disables jitter for all three schedules. Closing or destroying
the SDK client interrupts reconnect backoff and prevents further attempts.

The `HELLO 3 AUTH` handshake normally uses `@aws/developer-toolkit-elasticache` to cache and
automatically refresh short-lived IAM tokens from the SDK client's credentials.
The toolkit validates cache names and user IDs, normalizes them to lowercase
before signing, and refreshes the cached token in the background. If ElastiCacheIntegrations
rejects either the initial handshake or a background AUTH refresh, it invalidates
that token so the next authentication attempt uses a newly signed one. Destroying
the SDK client stops background
token refresh. A caller that already holds a token, or that authenticates
against a local or test endpoint, supplies `connectorOptions.authProvider`
instead: it returns `[userId, token]` and replaces toolkit token management
entirely, so no AWS credentials are required.

The first token is minted lazily. Set `iamRefreshAfterSeconds` to customize its
background refresh point; the accepted range, defaults, retry behavior, and
safety margin are defined by the
[toolkit token manager documentation](https://github.com/aws/developer-toolkit-elasticache/tree/release-2/node#caching-and-background-refresh).

`install()` creates this default manager, so destroying the SDK client also
closes the manager and stops its timers. When
`connectorOptions.authProvider` is supplied, ElastiCacheIntegrations does not create or close
that provider; the caller owns its lifecycle, matching Java's explicitly
constructed `IamAuthTokenManager`.

ElastiCacheIntegrations repeats `AUTH` on each live connection on a jittered 15-minute base
interval by default. A rejected refresh is retried with reconnect backoff while
the connection keeps serving with its last valid authentication state. A
timed-out or malformed AUTH reply drops the connection for FIFO safety and
starts normal recovery. Configure the cadence with
`reconnectPolicy.authRefreshIntervalMs`.

TLS is on by default and the server's certificate is verified against Node.js's
default CA store — its bundled Mozilla roots, plus the OS trust store only when
Node is run with system-CA support enabled — or against
`connectorOptions.caCerts` when supplied. A local or test endpoint whose
certificate cannot be verified is reached with
`connectorOptions.insecureSkipVerify`, which keeps TLS but waives the identity
check and emits a process warning on the first handshake that uses it. It is not
a production option; supply `caCerts` instead wherever the endpoint's CA is
available.

Authentication, transport, protocol, and middleware implementation details are
internal to the package. Overrides use standard JSON-compatible values;
non-finite JavaScript numbers are rejected instead of being silently converted
to `null`.

## Development

```bash
npm install
npm run verify
```

Until the ElastiCache toolkit is published to npm, it is pinned as a Git
submodule. Initialize it after cloning with
`git submodule update --init --recursive`.

The package builds ESM and CommonJS outputs and emits TypeScript declarations.
Annotated `vMAJOR.MINOR.PATCH` and `vMAJOR.MINOR.PATCH-rc.N` tags start the Node
release workflow. It validates the tag against `package.json`, tests and
inspects one tarball, then publishes that exact artifact through the protected
`release` environment using npm trusted publishing. Release candidates use the
`next` npm distribution tag; stable releases use `latest`. A manual workflow
run performs the same validation and uploads the tarball and checksum without
entering the release environment or publishing to npm.
