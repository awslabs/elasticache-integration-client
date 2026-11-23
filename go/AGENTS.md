# AGENTS.md

## Scope

- This directory is the Go implementation of the ElastiCache Integrations client for the AWS SDK
  for Go v2 (`github.com/aws/aws-sdk-go-v2/service/dynamodb`).
- `README.md` is the source for user-facing API behavior and feature scope. `DEVELOPER.md`
  covers building, testing, the live example and releasing.
- Keep this file focused on repository workflow, constraints, and non-obvious
  implementation context.

## Layout

- `go.mod`: module `github.com/awslabs/elasticache-integration-client/go`, `go 1.24.0`, no
  `toolchain` line. Direct runtime dependencies are the AWS SDK core, `service/dynamodb`,
  `smithy-go` and `cel-go`, and nothing else.
- `elasticacheintegrations/`: the one public package, one file per concern, named after the
  other clients' files: `protocol.go`, `transport.go`,
  `connection.go`, `pending_requests.go`, `reply_reader.go`, `session.go`,
  `periodic_runner.go`, `fallback_policy.go` (the request outcome table and the retry
  decision), `auth.go`, `predicates.go`, `predicate_body.go`, `predicate_fastpath.go`,
  `middleware.go` (the `aws.HTTPClient` wrapper and the request and response adapters),
  `client.go` (`NewFromConfig`, `New`, `Close`), `errors.go`, `models.go`, `options.go`,
  `options_validator.go`, `logging.go`. Unit tests sit next to the code, in the same package.
- `internal/resp/`: the RESP3 codec and the buffered frame reader. No dependency on the rest.
- `internal/mockcache/`: the in-process mock cache (TLS and plaintext, RESP3, deferred
  replies, hangs, drops, error codes) and a TCP relay that refuses, swallows or resets
  connections, for tests.
- `e2e/`: the end-to-end suite. It builds a real `dynamodb.Client` with `NewFromConfig`
  against the mock cache over TLS, with a fake DynamoDB behind it, and runs in
  `go test ./...` like the unit tests.
- `internal/testutil/`: test helpers: static credentials, `AWS_*` isolation, the shared
  fixture reader, a log capture, the no-secrets assertion.
- `examples/`: its own module (`replace` to `../`) with the live examples `local-e2e` and
  `live-validation`. They need a cache and AWS credentials, so they never run against one in the
  tests; the gate vets them and runs their hermetic unit tests.
- `scripts/verify.sh`: the complete local gate; CI runs it.
- `.github/workflows/go-ci.yml` (repository root): called by `ci.yml` on every push to `main`
  and when a pull request touches `go/`, `shared/`, `ci.yml`, `alignment-ci.yml` or a `go-ci`
  workflow. The `verify` job runs `scripts/verify.sh --skip-alignment` on Go 1.24 (the floor),
  `oldstable` and `stable`; the `vulncheck` job runs `govulncheck` on both modules.
  `alignment-ci.yml` runs the alignment tests; `compat.yml` (`go-latest`) runs the gate weekly
  with every dependency at its newest release.

## Validation

Run from this directory:

- `scripts/verify.sh` for the complete local gate (see `DEVELOPER.md` for each step).
- Run focused tests first when appropriate: `go test -race -run 'TestName' ./elasticacheintegrations`.
- The `shared/` fixtures run as the `TestAlignment*` tests; run them alone with
  `go test -run '^TestAlignment' ./...`. An SDK header difference from the fixtures is listed in
  the test with its reason, and the list fails when an entry goes stale.
- Fuzz a parser you change for a few minutes (`go test -run '^$' -fuzz '^FuzzX$' -fuzztime 5m`),
  and compare the benchmarks before and after a hot-path change.
- Before reporting completion, state which validation commands actually ran and
  whether any expected checks were not run.

## Repository contracts

- `README.md`, `go.mod`, tests, and CI are authoritative when this file becomes stale.
- The `go` directive stays at the floor the AWS SDK needs. Do not use a language feature or
  standard library API newer than it outside files with a matching `//go:build` line; the Go
  1.24 CI leg catches it.
- Do not add a runtime dependency, and add no module only the tests use (test helpers go in
  `internal/testutil`).
- The `require` lines are the minimums every consumer builds with. Raise one only when the
  client needs a newer release or for a security fix; Dependabot opens only security updates
  for `go/`. Tools such as staticcheck and govulncheck run with `go run` at a pinned
  version, never through `go.mod`.
- Ensure changed behavior has focused regression coverage; add or update tests
  when existing coverage does not already lock the behavior. The gate fails below 90%
  statement coverage.
- A change to an option, a default, an error type, a retry decision or a log event updates
  the matching table in `README.md` in the same change.
- Every `.go` file starts with the license header, above any `//go:build` line.

## Review bot

Review for concrete, actionable correctness, security, reliability, or
compatibility issues. Use the following behavioral invariants as contracts,
not as style preferences. If a change intentionally revises a contract,
require the corresponding tests, README, and build checks to change with it.

### High-value invariants

- **Public API surface:** `NewFromConfig`, `New`, `Client.Close`, `Options`, the error types
  and `ErrIntegrations`. Everything else stays unexported. Every error the package creates matches
  `ErrIntegrations` with `errors.Is`; a caller's `ctx` error is returned as is.
- **SDK integration:** the client is installed as `dynamodb.Options.HTTPClient`, wrapping the
  HTTP client the SDK would have used, after every caller option function. A served response
  carries canonical header keys and `Age: 0` (so the SDK learns no clock skew from a replayed
  `Date`), and the `X-Amz-Crc32` the DynamoDB client checks. A request whose signing key id or
  region is not the client's, whose body length is unknown or over 16 MiB, or that is
  unsigned goes to the origin untouched.
- **Retry decision:** errors reach the SDK with `RetryableError() bool` from
  `fallback_policy.go`. A `*RejectedError` is never retryable and never wrapped inside a
  retryable error. A `*ProtocolError` is not retryable when the connection stays open; a
  stream that cannot be decoded fails with a retryable `*ConnectionLostError` that wraps it. In strict mode (`FallbackDuration` 0) a routed request
  never goes to DynamoDB, also at and after `Close`.
- **Wire shape:** `HELLO 3 AUTH`, `aws.config` and `aws.request` with method, URL, normalized
  headers, body and metadata, in that order; `aws.response` pushes. The shared fixtures pin
  the bytes.
- **Connection safety:** register before writing; one frame per write under the write turn; a
  failed write, an in-order reply past its deadline, a corrupt stream or an unknown push closes
  the connection and fails every pending request; a deferred request times out alone. At most
  100 pending requests; a waiter that gives up never loses a slot.
- **Lifecycle:** every goroutine is owned by the session and stopped by `Close`, which is
  idempotent and never waits on the cache. No package-level mutable state except the
  once-per-process insecure-TLS warning.
- **Authentication and transport:** credentials come from the built DynamoDB client's
  provider for every token; a rejected AUTH forces a new token; TLS 1.2 or later with
  certificate and host name checks by default. Tokens, signatures, credentials and bodies never
  reach a log record or an error string; the no-secrets test covers this.

### Finding standard

- Report a finding only when the changed code has a concrete failure path; cite
  the file/line, explain impact and triggering scenario, and suggest the
  smallest safe fix.
- Prioritize wrong DynamoDB answers, request misrouting, secret exposure, goroutine or
  connection leaks, data races, broken public compatibility, and lifecycle failures over minor
  issues.
- Do not report formatting covered by `gofmt` or findings staticcheck already reports.
- If no concrete issue is found, say so plainly rather than inventing a finding.

## Security and test boundaries

- TLS with certificate and host name verification is the default. Plaintext exists only as an
  unexported test field.
- Never log or commit AWS credentials, IAM tokens, authorization headers, or
  other secret material.
- Tests are hermetic: no network beyond loopback, no AWS config files, `AWS_*` variables
  cleared (`testutil.ClearAWSEnv`). Use `internal/mockcache` for cache exchanges, its relay for outages, and
  `testing/synctest` with `net.Pipe` for timing; do not add a clock interface to production
  code.

## Generated files

- Do not commit test binaries, coverage profiles, `go.work` files, or IDE files
  (`go/.gitignore`).
- There is no release workflow. A release is a `go/vX.Y.Z` tag on a commit whose CI is green
  (`DEVELOPER.md`). Do not tag without explicit owner approval.

Keep changes small and reviewable.
