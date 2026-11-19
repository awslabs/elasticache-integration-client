using System.Text;
using System.Text.Json;
using Cel;

namespace Amazon.ElastiCache.Integrations.Tests;

public class FastPredicateTests
{
    // The expressions the proxy emits, verbatim.
    private static readonly string[] Proxy =
    [
        "service == 'dynamodb' && operation in ops",
        "service == 'dynamodb' && operation in ops && has(body.TableName) && body.TableName in allowed",
        "service == 'dynamodb' && operation in ops && has(body.RequestItems) && size(body.RequestItems) > 0 && body.RequestItems.all(t, t in allowed)",
        "service == 'dynamodb' && operation in ops && has(body.TransactItems) && size(body.TransactItems) > 0 && body.TransactItems.all(i, i.all(k, has(i[k].TableName) && i[k].TableName in allowed))",
        "service == 'dynamodb' && operation in ops && has(body.TransactItems) && size(body.TransactItems) > 0 && body.TransactItems.all(i, i.all(k, \"TableName\" in i[k] && i[k].TableName in allowed))",
        "service == 'dynamodb' && operation in ops && has(body.RequestItems) && size(body.RequestItems) > 0 && !body.RequestItems.all(t, !(t in allowed))",
        "service == 'dynamodb' && operation in ops && has(body.TransactItems) && size(body.TransactItems) > 0 && !body.TransactItems.all(i, i.all(k, !(\"TableName\" in i[k] && i[k].TableName in allowed)))",
        "service == 'dynamodb' && operation in ops && has(body.TableName) && !(body.TableName in excluded)",
        "service == 'dynamodb' && operation in ops && has(body.RequestItems) && size(body.RequestItems) > 0 && body.RequestItems.all(t, !(t in excluded))",
        "service == 'dynamodb' && operation in ops && has(body.TransactItems) && size(body.TransactItems) > 0 && body.TransactItems.all(i, i.all(k, has(i[k].TableName) && !(i[k].TableName in excluded)))",
    ];

    private static readonly string[] Core =
    [
        "body.F", "!body.F", "!!body.F", "!body.X", "!body.M", "(body.F)", "body.F && body.G", "body.F || body.G",
        "body.F && body.G || flag", "body.F ? body.G : flag", "body.M.k", "body.a.b.c", "body.X.Y", "body", "true",
        "false || body.F", "null", "'text'", "flag", "!flag", "on.flag", "on.missing", "dotted.name", "body.Y", "headers.x",
        "method",
    ];

    private static readonly string[] Relations =
    [
        "body.X == null", "null == body.X", "body.X != 'a'", "body.X == body.Y", "body.X == 1", "body.F == true", "body.N == null",
        "body.M.k == 1", "body.M.k == 'a'", "body.L == body.L", "body.a.b.c == 'x'", "dotted.name == 'v'", "1 < 2", "2 <= 1 || body.F",
        "body.TableName in body.RequestItems", "body.TableName in body.L", "'a' in body.L", "'k' in body.M", "'' in body.M",
        "body.X in allowed", "body.X in mixed", "body.X in on", "body.F ? body.X == 'a' : body.Y == 'a'", "body.X == 'a' || body.Y == 'a'",
        "body.L[0] == 'a'", "body.L[1]", "body.M['k'] == 'a'", "body.M[body.X] == null", "body.TransactItems[0].Put.TableName == 'Users'",
        "headers['x-test'] == 'yes'", "headers['x-test'] != 'no'", "method == 'POST' && url != ''", "operation in ops || service == 'x'",
    ];

    private static readonly string[] Macros =
    [
        "has(body)", "has(body.X)", "has(body.M.k)", "has(body.M['k'])", "has(body.M[body.X])", "has(nested.inner[0])", "has(size(body.L))",
        "size(body.RequestItems) == 2", "size(body.L) >= 1", "size(body.M) < 2", "size(body.S) > 0", "size(body.L) > size(body.M)",
        "size(body.L) == size(body.M)", "body.L.all(x, x == 'a')", "body.M.all(k, k != '')", "body.L.all(x, x.all(y, y == 'a'))",
        "body.TransactItems.all(i, has(i.Put))", "body.M.k.all(x, true)", "allowed.all(t, t != body.X)", "nested.inner.all(x, has(x))",
        "body.L.all(body, body == 'a')", "body.L.all(ops, ops == 'a')", "allowed.all(x, body.L.all(x, x == 'a'))", "on.all(k, k == 'flag')",
    ];

    private static readonly string[] Keys = ["TableName", "RequestItems", "TransactItems", "N", "F", "G", "X", "Y", "L", "M", "S", "a"];

    private static readonly string[] Atoms =
    [
        "null", "true", "false", "0", "1", "7", "1.5", "-2", "1e400", "\"\"", "\"a\"", "\"Users\"", "\"Secrets\"", "\"\\u0041\"",
        "[]", "[\"a\"]", "[\"a\",\"Users\"]", "[1,\"a\",null]", "[true]", "[[\"a\"]]", "[{}]", "{}", "{\"k\":1}", "{\"k\":\"a\",\"k\":1}",
        "{\"\":1}", "{\"Users\":{},\"Sessions\":[]}", "{\"Users\":{},\"Secrets\":{}}", "{\"Users\":{},\"Users\":[]}",
        "[{\"Put\":{\"TableName\":\"Users\"}}]", "[{\"Put\":{\"TableName\":\"Secrets\"}}]", "[{\"Get\":{\"TableName\":\"\"}}]",
        "[{\"Put\":{\"TableName\":1}}]", "[{\"Put\":{}}]", "[{\"Put\":null}]", "[{\"Put\":{\"TableName\":\"Users\"},\"Put\":{\"TableName\":\"Secrets\"}}]",
        "[{\"Put\":{\"TableName\":\"Users\"}},{\"Delete\":{\"TableName\":\"Sessions\"}}]", "[\"Users\"]", "[[]]", "{\"b\":{\"c\":\"x\"}}", "{\"k\":[\"a\"]}", "{\"k\":\"a\"}",
    ];

    private static readonly Dictionary<string, object?> Vars = PredicateEvaluator.ToMap(JsonDocument.Parse(
        """
        {"ops":["GetItem","PutItem","TransactWriteItems","BatchGetItem"],"allowed":["Users","Sessions"],"excluded":["Secrets"],
         "mixed":["a",1,null,true],"nested":{"inner":[1,1]},"dotted.name":"v","body.Y":"shadow","flag":true,"on":{"flag":true}}
        """).RootElement);

    public static TheoryData<string> AllExpressions => [.. Proxy, .. Core, .. Relations, .. Macros];

    [Theory]
    [MemberData(nameof(AllExpressions))]
    public void CoveredExpressionsCompile(string expr)
    {
        Assert.NotNull(Compile(expr));
    }

    [Theory]
    [InlineData("url.startsWith('https')")]
    [InlineData("body.X in ['a', 'b']")]
    [InlineData("{'a': 1}.a == 1")]
    [InlineData("body.N + 1 == 2")]
    [InlineData("-body.N == 1")]
    [InlineData("body.N == 1.5")]
    [InlineData("body.N == 1u")]
    [InlineData("body.?X == 'a'")]
    [InlineData("body.L[?0] == 'a'")]
    [InlineData("google.protobuf.NullValue.NULL_VALUE == 0")]
    [InlineData("protobuf.NullValue.NULL_VALUE == 0")]
    [InlineData(".service == 'dynamodb'")]
    [InlineData("undeclared == 1")]
    [InlineData("body.L.exists(x, x == 'a')")]
    [InlineData("body.L.all(x.y, true)")]
    [InlineData("has(body.X, body.Y)")]
    [InlineData("int(body.N) == 1")]
    public void UncoveredExpressionsUseCelOnly(string expr)
    {
        Assert.Null(Compile(expr));
    }

    [Fact]
    public void ProxyExpressionsNeverDeferOnRequestBodies()
    {
        foreach (var body in ProxyBodies())
        {
            foreach (var expr in Proxy)
            {
                Assert.NotNull(Compile(expr)!.Matches(View("TransactWriteItems", body)));
            }
        }
    }

    [Fact]
    public void SizeIsNotAnIndex()
    {
        // Cel's size() is an int, and Cel accepts only a long index.
        const string Expr = "body.L[size(body.M)] == 'a'";
        const string Body = """{"L":["x","a"],"M":{"k":1}}""";
        var view = View("GetItem", Body);

        Assert.Null(Compile(Expr)!.Matches(view));
        Assert.False(CelMatches(new CelEnvironment(null, null).Compile(Expr), new(view.Context)));
    }

    [Fact]
    public void AgreesWithCelOrDefers()
    {
        var environment = new CelEnvironment(null, null);
        var programs = AllExpressions.Select(row => (Expr: row.Data, Fast: Compile(row.Data)!, Cel: environment.Compile(row.Data))).ToArray();
        var answered = programs.ToDictionary(p => p.Expr, _ => 0);
        foreach (var body in Bodies())
        {
            foreach (var operation in new[] { "GetItem", "Scan" })
            {
                var view = View(operation, body);
                var activation = new Dictionary<string, object?>(view.Context, StringComparer.Ordinal);
                foreach (var (name, value) in Vars)
                {
                    activation[name] = value;
                }

                foreach (var (expr, fast, cel) in programs)
                {
                    var actual = fast.Matches(view);
                    if (actual is null)
                    {
                        continue;
                    }

                    answered[expr]++;
                    Assert.True(actual == CelMatches(cel, activation), $"{expr} on {body}: fast {actual}");
                }
            }
        }

        // Every expression must answer some bodies, or its comparison proves nothing.
        var never = answered.Where(a => a.Value == 0).Select(a => a.Key).ToList();
        Assert.True(never.Count == 0, string.Join(" | ", never));
    }

    private static bool CelMatches(CelProgramDelegate program, Dictionary<string, object?> activation)
    {
        try
        {
            return program(activation) is true;
        }
        catch (Exception)
        {
            return false;
        }
    }

    private static FastPredicate? Compile(string expr)
    {
        var environment = new CelEnvironment(null, null);
        return FastPredicate.TryCompile(environment, environment.Parse(expr), Vars);
    }

    private static PredicateRequest View(string operation, string body)
    {
        var request = new ElastiCacheIntegrationsRequest(
            "POST",
            "https://dynamodb.us-east-1.amazonaws.com/",
            new Dictionary<string, string> { ["x-test"] = "yes" },
            Encoding.UTF8.GetBytes(body),
            new RequestMetadata("dynamodb", operation));

        // Each body parses once per test run; the documents live for the test process.
        return new PredicateRequest(request, JsonDocument.Parse(body).RootElement);
    }

    private static IEnumerable<string> ProxyBodies()
    {
        foreach (var name in new[] { "Users", "Secrets", "Orders" })
        {
            yield return "{\"TableName\":\"" + name + "\",\"Key\":{\"id\":{\"S\":\"1\"}}}";
            yield return "{\"RequestItems\":{\"Users\":{\"Keys\":[]},\"" + name + "\":{\"Keys\":[]}}}";
            yield return "{\"TransactItems\":[{\"Put\":{\"TableName\":\"Users\",\"Item\":{}}},{\"ConditionCheck\":{\"TableName\":\"" + name + "\"}}]}";
        }

        yield return """{"TransactItems":[{"ConditionCheck":{"Key":{}}}]}""";
        yield return """{"RequestItems":{}}""";
        yield return "{}";
    }

    private static IEnumerable<string> Bodies()
    {
        var random = new Random(6303);
        for (var i = 0; i < 400; i++)
        {
            var fields = Keys.Where(_ => random.Next(3) > 0).Select(key => $"\"{key}\":{Atoms[random.Next(Atoms.Length)]}");
            yield return "{" + string.Join(",", fields) + "}";
        }
    }
}
