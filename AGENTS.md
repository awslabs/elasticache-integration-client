# AGENTS.md

## Scope

- This repository contains language-specific ElastiCache Integrations client implementations.
- Keep changes within the relevant client directory unless the change affects
  shared documentation or CI.
- Read the package-specific `AGENTS.md` and `README.md` before editing code in
  `node/` or `java/`.

## Repository map

- `node/`: TypeScript/Node.js ElastiCache Integrations client.
- `java/`: Java/AWS SDK v2 ElastiCache Integrations client.
- `python/`: Python/boto3 ElastiCache Integrations client.
- `shared/`: language-neutral client contracts. `idempotent-operations.json`
  is packaged into each client. `protocol-vectors.json` and
  `sdk-request-cases.json` are test fixtures that every client's tests run and
  that are not packaged. A change to the wire format or to how an SDK adapter
  builds a request updates the fixtures in the same change. The tests that run
  them are tagged `alignment` (a pytest marker, a JUnit tag, and the two Vitest
  files). Pull request CI runs them in `.github/workflows/alignment-ci.yml` and
  excludes them from the client CI workflows. The release workflows still run
  them before publishing.
- `README.md`: repository-wide overview and client scope.
- `.github/workflows/`: CI contracts for each client.

## Source of truth

- README files define user-facing behavior and supported feature scope.
- Build files, lockfiles, tests, and CI define executable toolchain and
  validation behavior.
- If this file becomes stale, follow the repository artifacts above and update
  the affected guidance as part of the same change.

## Validation

Run commands from the client directory they apply to:

- Node: use `npm ci` for clean installs, focused Vitest tests when appropriate,
  and `npm run verify` for the complete local gate. Use `npm run pack:smoke`
  when changing package metadata, exports, builds, or published contents.
- Java: use focused Gradle tests when appropriate and `./gradlew build` for the
  complete local gate. Gradle must run on JDK 21. Use `./gradlew spotlessApply`
  to fix formatting.
- Python: use focused pytest tests when appropriate and `python -m pytest` for
  the complete local gate.
- Repository: run `scripts/check-denylist.sh` from any directory. It fails when
  a file that git tracks, or an untracked file that `.gitignore` doesn't
  exclude, uses an internal term listed in `scripts/denylist.txt`. CI runs it on
  every pull request.
- Before reporting completion, state which validation commands actually ran and
  which expected checks were not run.

## Shared engineering rules

- Preserve public APIs, wire/protocol behavior, connection and session
  lifecycles, and security defaults unless the change explicitly revises the
  contract.
- Add or update focused regression coverage for changed behavior.
- Prefer hermetic tests and local mocks over live AWS or cache endpoints.
- Never log or commit credentials, IAM tokens, authorization headers, or other
  secret material.
- Do not commit generated build, coverage, distribution, cache, or IDE files.
- Keep changes small, reviewable, and isolated to the intended client.
- Timeouts are whole milliseconds: an `int` in Python and Java, an integer
  `number` in Node, both in configuration and internally. Convert to seconds
  only at the call that needs them, such as `time.monotonic()`,
  `Future.result()` or a socket API. Longer periods — fallback windows,
  cache-policy lifetimes, token refresh delays — stay in seconds.

## Completion checklist

- Relevant package guidance, README, tests, and build configuration agree.
- Changed behavior has focused regression coverage.
- Appropriate formatting, static checks, tests, and packaging checks have run.
- Any validation gap or intentional contract change is called out explicitly.
