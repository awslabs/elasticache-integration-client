# Photon

A collection of client libraries for integrating applications with
Photon. Each client is organized in its own language-specific directory.

Photon provides a caching layer for AWS service requests. Its clients connect
an application's AWS SDK to a Photon cache, send supported requests through
the Photon protocol, and allow cacheable responses to be served without
contacting the origin service. Client-specific documentation describes the
supported features and fallback behavior for each language.

## Repository layout

```text
.
├── node/    # Node.js client
├── java/    # Java client
└── python/  # Python client
```
