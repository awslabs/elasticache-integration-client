using System.Collections.Frozen;
using System.Text.Json;

namespace Amazon.ElastiCache.Integrations;

/// <summary>
/// The operations that are safe to replay after an unknown outcome: the shared catalog
/// <c>idempotent-operations.json</c>, embedded in this assembly.
/// </summary>
internal static class IdempotentOperations
{
    internal const string ResourceName = "Amazon.ElastiCache.Integrations.idempotent-operations.json";

    private static readonly FrozenDictionary<string, FrozenSet<string>> Catalog = Load();

    /// <summary>Services, lowercased, and their listed operations.</summary>
    internal static IReadOnlyDictionary<string, FrozenSet<string>> All => Catalog;

    /// <summary>Whether <paramref name="metadata"/> names a listed operation. Operation names are case-sensitive.</summary>
    public static bool Contains(RequestMetadata metadata) =>
        Catalog.TryGetValue(metadata.Service, out var operations) && operations.Contains(metadata.Operation);

    private static FrozenDictionary<string, FrozenSet<string>> Load()
    {
        using var stream = typeof(IdempotentOperations).Assembly.GetManifestResourceStream(ResourceName)
            ?? throw new InvalidOperationException($"The assembly has no {ResourceName} resource.");
        using var document = JsonDocument.Parse(stream);
        return document.RootElement.EnumerateObject().ToFrozenDictionary(
            service => service.Name.ToLowerInvariant(),
            service => service.Value.EnumerateArray().Select(operation => operation.GetString()!).ToFrozenSet(StringComparer.Ordinal),
            StringComparer.Ordinal);
    }
}
