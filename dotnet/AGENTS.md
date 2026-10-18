# AGENTS.md

## Scope

- This directory is the C# implementation of the Photon client for the AWS SDK
  for .NET v4 (`AWSSDK.DynamoDBv2`), targeting `net8.0`.
- `README.md` is the source for user-facing API behavior and feature scope.
- Keep this file focused on repository workflow, constraints, and non-obvious
  implementation context.

## Layout

- `Amazon.Photon.sln`: the solution.
- `src/Amazon.Photon/`: the `Amazon.Photon` package, one file per concern.
- `test/Amazon.Photon.Tests/`: xUnit v3 tests organized by production concern.
- `bench/Amazon.Photon.Benchmarks/`: a Stopwatch benchmark of the routing
  predicates. It builds with the solution and never runs in `dotnet test`.
- `samples/Amazon.Photon.LiveValidation/`: a console app that checks the
  client against a real cache. It builds with the solution and never runs in
  `dotnet test`: it needs a cache and AWS credentials.
- `scripts/pack-smoke.sh`: packs the library, checks the package, installs it
  into a throwaway console app (`scripts/pack-smoke/`, copied to a temporary
  directory, never built in place) and runs it against `CacheServer` through
  `PackSmokeTests`. That test is skipped unless the script runs it.
- `Directory.Build.props`: shared compiler settings (nullable, C# 12, warnings
  as errors, code style in build, deterministic builds, locked restore in CI).
- `Directory.Packages.props`: every package version. Library dependencies are
  minimum versions, because they are written into the package; test-only
  packages are exact. The lock files fix what every restore resolves.
- `global.json`: pins the .NET SDK 8.0.4xx band, 8.0.426 or a later patch.
- `.github/workflows/dotnet-ci.yml` (repository root): CI validation. The
  `verify` job runs the full gate and the pack smoke test on the .NET 8 SDK.
  The `net10-runtime` job runs the same `net8.0` test build on an image that
  has only the .NET 10 runtime, with `DOTNET_ROLL_FORWARD=Major`.

## Validation

Run from this directory:

- `dotnet restore`, then `dotnet format --verify-no-changes`.
- `dotnet build -warnaserror` and `dotnet test` for the complete local gate.
- Run focused tests first when appropriate:
  `dotnet test --filter "FullyQualifiedName~<ClassName>"`.
- For changes to the predicates, compare
  `dotnet run -c Release --project bench/Amazon.Photon.Benchmarks` before and
  after. Times vary with host load; compare runs on the same host.
- `scripts/pack-smoke.sh` for changes to package metadata, the public API or
  published contents. Pass `--no-build` to reuse a Release build.
- To check the .NET 10 runtime locally, run the Release test build in the
  `mcr.microsoft.com/dotnet/runtime:10.0` image with `DOTNET_ROLL_FORWARD=Major`
  (`dotnet Amazon.Photon.Tests.dll` from `test/Amazon.Photon.Tests/bin/Release/net8.0`).
- Before reporting completion, state which validation commands actually ran and
  whether any expected checks were not run.

## Repository contracts

- `README.md`, the project files, tests, and CI are authoritative when this
  file becomes stale.
- Add packages only through `Directory.Packages.props` (minimum version for
  library dependencies, exact `[x.y.z]` for test-only packages), and commit the
  updated `packages.lock.json` files.
- NuGet audit warnings are errors: a new advisory for a pinned package fails
  restore until the pin is raised.
- Every async API takes a `CancellationToken`. No sync-over-async. Library code
  uses `ConfigureAwait(false)`.
- Public types are deliberate and documented; internals are visible only to
  `Amazon.Photon.Tests` and `Amazon.Photon.Benchmarks`.
- Ensure changed behavior has focused regression coverage; add or update tests
  when existing coverage does not already lock the behavior.
- A change to an option, a default, an exception or a log event updates the
  matching table in `README.md` in the same change.
- Package metadata lives in `src/Amazon.Photon/Amazon.Photon.csproj`. The
  version is `0.0.1`, as for the other clients; `scripts/pack-smoke.sh` checks
  it. Source Link comes from the .NET SDK; the pack smoke test checks it only
  when `origin` is GitHub.

## Review bot

Review for concrete, actionable correctness, security, reliability, or
compatibility issues. Report a finding only when the changed code has a
concrete failure path; cite the file/line, explain impact and triggering
scenario, and suggest the smallest safe fix. Do not report formatting issues
covered by `dotnet format`. If no concrete issue is found, say so plainly.

## Security and test boundaries

- TLS with certificate and host name verification is the default. Plaintext
  and unverified TLS are explicit, opt-in local/test behavior.
- Never log or commit AWS credentials, IAM tokens, authorization headers, or
  other secret material.
- Prefer hermetic tests and local mocks over live AWS or Photon endpoints.
- Every test runs under `SdkEnvironment`: the SDK reads an empty config and
  credentials file instead of `~/.aws`, sees no `AWS_*` region, profile or
  endpoint variables, and has instance metadata off.
  Keep it; without it the SDK reads the host's files. A child process started
  by a test inherits it.
- Reuse `CacheServer` (many connections, checks commands like the proxy) for
  end-to-end tests, `MockCache` (scripted, one connection) for exact wire
  exchanges, and `SessionRig` with `FakeTimeProvider` for session timing.

## Generated and package files

- Do not commit `bin/`, `obj/`, `artifacts/`, `TestResults/`, `*.nupkg`,
  `*.snupkg`, or IDE files.
- This prototype has no publish automation; do not add publishing behavior
  unless explicitly requested.

Keep changes small and reviewable.
