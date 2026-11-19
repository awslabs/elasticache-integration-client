// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using System.Diagnostics;
using System.Text;
using System.Text.Json;
using Amazon.ElastiCache.Integrations;
using Cel;

// Cost of one routing decision, per proxy expression and body size. Run from dotnet/:
//   dotnet run -c Release --project bench/Amazon.ElastiCache.Integrations.Benchmarks [-- <case filter>]
// Columns, in microseconds, the median of 20 rounds after a 1 s warm-up:
//   route  PredicateEvaluator.ShouldRoute, body parse included when the predicate reads body
//   eval   the predicate alone over a parsed body
//   cel    the Cel program alone over a converted body
//   parse  JsonDocument.Parse of the body

// The expressions the proxy emits, verbatim.
const string AllOps = "service == 'dynamodb' && operation in ops";
const string TableName = "service == 'dynamodb' && operation in ops && has(body.TableName) && body.TableName in allowed";
const string RequestItems = "service == 'dynamodb' && operation in ops && has(body.RequestItems) && size(body.RequestItems) > 0 && body.RequestItems.all(t, t in allowed)";
const string TransactItems = "service == 'dynamodb' && operation in ops && has(body.TransactItems) && size(body.TransactItems) > 0 && body.TransactItems.all(i, i.all(k, has(i[k].TableName) && i[k].TableName in allowed))";
const string TableNameExcept = "service == 'dynamodb' && operation in ops && has(body.TableName) && !(body.TableName in excluded)";
const string RequestItemsExcept = "service == 'dynamodb' && operation in ops && has(body.RequestItems) && size(body.RequestItems) > 0 && body.RequestItems.all(t, !(t in excluded))";
const string TransactItemsExcept = "service == 'dynamodb' && operation in ops && has(body.TransactItems) && size(body.TransactItems) > 0 && body.TransactItems.all(i, i.all(k, has(i[k].TableName) && !(i[k].TableName in excluded)))";

string[] ops = ["GetItem", "BatchGetItem", "Query", "Scan", "PutItem", "UpdateItem", "DeleteItem", "BatchWriteItem", "TransactGetItems", "TransactWriteItems"];
string[] users = ["Users", "Sessions"];
string[] secrets = ["Secrets"];

var cases = new List<(string Name, string Expr, Dictionary<string, object> Vars, string Operation, string Body)>
{
    ("all operations", AllOps, Vars(), "GetItem", Single()),
    ("all operations x100", AllOps, Vars(), "TransactWriteItems", Transact(100)),
    ("TableName allow", TableName, Vars("allowed", users), "GetItem", Single()),
    ("TableName deny", TableNameExcept, Vars("excluded", secrets), "GetItem", Single()),
};
foreach (var n in new[] { 1, 10, 100 })
{
    var tables = Enumerable.Range(0, n).Select(Table).ToArray();
    cases.Add(($"RequestItems allow x{n}", RequestItems, Vars("allowed", tables), "BatchGetItem", Batch(n)));
    cases.Add(($"RequestItems deny x{n}", RequestItemsExcept, Vars("excluded", secrets), "BatchGetItem", Batch(n)));
}

foreach (var n in new[] { 1, 10, 100 })
{
    cases.Add(($"TransactItems allow x{n}", TransactItems, Vars("allowed", users), "TransactWriteItems", Transact(n)));
    cases.Add(($"TransactItems deny x{n}", TransactItemsExcept, Vars("excluded", secrets), "TransactWriteItems", Transact(n)));
}

var filter = args.Length > 0 ? args[0] : "";
Console.WriteLine($"{"case",-26} {"bytes",6} {"route",8} {"eval",8} {"cel",8} {"parse",8}");
foreach (var (name, expr, vars, operation, body) in cases.Where(c => c.Name.Contains(filter, StringComparison.OrdinalIgnoreCase)))
{
    using var config = JsonDocument.Parse(JsonSerializer.Serialize(new[] { new { expr, vars } }));
    var variables = PredicateEvaluator.ToMap(config.RootElement[0].GetProperty("vars"));
    var bytes = Encoding.UTF8.GetBytes(body);
    var request = new ElastiCacheIntegrationsRequest("POST", "https://dynamodb.us-east-1.amazonaws.com/", new Dictionary<string, string>(), bytes, new RequestMetadata("dynamodb", operation));
    using var parsed = JsonDocument.Parse(bytes);
    var view = new PredicateRequest(request, parsed.RootElement);

    var evaluator = PredicateEvaluator.Compile(config.RootElement);
    var environment = new CelEnvironment(null, null);
    var tree = environment.Parse(expr);
    var fast = FastPredicate.TryCompile(environment, tree, variables);
    var program = environment.Compile(tree);
    var activation = new Dictionary<string, object?>(view.Context, StringComparer.Ordinal);
    foreach (var (key, value) in variables)
    {
        activation[key] = value;
    }

    var route = Measure(() => evaluator.ShouldRoute(request));
    var eval = fast is null ? double.NaN : Measure(() => fast.Matches(view) == true);
    var cel = Measure(() => program(activation) is true);
    var parse = Measure(() =>
    {
        using var document = JsonDocument.Parse(bytes);
        return true;
    });
    Console.WriteLine($"{name,-26} {bytes.Length,6} {route,8:F2} {eval,8:F2} {cel,8:F2} {parse,8:F2}");
}

Dictionary<string, object> Vars(string? list = null, string[]? tables = null)
{
    var vars = new Dictionary<string, object> { ["ops"] = ops };
    if (list is not null)
    {
        vars[list] = tables!;
    }

    return vars;
}

static string Table(int i) => i == 0 ? "Users" : $"Table{i}";

static string Single() => """{"TableName":"Users","Key":{"id":{"S":"user-1"}}}""";

static string Batch(int n) =>
    "{\"RequestItems\":{" + string.Join(",", Enumerable.Range(0, n).Select(i => $"\"{Table(i)}\":{{\"Keys\":[{{\"id\":{{\"S\":\"k{i}\"}}}}]}}")) + "}}";

static string Transact(int n) =>
    "{\"TransactItems\":[" + string.Join(",", Enumerable.Range(0, n).Select(i =>
        $"{{\"Put\":{{\"TableName\":\"{(i % 2 == 0 ? "Users" : "Sessions")}\",\"Item\":{{\"id\":{{\"S\":\"k{i}\"}},\"v\":{{\"S\":\"value-{i}\"}}}}}}}}")) + "]}";

// Median time of one call over 20 rounds, after a 1 s warm-up. The call must route.
static double Measure(Func<bool> call)
{
    if (!call())
    {
        throw new InvalidOperationException("The case does not route.");
    }

    var watch = Stopwatch.StartNew();
    long calls = 0;
    while (watch.ElapsedMilliseconds < 1000)
    {
        call();
        calls++;
    }

    var perRound = Math.Max(1, calls / 20);
    var rounds = new double[20];
    for (var r = 0; r < rounds.Length; r++)
    {
        watch.Restart();
        for (long i = 0; i < perRound; i++)
        {
            call();
        }

        rounds[r] = watch.Elapsed.TotalMicroseconds / perRound;
    }

    Array.Sort(rounds);
    return rounds[rounds.Length / 2];
}
