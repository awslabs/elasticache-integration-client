# ElastiCache Integrations .NET foundation

This directory contains the `net8.0` project scaffold and AWS SDK for .NET
DynamoDB interception seam. It intentionally has no cache protocol, transport,
authentication, routing policy, resilience, packaging, or publishing
implementation.

Run the local checks from this directory:

```sh
dotnet restore
dotnet format --verify-no-changes
dotnet build -warnaserror
dotnet test
```
