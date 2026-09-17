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
same source with a CI freshness check. Photon failures fall back to the origin
only for cataloged operations; mixed or unknown operations are treated as
non-idempotent.

## Repository layout

```text
.
├── node/    # Node.js client
├── java/    # Java client
├── python/  # Python client
└── shared/  # Language-neutral client contracts
```
