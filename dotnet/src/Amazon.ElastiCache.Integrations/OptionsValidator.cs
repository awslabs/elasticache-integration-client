// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using System.Globalization;
using System.Text.Json;

namespace Amazon.ElastiCache.Integrations;

/// <summary>Checks <see cref="ElastiCacheIntegrationsClientOptions"/> before any I/O.</summary>
internal static class OptionsValidator
{
    private static readonly HashSet<string> TableFields = ["item_ttl", "item_negative_ttl", "query_ttl", "scan_ttl", "item_write_behavior"];

    private static readonly HashSet<string> OverrideFields = [.. TableFields, "cached_tables", "cached_operations", "table_overrides"];

    public static void Validate(ElastiCacheIntegrationsClientOptions options)
    {
        ArgumentNullException.ThrowIfNull(options);
        _ = ParseEndpoint(options.Endpoint);
        ValidateSigningName(options.CacheName, nameof(options.CacheName));
        NotBlank(options.UserId, nameof(options.UserId));
        ValidateSigningName(options.Region, nameof(options.Region));
        if (options.TlsServerName is not null)
        {
            NotBlank(options.TlsServerName, nameof(options.TlsServerName));
        }

        ValidateOverrides(options.Overrides ?? throw Invalid(nameof(options.Overrides), "cannot be null"));

        AtLeastOne(options.ConnectTimeoutMs, nameof(options.ConnectTimeoutMs));
        AtLeastOne(options.RequestTimeoutMs, nameof(options.RequestTimeoutMs));
        AtLeastOne(options.ConfigTimeoutMs, nameof(options.ConfigTimeoutMs));
        if (options.ReauthIntervalSeconds is < 1 or >= 12 * 60 * 60)
        {
            throw Invalid(nameof(options.ReauthIntervalSeconds), "must be positive and less than 12 hours");
        }

        if (options.ReconnectWaitMs < 0)
        {
            throw Invalid(nameof(options.ReconnectWaitMs), "cannot be negative");
        }

        AtLeastOne(options.CachePolicyTimeoutSeconds, nameof(options.CachePolicyTimeoutSeconds));
        AtLeastOne(options.MaxPending, nameof(options.MaxPending));
        AtLeastOne(options.MaxResponseSize, nameof(options.MaxResponseSize));

        var reconnect = options.Reconnect ?? throw Invalid(nameof(options.Reconnect), "cannot be null");
        AtLeastOne(reconnect.ConnectAttempts, "Reconnect.ConnectAttempts");
        AtLeastOne(reconnect.AttemptTimeoutMs, "Reconnect.AttemptTimeoutMs");
        AtLeastOne(reconnect.BaseDelayMs, "Reconnect.BaseDelayMs");
        if (reconnect.MaxDelayMs < reconnect.BaseDelayMs)
        {
            throw Invalid("Reconnect.MaxDelayMs", "must be at least Reconnect.BaseDelayMs");
        }

        if (!(reconnect.Jitter >= 0 && reconnect.Jitter < 1))
        {
            throw Invalid("Reconnect.Jitter", $"must be in [0, 1), got {reconnect.Jitter}");
        }
    }

    /// <summary>
    /// Splits <c>host:port</c> on the last colon and strips IPv6 brackets, as Node does,
    /// except that a whitespace-only host is rejected.
    /// </summary>
    public static (string Host, int Port) ParseEndpoint(string? endpoint)
    {
        const string name = nameof(ElastiCacheIntegrationsClientOptions.Endpoint);
        var separator = endpoint?.LastIndexOf(':') ?? -1;
        if (endpoint is null || separator < 1 || separator == endpoint.Length - 1)
        {
            throw Invalid(name, "must be host:port");
        }

        var host = endpoint[..separator];
        if (host.Length > 1 && host[0] == '[' && host[^1] == ']')
        {
            host = host[1..^1];
        }

        if (Uri.CheckHostName(host) == UriHostNameType.Unknown)
        {
            throw Invalid(name, "has an invalid host");
        }

        var portText = endpoint[(separator + 1)..].Trim();
        if (!int.TryParse(portText, NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out var port)
            || port is < 1 or > 65535)
        {
            throw Invalid(name, "has an invalid port");
        }

        return (host, port);
    }

    private static void ValidateOverrides(ElastiCacheIntegrationsOverrides overrides)
    {
        NoBlankItems(overrides.CachedTables, "Overrides.CachedTables");
        NoBlankItems(overrides.CachedOperations, "Overrides.CachedOperations");
        if (overrides.Extra?.ContainsKey("version") == true)
        {
            throw Invalid("Overrides.Extra[\"version\"]", "is reserved; the client sends the config version");
        }

        ValidatePolicy(overrides, OverrideFields, "Overrides");
        foreach (var (table, settings) in overrides.TableOverrides ?? new Dictionary<string, ElastiCacheIntegrationsTableOverrides>())
        {
            var name = $"Overrides.TableOverrides[\"{table}\"]";
            NotBlank(table, "Overrides.TableOverrides key");
            ValidatePolicy(settings ?? throw Invalid(name, "cannot be null"), TableFields, name);
        }
    }

    // The cache checks each lifetime against its own bounds; only what it cannot read is caught here.
    private static void ValidatePolicy(ElastiCacheIntegrationsCachePolicy policy, HashSet<string> modeled, string owner)
    {
        foreach (var (field, value) in new[]
        {
            ("ItemTtl", policy.ItemTtl), ("ItemNegativeTtl", policy.ItemNegativeTtl), ("QueryTtl", policy.QueryTtl), ("ScanTtl", policy.ScanTtl),
        })
        {
            if (value < 0)
            {
                throw Invalid($"{owner}.{field}", "cannot be negative");
            }
        }

        if (policy.ItemWriteBehavior is { } behavior && !Enum.IsDefined(behavior))
        {
            throw Invalid($"{owner}.ItemWriteBehavior", $"is not a defined value: {(int)behavior}");
        }

        ValidateExtra(policy.Extra, modeled, owner);
    }

    private static void NoBlankItems(IReadOnlyList<string>? items, string name)
    {
        if (items?.Any(string.IsNullOrWhiteSpace) == true)
        {
            throw Invalid(name, "cannot hold an empty entry");
        }
    }

    private static void ValidateExtra(IDictionary<string, JsonElement>? extra, HashSet<string> modeled, string owner)
    {
        foreach (var (key, value) in extra ?? new Dictionary<string, JsonElement>())
        {
            if (modeled.Contains(key))
            {
                throw Invalid($"{owner}.Extra[\"{key}\"]", "repeats a modeled field; set the property instead");
            }

            if (value.ValueKind == JsonValueKind.Undefined)
            {
                throw Invalid($"{owner}.Extra[\"{key}\"]", "has no JSON value");
            }
        }
    }

    private static void ValidateSigningName(string? value, string name)
    {
        NotBlank(value, name);
        if (value!.Any(c => !char.IsAsciiLetterOrDigit(c) && c != '-'))
        {
            throw Invalid(name, "must contain only ASCII letters, digits, or hyphens");
        }
    }

    private static void NotBlank(string? value, string name)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            throw Invalid(name, "cannot be empty");
        }
    }

    private static void AtLeastOne(int value, string name)
    {
        if (value < 1)
        {
            throw Invalid(name, $"must be at least 1, got {value}");
        }
    }

    private static ElastiCacheIntegrationsConfigurationException Invalid(string name, string problem) =>
        new($"ElastiCacheIntegrationsClientOptions.{name} {problem}.");
}
