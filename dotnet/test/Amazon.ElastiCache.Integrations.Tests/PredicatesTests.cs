using System.Text;
using System.Text.Json;

namespace Amazon.ElastiCache.Integrations.Tests;

public class PredicatesTests
{
    // The expressions the proxy emits, verbatim.
    private const string AllOps = "service == 'dynamodb' && operation in ops";
    private const string TableName = "service == 'dynamodb' && operation in ops && has(body.TableName) && body.TableName in allowed";
    private const string RequestItems = "service == 'dynamodb' && operation in ops && has(body.RequestItems) && size(body.RequestItems) > 0 && body.RequestItems.all(t, t in allowed)";
    private const string TransactItems = "service == 'dynamodb' && operation in ops && has(body.TransactItems) && size(body.TransactItems) > 0 && body.TransactItems.all(i, i.all(k, has(i[k].TableName) && i[k].TableName in allowed))";
    private const string TableNameExcept = "service == 'dynamodb' && operation in ops && has(body.TableName) && !(body.TableName in excluded)";
    private const string RequestItemsExcept = "service == 'dynamodb' && operation in ops && has(body.RequestItems) && size(body.RequestItems) > 0 && body.RequestItems.all(t, !(t in excluded))";
    private const string TransactItemsExcept = "service == 'dynamodb' && operation in ops && has(body.TransactItems) && size(body.TransactItems) > 0 && body.TransactItems.all(i, i.all(k, has(i[k].TableName) && !(i[k].TableName in excluded)))";

    private static readonly string[] AllOperations =
    [
        "GetItem", "BatchGetItem", "Query", "Scan", "PutItem", "UpdateItem", "DeleteItem",
        "BatchWriteItem", "TransactGetItems", "TransactWriteItems",
    ];

    private static readonly string[] SingleTableOps = ["GetItem", "Query", "Scan", "PutItem", "UpdateItem", "DeleteItem"];
    private static readonly string[] BatchOps = ["BatchGetItem", "BatchWriteItem"];
    private static readonly string[] TransactOps = ["TransactGetItems", "TransactWriteItems"];

    // {} overrides.
    private static readonly string DefaultConfig = Config((AllOps, Vars(AllOperations)));

    // {"cached_tables":["Users","Sessions"]}
    private static readonly string AllowConfig = Config(
        (TableName, Vars(SingleTableOps, "allowed", "Users", "Sessions")),
        (RequestItems, Vars(BatchOps, "allowed", "Users", "Sessions")),
        (TransactItems, Vars(TransactOps, "allowed", "Users", "Sessions")));

    // A denylist shape: current overrides no longer produce it, but the evaluator must still run it.
    private static readonly string DenyConfig = Config(
        (TableNameExcept, Vars(SingleTableOps, "excluded", "Secrets")),
        (RequestItemsExcept, Vars(BatchOps, "excluded", "Secrets")),
        (TransactItemsExcept, Vars(TransactOps, "excluded", "Secrets")));

    public static TheoryData<string, string, bool> AllowCases => new()
    {
        { "GetItem", """{"TableName":"Users","Key":{"id":{"S":"1"}}}""", true },
        { "Query", """{"TableName":"Sessions"}""", true },
        { "Scan", """{"TableName":"Users"}""", true },
        { "PutItem", """{"TableName":"Users"}""", true },
        { "UpdateItem", """{"TableName":"Users"}""", true },
        { "DeleteItem", """{"TableName":"Users"}""", true },
        { "GetItem", """{"TableName":"Orders"}""", false },
        { "PutItem", """{"TableName":"Orders"}""", false },
        { "GetItem", """{"Key":{}}""", false },
        { "GetItem", "{}", false },
        { "GetItem", """{"TableName":"arn:aws:dynamodb:us-east-1:123456789012:table/Users"}""", false },
        { "GetItem", """{"TableName":"users"}""", false },
        { "BatchGetItem", """{"RequestItems":{"Users":{},"Sessions":{}}}""", true },
        { "BatchWriteItem", """{"RequestItems":{"Users":[]}}""", true },
        { "BatchGetItem", """{"RequestItems":{"Users":{},"Orders":{}}}""", false },
        { "BatchWriteItem", """{"RequestItems":{"Users":[],"Orders":[]}}""", false },
        { "BatchGetItem", """{"RequestItems":{}}""", false },
        { "BatchGetItem", """{"TableName":"Users"}""", false },
        { "TransactGetItems", """{"TransactItems":[{"Get":{"TableName":"Users","Key":{}}},{"Get":{"TableName":"Sessions","Key":{}}}]}""", true },
        { "TransactWriteItems", """{"TransactItems":[{"Put":{"TableName":"Users"}},{"Delete":{"TableName":"Sessions"}}]}""", true },
        { "TransactGetItems", """{"TransactItems":[{"Get":{"TableName":"Users"}},{"Get":{"TableName":"Orders"}}]}""", false },
        { "TransactWriteItems", """{"TransactItems":[{"Put":{"TableName":"Orders"}}]}""", false },
        { "TransactWriteItems", """{"TransactItems":[{"ConditionCheck":{"Key":{}}}]}""", false },
        { "TransactGetItems", """{"TransactItems":[]}""", false },
        { "TransactGetItems", """{"TableName":"Users"}""", false },
        { "CreateTable", """{"TableName":"Users"}""", false },
    };

    public static TheoryData<string, string, bool> DenyCases => new()
    {
        { "GetItem", """{"TableName":"Users"}""", true },
        { "PutItem", """{"TableName":"Orders"}""", true },
        { "GetItem", """{"TableName":"Secrets"}""", false },
        { "DeleteItem", """{"TableName":"Secrets"}""", false },
        { "GetItem", """{"Key":{}}""", false },

        // Cel's has() is false for an empty or null value, so these go to DynamoDB; Node routes them.
        { "PutItem", """{"TableName":""}""", false },
        { "PutItem", """{"TableName":null}""", false },
        { "BatchGetItem", """{"RequestItems":{"Users":{},"Orders":{}}}""", true },
        { "BatchWriteItem", """{"RequestItems":{"Users":[],"Secrets":[]}}""", false },
        { "BatchGetItem", """{"RequestItems":{}}""", false },
        { "TransactWriteItems", """{"TransactItems":[{"Put":{"TableName":"Users"}},{"Update":{"TableName":"Orders"}}]}""", true },
        { "TransactGetItems", """{"TransactItems":[{"Get":{"TableName":"Users"}},{"Get":{"TableName":"Secrets"}}]}""", false },
        { "TransactWriteItems", """{"TransactItems":[{"ConditionCheck":{"Key":{}}}]}""", false },
        { "TransactGetItems", """{"TransactItems":[]}""", false },
        { "Query", "", false },
        { "DescribeTable", """{"TableName":"Users"}""", false },
    };

    public static TheoryData<string> NotObjectBodies =>
    [
        "not json", "[]", "[\"TableName\"]", "null", "\"text\"", "1", "true",
        """{"TableName":"Users"} trailing""", """{"TableName":"Users",}""", """{/* c */"TableName":"Users"}""",
        """{"TableName":"\ud800"}""",
    ];

    [Fact]
    public void DefaultConfigRoutesTheTenOperations()
    {
        var predicates = Compile(DefaultConfig);

        foreach (var operation in AllOperations)
        {
            Assert.True(predicates.ShouldRoute(Request(operation)), operation);
        }

        foreach (var operation in new[] { "CreateTable", "DescribeTable", "ExecuteStatement", "ListTables" })
        {
            Assert.False(predicates.ShouldRoute(Request(operation)), operation);
        }

        Assert.False(predicates.ShouldRoute(Request("GetItem", service: "s3")));
    }

    [Fact]
    public void ExcludedOperationDoesNotRoute()
    {
        var predicates = Compile(Config((AllOps, Vars(AllOperations.Where(o => o != "Scan").ToArray()))));

        Assert.False(predicates.ShouldRoute(Request("Scan")));
        Assert.True(predicates.ShouldRoute(Request("Query")));
    }

    [Theory]
    [MemberData(nameof(AllowCases))]
    public void AllowlistRoutesOnlyListedTables(string operation, string body, bool expected)
    {
        Assert.Equal(expected, Compile(AllowConfig).ShouldRoute(Request(operation, body)));
    }

    [Theory]
    [MemberData(nameof(DenyCases))]
    public void DenylistRoutesEveryOtherTable(string operation, string body, bool expected)
    {
        Assert.Equal(expected, Compile(DenyConfig).ShouldRoute(Request(operation, body)));
    }

    [Fact]
    public void AllowlistWithoutTheBatchGroupKeepsBatchesOut()
    {
        // {"cached_tables":["Users"]} with every operation but the batch ones in cached_operations
        var predicates = Compile(Config(
            (TableName, Vars(SingleTableOps, "allowed", "Users")),
            (TransactItems, Vars(TransactOps, "allowed", "Users"))));

        Assert.True(predicates.ShouldRoute(Request("GetItem", """{"TableName":"Users"}""")));
        Assert.True(predicates.ShouldRoute(Request("TransactGetItems", """{"TransactItems":[{"Get":{"TableName":"Users"}}]}""")));
        Assert.False(predicates.ShouldRoute(Request("BatchGetItem", """{"RequestItems":{"Users":{}}}""")));
    }

    [Fact]
    public void EmptyAllowlistRoutesNothing()
    {
        // An allowlist with no tables in it.
        var predicates = Compile(Config(
            (TableName, Vars(SingleTableOps, "allowed")),
            (RequestItems, Vars(BatchOps, "allowed")),
            (TransactItems, Vars(TransactOps, "allowed"))));

        Assert.False(predicates.ShouldRoute(Request("GetItem", """{"TableName":"Users"}""")));
        Assert.False(predicates.ShouldRoute(Request("BatchGetItem", """{"RequestItems":{"Users":{}}}""")));
        Assert.False(predicates.ShouldRoute(Request("TransactGetItems", """{"TransactItems":[{"Get":{"TableName":"Users"}}]}""")));
    }

    [Fact]
    public void AnEmptyArrayRoutesNothing()
    {
        Assert.False(Compile("[]").ShouldRoute(Request("GetItem")));
        Assert.False(PredicateEvaluator.Empty.ShouldRoute(Request("GetItem")));
    }

    [Theory]
    [InlineData("null", "predicates is Null, not an array")]
    [InlineData("\"invalid\"", "predicates is String, not an array")]
    [InlineData("{}", "predicates is Object, not an array")]
    public void PredicatesThatAreNotAnArrayAreUnusable(string predicates, string reason)
    {
        var e = Assert.Throws<ElastiCacheIntegrationsProtocolException>(() => Compile(predicates));
        Assert.Equal($"Unusable aws.config reply: {reason}.", e.Message);
    }

    [Theory]
    [InlineData("\"not-an-object\"", "a value that is not an object")]
    [InlineData("42", "a value that is not an object")]
    [InlineData("null", "a value that is not an object")]
    [InlineData("[]", "a value that is not an object")]
    [InlineData("""{"vars": {}}""", "no string expr")]
    [InlineData("""{"expr": 1}""", "no string expr")]
    [InlineData("""{"expr": "operation == 'PutItem'", "vars": null}""", "vars that is not an object")]
    [InlineData("""{"expr": "operation == 'PutItem'", "vars": ["x"]}""", "vars that is not an object")]
    [InlineData("""{"expr": ""}""", "an invalid expression or vars")]
    [InlineData("""{"expr": "this is not (valid CEL"}""", "an invalid expression or vars")]
    public void OneBadEntryMakesTheWholeConfigUnusable(string entry, string reason)
    {
        var e = Assert.Throws<ElastiCacheIntegrationsProtocolException>(() => Compile($$"""[{"expr": "operation == 'GetItem'"}, {{entry}}]"""));

        Assert.Equal($"Unusable aws.config reply: predicate 1 has {reason}.", e.Message);
        Assert.Equal(reason == "an invalid expression or vars", e.InnerException is not null);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("1")]
    [InlineData("1.0")]
    [InlineData("1.7")]
    public void AConfigOfFormatOneCompiles(string? version)
    {
        var config = Resolved(version, """{"predicates":[{"expr":"operation == 'GetItem'"}]}""");

        Assert.True(PredicateEvaluator.Compile(config).ShouldRoute(Request("GetItem")));
    }

    [Theory]
    [InlineData("2.0", """{"predicates":[]}""", "config format 2.0 is not supported; this client reads 1.x")]
    [InlineData("10", """{"predicates":[]}""", "config format 10 is not supported; this client reads 1.x")]
    [InlineData("1.0", "{}", "it has no predicates")]
    public void AConfigWithAnotherFormatOrNoPredicatesIsUnusable(string version, string json, string reason)
    {
        var e = Assert.Throws<ElastiCacheIntegrationsProtocolException>(() => PredicateEvaluator.Compile(Resolved(version, json)));

        Assert.Equal($"Unusable aws.config reply: {reason}.", e.Message);
    }

    [Theory]
    [InlineData("enabled", """{"enabled": true}""", true)]
    [InlineData("enabled", """{"enabled": false}""", false)]
    [InlineData("value", """{"value": 1}""", false)]
    [InlineData("value", """{"value": "non-empty"}""", false)]
    [InlineData("1", "{}", false)]
    [InlineData("'yes'", "{}", false)]
    [InlineData("['GetItem']", "{}", false)]
    [InlineData("{'a': 1}", "{}", false)]
    [InlineData("body.Missing", "{}", false)]
    public void RoutesOnlyWhenTheResultIsExactlyTrue(string expr, string vars, bool expected)
    {
        var predicates = Compile($$"""[{"expr": {{JsonSerializer.Serialize(expr)}}, "vars": {{vars}}}]""");

        Assert.Equal(expected, predicates.ShouldRoute(Request("GetItem")));
    }

    [Fact]
    public void EmptyBodyIsAnEmptyMap()
    {
        Assert.True(Compile(DefaultConfig).ShouldRoute(Request("GetItem", "")));
        Assert.True(Compile(Config(("size(body) == 0", "{}"))).ShouldRoute(Request("GetItem", "")));
    }

    [Theory]
    [MemberData(nameof(NotObjectBodies))]
    public void UnreadableOrNonObjectBodyIsNoMatchOnlyForPredicatesThatReadIt(string body)
    {
        Assert.True(Compile(DefaultConfig).ShouldRoute(Request("GetItem", body)));
        Assert.False(Compile(AllowConfig).ShouldRoute(Request("GetItem", body)));
        Assert.False(Compile(DenyConfig).ShouldRoute(Request("GetItem", body)));
        Assert.False(Compile(Config(("size(body) >= 0", "{}"))).ShouldRoute(Request("GetItem", body)));
    }

    [Fact]
    public void InvalidUtf8BodyIsNoMatchOnlyForPredicatesThatReadIt()
    {
        byte[] body = [.. "{\"TableName\":\"Users\",\"invalid\":\""u8, 0xff, .. "\"}"u8];

        Assert.True(Compile(DefaultConfig).ShouldRoute(Request("GetItem", body)));
        Assert.False(Compile(AllowConfig).ShouldRoute(Request("GetItem", body)));
    }

    [Theory]
    [InlineData(AllOps, false)]
    [InlineData(TableName, true)]
    [InlineData(RequestItems, true)]
    [InlineData(TransactItems, true)]
    [InlineData(TableNameExcept, true)]
    [InlineData(RequestItemsExcept, true)]
    [InlineData(TransactItemsExcept, true)]
    [InlineData("has(body)", true)]
    [InlineData(".body.TableName == 'Users'", true)]
    [InlineData("headers.all(body, body == 'x')", true)]
    [InlineData("'body' in headers", false)]
    [InlineData("headers.body == 'x'", false)]
    public void APredicateReadsTheBodyOnlyWhenItNamesIt(string expr, bool reads)
    {
        // Always true, but a predicate that reads the body cannot match one that is not an object.
        var predicates = Compile(Config(($"operation == 'GetItem' || ({expr})", "{}")));

        Assert.Equal(!reads, predicates.ShouldRoute(Request("GetItem", "[]")));
    }

    [Theory]
    [InlineData("body.TableName == 'Users'")]
    [InlineData("body.TableName.startsWith('U')")]
    public void AVariableNamedBodyHidesTheRequestBody(string expr)
    {
        var predicates = Compile(Config((expr, """{"body": {"TableName": "Users"}}""")));

        Assert.True(predicates.ShouldRoute(Request("GetItem", "not json")));
    }

    [Fact]
    public void ADottedVariableStillCountsAsReadingTheBody()
    {
        var predicates = Compile(Config(("body.TableName == 'Users'", """{"body.TableName": "Users"}""")));

        Assert.True(predicates.ShouldRoute(Request("GetItem", "{}")));
        Assert.False(predicates.ShouldRoute(Request("GetItem", "not json")));
    }

    [Fact]
    public void ABodyFreePredicateListedAfterABodyReaderStillMatchesABadBody()
    {
        var predicates = Compile(Config(
            (TableName, Vars(SingleTableOps, "allowed", "Users")),
            ("operation == 'Query'", "{}")));

        Assert.True(predicates.ShouldRoute(Request("Query", "not json")));
        Assert.False(predicates.ShouldRoute(Request("GetItem", "not json")));
        Assert.True(predicates.ShouldRoute(Request("GetItem", """{"TableName":"Users"}""")));
    }

    [Fact]
    public void ABodyFreeSlowPathPredicateStillGivesABodyReaderItsBody()
    {
        // Neither expression is on the fast path, so both share one Cel context.
        var predicates = Compile(Config(
            ("operation.startsWith('Put')", "{}"),
            ("body.TableName.startsWith('Us')", "{}")));

        Assert.True(predicates.ShouldRoute(Request("GetItem", """{"TableName":"Users"}""")));
        Assert.False(predicates.ShouldRoute(Request("GetItem", """{"TableName":"Orders"}""")));
    }

    [Theory]
    [InlineData("""{"TableName":"Users","Pad":"\ud83d\ude00"}""")]
    [InlineData("""{"TableName":"Users","Pad":"\\ud800"}""")]
    [InlineData("""{"TableName":"U\u0073ers"}""")]
    [InlineData("""{"TableName":"Users","Pad":"é😀"}""")]
    public void EscapedAndNonAsciiStringsAreRead(string body)
    {
        Assert.True(Compile(AllowConfig).ShouldRoute(Request("PutItem", body)));
    }

    [Theory]
    [InlineData("""{"TableName":"Users","Pad":"\udc00"}""")]
    [InlineData("""{"TableName":"Users","Pad":"\uD83D"}""")]
    [InlineData("""{"\ud800":1,"TableName":"Users"}""")]
    public void UnpairedSurrogateEscapeIsNoMatch(string body)
    {
        Assert.False(Compile(AllowConfig).ShouldRoute(Request("PutItem", body)));
        Assert.False(Compile(DenyConfig).ShouldRoute(Request("PutItem", body)));
    }

    [Fact]
    public void LeadingByteOrderMarkIsIgnored()
    {
        byte[] body = [0xEF, 0xBB, 0xBF, .. """{"TableName":"Users"}"""u8];

        Assert.True(Compile(AllowConfig).ShouldRoute(Request("GetItem", body)));
        Assert.False(Compile(AllowConfig).ShouldRoute(Request("GetItem", new byte[] { 0xEF, 0xBB, 0xBF })));
    }

    [Fact]
    public void RepeatedBodyFieldKeepsTheLastValue()
    {
        var predicates = Compile(AllowConfig);

        Assert.True(predicates.ShouldRoute(Request("GetItem", """{"TableName":"Orders","TableName":"Users"}""")));
        Assert.False(predicates.ShouldRoute(Request("GetItem", """{"TableName":"Users","TableName":"Orders"}""")));
    }

    [Fact]
    public void EvaluationErrorIsNoMatchAndLaterPredicatesStillRun()
    {
        var predicates = Compile(
            """
            [
              {"expr": "body.Missing == 'value'"},
              {"expr": "undeclared == 1"},
              {"expr": "headers['absent'] == 'x'"},
              {"expr": "operation == expected && method == 'POST' && url.startsWith(prefix) && headers['x-test'] == 'yes'",
               "vars": {"expected": "GetItem", "prefix": "https://dynamodb."}}
            ]
            """);
        var headers = new Dictionary<string, string> { ["x-test"] = "yes" };

        Assert.True(predicates.ShouldRoute(Request("GetItem", headers: headers)));
        Assert.False(predicates.ShouldRoute(Request("GetItem", headers: headers, url: "https://s3.us-east-1.amazonaws.com/")));
        Assert.False(predicates.ShouldRoute(Request("PutItem", headers: headers)));
        Assert.False(predicates.ShouldRoute(Request("GetItem")));
    }

    [Fact]
    public void VariableWinsOverARequestFieldOfTheSameName()
    {
        var predicates = Compile(Config(("service == 's3' && operation in ops", """{"service": "s3", "ops": ["GetItem"]}""")));

        Assert.True(predicates.ShouldRoute(Request("GetItem")));
        Assert.False(predicates.ShouldRoute(Request("PutItem")));
    }

    [Fact]
    public void BodyValuesKeepTheirJsonTypes()
    {
        var predicates = Compile(Config((
            "body.Limit == 10 && body.Ratio == 0.5 && body.Flag && body.Missing == null && size(body.List) == 2 && body.Huge > 1.0e300",
            "{}")));

        Assert.True(predicates.ShouldRoute(Request("Query", """{"Limit":10,"Ratio":0.5,"Flag":true,"Missing":null,"List":[1,"a"],"Huge":1e400}""")));
    }

    [Theory]
    [InlineData(600 * 1024)]
    [InlineData(1024 * 1024)]
    public void LargeBodyIsInspected(int size)
    {
        const string Prefix = "{\"TableName\":\"Users\",\"Pad\":\"";
        var body = Prefix + new string('x', size - Prefix.Length - 2) + "\"}";

        Assert.Equal(size, Encoding.UTF8.GetByteCount(body));
        Assert.True(Compile(AllowConfig).ShouldRoute(Request("PutItem", body)));
    }

    [Fact]
    public void DeeplyNestedItemIsInspected()
    {
        // A map attribute nested 32 levels, the DynamoDB limit.
        var item = "{\"S\":\"leaf\"}";
        for (var level = 0; level < 32; level++)
        {
            item = "{\"M\":{\"a\":" + item + "}}";
        }

        Assert.True(Compile(AllowConfig).ShouldRoute(Request("PutItem", "{\"TableName\":\"Users\",\"Item\":{\"a\":" + item + "}}")));
        var tooDeep = "{\"TableName\":\"Users\",\"a\":" + string.Concat(Enumerable.Repeat("{\"a\":", 300)) + "1" + new string('}', 301);
        Assert.False(Compile(AllowConfig).ShouldRoute(Request("GetItem", tooDeep)));
    }

    [Fact]
    public async Task EvaluationIsThreadSafe()
    {
        var predicates = Compile(AllowConfig);
        var cases = AllowCases.Select(row => row.Data).ToArray();

        var results = await Task.WhenAll(Enumerable.Range(0, 64).Select(worker => Task.Run(() =>
        {
            for (var i = 0; i < 200; i++)
            {
                var (operation, body, expected) = cases[(worker + i) % cases.Length];
                if (predicates.ShouldRoute(Request(operation, body)) != expected)
                {
                    return false;
                }
            }

            return true;
        })));

        Assert.All(results, Assert.True);
    }

    private static PredicateEvaluator Compile(string predicates)
    {
        // The document is disposed before evaluation, so the evaluator must not hold on to it.
        using var document = JsonDocument.Parse(predicates);
        return PredicateEvaluator.Compile(document.RootElement);
    }

    private static ResolvedConfig Resolved(string? version, string json)
    {
        using var document = JsonDocument.Parse(json);
        return new ResolvedConfig(version, TimeSpan.FromSeconds(60), document.RootElement);
    }

    private static string Config(params (string Expr, string Vars)[] predicates) =>
        "[" + string.Join(",", predicates.Select(p => $$"""{"expr":{{JsonSerializer.Serialize(p.Expr)}},"vars":{{p.Vars}}}""")) + "]";

    private static string Vars(string[] ops, string? listName = null, params string[] tables)
    {
        var vars = new Dictionary<string, string[]> { ["ops"] = ops };
        if (listName is not null)
        {
            vars[listName] = tables;
        }

        return JsonSerializer.Serialize(vars);
    }

    private static ElastiCacheIntegrationsRequest Request(
        string operation,
        string body = "{}",
        string service = "dynamodb",
        string url = "https://dynamodb.us-east-1.amazonaws.com/",
        Dictionary<string, string>? headers = null) =>
        Request(operation, Encoding.UTF8.GetBytes(body), service, url, headers);

    private static ElastiCacheIntegrationsRequest Request(
        string operation,
        byte[] body,
        string service = "dynamodb",
        string url = "https://dynamodb.us-east-1.amazonaws.com/",
        Dictionary<string, string>? headers = null) =>
        new("POST", url, headers ?? [], body, new RequestMetadata(service, operation));
}
