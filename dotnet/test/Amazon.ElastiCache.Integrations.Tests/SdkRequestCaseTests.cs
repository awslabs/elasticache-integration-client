using System.Collections;
using System.Globalization;
using System.Reflection;
using System.Text.Json;
using Amazon.DynamoDBv2;
using Amazon.Runtime;

namespace Amazon.ElastiCache.Integrations.Tests;

/// <summary>
/// The cross-client SDK cases in <c>shared/sdk-request-cases.json</c>: each call goes through the
/// real AWS SDK into <see cref="CacheServer"/>, which checks the <c>aws.request</c> it receives.
/// </summary>
[Trait("Category", "alignment")]
public class SdkRequestCaseTests
{
    private static readonly JsonElement Cases = SharedFixtures.Load("sdk-request-cases.json");

    /// <summary>
    /// Header names the .NET SDK sends or omits compared with <c>expected.headers</c>, which lists
    /// what Python sends. The check is exact, so a stale entry fails too.
    /// </summary>
    private static readonly (string[] Extra, string[] Missing) AcceptedHeaderDifferences = (["host", "x-amz-api-version", "x-amz-content-sha256"], []);

    private static CancellationToken Token => TestContext.Current.CancellationToken;

    public static TheoryData<string> CaseNames => SharedFixtures.Names(Cases, "cases");

    [Theory]
    [MemberData(nameof(CaseNames))]
    public async Task Sends(string name)
    {
        var testCase = SharedFixtures.Find(Cases, "cases", name);
        Assert.Equal("dynamodb", testCase.GetProperty("service").GetString());
        await using var server = new CacheServer(Cases.GetProperty("config").GetRawText()) { OnRequest = _ => SdkEndToEndTests.Cached("{}") };

        // Every case must go through the cache; the origin counts any call that does not.
        var origin = new HttpHandlerTests.RecordingOrigin();
        await using (var client = await ElastiCacheIntegrationsClient.CreateDynamoDBClientAsync(
            server.Options(origin),
            new AmazonDynamoDBConfig { RegionEndpoint = RegionEndpoint.USEast1, MaxErrorRetry = 0 },
            // A session token makes the SDK send x-amz-security-token, which the client must strip.
            new SessionAWSCredentials("testkey", "testsecret", "testtoken"),
            Token))
        {
            await SendAsync(client, testCase.GetProperty("operation").GetString()!, testCase.GetProperty("params"));
        }

        Assert.Equal(0, origin.Calls);
        var request = Assert.Single(server.Requests);
        var expected = testCase.GetProperty("expected");
        Assert.Equal(expected.GetProperty("method").GetString(), request[1]);
        Assert.Equal(expected.GetProperty("url").GetString(), request[2]);
        Assert.Equal(expected.GetProperty("metadata").GetString(), request[5]);
        using (var body = JsonDocument.Parse(request[4]))
        {
            Assert.True(SharedFixtures.JsonEquals(testCase.GetProperty("params"), body.RootElement), $"body {request[4]}");
        }

        // Normalized headers are one name:value line each.
        var lines = request[3].Split('\n');
        var expectedHeaders = expected.GetProperty("headers").EnumerateObject().ToList();
        Assert.Equal(
            string.Join(',', expectedHeaders.Select(h => h.Name).Except(AcceptedHeaderDifferences.Missing).Concat(AcceptedHeaderDifferences.Extra).Order(StringComparer.Ordinal)),
            string.Join(',', lines.Select(l => l[..l.IndexOf(':', StringComparison.Ordinal)]).Order(StringComparer.Ordinal)));
        foreach (var header in expectedHeaders.Where(h => h.Value.ValueKind != JsonValueKind.Null))
        {
            Assert.Contains($"{header.Name}:{header.Value.GetString()}", lines);
        }

        foreach (var excluded in Cases.GetProperty("excluded_headers").EnumerateArray())
        {
            Assert.DoesNotContain(lines, l => l.StartsWith($"{excluded.GetString()}:", StringComparison.Ordinal));
        }
    }

    // A field this runner does not read would be skipped silently, so the shapes are pinned.
    [Fact]
    public void UsesOnlyKnownFields()
    {
        Assert.Equal("cases,config,description,excluded_headers", SharedFixtures.Keys(Cases));
        foreach (var testCase in Cases.GetProperty("cases").EnumerateArray())
        {
            Assert.Equal("expected,name,operation,params,service", SharedFixtures.Keys(testCase));
            Assert.Equal("headers,metadata,method,url", SharedFixtures.Keys(testCase.GetProperty("expected")));
        }
    }

    /// <summary>Builds the SDK request for <paramref name="operation"/> from its JSON params and calls it.</summary>
    private static async Task SendAsync(AmazonDynamoDBClient client, string operation, JsonElement parameters)
    {
        var requestType = typeof(AmazonDynamoDBClient).Assembly.GetType($"Amazon.DynamoDBv2.Model.{operation}Request", throwOnError: true)!;
        var method = typeof(AmazonDynamoDBClient).GetMethod($"{operation}Async", [requestType, typeof(CancellationToken)])
            ?? throw new InvalidOperationException($"AmazonDynamoDBClient has no {operation}Async");
        await (Task)method.Invoke(client, [Bind(requestType, parameters), Token])!;
    }

    /// <summary>Maps DynamoDB JSON onto an SDK model by property name; an unknown name fails.</summary>
    private static object? Bind(Type type, JsonElement json)
    {
        var target = Nullable.GetUnderlyingType(type) ?? type;
        switch (json.ValueKind)
        {
            case JsonValueKind.String when target == typeof(string):
                return json.GetString();
            case JsonValueKind.Number:
                return Convert.ChangeType(json.GetRawText(), target, CultureInfo.InvariantCulture);
            case JsonValueKind.True or JsonValueKind.False:
                return json.GetBoolean();
            case JsonValueKind.Array when target.IsGenericType && target.GetGenericTypeDefinition() == typeof(List<>):
                var list = (IList)Activator.CreateInstance(target)!;
                foreach (var item in json.EnumerateArray())
                {
                    list.Add(Bind(target.GetGenericArguments()[0], item));
                }

                return list;
            case JsonValueKind.Object when target.IsGenericType && target.GetGenericTypeDefinition() == typeof(Dictionary<,>):
                var map = (IDictionary)Activator.CreateInstance(target)!;
                foreach (var entry in json.EnumerateObject())
                {
                    map.Add(entry.Name, Bind(target.GetGenericArguments()[1], entry.Value));
                }

                return map;
            case JsonValueKind.Object:
                var model = Activator.CreateInstance(target)!;
                foreach (var field in json.EnumerateObject())
                {
                    var property = target.GetProperty(field.Name, BindingFlags.Public | BindingFlags.Instance)
                        ?? throw new InvalidOperationException($"{target.Name} has no property {field.Name}");
                    property.SetValue(model, Bind(property.PropertyType, field.Value));
                }

                return model;
            default:
                throw new InvalidOperationException($"cannot bind {json.ValueKind} to {target.Name}");
        }
    }
}
