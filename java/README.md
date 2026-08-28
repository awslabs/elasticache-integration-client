# Photon Java client

Java 8-compatible Photon transport for AWS SDK v2 clients.

## Request routing

Opening a Photon session compiles the CEL predicates returned by
`photon.config`. Each expression can use the request's `service`, `operation`,
`method`, `url`, `headers`, and parsed JSON `body`, plus arbitrary variables
bound by the predicate's `vars` object.

A request is sent to Photon when any predicate evaluates to `true`. Otherwise,
the original signed SDK request is passed to the configured origin HTTP
transport without a cache round trip. This supports the proxy's read-through
and write-through routing, including table allowlists for batch and transaction
operations.

Expressions that cannot be compiled are ignored. Evaluation errors, malformed
or non-object JSON bodies, and bodies larger than 512 KiB count as no match.
The resolved config is compiled once when the session opens.
