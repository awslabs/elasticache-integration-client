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
session-level IAM auth (POC). The transport runs on a **pure-Python RESP3 connection**
(`PhotonConnection`) — no Glide or other native dependency.

The earlier `PhotonConnector`, `PhotonConnectionLost`, and `IncompleteFrame`
names remain available as compatibility aliases. New code should use
`PhotonConnection`, `PhotonConnectionLostError`, and `IncompleteFrameError`.

## Next steps:

- **Cacheability routing.** Today every intercepted request is forwarded to the cache. The
  `photon.config` handshake already returns the resolved predicate set (kept in
  `ResolvedConfig.raw`); a follow-up evaluates those predicates to decide cache vs. pass-through to
  the origin service.
- **Resilience / failover.** Connection failures, timeouts, and errors currently propagate as
  typed errors (`PhotonConnectionError`, `PhotonConnectionLostError`, `PhotonTimeoutError`,
  `PhotonProtocolError`). A follow-up maps them to pass-through to the origin (direct AWS) on
  cache failure, reconnection with exponential backoff, and circuit breaking.
- **IAM authentication hardening.** The connection performs IAM-token session auth at connect time
  (SigV4-presigned `elasticache:connect` token — POC). Follow-ups: validation against a real
  cache, token refresh on reconnect, and the origin-call (FAS) token.
- **Periodic config refresh.** The resolved config is fetched once at `connect()`. A follow-up
  re-fetches on the configured refresh interval and atomically swaps predicates when the config
  version changes.
- **Asynchronous / out-of-band responses.** Only the synchronous reply is handled. A follow-up
  handles the out-of-band response (request-id followed by a later push) so a slow cache miss does
  not block pipelined hits.
- **Public TTL / NTTL controls.** TTL/NTTL policy is resolved server-side from the
  `photon.config` overrides; the public API does not expose per-table or per-request TTL
  overrides yet.
