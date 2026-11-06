# AGENTS.md

## Scope

- This repository is a TypeScript implementation of the ElastiCacheIntegrations Node.js client.
- `README.md` is the source for user-facing API behavior and feature scope.
- Keep this file focused on repository workflow, constraints, and non-obvious
  implementation context.

## Layout

- `src/`: production TypeScript modules.
- `test/`: Vitest tests organized by production concern.
- `test/mock-cache.ts`: reusable local plaintext/TLS ElastiCacheIntegrations mock.
- `scripts/`: build, cleanup, and package smoke-test tooling.
- `src/index.ts`: public package export boundary.
- `src/predicates.ts`: compiled CEL request-routing predicates.
- `.github/workflows/node-ci.yml`: CI validation on Node.js 22, 24, and 26.

## Validation

- Use `npm ci` for a clean, CI-like install. Use `npm install` only when
  intentionally changing dependencies.
- Run focused tests first when appropriate:
  `npx vitest run test/<file>.test.ts`.
- Run static checks with:
  `npm run format:check && npm run lint && npm run typecheck`.
- Run the complete local gate with `npm run verify`.
- Run `npm run pack:smoke` for changes to package metadata, exports, builds, or
  published contents.
- Prefer targeted formatting such as `npx prettier --write <paths>` over the
  broad `npm run format` command.
- Before reporting completion, state which validation commands actually ran and
  whether any expected checks were not run.

CI runs formatting, linting, typechecking, `npm test`, and package smoke checks
as separate steps. `npm run verify` is the equivalent aggregate local gate.

## Repository contracts

- `README.md`, `package.json`, configs, tests, and CI are authoritative when
  this file becomes stale.
- Preserve NodeNext `.js` import specifiers in TypeScript source.
- Keep public exports centralized in `src/index.ts`; do not expose internal
  session or middleware helpers accidentally.
- Preserve ESM, CommonJS, and generated declaration outputs.
- Keep `scripts/package-consumer-smoke.mjs` synchronized with the public export
  surface. Add runtime exports to its consumer checks, and cover new type-only
  exports through the package type validation.
- Ensure changed behavior has focused regression coverage; add or update tests
  when existing coverage does not already lock the behavior.
- Use `test/mock-cache.ts` for ElastiCacheIntegrations command/response integration tests. A
  purpose-built socket is acceptable for transport-failure or handshake tests.

## Review bot

Review for concrete, actionable correctness, security, reliability, or
compatibility issues. Use the following behavioral invariants as contracts,
not as style preferences. If a change intentionally revises a contract,
require the corresponding tests, README, and package checks to change with it.

### High-value invariants

- **Package surface:** flag root export, error-hierarchy/cause, NodeNext
  specifier, ESM/CommonJS/declaration, or package-content changes that can
  break consumers or expose internals. Safe path: keep `src/index.ts`
  deliberate and update README/tests plus `npm run pack:smoke` for intentional
  changes.
- **Wire shape:** flag changes that can alter ElastiCacheIntegrations interoperability or
  corrupt bytes. Safe path: preserve binary-safe RESP framing and the exact
  `aws.request` five-argument order—method, URL, normalized headers, body,
  metadata—and the JSON-object `aws.config` contract.
- **Header separation:** flag nondeterministic normalization or volatile
  authorization/date/request headers entering normalized wire headers. Safe
  path: lowercase, trim, sort, and exclude them while preserving the final
  serialized unsigned request for middleware; never log secrets.
- **RESP semantics:** flag changes that confuse incomplete with malformed
  frames, lose trailing/binary values, lose unsafe-integer precision, or remove
  response-size bounds. Safe path: preserve incremental parsing and bounded
  buffering.
- **Connection safety:** flag changes that can mis-correlate FIFO replies or
  leave pending requests unresolved after peer loss, protocol failure, close,
  or destroy, or that resurrect a closed connector or allow concurrent
  connects. Safe path: reject use before connect, register before writing,
  enforce the response-size bound, and reset the whole connection on timeout
  so late replies cannot match later requests.
- **Session lifecycle and recovery:** flag changes that permit requests before
  `connect → authenticate → aws.config`, use after close, or duplicate
  teardown, create parallel recovery flights, reuse stale AUTH credentials, or
  publish a connector with stale predicates. Safe path: preserve the one-way
  lifecycle, cleanup on failed open, one continuous background reconnect loop
  with fresh AUTH and config, a per-outage reconnect wait of
  `connectionTimeout` followed by origin fallback while disconnected, startup
  that retries until `connectionTimeout` passes, and idempotent
  `close()`/`destroy()` that wake waiting requests.
- **Middleware path:** flag changes that use a non-final serialized request,
  bypass normal SDK deserialization/retry behavior, consume a body that is not
  bytes-like, or destroy the SDK before ElastiCacheIntegrations. Safe path: preserve final
  serialized unsigned request ordering, synthetic responses, bodies that are
  not bytes-like, such as streams, passed through to the origin without
  calling ElastiCacheIntegrations, dropped-connection fallback
  only for cataloged operations, healthy-connection `ORIGIN_ERROR` retries
  only for cataloged operations, other RESP/non-idempotent/validation/shutdown
  failures propagating, and ElastiCacheIntegrations-first teardown.
- **Authentication and transport:** flag divergence from the SDK credential
  provider, stale IAM tokens, reusable connections after initial AUTH failure,
  TLS verification that is off by default or off implicitly, or plaintext by
  default. Safe path: read credentials from the SDK client's credential provider
  with no options for each fresh scheme-free 15-minute token, so its cached
  identity is reused, and never pass `forceRefresh`. Periodically re-AUTH live
  connections, keep the last valid
  state after an explicit refresh rejection, invalidate the stream after a
  timeout or malformed reply, confine tokens to AUTH, and keep verified TLS as
  the default; unverified TLS (`verify: false`) is explicit, opt-in
  local/test behavior. Plaintext (`tls: false`) and a caller-supplied
  `authProvider` are internal connector options that `install()` does not
  expose; flag a change that makes them public.
- **Client readiness:** flag resolving `install()` on a partially initialized
  client or masking an initialization error with cleanup failures. Safe path:
  resolve only after auth, session opening, and middleware installation succeed, and
  clean up only what `install()` opened. The caller owns the SDK client, so a
  failed install must not destroy it.

### Finding standard

- Report a finding only when the changed code has a concrete failure path;
  cite the file/line, explain impact and triggering scenario, and suggest the
  smallest safe fix.
- Prioritize data corruption, request misrouting, secret exposure, resource
  leaks, broken public compatibility, and lifecycle failures over minor issues.
- Do not report formatting, lint, or type-check issues already covered by
  automation. Do not raise speculative findings or subjective rewrites;
  report naming only when it changes a public or wire contract.
- Require focused regression coverage for changed behavior; use the existing
  local mock and protocol/session tests.
- If no concrete issue is found, say so plainly rather than inventing a
  finding.

### Avoid implementation overfit

- Do not require a particular private class/module decomposition, queue type,
  or locking mechanism when the observable invariants remain intact.
- Do not treat timeout, read-size, or maximum-response values as immutable;
  bounded and safe behavior matters more than the current defaults.
- Do not flag exact error-message wording or documented out-of-scope features
  by themselves; intentional scope changes require README and focused tests.

### Scope and review boundaries

Review changes against the current contract in `README.md`, linked
requirements, and the PR description. Use the PR description to understand the
intended scope of the change, including whether it intentionally introduces a
previously unsupported capability.

Do not report the absence of behavior that the current contract and change
requirements do not require. Intentional scope expansions must include an
explicit contract decision, README updates, and focused regression coverage.
The PR description does not replace those updates.

If the PR description, README, and implementation disagree, report the
contract mismatch or ambiguity rather than inferring intent.

Normal AWS SDK retry and deserialization behavior remains in scope.

## Security and test boundaries

- TLS and certificate verification are enabled by default. `verify: false`
  (TLS without certificate verification) is for local or test endpoints only.
  Tests that go through `install()` use the TLS mock cache with the localhost
  fixture as `caCerts`; plaintext (`tls: false`) is reachable only by
  constructing the connector directly in tests.
- Never log or commit AWS credentials, IAM tokens, authorization headers, or
  other secret material. Wrapped requests are fully serialized but not
  origin-signed; the cache owns origin signing. ElastiCache IAM `elasticache:connect`
  token signing remains separate and is confined to `AUTH`.
- Prefer hermetic tests and local mocks over live AWS or ElastiCacheIntegrations endpoints.
- The checked-in TLS certificate and key under `test/fixtures/tls/` are
  localhost-only test fixtures and must not be used for production.

## Generated and package files

- Do not commit generated `dist/`, `coverage/`, `.artifacts/`, or `.omx/`
  contents.
- `npm run pack:smoke` verifies the tarball contains both ESM and CommonJS
  entrypoints and excludes source, tests, `node_modules`, cache artifacts, and
  private keys. It also runs publint and `@arethetypeswrong/cli` against the tarball.
- `.github/workflows/node-release.yml` validates annotated release tags, builds
  one npm tarball, and publishes that exact artifact through the protected
  `release` environment. The tag supplies the published version; keep checked-in
  package and runtime versions at the local development baseline and let
  `scripts/set-package-version.mjs` stamp the release checkout. Manual
  dispatches are non-publishing dry runs. Keep npm publication tokenless through
  trusted publishing after the package has been bootstrapped. Do not force npm
  provenance while the source repository is private; trusted publishing adds it
  automatically once the repository is public.

Keep changes small and reviewable.
