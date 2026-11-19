// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using System.Text;
using Amazon.DynamoDBv2.Model;

namespace Amazon.ElastiCache.Integrations.LiveValidation;

/// <summary>Canonical text of DynamoDB data: names and sets sorted, so equal data gives equal text.</summary>
internal static class Canon
{
    public static string Item(Dictionary<string, AttributeValue>? item) =>
        item is null || item.Count == 0 ? "<none>" : Map(item);

    /// <summary>The sorted <c>pk</c> values of <paramref name="items"/>.</summary>
    public static string Keys(IEnumerable<Dictionary<string, AttributeValue>>? items) =>
        string.Join(",", (items ?? []).Select(i => i.GetValueOrDefault("pk")?.S).Order(StringComparer.Ordinal));

    /// <summary>Items sorted by their canonical text, since DynamoDB does not order batch replies.</summary>
    public static string Items(IEnumerable<Dictionary<string, AttributeValue>>? items) =>
        "[" + string.Join(",", (items ?? []).Select(Item).Order(StringComparer.Ordinal)) + "]";

    private static string Map(Dictionary<string, AttributeValue> map) =>
        "{" + string.Join(",", map.OrderBy(p => p.Key, StringComparer.Ordinal).Select(p => $"{p.Key}:{Value(p.Value)}")) + "}";

    private static string Value(AttributeValue v)
    {
        var text = new StringBuilder();
        if (v.S is not null)
        {
            text.Append("S:").Append(v.S);
        }
        else if (v.N is not null)
        {
            text.Append("N:").Append(v.N);
        }
        else if (v.BOOL is { } b)
        {
            text.Append("BOOL:").Append(b);
        }
        else if (v.NULL == true)
        {
            text.Append("NULL");
        }
        else if (v.M is not null)
        {
            text.Append("M:").Append(Map(v.M));
        }
        else if (v.L is not null)
        {
            text.Append("L:[").AppendJoin(",", v.L.Select(Value)).Append(']');
        }
        else if (v.SS is not null)
        {
            text.Append("SS:[").AppendJoin(",", v.SS.Order(StringComparer.Ordinal)).Append(']');
        }
        else if (v.NS is not null)
        {
            text.Append("NS:[").AppendJoin(",", v.NS.Order(StringComparer.Ordinal)).Append(']');
        }
        else if (v.B is not null)
        {
            text.Append("B:").Append(Convert.ToBase64String(v.B.ToArray()));
        }
        else if (v.BS is not null)
        {
            text.Append("BS:[").AppendJoin(",", v.BS.Select(b => Convert.ToBase64String(b.ToArray())).Order(StringComparer.Ordinal)).Append(']');
        }
        else
        {
            throw new NotSupportedException("An attribute value of an unknown type.");
        }

        return text.ToString();
    }
}
