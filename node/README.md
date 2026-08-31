# Photon Node

Photon implementation done for Node.js.

## Scope

This package currently ports the Python implementation's:

- Photon error taxonomy and cause preservation;
- request, response, metadata, and resolved-config models;
- binary-safe RESP3 command encoding and decoding;
- `photon.config` and `photon.request` serialization and parsing;
- deterministic request-header normalization;
- a single-connection TCP/TLS transport with FIFO response correlation,
  bounded response frames, bounded in-flight requests, bounded send backpressure,
  differentiated connect/request/initialization timeouts, idempotent close, and
  injected AUTH credentials;
- a session that performs connect, AUTH, and the `photon.config` handshake;
- AWS SDK v3 middleware that receives the final signed request, strips volatile
  SigV4 headers from the Photon copy, and routes idempotent Photon failures to
  the origin;
- an async `client()` factory that returns an ordinary AWS SDK v3 client.

Cacheability routing, automatic Photon failover/reconnection, circuit breaking,
periodic config refresh, and out-of-band responses remain out of scope. The
middleware has a narrow origin fallback for idempotent requests when Photon
fails; non-idempotent Photon failures and non-Photon errors propagate.

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
request and retry pipeline. Non-idempotent Photon errors and request validation
errors propagate. Calling `destroy()` detaches Photon before SDK teardown,
rejects in-flight Photon requests without passing shutdown failures to the
origin, and is safe to repeat.
`region` and `userId` are optional and otherwise resolve from the standard
Node.js AWS configuration. `clientConfig` and `connectionOptions` are
Node-specific; all `photon.config` overrides are passed through the `overrides`
option. The previous `connectorOptions` spelling remains supported as a
deprecated alias.

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
