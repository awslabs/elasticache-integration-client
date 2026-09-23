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
  reconnects failed transports in the background, and controls safe origin
  fallback with a per-outage window;
- AWS SDK v3 middleware that receives the final signed request, strips volatile
  SigV4 headers from the Photon copy, and routes idempotent Photon failures to
  the origin;
- an async `client()` factory that returns an ordinary AWS SDK v3 client.

Automatic Photon failover to another endpoint and periodic config refresh
remain out of scope. Deferred cache responses are correlated by request
ID and later `photon.response` pushes, while callers still receive ordinary
promises. A timeout for a deferred request removes only that request and
preserves the connection; a FIFO or write timeout remains connection-fatal. The
middleware sends requests with no matching predicate directly to origin.

## Request routing

Opening a Photon session compiles the CEL predicates returned by
`photon.config`. Expressions can use the request's `service`, `operation`,
`method`, `url`, `headers`, and parsed JSON `body`, plus arbitrary values bound
by the predicate's `vars` object.

A request is sent to Photon when any predicate evaluates to `true`. Otherwise,
the original signed SDK request continues to the configured origin transport
without a cache round trip. Invalid predicates are ignored independently.
Evaluation errors and malformed or non-object JSON bodies count as no match.
The resolved config is compiled once when the session opens.

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
the original signed request remains untouched for origin pass-through. Photon
errors for idempotent operations therefore return to the SDK's normal origin
request and retry pipeline. Non-idempotent Photon errors, fallback-window
exhaustion, and request validation errors propagate. Calling `destroy()`
detaches Photon before SDK teardown, rejects in-flight Photon requests, stops
reconnect activity, and is safe to repeat.
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

AUTH normally uses `@aws/developer-toolkit-elasticache` to presign a short-lived
IAM token from the SDK credential chain. The toolkit validates cache names and
user IDs and normalizes them to lowercase before signing. A caller that already
holds a token, or that authenticates against a local or test endpoint, supplies
`connectorOptions.authProvider` instead: it returns `[userId, token]` and
replaces token generation entirely, so no AWS credentials are required.

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
