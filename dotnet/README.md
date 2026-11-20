# ElastiCache Integrations .NET foundation

This directory contains the `net8.0` project scaffold and AWS SDK for .NET
DynamoDB interception seam. It intentionally has no cache protocol, transport,
authentication, routing policy, resilience, packaging, or publishing
implementation.

The interceptor seam is asynchronous because the `net8.0` DynamoDB service
APIs are asynchronous. A direct synchronous HTTP transport call bypasses the
interceptor and preserves the SDK's normal origin path.

Run the local checks from this directory:

```sh
dotnet restore
dotnet format --verify-no-changes
dotnet build -warnaserror
dotnet test
```
