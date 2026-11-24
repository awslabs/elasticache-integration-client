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
}
