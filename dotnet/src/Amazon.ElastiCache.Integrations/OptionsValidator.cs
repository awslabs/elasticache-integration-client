using System.Globalization;
using System.Text.Json;

namespace Amazon.ElastiCache.Integrations;

/// <summary>Checks <see cref="ElastiCacheIntegrationsClientOptions"/> before any I/O.</summary>
internal static class OptionsValidator
{
    // Task.Delay and CancellationTokenSource work in whole milliseconds, at most int.MaxValue.
    private static readonly TimeSpan MinTimeout = TimeSpan.FromMilliseconds(1);
    private static readonly TimeSpan MaxTimeout = TimeSpan.FromMilliseconds(int.MaxValue);

    // ElastiCache closes an IAM-authenticated connection after 12 hours.
    private static readonly TimeSpan MaxReauthInterval = TimeSpan.FromHours(12);

    private static readonly HashSet<string> OverrideFields =
        ["exclude_operations", "exclude_tables", "ttl", "nttl", "populate_on_write", "populate_full_item", "tables"];

    private static readonly HashSet<string> TableFields = ["ttl", "nttl", "populate_on_write", "populate_full_item"];

    public static void Validate(ElastiCacheIntegrationsClientOptions options)
    {
        ArgumentNullException.ThrowIfNull(options);
        _ = ParseEndpoint(options.Endpoint);
        NotBlank(options.CacheName, nameof(options.CacheName));
        NotBlank(options.UserId, nameof(options.UserId));
        if (options.Region is not null)
        {
            NotBlank(options.Region, nameof(options.Region));
        }

        if (options.TlsServerName is not null)
        {
            NotBlank(options.TlsServerName, nameof(options.TlsServerName));
        }

        if (!options.UseTls && (options.InsecureSkipVerify || options.CaCertificates is not null || options.TlsServerName is not null))
        {
            throw Invalid(nameof(options.UseTls), "is false, so TlsServerName, CaCertificates and InsecureSkipVerify cannot be set");
        }

        ValidateOverrides(options.Overrides ?? throw Invalid(nameof(options.Overrides), "cannot be null"));

        Positive(options.ConnectTimeout, nameof(options.ConnectTimeout));
        Positive(options.RequestTimeout, nameof(options.RequestTimeout));
        Positive(options.ConfigTimeout, nameof(options.ConfigTimeout));
        Positive(options.ReauthInterval, nameof(options.ReauthInterval));
        if (options.ReauthInterval >= MaxReauthInterval)
        {
            throw Invalid(nameof(options.ReauthInterval), $"must be under {MaxReauthInterval}, got {options.ReauthInterval}");
        }
        NotNegative(options.ReconnectWait, nameof(options.ReconnectWait));
        if (options.FallbackDuration != Timeout.InfiniteTimeSpan)
        {
            NotNegative(options.FallbackDuration, nameof(options.FallbackDuration));
        }

        Positive(options.CachePolicyTimeout, nameof(options.CachePolicyTimeout));
        AtLeastOne(options.MaxPending, nameof(options.MaxPending));
        AtLeastOne(options.MaxResponseSize, nameof(options.MaxResponseSize));

        var reconnect = options.Reconnect ?? throw Invalid(nameof(options.Reconnect), "cannot be null");
        AtLeastOne(reconnect.ConnectAttempts, "Reconnect.ConnectAttempts");
        Positive(reconnect.AttemptTimeout, "Reconnect.AttemptTimeout");
        Positive(reconnect.BaseDelay, "Reconnect.BaseDelay");
        Positive(reconnect.MaxDelay, "Reconnect.MaxDelay");
        if (reconnect.MaxDelay < reconnect.BaseDelay)
        {
            throw Invalid("Reconnect.MaxDelay", $"must be at least Reconnect.BaseDelay, got {reconnect.MaxDelay}");
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
            throw Invalid(name, $"must be host:port, got \"{endpoint}\"");
        }

        var host = endpoint[..separator];
        if (host.Length > 1 && host[0] == '[' && host[^1] == ']')
        {
            host = host[1..^1];
        }

        if (string.IsNullOrWhiteSpace(host))
        {
            throw Invalid(name, $"has an empty host: \"{endpoint}\"");
        }

        var portText = endpoint[(separator + 1)..].Trim();
        if (!int.TryParse(portText, NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out var port)
            || port is < 1 or > 65535)
        {
            throw Invalid(name, $"has an invalid port: \"{endpoint}\"");
        }

        return (host, port);
    }

    private static void ValidateOverrides(ElastiCacheIntegrationsOverrides overrides)
    {
        NoBlankItems(overrides.ExcludeOperations, "Overrides.ExcludeOperations");
        NoBlankItems(overrides.ExcludeTables, "Overrides.ExcludeTables");
        if (overrides.Extra?.ContainsKey("version") == true)
        {
            throw Invalid("Overrides.Extra[\"version\"]", "is reserved; the client sends the config version");
        }

        ValidateExtra(overrides.Extra, OverrideFields, "Overrides");
        foreach (var (table, settings) in overrides.Tables ?? new Dictionary<string, ElastiCacheIntegrationsTableOverrides>())
        {
            var name = $"Overrides.Tables[\"{table}\"]";
            NotBlank(table, "Overrides.Tables key");
            ValidateExtra((settings ?? throw Invalid(name, "cannot be null")).Extra, TableFields, name);
        }
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

    private static void NotBlank(string? value, string name)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            throw Invalid(name, "cannot be empty");
        }
    }

    private static void Positive(TimeSpan value, string name)
    {
        if (value < MinTimeout || value > MaxTimeout)
        {
            throw Invalid(name, $"must be from {MinTimeout} to {MaxTimeout}, got {value}");
        }
    }

    private static void NotNegative(TimeSpan value, string name)
    {
        if (value < TimeSpan.Zero || value > MaxTimeout)
        {
            throw Invalid(name, $"must be zero or positive and at most {MaxTimeout}, got {value}");
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
