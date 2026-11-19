// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using System.Text.Json;
using System.Text.Json.Nodes;

namespace Amazon.ElastiCache.Integrations.Tests;

/// <summary>The cross-client fixtures in the repository's <c>shared/</c> directory, copied next to the test assembly.</summary>
internal static class SharedFixtures
{
    public static JsonElement Load(string name)
    {
        using var document = JsonDocument.Parse(File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, "shared", name)));
        return document.RootElement.Clone();
    }

    public static TheoryData<string> Names(JsonElement fixture, string section)
    {
        var names = new TheoryData<string>();
        foreach (var entry in fixture.GetProperty(section).EnumerateArray())
        {
            names.Add(entry.GetProperty("name").GetString()!);
        }

        return names;
    }

    public static JsonElement Find(JsonElement fixture, string section, string name) =>
        fixture.GetProperty(section).EnumerateArray().Single(e => e.GetProperty("name").GetString() == name);

    /// <summary>The property names of an object, sorted and comma-joined.</summary>
    public static string Keys(JsonElement value) =>
        string.Join(',', value.EnumerateObject().Select(p => p.Name).Order(StringComparer.Ordinal));

    public static bool JsonEquals(JsonElement expected, JsonElement actual) =>
        JsonNode.DeepEquals(JsonNode.Parse(expected.GetRawText()), JsonNode.Parse(actual.GetRawText()));

    /// <summary>
    /// Runs a fixture check. A name in <paramref name="knownGaps"/> must fail with a message that
    /// contains its fragment; once it passes, the test fails until the name is removed.
    /// </summary>
    public static void Check(IReadOnlyDictionary<string, string> knownGaps, string name, Action check)
    {
        if (!knownGaps.TryGetValue(name, out var fragment))
        {
            check();
            return;
        }

        try
        {
            check();
        }
        catch (Exception e)
        {
            Assert.Contains(fragment, e.Message, StringComparison.Ordinal);
            return;
        }

        Assert.Fail($"{name} now matches the shared fixture; remove it from KnownGaps");
    }
}
