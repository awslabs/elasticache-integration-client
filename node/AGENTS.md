# AGENTS.md

## Scope

- This repository is a TypeScript implementation of the Photon Node.js client.
- `README.md` is the source for user-facing API behavior and feature scope.
- Keep this file focused on repository workflow, constraints, and non-obvious
  implementation context.

## Layout

- `src/`: production TypeScript modules.
- `test/`: Vitest tests organized by production concern.
- `test/mock-cache.ts`: reusable local plaintext/TLS Photon mock.
- `scripts/`: build, cleanup, and package smoke-test tooling.
- `src/index.ts`: public package export boundary.
- `.github/workflows/node-ci.yml`: CI validation on Node.js 22 and 24.

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
- Ensure changed behavior has focused regression coverage; add or update tests
  when existing coverage does not already lock the behavior.
- Use `test/mock-cache.ts` for Photon command/response integration tests. A
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
- **Wire shape:** flag changes that can alter Photon interoperability or
  corrupt bytes. Safe path: preserve binary-safe RESP framing and the exact
  `photon.request` five-argument order—method, URL, normalized headers, body,
  metadata—and the JSON-object `photon.config` contract.
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
- **Session lifecycle:** flag changes that permit requests before
  `connect → authenticate → photon.config`, use after close, or duplicate
  teardown. Safe path: preserve the one-way lifecycle, cleanup on failed open,
  and idempotent `close()`/`destroy()`.
- **Middleware path:** flag changes that use a non-final serialized request,
  call the origin handler, bypass normal SDK deserialization/retry behavior,
  silently add unsupported streaming-body behavior, or destroy the SDK before
  Photon. Safe path: preserve final serialized unsigned request ordering,
  synthetic responses, explicit streaming-body rejection, and Photon-first
  teardown.
- **Authentication and transport:** flag divergence from the SDK credential
  provider, stale IAM tokens, reusable connections after AUTH failure, disabled
  TLS verification, or plaintext by default. Safe path: force-refresh
  credentials for each fresh scheme-free 15-minute token, confine tokens to
  AUTH, and keep verified TLS as the default; plaintext is explicit local/test
  behavior.
- **Client readiness:** flag returning a partially initialized client or
  masking an initialization error with cleanup failures. Safe path: return
  only after auth, session opening, and middleware installation succeed, and
  clean up resources already owned by the factory.

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

- TLS and certificate verification are enabled by default. Use `tls: false`
  only for local or test endpoints.
- Never log or commit AWS credentials, IAM tokens, authorization headers, or
  other secret material. Wrapped requests are fully serialized but not
  origin-signed; Roxy owns origin signing. ElastiCache IAM `elasticache:connect`
  token signing remains separate and is confined to `AUTH`.
- Prefer hermetic tests and local mocks over live AWS or Photon endpoints.
- The checked-in TLS certificate and key under `test/fixtures/tls/` are
  localhost-only test fixtures and must not be used for production.

## Generated and package files

- Do not commit generated `dist/`, `coverage/`, `.artifacts/`, or `.omx/`
  contents.
- `npm run pack:smoke` verifies the tarball contains both ESM and CommonJS
  entrypoints and excludes source, tests, cache artifacts, and private keys.
- This prototype has no publish automation; do not add publishing behavior
  unless explicitly requested.

Keep changes small and reviewable.
