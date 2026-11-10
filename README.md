# ElastiCache Integrations

A collection of client libraries for integrating applications with
ElastiCache Integrations. Each client is organized in its own language-specific
directory.

ElastiCache Integrations provides a caching layer for AWS service requests. Its
clients connect an application's AWS SDK to the cache, send supported requests
through the cache protocol, and allow cacheable responses to be served without
contacting the origin service. Client-specific documentation describes the
supported features and fallback behavior for each language.

The language-neutral replay-safety catalog is
`shared/idempotent-operations.json`. Java packages it as a resource, .NET
embeds it in the assembly, Python copies it into built packages, and Node
generates its compiled lookup from the same source with a CI freshness check. Indeterminate cache failures fall back
to the origin only for cataloged operations after the cache connection drops;
mixed or unknown operations are treated as non-idempotent. On a healthy
connection, an `ORIGIN_ERROR` is SDK-retryable only for a cataloged operation.
`AUTH_ERROR` closes the stale connection and safely passes the rejected
request through while reconnecting. Other RESP errors are non-retryable and do
not trigger origin fallback.

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

## .NET client (preview)

`dotnet/` holds a C# client for the AWS SDK for .NET v4 (`net8.0`, also tested
on the .NET 10 runtime), published as the `Amazon.ElastiCache.Integrations`
NuGet package. Its tests run the `shared/` fixtures, and it embeds
`shared/idempotent-operations.json` as a resource. Like the other clients, it
runs in the `ci` check through `.github/workflows/dotnet-ci.yml`.

## Repository layout

```text
.
├── node/    # Node.js client
├── java/    # Java client
├── python/  # Python client
├── dotnet/  # .NET (C#) client (preview)
└── shared/  # Language-neutral client contracts
```
