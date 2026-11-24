# AGENTS.md

## Scope

- This directory is the C# integration foundation for AWS SDK for .NET v4
  DynamoDB clients, targeting `net8.0`.
- The foundation ends at the raw HTTP interception seam. Do not add cache
  protocol, transport, authentication, routing policy, resilience, packaging,
  publishing, examples, or customer-facing configuration here.

## Layout

- `Amazon.ElastiCache.Integrations.sln`: library and focused test projects.
- `src/Amazon.ElastiCache.Integrations/`: SDK installation, interception, and
  lifecycle ownership.
- `test/Amazon.ElastiCache.Integrations.Tests/`: hermetic foundation tests.

## Validation

Run from this directory:

- `dotnet restore`
- `dotnet format --verify-no-changes`
- `dotnet build -warnaserror`
- `dotnet test`

## Engineering rules

- Preserve the original signed `HttpRequestMessage` when an interceptor passes
  a request through to the SDK's origin handler.
- Keep the integration thread safe and make ownership of interceptors and
  origin handlers explicit.
- Reject an `AmazonDynamoDBConfig` that already has an `HttpClientFactory`.
- Do not classify operations or interpret request bodies in this foundation.
- Prefer focused, hermetic tests with in-memory handlers.
