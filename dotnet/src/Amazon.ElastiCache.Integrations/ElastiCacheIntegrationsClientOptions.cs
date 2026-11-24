// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Amazon.ElastiCache.Integrations;

/// <summary>Settings for an ElastiCache Integrations client. Validated before any network I/O.</summary>
public sealed class ElastiCacheIntegrationsClientOptions
{
    /// <summary>The cache endpoint as <c>host:port</c>; an IPv6 host may be bracketed.</summary>
    public required string Endpoint { get; init; }

    /// <summary>The ElastiCache cache name, signed into the IAM token.</summary>
    public required string CacheName { get; init; }

    /// <summary>The AWS region of the cache.</summary>
    public required string Region { get; init; }

    /// <summary>The ElastiCache user id sent with <c>AUTH</c>.</summary>
    public string UserId { get; init; } = "default.iam-user";

    /// <summary>
    /// Config overrides sent with <c>aws.config</c>. Unset fields use server defaults.
    /// Read once when the client opens; later changes to its collections are not sent.
    /// </summary>
    public ElastiCacheIntegrationsOverrides Overrides { get; init; } = new();

    /// <summary>Name for SNI and certificate checks. Default: the endpoint host.</summary>
    public string? TlsServerName { get; init; }

    private X509Certificate2Collection? _caCertificates;

    /// <summary>
    /// Trusted root certificates that replace the system store. Copied when the client opens, so
    /// the caller may dispose or change them afterwards.
    /// </summary>
    public X509Certificate2Collection? CaCertificates { get => _caCertificates; init => _caCertificates = value; }

    /// <summary>TCP connect and TLS handshake timeout in milliseconds.</summary>
    public int ConnectTimeoutMs { get; init; } = 1000;

    /// <summary>Time in milliseconds to send a request and receive its reply, including write lock contention.</summary>
    public int RequestTimeoutMs { get; init; } = 5000;

    /// <summary>Timeout in milliseconds for each AUTH, HELLO and config exchange, and for draining on async shutdown.</summary>
    public int ConfigTimeoutMs { get; init; } = 2000;

    /// <summary>Time between successful IAM authentications, in seconds.</summary>
    public int ReauthIntervalSeconds { get; init; } = 1800;

    /// <summary>Time in milliseconds that a request waits for a reconnect. Zero fails immediately.</summary>
    public int ReconnectWaitMs { get; init; } = 100;

    /// <summary>
    /// How long the client uses a config the cache has not confirmed. Past it, the next failed
    /// config refresh replaces the connection to fetch a current config.
    /// </summary>
    public int CachePolicyTimeoutSeconds { get; init; } = 3600;

    /// <summary>Connect retries and backoff.</summary>
    public ElastiCacheIntegrationsReconnectPolicy Reconnect { get; init; } = new();

    /// <summary>Maximum concurrent application requests. Excess requests fail before encoding or writing.</summary>
    public int MaxPending { get; init; } = 100;

    /// <summary>Largest reply accepted, in bytes.</summary>
    public int MaxResponseSize { get; init; } = 16 * 1024 * 1024;

    /// <summary>A copy that holds its own <see cref="CaCertificates"/>, read again on every reconnect. The caller disposes them.</summary>
    /// <exception cref="ElastiCacheIntegrationsConfigurationException">A certificate in <see cref="CaCertificates"/> cannot be read.</exception>
    internal ElastiCacheIntegrationsClientOptions Snapshot()
    {
        var copy = (ElastiCacheIntegrationsClientOptions)MemberwiseClone();
        if (_caCertificates is { } roots)
        {
            var own = new X509Certificate2Collection();
            try
            {
                foreach (var root in roots)
                {
                    own.Add(new X509Certificate2(root.RawData));
                }
            }
            catch (Exception e) when (e is CryptographicException or ObjectDisposedException)
            {
                DisposeAll(own);
                throw new ElastiCacheIntegrationsConfigurationException("CaCertificates holds a certificate that cannot be read, such as a disposed one.", e);
            }

            copy._caCertificates = own;
        }

        return copy;
    }

    internal static void DisposeAll(X509Certificate2Collection? certificates)
    {
        foreach (var certificate in certificates ?? [])
        {
            certificate.Dispose();
        }
    }
}

/// <summary>How connection attempts are retried.</summary>
public sealed class ElastiCacheIntegrationsReconnectPolicy
{
    /// <summary>Bring-up attempts before client creation fails.</summary>
    public int ConnectAttempts { get; init; } = 3;

    /// <summary>Limit for one whole bring-up attempt, in milliseconds.</summary>
    public int AttemptTimeoutMs { get; init; } = 5000;

    /// <summary>First backoff delay in milliseconds. It doubles up to <see cref="MaxDelayMs"/>.</summary>
    public int BaseDelayMs { get; init; } = 100;

    /// <summary>Largest backoff delay in milliseconds.</summary>
    public int MaxDelayMs { get; init; } = 3200;

    /// <summary>Random share of each delay, in [0, 1).</summary>
    public double Jitter { get; init; } = 0.2;
}

/// <summary>How a committed item write leaves the cache.</summary>
/// <remarks>Sent as <c>INVALIDATE_ONLY</c>, <c>UPDATE_CACHED</c> or <c>UPDATE_CACHED_AND_STORE_FULL_ITEM</c>.</remarks>
[JsonConverter(typeof(ItemWriteBehaviorConverter))]
public enum ItemWriteBehavior
{
    /// <summary>Drop the item's entry and store nothing.</summary>
    InvalidateOnly,

    /// <summary>Refresh the projections already cached for the item, and create none.</summary>
    UpdateCached,

    /// <summary>Also store the full-item entry, even for an item that was not cached.</summary>
    UpdateCachedAndStoreFullItem,
}

internal sealed class ItemWriteBehaviorConverter() : JsonStringEnumConverter<ItemWriteBehavior>(JsonNamingPolicy.SnakeCaseUpper, allowIntegerValues: false);

/// <summary>Cache policy settings, the same at the connection level and for one table. Null fields are left out.</summary>
public abstract class ElastiCacheIntegrationsCachePolicy
{
    /// <summary>Lifetime of a cached item, in seconds. Applies to every operation except Query and Scan.</summary>
    [JsonPropertyName("item_ttl")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? ItemTtl { get; init; }

    /// <summary>Lifetime of a cached "item does not exist" answer, in seconds. 0 turns it off.</summary>
    [JsonPropertyName("item_negative_ttl")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? ItemNegativeTtl { get; init; }

    /// <summary>Lifetime of a cached Query page, in seconds.</summary>
    [JsonPropertyName("query_ttl")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? QueryTtl { get; init; }

    /// <summary>Lifetime of a cached Scan page, in seconds.</summary>
    [JsonPropertyName("scan_ttl")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? ScanTtl { get; init; }

    /// <summary>How a committed item write leaves the cache.</summary>
    [JsonPropertyName("item_write_behavior")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public ItemWriteBehavior? ItemWriteBehavior { get; init; }

    /// <summary>Fields this client does not model, sent unchanged. A key may not repeat a modeled field.</summary>
    [JsonExtensionData]
    public IDictionary<string, JsonElement>? Extra { get; init; }
}

/// <summary>
/// Overrides sent with <c>aws.config</c>. <see cref="CachedTables"/> and <see cref="CachedOperations"/>
/// are allowlists, and null or empty means everything. A field the cache does not know is ignored.
/// </summary>
public sealed class ElastiCacheIntegrationsOverrides : ElastiCacheIntegrationsCachePolicy
{
    /// <summary>The only tables that are cached.</summary>
    [JsonPropertyName("cached_tables")]
    [JsonPropertyOrder(-2)]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public IReadOnlyList<string>? CachedTables { get; init; }

    /// <summary>The only operations that are cached.</summary>
    [JsonPropertyName("cached_operations")]
    [JsonPropertyOrder(-1)]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public IReadOnlyList<string>? CachedOperations { get; init; }

    /// <summary>Per-table settings. An entry never puts a table in scope.</summary>
    [JsonPropertyName("table_overrides")]
    [JsonPropertyOrder(1)]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public IReadOnlyDictionary<string, ElastiCacheIntegrationsTableOverrides>? TableOverrides { get; init; }
}

/// <summary>Settings for one table in <see cref="ElastiCacheIntegrationsOverrides.TableOverrides"/>.</summary>
public sealed class ElastiCacheIntegrationsTableOverrides : ElastiCacheIntegrationsCachePolicy
{
}
