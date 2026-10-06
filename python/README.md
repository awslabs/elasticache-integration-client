# Photon (Python client)


## Install & test

```bash
cd python
pip install -e '.[test]'   # runtime deps: boto3, botocore
python -m pytest           # see tests/README.md for details
pip install -e '.[dev]'    # pinned ruff, mypy, build, twine
ruff format --check . && ruff check . && mypy
```

Runtime requires Python ≥ 3.10. See [`tests/README.md`](tests/README.md) for the test layout and
how to run the unit vs. end-to-end tests.

## Releases

Python publication is driven by annotated Git tags. Stable releases use
`vMAJOR.MINOR.PATCH`; release candidates use `vMAJOR.MINOR.PATCH-rc.N`, which maps to the
PEP 440 version `MAJOR.MINOR.PATCHrcN`. A release tag applies to every language client: Node,
Python, and Java release together and their package metadata must use the same version.

Before tagging, update the version in `pyproject.toml`; `photon.__version__` reads that installed
distribution metadata. The `Python Release` workflow rejects any tag that does not match the
project version. It runs the reusable Python CI workflow, builds one wheel and one source
distribution, and smoke tests the wheel on Python 3.10, 3.12, and 3.14 before waiting for approval
from the protected `release` environment and publishing
with PyPI trusted publishing and attestations. The workflow verifies the published version and
attaches the distributions plus `python-SHA256SUMS` to the matching GitHub Release.

The provisional PyPI distribution name is `photon-python`, while the import package remains
`photon`; the public distribution name is pending the final naming decision. The PyPI project must
configure `.github/workflows/python-release.yml` as its trusted publisher for the `release`
environment. No PyPI token is used by the workflow. A manual workflow run performs the same test,
packaging, artifact validation, and wheel smoke stages without entering the release environment or
publishing to PyPI.

## Current state

The library delivers the happy path: intercept → serialize → `photon.config` handshake →
`photon.request` → return the cached response over a real TLS RESP3 connection, with
session-level IAM auth (POC). The transport runs on a **pure-Python RESP3 connector**
(`PhotonConnector`) — no Glide or other native dependency.
Its per-request timeout defaults to 5 seconds and can be changed with the
`photon_request_timeout` field (see below).

## Configuration

Optional Photon settings go in one `photon.PhotonClientConfiguration`, passed as
`client_configuration`. Every field has a default, so set only what you change. Cache policy
settings are sent to the cache through `photon.config`.

```python
ddb = photon.client(
    "dynamodb",
    endpoint="my-cache:6379",
    cache_name="my-cache",
    client_configuration=photon.PhotonClientConfiguration(
        photon_request_timeout=2.0,
        fallback_duration=60,
        cache_policy_overrides={"exclude_tables": ["audit-log"]},
    ),
)
```

| Field | Default | Meaning |
|---|---|---|
| `photon_request_timeout` | 5 s | How long to wait for each Photon request's reply. |
| `fallback_duration` | `None` (indefinite) | How long requests may go to the origin service while there is no cache connection (see below). |
| `cache_policy_overrides` | `None` | Settings that change what Photon caches, such as tables to exclude and cache entry lifetimes. |
| `cache_policy_timeout` | 3600 s | How long Photon may use a cache policy without confirmation from the cache. |

Building `PhotonClientConfiguration` raises `PhotonConfigError` for a value that is not usable.

## Reconnection and fallback

A lost connection is rebuilt in the background and retried until it succeeds.
Reconnecting never blocks a request: while there is no connection, requests are
declined so the AWS SDK sends them to the origin service using the application's
own credentials.

How long requests may fall back is controlled by the `fallback_duration` field:

| `fallback_duration` | Behavior |
|---|---|
| `None` (default), or negative | Serve from the origin service indefinitely, so a cache outage never becomes an application outage. |
| `N > 0` | Serve from the origin service for N seconds, then raise `PhotonCacheUnavailable`. |
| `0` | Never fall back — the first request after a connection is lost raises. |

The window is **per outage, not a total budget**: `fallback_duration=60` allows 60 seconds of
fallback each time the connection is lost, and a connection that keeps flapping gets a fresh 60
seconds after every successful reconnect.

`photon.PhotonCacheUnavailable` subclasses botocore's `ConnectionClosedError`, so it is retryable and
an application's existing `except botocore...` blocks already catch it. Retryable cuts both ways:
under the SDK's default retry mode a raised request is retried, which is usually what you want — a
retry after backoff can land once the connection is back — but with `fallback_duration=0` it turns
one fast failure into several.

When there is no live cache connection, the request has not been sent and can safely pass through
for any operation while the fallback window remains open. After a request is sent, origin fallback
is allowed only when the connection drops and the operation is in the shared replay-safe catalog.
While the connection remains healthy, `ORIGIN_ERROR` becomes a botocore-retryable error only for a
cataloged operation. `AUTH_ERROR` closes the stale connection and passes the rejected
request through while reconnection obtains fresh authentication. Other RESP errors, malformed
replies, and failures for non-idempotent operations propagate without origin fallback.

## Cache policy timeout

Photon automatically confirms the resolved cache policy in the background through `photon.config`.
A new policy replaces the current one. An unchanged reply confirms that the current policy is still
valid. Both replies restart the timeout.

If confirmation fails, Photon keeps using the last confirmed policy. That tolerance is bounded by
`cache_policy_timeout`, one hour by default. After the timeout, the next failed confirmation drops
the connection so a replacement can fetch a current policy. Requests follow the `fallback_duration`
rules while the connection is being replaced.

## Next steps:

- **Observability.** Reconnection and config staleness are logged but emit no metrics, so under the
  default `fallback_duration` a cache outage has no symptom other than the origin service's bill.
- **Detecting a silently dead peer.** A peer that vanishes without closing the socket is noticed only
  when the next request times out. Closing that gap needs a periodic health check.
- **IAM authentication hardening.** The connector performs IAM-token session auth at connect time
  (SigV4-presigned `elasticache:connect` token — POC). Follow-ups: validation against a real
  cache, periodic re-`AUTH` (which also prevents the 12-hour IAM disconnect), and the origin-call
  (FAS) token. Re-`AUTH` recovers by recycling the connection, which now exists.
- **Asynchronous / out-of-band responses.** Only the synchronous reply is handled. A follow-up
  handles the out-of-band response (request-id followed by a later push) so a slow cache miss does
  not block pipelined hits.
- **Public TTL / NTTL controls.** TTL/NTTL policy is resolved server-side from the
  `photon.config` overrides; the public API does not expose per-table or per-request TTL
  overrides yet.
