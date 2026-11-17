# Shared test cases

The files in this directory are test cases that every client runs, so the three clients behave the same way.

`shared/protocol-vectors.json` holds wire vectors: the exact RESP bytes each
client must send for a given request or config, and the response or error type
each client must produce for a given reply. Every client's test suite runs all
of them, and Python, the reference client, defines the expected values. The
Node and Java vector tests list the vectors they do not match yet in
`KNOWN_GAPS`, each with a fragment of the failure message it produces today. A
listed vector must keep failing with that message until it is fixed and
removed.

`shared/sdk-request-cases.json` holds SDK-level cases: a DynamoDB call and the
`aws.request` it must produce. Each client sends the call through its real
AWS SDK into its mock cache and checks the method, URL, and metadata. It also
checks that the body, as parsed JSON, equals the call's `params`, and that no
excluded header is sent. The header names sent must equal the case's
`expected.headers`, and a header whose expected value is `null` is checked by
name only. Each SDK adds or omits a few headers of its own, so each runner
lists those names in `ACCEPTED_HEADER_DIFFERENCES`. An entry that no longer
matches what the SDK sends fails the test.

Local test runs include both sets of tests. CI runs them in a separate
`alignment` job for each changed client and excludes them from the client jobs,
so `CI / alignment / <client>` reports drift from the shared fixtures on its
own.

Each client includes a live DynamoDB E2E example that runs the scenarios
described in [Run the examples against a live cache](../EXAMPLES.md).

## Behavior every client shares

Retries work as they do without the cache: the SDK's retry settings decide
whether and how many times a request is retried. If a request times out or loses
its connection to the cache, the SDK can retry it, for any operation. If the
cache returns `ORIGIN_ERROR` or `INTERNAL_ERROR` on a healthy connection,
the SDK can retry the request. Other errors from the cache are not retried.

When a routed request has not reached the cache because the connection is down,
it may use the configured origin fallback window, including for writes.
`AUTH_ERROR` closes the stale connection and safely passes the rejected
request through while reconnecting. With the fallback set to 0, the rejected
request raises `ElastiCacheIntegrationsCacheUnavailable` at once instead. Other
errors from the cache are not retried and do not trigger origin fallback.
