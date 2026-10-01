# Photon

A collection of client libraries for integrating applications with
Photon. Each client is organized in its own language-specific directory.

Photon provides a caching layer for AWS service requests. Its clients connect
an application's AWS SDK to a Photon cache, send supported requests through
the Photon protocol, and allow cacheable responses to be served without
contacting the origin service. Client-specific documentation describes the
supported features and fallback behavior for each language.

The language-neutral replay-safety catalog is
`shared/idempotent-operations.json`. Java packages it as a resource, Python
copies it into built packages, and Node generates its compiled lookup from the
same source with a CI freshness check. Indeterminate Photon failures fall back
to the origin only for cataloged operations after the Photon connection drops;
mixed or unknown operations are treated as non-idempotent. On a healthy
connection, an `ORIGIN_ERROR` is SDK-retryable only for a cataloged operation.
`AUTH_ERROR` closes the stale connection and safely passes the rejected
request through while reconnecting. Other RESP errors are non-retryable and do
not trigger origin fallback.

## Repository layout

```text
.
├── node/    # Node.js client
├── java/    # Java client
├── python/  # Python client
└── shared/  # Language-neutral client contracts
```
