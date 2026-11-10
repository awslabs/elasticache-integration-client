using System.Text.Json;

namespace Amazon.ElastiCache.Integrations;

/// <summary>Service-agnostic request metadata, sent as <c>service=X&amp;op=Y</c>. The service is lowercased.</summary>
internal sealed record RequestMetadata
{
    public RequestMetadata(string service, string operation)
    {
        ArgumentException.ThrowIfNullOrEmpty(service);
        ArgumentException.ThrowIfNullOrEmpty(operation);
        Service = service.ToLowerInvariant();
        Operation = operation;
    }

    public string Service { get; }

    public string Operation { get; }
}

/// <summary>An intercepted SDK request to send to the cache.</summary>
internal sealed class ElastiCacheIntegrationsRequest
{
    public ElastiCacheIntegrationsRequest(
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

    public string Method { get; }

    public string Url { get; }

    /// <summary>Header names, lowercased, and values; multiple values are already joined.</summary>
    public IReadOnlyDictionary<string, string> Headers { get; }

    public ReadOnlyMemory<byte> Body { get; }

    public RequestMetadata Metadata { get; }
}

/// <summary>
/// A <c>[status, headers, body]</c> reply from the cache. Code that builds one from wire data
/// checks the status first and raises <see cref="ElastiCacheIntegrationsProtocolException"/>.
/// </summary>
internal sealed class ElastiCacheIntegrationsResponse
{
    public ElastiCacheIntegrationsResponse(int statusCode, IReadOnlyDictionary<string, string> headers, ReadOnlyMemory<byte> body)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(statusCode, 100);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(statusCode, 599);
        ArgumentNullException.ThrowIfNull(headers);
        StatusCode = statusCode;
        Headers = headers;
        Body = body;
    }

    public int StatusCode { get; }

    public IReadOnlyDictionary<string, string> Headers { get; }

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
