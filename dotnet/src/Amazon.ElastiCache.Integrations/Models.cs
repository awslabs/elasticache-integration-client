// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using System.Text.Json;

namespace Amazon.ElastiCache.Integrations;

/// <summary>Service-agnostic request metadata, sent as <c>service=X&amp;op=Y</c>. The service is lowercased.</summary>
internal sealed record RequestMetadata
{
    public RequestMetadata(string service, string operation)
    {
        ArgumentException.ThrowIfNullOrEmpty(service);
        ArgumentException.ThrowIfNullOrEmpty(operation);
        if (!service.All(char.IsAsciiLetterOrDigit) || !operation.All(char.IsAsciiLetterOrDigit))
        {
            throw new ArgumentException("The service and operation must contain only ASCII letters and digits.");
        }

        Service = service.ToLowerInvariant();
        Operation = operation;
    }

    public string Service { get; }

    public string Operation { get; }
}

/// <summary>An HTTP request to send through the cache.</summary>
public sealed class ElastiCacheIntegrationsRequest
{
    /// <summary>Creates a request with the target service and operation used by the cache.</summary>
    public ElastiCacheIntegrationsRequest(string method, string url, IReadOnlyDictionary<string, string> headers,
        ReadOnlyMemory<byte> body, string service, string operation)
        : this(method, url, headers, body, new RequestMetadata(service, operation))
    {
    }

    internal ElastiCacheIntegrationsRequest(
        string method,
        string url,
        IReadOnlyDictionary<string, string> headers,
        ReadOnlyMemory<byte> body,
        RequestMetadata metadata)
    {
        ArgumentException.ThrowIfNullOrEmpty(method);
        ArgumentException.ThrowIfNullOrEmpty(url);
        ArgumentNullException.ThrowIfNull(headers);
        ArgumentNullException.ThrowIfNull(metadata);
        Method = method;
        Url = url;
        Headers = headers;
        Body = body;
        Metadata = metadata;
    }

    /// <summary>The HTTP method.</summary>
    public string Method { get; }

    /// <summary>The URL of the origin request.</summary>
    public string Url { get; }

    /// <summary>Header names, lowercased, and values; multiple values are already joined.</summary>
    public IReadOnlyDictionary<string, string> Headers { get; }

    /// <summary>The HTTP message body.</summary>
    public ReadOnlyMemory<byte> Body { get; }

    internal RequestMetadata Metadata { get; }
}

/// <summary>
/// A <c>[status, headers, body]</c> reply from the cache. Code that builds one from wire data
/// checks the status first and raises <see cref="ElastiCacheIntegrationsProtocolException"/>.
/// </summary>
public sealed class ElastiCacheIntegrationsResponse
{
    internal ElastiCacheIntegrationsResponse(int statusCode, IReadOnlyDictionary<string, string> headers, ReadOnlyMemory<byte> body)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(statusCode, 100);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(statusCode, 599);
        ArgumentNullException.ThrowIfNull(headers);
        StatusCode = statusCode;
        Headers = headers;
        Body = body;
    }

    /// <summary>The upstream HTTP status code.</summary>
    public int StatusCode { get; }

    /// <summary>The upstream HTTP response headers.</summary>
    public IReadOnlyDictionary<string, string> Headers { get; }

    /// <summary>The HTTP message body.</summary>
    public ReadOnlyMemory<byte> Body { get; }
}

/// <summary>The config the cache resolved in reply to <c>aws.config</c>.</summary>
internal sealed class ResolvedConfig
{
    public ResolvedConfig(string? version, TimeSpan refreshInterval, JsonElement raw)
    {
        ArgumentOutOfRangeException.ThrowIfLessThanOrEqual(refreshInterval, TimeSpan.Zero);
        if (raw.ValueKind != JsonValueKind.Object)
        {
            throw new ArgumentException($"Config must be a JSON object, got {raw.ValueKind}.", nameof(raw));
        }

        Version = version;
        RefreshInterval = refreshInterval;
        Raw = raw.Clone();
    }

    public string? Version { get; }

    /// <summary>How often to refresh the config. Always positive.</summary>
    public TimeSpan RefreshInterval { get; }

    /// <summary>The whole reply object, detached from its source document.</summary>
    public JsonElement Raw { get; }
}
