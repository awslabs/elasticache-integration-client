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
- a session that performs connect, AUTH, and the `photon.config` handshake;
- AWS SDK v3 middleware that receives the final signed request, strips volatile
  SigV4 headers from the Photon copy, and routes transport failures to the
  origin only for operations in the shared replay-safe catalog;
- an async `client()` factory that returns an ordinary AWS SDK v3 client.

Automatic Photon failover/reconnection, circuit breaking, periodic config
refresh remain out of scope. Deferred cache responses are correlated by request
ID and later `photon.response` pushes, while callers still receive ordinary
promises. A timeout for a deferred request removes only that request and
preserves the connection; a FIFO or write timeout remains connection-fatal. The
middleware sends requests with no matching predicate directly to origin and has
a narrow origin fallback for cataloged idempotent requests when Photon times
out or loses its connection; non-idempotent transport failures and non-Photon
errors propagate. Response-level Photon errors still fall through because
Photon answered the request.

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
transport errors for operations in the shared replay-safe catalog therefore
return to the SDK's normal origin request and retry pipeline. Non-idempotent
transport errors and request validation errors propagate. Calling `destroy()`
detaches Photon before SDK teardown,
rejects in-flight Photon requests without passing shutdown failures to the
origin, and is safe to repeat.
`region` and `userId` are optional and otherwise resolve from the standard
Node.js AWS configuration. `clientConfig` and `connectorOptions` are
Node-specific; all `photon.config` overrides are passed through the `overrides`
option.

Authentication, transport, protocol, and middleware implementation details are
internal to the package. Overrides use standard JSON-compatible values;
non-finite JavaScript numbers are rejected instead of being silently converted
to `null`.

## Development

```bash
npm install
npm run verify
```

The package builds ESM and CommonJS outputs, emits TypeScript declarations, and
does not publish as part of this prototype.
