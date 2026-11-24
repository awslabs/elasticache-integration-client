# AGENTS.md

## Scope

- This directory contains the C# ElastiCache Integrations library, targeting `net8.0` and AWS SDK for .NET v4.
- The SDK integration ends at the raw HTTP interception seam. The standalone cache runtime owns configuration, protocol, TLS, IAM authentication and the connection lifecycle. Routing policy belongs outside the runtime.

## Layout

- `Amazon.ElastiCache.Integrations.sln` contains the library and test projects.
- `src/Amazon.ElastiCache.Integrations/` contains the SDK integration and cache runtime.
- `test/Amazon.ElastiCache.Integrations.Tests/` contains hermetic SDK and cache tests. The `Category=alignment` tests read the shared protocol vectors.

## Validation

Run from this directory.

- `dotnet restore`
- `dotnet format --verify-no-changes`
- `dotnet build -warnaserror`
- `dotnet test`

GitHub Actions runs the client and alignment suites separately for changes to
`dotnet/`, the shared fixtures, or their CI workflows.

## Engineering rules

- Preserve the original signed `HttpRequestMessage` when an interceptor passes a request through to the SDK's origin handler.
- Keep the integration thread safe and make ownership of interceptors and origin handlers explicit.
- Reject an `AmazonDynamoDBConfig` that already has an `HttpClientFactory`.
- Require TLS and verify the cache certificate and host name before sending IAM credentials.
- Keep accepted requests and response buffers bounded. Never replay a request after an ambiguous failure.
- Prefer hermetic tests with in-memory handlers or a loopback mock cache.
