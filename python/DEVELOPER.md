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

## Known limitations

- The client emits no metrics. Reconnects and cache policy staleness are logged only, so while
  `fallback_duration` allows fallback, a cache outage shows up only as extra traffic to the origin
  service.
- The client has no dedicated health check. A cache that disappears without closing the socket is
  detected when a command on the connection times out, which closes the connection and starts a
  reconnect. That command is whichever comes first: the next request, the next background
  `aws.config` refresh (every `refresh_interval` seconds, which the cache sets), or the next
  background `AUTH` (every 15 minutes).

## Live DynamoDB E2E example

Complete the repository-wide
[live cache setup](../EXAMPLES.md),
then run from `python/`:

```bash
python -m pip install -e .
python examples/local_e2e.py
```

The example loads the repository-root `.env` and runs the scenarios listed in
[EXAMPLES.md](../EXAMPLES.md).
Each run uses a freshly generated item ID so the first ElastiCache Integrations read is a cache
miss; set `ELASTICACHE_ITEM_ID` only to pin one ID. Supported overrides include
`ELASTICACHE_CACHE_HOST`, `ELASTICACHE_CACHE_PORT`, `ELASTICACHE_CACHE_NAME`,
`ELASTICACHE_TUNNEL_HOST`, `ELASTICACHE_CACHE_USER_ID`, `ELASTICACHE_CA_CERT`,
`ELASTICACHE_REQUEST_TIMEOUT_SECONDS`, `AWS_REGION` (default `us-east-1`),
`ELASTICACHE_TABLE_NAME`, and `ELASTICACHE_CONCURRENCY`.

## Releasing

Python releases are built from annotated `vMAJOR.MINOR.PATCH` or `vMAJOR.MINOR.PATCH-rc.N` tags. A release candidate tag maps to the PEP 440 version `MAJOR.MINOR.PATCHrcN`. A release tag applies to every language client: Node, Python and Java release together.

The tag is the release version source of truth. The `Python Release` workflow converts it to PEP 440 form and stamps `pyproject.toml` only in its temporary checkout, so the checked-in version stays at its local development baseline. It runs the reusable Python CI workflow, builds one wheel and one source distribution, and smoke tests the wheel on Python 3.10, 3.12 and 3.14. It then waits for approval in the protected `release` environment and publishes with PyPI trusted publishing and attestations. Finally it verifies the published version and attaches the distributions and `python-SHA256SUMS` to the matching GitHub release.

The PyPI distribution name is `aws-elasticache-integration-client`, and the import package is `aws_elasticache_integration_client`. The PyPI project must configure repository owner `awslabs`, repository `elasticache-integration-client`, workflow `python-release.yml` and environment `release` as its trusted publisher. PyPI matches the repository name in the GitHub Actions OIDC token, so this must be updated whenever the repository is renamed; GitHub's redirects don't apply to trusted publishing. The workflow uses no PyPI token. A manual workflow run, with an optional version, performs the same test, packaging, artifact validation and wheel smoke stages without entering the release environment or publishing to PyPI.
