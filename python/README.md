# Photon (Python client)


## Install & test

```bash
cd python
pip install -e '.[test]'   # runtime deps: boto3, botocore
python -m pytest           # see tests/README.md for details
```

Runtime requires Python ≥ 3.10. See [`tests/README.md`](tests/README.md) for the test layout and
how to run the unit vs. end-to-end tests.

## Current state

The library delivers the happy path: intercept → serialize → `photon.config` handshake →
`photon.request` → return the cached response over a real TLS RESP3 connection, with
session-level IAM auth (POC). The transport runs on a **pure-Python RESP3 connector**
(`PhotonConnector`) — no Glide or other native dependency.

## Reconnection and fallback

A lost connection is rebuilt in the background and retried until it succeeds.
Reconnecting never blocks a request: while there is no connection, requests are
declined so the AWS SDK sends them to the origin service using the application's
own credentials.

How long requests may fall back is controlled by `fallback_duration`:

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
cataloged operation. `PHOTON_AUTH_ERROR` closes the stale connection and passes the rejected
request through while reconnection obtains fresh authentication. Other RESP errors, malformed
replies, and failures for non-idempotent operations propagate without origin fallback.

## Next steps:

- **Observability.** Reconnection logs but emits no metrics, so under the default
  `fallback_duration` a cache outage has no symptom other than the origin service's bill.
- **Detecting a silently dead peer.** A peer that vanishes without closing the socket is noticed only
  when the next request times out. Closing that gap needs a periodic health check.
- **IAM authentication hardening.** The connector performs IAM-token session auth at connect time
  (SigV4-presigned `elasticache:connect` token — POC). Follow-ups: validation against a real
  cache, periodic re-`AUTH` (which also prevents the 12-hour IAM disconnect), and the origin-call
  (FAS) token. Re-`AUTH` recovers by recycling the connection, which now exists.
- **Periodic config refresh.** The resolved config is fetched once at `connect()`, and again on every
  reconnect. A follow-up re-fetches on the configured refresh interval and atomically swaps predicates
  when the config version changes. A refresh that carries *overrides* and fails must not keep using the
  connection, because overrides can narrow what is reachable — it recycles the connection instead.
- **Asynchronous / out-of-band responses.** Only the synchronous reply is handled. A follow-up
  handles the out-of-band response (request-id followed by a later push) so a slow cache miss does
  not block pipelined hits.
- **Public TTL / NTTL controls.** TTL/NTTL policy is resolved server-side from the
  `photon.config` overrides; the public API does not expose per-table or per-request TTL
  overrides yet.
