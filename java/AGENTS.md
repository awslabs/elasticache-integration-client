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
  - `auth/`: IAM authentication token generation.
  - `model/`: PhotonRequest, PhotonResponse, metadata types.
  - `exception/`: Photon error hierarchy.
- `src/test/java/software/amazon/photon/`: JUnit 5 tests organized by
  production concern.
- `src/test/.../mock/`: MockCache and MockTlsServer for integration testing.
- `build.gradle.kts`: Gradle Kotlin DSL build configuration.

## Validation

- Use `./gradlew build` for the complete local gate (compile, test, check).
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
- Target Java 8 source and bytecode compatibility; CI also validates JDK 11 and
  JDK 17. Do not use language or library APIs introduced after Java 8.
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
  credentials for each fresh 15-minute token, confine tokens to AUTH, and keep
  verified TLS with hostname checking as the default.
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

Reconnection, circuit breaking, periodic config refresh, and out-of-band
responses are currently out of scope.
Do not introduce them silently or mistake their absence for a review finding;
intentional scope changes require README, tests, and an explicit contract
decision. Normal AWS SDK retry/deserialization behavior remains in scope.

## Security and test boundaries

- TLS with hostname verification is enabled by default. Use plaintext only for
  local or test endpoints.
- Never log or commit AWS credentials, IAM tokens, authorization headers, or
  other secret material.
- Prefer hermetic tests and local mocks over live AWS or Photon endpoints.
- Test TLS certificates under `src/test/` are localhost-only fixtures and must
  not be used for production.

## Generated and build files

- Do not commit generated `build/`, `.gradle/` contents, or IDE project files.
- This prototype has no publish automation; do not add publishing behavior
  unless explicitly requested.

Keep changes small and reviewable.
