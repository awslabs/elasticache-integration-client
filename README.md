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

## Shared terminology

Equivalent concepts use the same vocabulary across clients, with normal
language casing:

| Concept | Java / Node.js | Python |
| --- | --- | --- |
| Cache transport | `PhotonConnection` | `PhotonConnection` |
| Mid-flight connection failure | `PhotonConnectionLostError` | `PhotonConnectionLostError` |
| Partial RESP frame | `IncompleteFrameError` | `IncompleteFrameError` |
| Raw command execution | `execute` | `execute` |
| Config handshake customizations | `overrides` | `overrides` |
| Resolved handshake config | `getResolvedConfig` / `resolvedConfig` | `resolved_config` |
| Pending-request limit | `maxPendingRequests` | `max_pending_requests` |

Deprecated aliases remain where an earlier client exposed a different public
name.
