# ElastiCache Integrations for .NET

This `net8.0` library provides the AWS SDK for .NET v4 DynamoDB HTTP interceptor seam and a standalone cache runtime. The interceptor still preserves the original signed SDK request when it declines interception. Synchronous HTTP calls follow the SDK origin path.

## Cache runtime

Open an `ElastiCacheIntegrationsCache` with `ElastiCacheIntegrationsClientOptions` and an AWS SDK `AWSCredentials` provider. Supply the cache endpoint (`host:port`), cache name, and AWS region. `UserId` defaults to `default.iam-user`. Use the same credential provider and region as the origin SDK client so cache traffic runs under the intended identity.

The runtime verifies the cache certificate and host name over TLS. `CaCertificates` can supply custom trusted roots and `TlsServerName` can supply the verified host name. The runtime signs a fresh IAM token on startup and reauthentication. It negotiates RESP3 with RESP2 fallback and sends the configured policy overrides with `aws.config`. `CurrentConfig` exposes the latest server config. The server's refresh interval controls when the runtime checks for new config.

`SendAsync` accepts an `ElastiCacheIntegrationsRequest` containing the HTTP method, URL, headers, body, service and operation. The runtime drops origin signing headers and returns the HTTP status, headers and body from either an in-band reply or a deferred RESP3 reply. The caller owns routing decisions and can use the existing SDK interception seam to integrate its own policy.

| Setting | Default |
| --- | ---: |
| `ConnectTimeoutMs` | 1,000 ms |
| `ConfigTimeoutMs` | 2,000 ms |
| `RequestTimeoutMs` | 5,000 ms |
| `ReconnectWaitMs` | 100 ms |
| `ReauthIntervalSeconds` | 1,800 s |
| `CachePolicyTimeoutSeconds` | 3,600 s |
| `MaxPending` | 100 requests |
| `MaxResponseSize` | 16 MiB per RESP frame |

Requests above `MaxPending` fail before encoding. Connection loss, an invalid frame, or cancellation or timeout while waiting for an in-band reply can close the connection and fail pending requests. A timed-out deferred request releases its slot and the runtime discards its late push. Reconnect runs in the background with capped exponential backoff. Requests made during reconnect wait up to `ReconnectWaitMs`. The runtime never replays a failed request.

If the cache rejects the configuration overrides during reconnect, the session stops retrying. Waiting and subsequent sends fail with `ElastiCacheIntegrationsCacheUnavailableException` carrying the configuration exception. Open a new session after correcting the configuration.

Use `await using` to stop admitting new requests, drain pending replies up to the configured drain timeout, and join background work. `Dispose()` closes the connection immediately and fails pending requests.

## Validation

Run from this directory.

```sh
dotnet restore
dotnet format --verify-no-changes
dotnet build -warnaserror
dotnet test
```

Runtime tests use a loopback TLS server and synthetic AWS credentials. Protocol alignment tests use `shared/protocol-vectors.json`.
