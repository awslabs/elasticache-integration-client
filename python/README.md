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
evaluate the resolved predicates → `photon.request` → return the cached response over a real TLS
RESP3 connection, with session-level IAM auth (POC). The transport runs on a **pure-Python RESP3
connector** (`PhotonConnector`) — no Glide or other native dependency.

Requests are routed to the cache only when a predicate from the resolved config matches
(`photon/predicates.py`); everything else — writes, excluded operations, tables outside an
allowlist — passes through to the origin service untouched. Batch reads are matched
all-or-nothing: `BatchGetItem` is routed only when every table in its `RequestItems` map is
allowlisted, since a predicate cannot split a batch.

## Next steps:
- **Resilience / failover.** Connection failures, timeouts, and errors currently propagate as
  typed errors (`PhotonConnectionError`, `PhotonConnectionLost`, `PhotonTimeoutError`,
  `PhotonProtocolError`). A follow-up maps them to pass-through to the origin (direct AWS) on
  cache failure, reconnection with exponential backoff, and circuit breaking.
- **IAM authentication hardening.** The connector performs IAM-token session auth at connect time
  (SigV4-presigned `elasticache:connect` token — POC). Follow-ups: validation against a real
  cache, token refresh on reconnect, and the origin-call (FAS) token.
- **Periodic config refresh.** The resolved config is fetched once at `connect()`. A follow-up
  re-fetches on the configured refresh interval and atomically swaps the predicates. Note the swap
  cannot be gated on the config's `version`: the proxy's `version` is the predicate *format*
  version (constant `"1.0"`), not a content hash, so a refresh replaces unconditionally.
- **Asynchronous / out-of-band responses.** Only the synchronous reply is handled. A follow-up
  handles the out-of-band response (request-id followed by a later push) so a slow cache miss does
  not block pipelined hits.
- **Public TTL / NTTL controls.** TTL/NTTL policy is resolved server-side from the
  `photon.config` overrides; the public API does not expose per-table or per-request TTL
  overrides yet.
