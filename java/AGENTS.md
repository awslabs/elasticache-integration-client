# AGENTS.md

## Scope

- This repository is a Java implementation of the Photon client for AWS SDK v2.
- `README.md` is the source for user-facing API behavior and feature scope.
- Keep this file focused on repository workflow, constraints, and non-obvious
  implementation context.

## Layout

- `src/main/java/software/amazon/photon/`: production Java modules organized by
  concern:
  - `connection/`: TCP/TLS socket management, FIFO/request-ID correlation.
  - `session/`: PhotonSession, PhotonClient (SDK integration), PhotonInterceptor.
  - `protocol/`: Photon wire protocol serialization.
  - `predicate/`: compiled CEL request-routing predicates.
  - `resp/`: RESP3 codec and value types.
  - `auth/`: Photon adapters for the sourced ElastiCache IAM toolkit.
  - `model/`: PhotonRequest, PhotonResponse, metadata types.
  - `exception/`: Photon error hierarchy.
- `src/test/java/software/amazon/photon/`: JUnit 5 tests organized by
  production concern.
- `src/test/.../mock/`: MockCache and MockTlsServer for integration testing.
- `build.gradle.kts`: Gradle Kotlin DSL build configuration.

## Validation

- Use `./gradlew build` for the complete local gate: compile with Error Prone,
  Spotless check, tests, JaCoCo reports, Javadoc, and the JAR contents check.
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
- Preserve the package structure `software.amazon.photon.*`.
- Ensure changed behavior has focused regression coverage; add or update tests
  when existing coverage does not already lock the behavior.
- Use `MockCache` and `MockTlsServer` for Photon command/response integration
  tests. A purpose-built socket is acceptable for transport-failure or
  handshake tests.

## Review bot

Review for concrete, actionable correctness, security, reliability, or
compatibility issues. Use the following behavioral invariants as contracts,
not as style preferences. If a change intentionally revises a contract,
require the corresponding tests, README, and build checks to change with it.

### High-value invariants

- **Public API surface:** flag changes to PhotonClient.Builder, PhotonSession,
  or exception types that can break consumers or expose internals. Safe path:
  keep public API deliberate and update README/tests for intentional changes.
- **SDK integration:** flag changes that overwrite caller-provided
  ClientOverrideConfiguration, break ExecutionInterceptor registration, or
  interfere with SDK retry/deserialization behavior. Safe path: merge
  interceptors with existing config using the SDK builder's getter.
- **Wire shape:** flag changes that can alter Photon interoperability or corrupt
  bytes. Safe path: preserve binary-safe RESP framing and the exact
  `photon.request` five-argument order—method, URL, normalized headers, body,
  metadata—and the JSON-object `photon.config` contract.
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
  `connect → authenticate → photon.config`, use after close, or duplicate
  teardown. Safe path: preserve the one-way lifecycle, cleanup on failed open,
  and idempotent `close()`.
- **Authentication and transport:** flag divergence from the SDK credential
  provider, stale IAM tokens, reusable connections after AUTH failure, disabled
  TLS verification, or plaintext by default. Safe path: force-refresh
  credentials for each fresh 15-minute token, periodically re-AUTH live
  connections, keep the last valid state after an explicit rejection, invalidate
  the stream after a timeout or malformed reply, confine tokens to AUTH, and
  keep verified TLS with hostname checking as the default.
- **Client readiness:** flag returning a partially initialized client or masking
  an initialization error with cleanup failures. Safe path: return only after
  auth, session opening, and interceptor installation succeed, and clean up
  resources already owned by the factory.

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

Failover to another Photon endpoint remains out of scope. CEL predicate routing,
indefinite background reconnection with a bounded origin-fallback window,
periodic config refresh, and deferred responses delivered by `photon.response`
pushes are
supported; other push kinds are fatal protocol corruption (raised as
`PhotonProtocolError` and wrapped as `PhotonConnectionLost` by the reader
loop), matching the Node and Python clients: a client that does not
understand a push has no request to correlate it with, so the stream can no
longer be trusted. Do not reintroduce silent ignoring of unknown push kinds or
mistake this contract for a review finding; intentional scope changes require
README, tests, and an explicit contract decision. Normal AWS SDK
retry/deserialization behavior remains in scope.

Periodic config refresh is in scope: each config `Refresher` tick sends
`photon.config`, using the zero-argument form once the session's overrides
have been acknowledged by the server (an unacknowledged override map, including
an explicit empty map, is resent), and applies the result. A RESP3 null reply
means the server's config is unchanged, so nothing is applied or rescheduled;
otherwise the reply is parsed, its predicates compiled, and both published
together as one immutable snapshot, with the refresher's interval updated to
match. A refresh failure (`PhotonError`) that kills the connector starts the
normal reconnect loop, which obtains a fresh connection and config; periodic
ticks do nothing while reconnecting, and stale replies from replaced connectors
are discarded. Any other refresh failure is logged and leaves the current
config, predicates, and interval as last-known-good. Every config reply, null
reply, open, and reconnect restarts the staleness window. A refresh that fails
after `stalenessDurationSeconds` (default 3600) have passed without
confirmation drops the connector into the normal reconnect and origin-fallback
path. `PhotonOverridesRejected` (`CONFIG_INVALID`) and `PhotonConfigUnevaluable` 
(a predicate that cannot be compiled, which rejects the whole config) are logged 
at `SEVERE` on every occurrence.

## Security and test boundaries

- TLS with hostname verification is enabled by default. Use plaintext only for
  local or test endpoints.
- Never log or commit AWS credentials, IAM tokens, authorization headers, or
  other secret material.
- The Java IAM toolkit is pinned under `vendor/` and built by Gradle through
  Maven. Keep token validation and signing in that library; Photon owns only
  the adapter, TLS connection, and AUTH lifecycle.
- Prefer hermetic tests and local mocks over live AWS or Photon endpoints.
- `MockTlsServer` generates a self-signed `CN=localhost` certificate at test
  time with BouncyCastle (test scope only). It is a localhost-only fixture and
  must not be used for production.

## Generated and build files

- Do not commit generated `build/`, `.gradle/` contents, or IDE project files.
- This prototype has no publish automation; do not add publishing behavior
  unless explicitly requested.

Keep changes small and reviewable.
