# Photon Node

Photon implementation done for Node.js.

## Scope

This package currently ports the Python implementation's:

- Photon error taxonomy and cause preservation;
- request, response, metadata, and resolved-config models;
- binary-safe RESP3 command encoding and decoding;
- `photon.config` and `photon.request` serialization and parsing;
- deterministic request-header normalization;
- compiled CEL predicate evaluation for cacheability routing;
- a single-connection TCP/TLS transport with FIFO response correlation,
  request-ID correlation for deferred cache responses delivered through later
  RESP3 pushes,
  bounded response frames, a single `maxPending` bound covering both
  positional/FIFO and deferred requests, bounded send backpressure,
  differentiated connect/request/initialization timeouts, idempotent close,
  and injected AUTH credentials;
- a session that performs connect, AUTH, and the `photon.config` handshake,
  reconnects failed transports in the background, periodically refreshes the
  config on the interval the cache returns, and controls safe origin fallback
  with a per-outage window;
- AWS SDK v3 middleware that receives the final signed request, strips volatile
  SigV4 headers from the Photon copy, routes dropped-connection failures to the
  origin only for operations in the shared replay-safe catalog, and marks a
  healthy-connection `ORIGIN_ERROR` retryable only for those operations;
- an async `client()` factory that returns an ordinary AWS SDK v3 client.

Automatic Photon failover to another endpoint remains out of scope. Deferred
cache responses are correlated by request ID and later `photon.response`
pushes, while callers still receive ordinary promises. The per-request timeout
defaults to 5 seconds and can be overridden with
`connectorOptions.requestTimeout`. A timeout for a deferred request removes only
that request and preserves the connection; a FIFO or write timeout remains
connection-fatal. The middleware sends requests with no matching predicate
directly to origin and has a narrow origin fallback for cataloged idempotent
requests when the connection drops. On a healthy connection, `ORIGIN_ERROR` is
returned to the SDK as retryable for a cataloged operation.
`PHOTON_AUTH_ERROR` closes the stale connection, starts background recovery, and
passes the explicitly rejected request through. Other RESP errors, malformed
responses, and failures for non-idempotent operations propagate without origin
fallback.

## Request routing

Opening a Photon session compiles the CEL predicates returned by
`photon.config`. Expressions can use the request's `service`, `operation`,
`method`, `url`, `headers`, and parsed JSON `body`, plus arbitrary values bound
by the predicate's `vars` object.

A request is sent to Photon when any predicate evaluates to `true`. Otherwise,
the original signed SDK request continues to the configured origin transport
without a cache round trip. Invalid predicates are ignored independently.
Evaluation errors and malformed or non-object JSON bodies count as no match.

The predicates are compiled when the session opens and recompiled whenever a
refresh returns a new config. A refresh sends `photon.config` with no argument;
a RESP3 null reply means the config is unchanged. A failed or malformed config
reply keeps the last known-good config, predicates, and refresh interval, and
the next cycle retries. A transport timeout or connection loss discards the
unusable connector and starts background recovery.

## Client

```ts
import { DynamoDBClient, ListTablesCommand } from "@aws-sdk/client-dynamodb";
import { client } from "photon-nodejs";

const dynamodb = await client("dynamodb", DynamoDBClient, {
  endpoint: "cache.example.com:6379",
  cacheName: "my-cache",
  region: "us-east-1",
});

try {
  const result = await dynamodb.send(new ListTablesCommand({}));
  console.log(result.TableNames);
} finally {
  dynamodb.destroy();
}
```

The factory completes IAM authentication and the `photon.config` handshake
before installing middleware and returning the ordinary SDK client. Wrapped AWS
requests are fully serialized and signed by the SDK before Photon intercepts
them. Photon strips volatile SigV4 headers only from its copied request model;
the original signed request remains untouched for origin pass-through. When the
Photon connection drops, failures for operations in the shared replay-safe
catalog return to the SDK's normal origin pipeline. While the connection
remains healthy, only an `ORIGIN_ERROR` for a cataloged operation is marked
SDK-retryable. Other RESP errors, non-idempotent failures, and request
validation errors and fallback-window exhaustion propagate. Calling `destroy()`
detaches Photon before SDK teardown, rejects in-flight Photon requests without
passing shutdown failures to the origin, stops reconnect activity, and is safe
to repeat.
`region` and `userId` are optional and otherwise resolve from the standard
Node.js AWS configuration. `clientConfig` and `connectorOptions` are
Node-specific; all `photon.config` overrides are passed through the `overrides`
option.

## Reconnection and fallback

Client creation retries the initial connection a bounded number of times and
still fails loudly if no attempt completes. After a live connection is lost,
Photon reconnects in the background until it succeeds or the client is
destroyed. Idle socket loss starts recovery without waiting for a request.
Every replacement resolves fresh AUTH credentials, repeats `photon.config`, and
recompiles the resolved predicates.

While there is no connection, a routed request has not been sent to Photon and
can safely use the origin. `fallbackDuration` controls that per-outage window:

| `fallbackDuration`                         | Behavior                                                                         |
| ------------------------------------------ | -------------------------------------------------------------------------------- |
| `undefined` (default), `null`, or negative | Fall back indefinitely, so a cache outage does not become an application outage. |
| Positive integer                           | Fall back for that many seconds, then raise `PhotonCacheUnavailable`.            |
| `0`                                        | Never fall back; routed requests raise immediately while disconnected.           |

The window resets after every successful reconnect. Requests that do not match
the resolved predicates continue to origin even after a finite window expires.
`PhotonCacheUnavailable` is exported and marked retryable for the AWS SDK.

```ts
const dynamodb = await client("dynamodb", DynamoDBClient, {
  endpoint: "cache.example.com:6379",
  cacheName: "my-cache",
  fallbackDuration: 60,
  reconnectPolicy: {
    connectAttempts: 3,
    connectionTimeoutMs: 5_000,
    baseDelayMs: 100,
    maxDelayMs: 3_200,
    jitter: 0.2,
  },
});
```

Reconnect delay doubles to `maxDelayMs`, with symmetric jitter applied to every
attempt including the capped tail. Closing or destroying the SDK client
interrupts reconnect backoff and prevents further attempts.

AUTH normally uses `@aws/developer-toolkit-elasticache` to cache and
automatically refresh short-lived IAM tokens from the SDK credential chain.
The toolkit validates cache names and user IDs, normalizes them to lowercase
before signing, and refreshes the cached token in the background. If Photon
receives an AUTH rejection, it invalidates that token so the next connection
attempt uses a newly signed one. Destroying the SDK client stops background
token refresh. A caller that already holds a token, or that authenticates
against a local or test endpoint, supplies `connectorOptions.authProvider`
instead: it returns `[userId, token]` and replaces toolkit token management
entirely, so no AWS credentials are required.

The first token is minted lazily. Set `iamRefreshAfterSeconds` to customize its
background refresh point; the accepted range, defaults, retry behavior, and
safety margin are defined by the
[toolkit token manager documentation](https://github.com/aws/developer-toolkit-elasticache/tree/release-2/node#caching-and-background-refresh).

The `client()` factory creates this default manager, so destroying the returned
SDK client also closes the manager and stops its timers. When
`connectorOptions.authProvider` is supplied, Photon does not create or close
that provider; the caller owns its lifecycle, matching Java's explicitly
constructed `IamAuthTokenManager`.

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

The package builds ESM and CommonJS outputs, emits TypeScript declarations, and
does not publish as part of this prototype.
