# AGENTS.md

## Scope

- This directory is the Java implementation of the ElastiCache Integrations client for AWS SDK v2.
- `README.md` is the source for user-facing API behavior and feature scope.
- Keep this file focused on repository workflow, constraints, and non-obvious
  implementation context.

## Layout

- `src/main/java/software/amazon/elasticache/integrations/`: production Java modules organized by
  concern:
  - `connection/`: TCP/TLS socket management, FIFO/request-ID correlation.
  - `session/`: ElastiCacheIntegrationsSession, ElastiCacheIntegrationsClient (the `install` and
    `installAsync` entry point), ElastiCacheIntegrationsClientConfiguration, ElastiCacheIntegrationsInterceptor.
    Only ElastiCacheIntegrationsClient and ElastiCacheIntegrationsClientConfiguration are public. The
    session, handles, interceptors, transports, ReconnectPolicy and the parsing and SDK adapter helpers
    are package-private. In `auth/`, IamAuthTokenGenerator is package-private, and IamAuthTokenProvider
    and AuthCredentials are internal but must stay public because other packages use them.
  - `protocol/`: ElastiCache Integrations wire protocol serialization.
  - `predicate/`: compiled CEL request-routing predicates.
  - `resp/`: RESP3 codec and value types.
  - `auth/`: adapters for the ElastiCache IAM toolkit dependency.
  - `model/`: ElastiCacheIntegrationsRequest, ElastiCacheIntegrationsResponse, metadata types.
  - `exception/`: ElastiCacheIntegrations error hierarchy.
- `src/test/java/software/amazon/elasticache/integrations/`: JUnit 5 tests organized by
  production concern.
- `src/test/.../mock/`: MockCache, MockTlsServer, and MockTcpProxy (a TCP relay
  that can drop connections, for reconnect tests) for integration testing.
- `build.gradle.kts`: Gradle Kotlin DSL build configuration.

## Validation

- Use `./gradlew build` for the complete local gate: compile with Error Prone,
  Spotless check, tests, JaCoCo reports and coverage gate (85% line, 70%
  branch), Javadoc, and the JAR contents check.
  Gradle must run on JDK 21.
- Run `./gradlew spotlessApply` to format `src/` with google-java-format.
- Run tests on another JDK with `./gradlew test -PtestJavaVersion=<8|11|17|21>`.
  The JDK must be installed locally, or listed in
  `-Porg.gradle.java.installations.paths=<paths>`.
- Run focused tests first when appropriate:
  `./gradlew test --tests "ClassName"` or
  `./gradlew test --tests "ClassName.methodName"`.
- Run all tests with `./gradlew test`.
- Use `./gradlew clean` before builds when investigating stale class issues.
- Before reporting completion, state which validation commands actually ran and
  whether any expected checks were not run.

## Repository contracts

- `README.md`, `build.gradle.kts`, tests, and CI are authoritative when this
  file becomes stale.
- Target Java 8 source and bytecode compatibility; CI runs the tests on JDK 8,
  11, 17, and 21. Do not use language or library APIs introduced after Java 8.
- Preserve the package structure `software.amazon.elasticache.integrations.*`.
- Ensure changed behavior has focused regression coverage; add or update tests
  when existing coverage does not already lock the behavior.
- Use `MockCache` and `MockTlsServer` for cache command/response integration
  tests, and `MockTcpProxy` to drop connections. A purpose-built socket is
  acceptable for transport-failure or handshake tests.

## Review bot

Review for concrete, actionable correctness, security, reliability, or
compatibility issues. Use the following behavioral invariants as contracts,
not as style preferences. If a change intentionally revises a contract,
require the corresponding tests, README, and build checks to change with it.

### High-value invariants

- **Public API surface:** flag changes to ElastiCacheIntegrationsClient.Builder,
  ElastiCacheIntegrationsClientConfiguration, or exception types that can break consumers or expose
  internals. Safe path: keep public API deliberate and update README/tests for intentional changes.
  The session, handles, interceptors, transports and ReconnectPolicy stay package-private; do not
  reintroduce a manual lifecycle.
- **SDK integration:** flag changes that overwrite caller-provided
  ClientOverrideConfiguration, break ExecutionInterceptor registration, or
  interfere with SDK retry/deserialization behavior. Safe path: copy the SDK
  builder's `overrideConfiguration()` with `toBuilder()` and add the interceptor
  to the copy. Set the transport with `httpClientBuilder(...)`, never
  `httpClient(...)`, so the SDK closes it with the client. Restore the builder's
  override configuration and clear the `httpClientBuilder` after every build,
  successful or not, so the application's builder can be installed into again. A
  builder that already has an `httpClient` fails with
  `ElastiCacheIntegrationsConfigError`.
- **Wire shape:** flag changes that can alter cache interoperability or corrupt
  bytes. Safe path: preserve binary-safe RESP framing and the exact
  `aws.request` five-argument order—method, URL, normalized headers, body,
  metadata—and the JSON-object `aws.config` contract.
- **Header normalization:** flag nondeterministic normalization or volatile
  authorization/date headers entering normalized wire headers. Safe path:
  lowercase, trim, sort, and exclude them; never log secrets.
- **RESP semantics:** flag changes that confuse incomplete with malformed
  frames, lose trailing/binary values, or remove response-size bounds. Safe
  path: preserve incremental parsing and bounded buffering.
- **Connection safety:** flag changes that can mis-correlate FIFO replies or
  leave pending requests unresolved after peer loss, protocol failure, or close.
  Safe path: reject use before connect, register before writing, and reset the
  whole connection for FIFO/send/write timeouts; deferred request-ID timeouts
  may fail only that request and drop a later push for the removed ID.
- **Session lifecycle:** flag changes that permit requests before
  `connect → HELLO 3 AUTH → aws.config`, use after close, or duplicate
  teardown. Safe path: preserve the one-way lifecycle, cleanup on failed open,
  and idempotent `close()`.
- **Authentication and transport:** flag divergence from the built SDK client's
  region and credentials provider (read from `serviceClientConfiguration()`),
  stale IAM tokens, reusable connections after HELLO/AUTH failure, disabled
  TLS verification, or plaintext by default. Safe path: resolve the SDK
  client's credentials again for each fresh 15-minute token, force-refresh the
  token after an AUTH rejection, periodically re-AUTH live connections, keep
  the last valid state after an explicit rejection, invalidate the stream after
  a timeout or malformed reply, confine tokens to the initial
  HELLO AUTH trailer and subsequent AUTH commands, and keep verified TLS with
  hostname checking as the default.
- **Client readiness:** flag returning a partially initialized client or masking
  an initialization error with cleanup failures. Safe path: open the session
  only after the SDK client is built (its region and credentials create the IAM
  token manager), return only after auth and session opening succeed, close the
  built client on any later failure, and attach a close failure as suppressed on
  the original error.
- **Close ordering:** flag a close that skips a step or leaks a caller-owned
  resource. Safe path: the transport's `close()` closes the session, then the
  IAM token manager, then the origin transport only when the client created it. A
  caller-supplied origin transport or executor stays open, and repeated
  `close()` is safe.

### Finding standard

- Report a finding only when the changed code has a concrete failure path; cite
  the file/line, explain impact and triggering scenario, and suggest the
  smallest safe fix.
- Prioritize data corruption, request misrouting, secret exposure, resource
  leaks, broken public compatibility, and lifecycle failures over minor issues.
- Do not report formatting or style issues. Do not raise speculative findings or
  subjective rewrites; report naming only when it changes a public or wire
  contract.
- Require focused regression coverage for changed behavior; use the existing
  MockCache and MockTlsServer for protocol/session tests.
- If no concrete issue is found, say so plainly rather than inventing a finding.

### Avoid implementation overfit

- Do not require a particular private class decomposition, collection type, or
  synchronization mechanism when the observable invariants remain intact.
- Do not treat timeout, read-size, or maximum-response values as immutable;
  bounded and safe behavior matters more than the current defaults.
- Do not flag exact error-message wording or documented out-of-scope features by
  themselves; intentional scope changes require README and focused tests.

### Scope gate

Failover to another cache endpoint remains out of scope. CEL predicate routing,
indefinite background reconnection with a bounded origin-fallback window,
periodic config refresh, and deferred responses delivered by `aws.response`
pushes are
supported; other push kinds are fatal protocol corruption (raised as
`ElastiCacheIntegrationsProtocolError` and wrapped as `ElastiCacheIntegrationsConnectionLost` by the reader
loop), matching the Node and Python clients: a client that does not
understand a push has no request to correlate it with, so the stream can no
longer be trusted. Do not reintroduce silent ignoring of unknown push kinds or
mistake this contract for a review finding; intentional scope changes require
README, tests, and an explicit contract decision. Normal AWS SDK
retry/deserialization behavior remains in scope.

One timeout, `connectionTimeoutMs` (default 5000), bounds startup and the
reconnect wait. Within each attempt it also bounds each step: the TCP connect
and TLS handshake together, `HELLO 3 AUTH`, and the first `aws.config`. The
periodic re-AUTH and `aws.config` refresh on a live connection use the request
timeout. `open()` keeps retrying the first connection with backoff until
it passes, caps each attempt by the time left, and sleeps no longer than the time
left. On failure it closes every connector it built. A connection failure is
thrown as `ElastiCacheIntegrationsConnectionError` ("opening a connection
timed out after Nms") with the last failure as its cause. Any other last failure
(`ElastiCacheIntegrationsAuthError`, `ElastiCacheIntegrationsOverridesRejected`,
`ElastiCacheIntegrationsConfigUnevaluable`) is rethrown unchanged. A session built
around one concrete connector makes a single attempt. When a live connection is
lost, one clock reading sets `readyDeadline = now + connectionTimeout` and
`fallbackDeadline = readyDeadline + fallbackDuration`, both with a saturating add.
A routed request with no ready connector starts the reconnect and waits until
`readyDeadline` for a connector, on the caller's thread for the sync transport and
on the executor thread for the async transport. It is served on the connector if one
arrives. Otherwise the request falls back to the origin while the fallback
window is open, including routed writes. `fallbackDurationSeconds == 0` never
allows fallback. `close()` wakes every waiting request. The background reconnect
has its own 5000 ms cap per attempt, not `connectionTimeoutMs`.

Periodic config refresh is in scope: each config `Refresher` tick sends
`aws.config`, using the zero-argument form once the session's overrides
have been acknowledged by the server (an unacknowledged override map, including
an explicit empty map, is resent), and applies the result. A RESP3 null reply
means the server's config is unchanged, so nothing is applied or rescheduled;
otherwise the reply is parsed, its predicates compiled, and both published
together as one immutable snapshot, with the refresher's interval updated to
match. A refresh failure (`ElastiCacheIntegrationsError`) that kills the connector starts the
normal reconnect loop, which obtains a fresh connection and config; periodic
ticks do nothing while reconnecting, and stale replies from replaced connectors
are discarded. Any other refresh failure is logged and leaves the current
config, predicates, and interval as last-known-good. Every config reply, null
reply, open, and reconnect restarts the staleness window. A refresh that fails
after `cachePolicyTimeoutSeconds` (default 3600) have passed without
confirmation drops the connector into the normal reconnect and origin-fallback
path. `ElastiCacheIntegrationsOverridesRejected` (`CONFIG_INVALID`) and `ElastiCacheIntegrationsConfigUnevaluable`
(a predicate that cannot be compiled, which rejects the whole config) are logged
at `SEVERE` on every occurrence.

## Security and test boundaries

- TLS with hostname verification is enabled by default. Use plaintext only for
  local or test endpoints.
- Never log or commit AWS credentials, IAM tokens, authorization headers, or
  other secret material.
- The Java IAM toolkit is the Maven Central dependency
  `software.amazon.elasticache:developer-toolkit-elasticache`. Keep token
  validation and signing in that library; this client owns only
  the adapter, TLS connection, and HELLO/AUTH lifecycle.
- Prefer hermetic tests and local mocks over live AWS or cache endpoints.
- `MockTlsServer` generates a self-signed `CN=localhost` certificate at test
  time with BouncyCastle (test scope only). It is a localhost-only fixture and
  must not be used for production.

## Generated and build files

- Do not commit generated `build/`, `.gradle/` contents, or IDE project files.
- Release publishing is configured by `release.gradle.kts` and enabled only
  with `-PreleasePublishing`, so normal builds do not load the publishing
  plugin. The tag-triggered workflow builds and smoke-tests
  artifacts with the tag-derived `releaseVersion` before publish-step-scoped
  credentials sign and publish them from the protected `release` environment.
  Keep the checked-in Gradle version at the local development baseline.
  Publication, propagation verification, and GitHub release creation are
  separate jobs. Manual workflow dispatches perform the full validation as a
  non-publishing dry run.

Keep changes small and reviewable.
