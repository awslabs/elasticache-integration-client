# Developing the ElastiCache Integrations Node.js client

## Building and testing

Run commands from `node/`. CI runs on Node.js 22, 24 and 26.

```bash
npm ci
npm run verify
```

- `npm run verify` is the complete local gate: it checks the generated idempotency catalog, formatting (Prettier, including the Markdown files), lint (ESLint) and types, runs the tests with coverage, then builds the package and runs the package smoke tests.
- `npx vitest run test/<file>.test.ts` runs focused tests.
- `npx prettier --write <paths>` fixes formatting.
- `npm run pack:smoke` builds the package and checks the tarball: both ESM and CommonJS entry points, no sources, tests or private keys, publint and `@arethetypeswrong/cli`. Run it when you change package metadata, exports, builds or published contents.

The build emits ESM and CommonJS outputs with TypeScript declarations for both.

Two further suites run in CI:

- The compatibility jobs in `.github/workflows/compat.yml`, which run the tests against the newest dependencies and against the oldest `@aws-sdk/*` and `@smithy/*` versions `package.json` allows.
- The cross-client alignment tests, `test/protocol-vectors.test.ts` and `test/sdk-request-cases.test.ts`, which run the fixtures in `shared/` that every client must agree on.

## How the client works

The package is a port of the Python client. It contains:

- the error taxonomy, with the originating error kept as `cause`;
- the request, response, metadata and resolved-config models;
- binary-safe RESP3 command encoding and decoding;
- `aws.config` and `aws.request` serialization and parsing;
- deterministic request-header normalization;
- compiled CEL predicates for request routing;
- a single-connection TCP/TLS connector;
- a session that owns the connection, its recovery and the origin fallback;
- AWS SDK v3 middleware, and the `install()` entry point.

Only `install()`, the `ElastiCacheIntegrationsClientConfiguration` type, three error classes and `version` are exported, from `src/index.ts`. Automatic failover to another cache endpoint is out of scope.

### Connector

The connector opens one TCP connection, upgrades it to TLS and authenticates with a single `HELLO 3 AUTH <user> <token>` exchange. Replies are matched to requests in order (FIFO). A cache response that is deferred is instead matched by request ID, when a later RESP3 `aws.response` push delivers it; callers still get an ordinary promise. Response frames are bounded in size, and send backpressure is bounded.

One limit, `maxPending` (100), covers both in-order and deferred requests waiting for a reply. A caller that finds no slot waits for one within `cacheRequestTimeout`. Waiting for a slot may use at most 75% of that timeout, leaving 25% to send and receive the reply. If that budget runs out, the request is not sent (`ElastiCacheIntegrationsRequestNotSent`) and the middleware passes it to the origin, whatever its operation.

A timeout of a deferred request removes only that request and keeps the connection. An in-order or write timeout resets the whole connection, so a late reply cannot be matched to a later request. Connect, request and initialization have separate timeouts, and `close()` is idempotent.

### Session

The session performs connect, `HELLO 3 AUTH` and the first `aws.config` exchange, then compiles the returned predicates. It refreshes the config on the interval the cache returns, by sending `aws.config` with no argument once the overrides have been acknowledged; a RESP3 null reply means the config is unchanged. A new config and its predicates are published together. A predicate that cannot be compiled rejects the whole config.

Startup retries until `connectionTimeout` passes. After a live connection is lost, one continuous background loop reconnects until it succeeds or the session closes. Each background attempt is capped at 5 seconds. The delay between attempts starts at 100 ms and doubles up to 3.2 s, with ±20% jitter on every attempt, including the capped ones. The same jitter applies to the config refresh and re-AUTH intervals, as in the other clients. Closing the session interrupts the backoff and stops further attempts.

On a disconnect, one clock reading sets the outage's ready deadline (`now + connectionTimeout`) and fallback deadline (ready deadline plus `fallbackDuration`); both reset after a successful reconnect.

Each live connection runs `AUTH` again on a jittered 15-minute base interval. A rejection keeps the connection, which keeps its last valid authentication, and is retried with the reconnect backoff. A timeout or a malformed reply drops the connection. After a rejected `HELLO` or `AUTH`, the toolkit's token is invalidated so the next attempt signs a new one. The toolkit validates cache names and user IDs and lowercases them before signing. `install()` resolves the SDK client's credentials once, before connecting, and the first token is signed with them; later tokens read the client's credential provider again, without `forceRefresh`, so its cached identity is reused.

### Middleware

The middleware is added after the SDK's deserializer, so it sees the final serialized, signed request and the command name. It builds the cache request from a copy without the SigV4 headers (`authorization`, `x-amz-date`, `x-amz-security-token`) and the `host`, `x-amz-content-sha256` and `x-amz-user-agent` headers the JS SDK adds. The original signed request stays untouched for the origin.

The client's request handler is wrapped too, because the request handler is the only step that receives the call's `abortSignal`. It is wrapped rather than having its `handle` patched, because an application may share one handler between clients. A handler assigned to `config.requestHandler` after `install()` is wrapped too. A cache response is returned to the SDK as a synthetic `HttpResponse`, so normal deserialization and retry apply. A body that is not bytes-like, such as a stream, passes through to the origin without calling the cache.

How a failed cache request reaches the SDK:

- A lost connection (`ElastiCacheIntegrationsConnectionLost` while the connection is not healthy) is marked `$retryable` for every operation.
- Other transport failures on a dropped connection, a timeout on a healthy one, and a healthy-connection `ORIGIN_ERROR` are marked `$retryable` only for operations in `shared/idempotent-operations.json`.
- An `AUTH_ERROR` reply closes the connection, starts recovery and passes the request to the origin, or raises `ElastiCacheIntegrationsCacheUnavailable` when `fallbackDuration` is `0`.
- Other RESP errors, malformed responses, non-idempotent failures, validation errors and shutdown failures propagate.

`destroy()` detaches the cache before the SDK's own teardown, rejects in-flight cache requests without passing them to the origin, stops reconnecting, closes the IAM token manager and is safe to repeat. `install()` resolves only after authentication, the session and the middleware are in place; on failure it releases only what it opened and never destroys the caller's client.

## Live DynamoDB E2E example

Complete the repository-wide
[live cache setup](../README.md#run-the-examples-against-a-live-cache),
then run from `node/`:

```bash
npm ci
npm run example:local-e2e
```

The example loads the repository-root `.env` and runs the scenarios listed in
the repository README.
Each run uses a freshly generated item ID so the first ElastiCache Integrations read is a cache
miss; set `ELASTICACHE_ITEM_ID` only to pin one ID. Supported overrides include
`ELASTICACHE_CACHE_HOST`, `ELASTICACHE_CACHE_PORT`, `ELASTICACHE_CACHE_NAME`,
`ELASTICACHE_TUNNEL_HOST`, `ELASTICACHE_CACHE_USER_ID`, `ELASTICACHE_CA_CERT`,
`ELASTICACHE_REQUEST_TIMEOUT_SECONDS`, `AWS_REGION` (default `us-east-1`),
`ELASTICACHE_TABLE_NAME`, and `ELASTICACHE_CONCURRENCY`.

## Releasing

Node releases are built from annotated `vMAJOR.MINOR.PATCH` or `vMAJOR.MINOR.PATCH-rc.N` tags. The tag is the release version source of truth: the `Node.js Release` workflow stamps `package.json`, `package-lock.json` and the exported runtime version in its temporary checkout with `scripts/set-package-version.mjs`, and the checked-in versions stay at the local development baseline.

The workflow builds and inspects one tarball, then publishes that exact artifact through the approval-protected `release` environment. Release candidates use the `next` npm distribution tag; stable releases use `latest`. A manual workflow run can take a version and performs the same validation without entering the release environment or publishing to npm.
