# Developing the ElastiCache Integrations Python client

## Building and testing

Run everything from `python/`. The runtime requires Python 3.10 or later.

```bash
pip install -e '.[test]'   # runtime dependencies plus pytest and the TLS test fixtures
python -m pytest           # the complete test gate, with coverage
pip install -e '.[dev]'    # pinned ruff, mypy, build and twine
ruff format --check . && ruff check . && mypy
```

- `python -m pytest` runs the whole suite and fails if combined line and branch coverage drops below `fail_under` in `pyproject.toml`. Pass `--no-cov` when running individual files.
- `ruff format .` fixes formatting.
- [`tests/README.md`](tests/README.md) describes the test layout and how to run the unit and end-to-end tests separately.

CI runs the tests on Python 3.10, 3.12 and 3.14, and the lint, type check and package build on 3.12. Two further suites run in CI and can be reproduced locally:

- The compatibility matrix, which runs the tests on every Python version from 3.10 to 3.14, against the minimum supported boto3, and from a clean wheel install.
- The cross-client alignment tests, which run the fixtures in `shared/` that every client must agree on. Run them with `python -m pytest -m alignment --no-cov`. The client CI workflow excludes them with `-m "not alignment"`.

## Internals

The transport is a pure-Python RESP3 connector, `ElastiCacheIntegrationsConnector`, over TLS, with no Glide or other native dependency. Every read and write on a connection runs on one I/O thread with its own asyncio event loop. Each connection authenticates with `HELLO 3 AUTH` and then exchanges `aws.config` before it serves requests.

Replies are correlated in FIFO order. The cache may instead reply with a request ID and deliver the result later in an `aws.response` RESP3 push, so a request can complete out of order. A deferred request does not block later FIFO replies. If a deferred request times out, only that request is abandoned, and a late push for its ID is dropped. A timeout while a request is still FIFO-correlated closes the connection, to prevent replies from reaching the wrong caller. A RESP3 push of an unrecognized kind is treated as protocol corruption and closes the connection.

One connection sends at most 100 requests awaiting replies at once. Further callers wait for a slot, in arrival order, within their `cache_request_timeout` instead of failing at once. Admission uses at most 75% of that timeout, keeping 25% to send the request and receive the reply. A request whose admission budget runs out is not sent to the cache and goes to the origin, whatever its operation.

## Known gaps

- **Observability.** Reconnection and config staleness are logged but emit no metrics, so under the default `fallback_duration` a cache outage has no symptom other than the origin service's bill.
- **Detecting a silently dead peer.** A peer that vanishes without closing the socket is noticed only when the next request times out. Closing that gap needs a periodic health check.
- **IAM authentication validation.** Exercise the toolkit-backed flow against a real IAM-enabled cache, in addition to the hermetic mock-cache coverage.
- **Public TTL controls.** Entry lifetimes are resolved by the cache from the `aws.config` overrides. The public API does not expose per-request lifetime overrides.

## Releasing

Python releases are built from annotated `vMAJOR.MINOR.PATCH` or `vMAJOR.MINOR.PATCH-rc.N` tags. A release candidate tag maps to the PEP 440 version `MAJOR.MINOR.PATCHrcN`. A release tag applies to every language client: Node, Python and Java release together.

The tag is the release version source of truth. The `Python Release` workflow converts it to PEP 440 form and stamps `pyproject.toml` only in its temporary checkout, so the checked-in version stays at its local development baseline. It runs the reusable Python CI workflow, builds one wheel and one source distribution, and smoke tests the wheel on Python 3.10, 3.12 and 3.14. It then waits for approval in the protected `release` environment and publishes with PyPI trusted publishing and attestations. Finally it verifies the published version and attaches the distributions and `python-SHA256SUMS` to the matching GitHub release.

The PyPI distribution name is `aws-elasticache-integration-client`, and the import package is `aws_elasticache_integration_client`. The PyPI project must configure `.github/workflows/python-release.yml` as its trusted publisher for the `release` environment. The workflow uses no PyPI token. A manual workflow run, with an optional version, performs the same test, packaging, artifact validation and wheel smoke stages without entering the release environment or publishing to PyPI.
