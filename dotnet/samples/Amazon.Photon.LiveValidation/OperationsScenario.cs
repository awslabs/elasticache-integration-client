using Amazon.DynamoDBv2.Model;
using Amazon.Runtime;

namespace Amazon.Photon.LiveValidation;

/// <summary>The ten operations, a pass-through call, a hit, a miss and an invalidating write, each compared with DynamoDB.</summary>
internal sealed class OperationsScenario(LiveContext live)
{
    private string Table => live.Table;

    public async Task RunAsync()
    {
        var credentials = new EnvironmentVariablesAWSCredentials();
        var (photon, openMs) = await LiveContext.TimeAsync(() => live.OpenAsync(credentials));
        live.Check("AUTH accepts the client's IAM token", photon.Handle.IsOpen, $"bring-up {openMs:F0} ms");
        var upper = await live.OpenAsync(credentials, cacheName: live.CacheName.ToUpperInvariant());
        live.Check("AUTH accepts the token for an upper-case cache name", upper.Handle.IsOpen);

        string a = live.Key("a"), b = live.Key("b"), c = live.Key("c"), d = live.Key("d"), e = live.Key("e");
        foreach (var key in new[] { a, b, c })
        {
            await live.Plain.PutItemAsync(new PutItemRequest { TableName = Table, Item = Item(key, 1) });
        }

        // The cache's reads are eventually consistent; give the writes time to reach every replica.
        await Task.Delay(TimeSpan.FromSeconds(1), live.Stop);

        var (missed, missMs) = await LiveContext.TimeAsync(() => photon.GetItemAsync(Get(a)));
        await SameAsync("GetItem miss", missed.Item, a, $"{missMs:F1} ms");
        var (again, againMs) = await LiveContext.TimeAsync(() => photon.GetItemAsync(Get(a)));
        await SameAsync("GetItem second read", again.Item, a, $"{againMs:F1} ms");

        // The cache does not see a direct write, so the old value through Photon is a hit.
        await live.Plain.UpdateItemAsync(SetV(a, 2));
        var direct = V((await live.Plain.GetItemAsync(Get(a, consistent: true))).Item);
        await Task.Delay(TimeSpan.FromSeconds(2), live.Stop);
        var cached = V((await photon.GetItemAsync(Get(a))).Item);
        live.Check("a read after a direct write is served from the cache", direct == "2" && cached == "1", $"Photon v={cached}, DynamoDB v={direct}");

        await photon.UpdateItemAsync(SetV(a, 3));
        await SameAsync("UpdateItem invalidates", (await photon.GetItemAsync(Get(a))).Item, a);

        await photon.GetItemAsync(Get(b));
        await photon.PutItemAsync(new PutItemRequest { TableName = Table, Item = Item(b, 10) });
        await SameAsync("PutItem updates the cached item", (await photon.GetItemAsync(Get(b))).Item, b);

        await photon.GetItemAsync(Get(c));
        await photon.DeleteItemAsync(new DeleteItemRequest { TableName = Table, Key = Key(c) });
        await SameAsync("DeleteItem invalidates", (await photon.GetItemAsync(Get(c))).Item, c);

        QueryRequest Query(bool consistent) => new()
        {
            TableName = Table,
            ConsistentRead = consistent,
            KeyConditionExpression = "pk = :k",
            ExpressionAttributeValues = new() { [":k"] = S(a) },
        };
        var queried = (await photon.QueryAsync(Query(false))).Items;
        Same("Query", Canon.Items(queried), Canon.Items((await live.Plain.QueryAsync(Query(true))).Items), Canon.Keys(queried), a);

        ScanRequest Scan(bool consistent) => new()
        {
            TableName = Table,
            ConsistentRead = consistent,
            FilterExpression = "begins_with(pk, :p)",
            ExpressionAttributeValues = new() { [":p"] = S(live.Key("")) },
        };
        var scanned = (await photon.ScanAsync(Scan(false))).Items;
        Same("Scan", Canon.Items(scanned), Canon.Items((await live.Plain.ScanAsync(Scan(true))).Items), Canon.Keys(scanned), $"{a},{b}");

        BatchGetItemRequest BatchGet(bool consistent) => new()
        {
            RequestItems = new() { [Table] = new KeysAndAttributes { Keys = [Key(a), Key(b)], ConsistentRead = consistent } },
        };
        var batched = (await photon.BatchGetItemAsync(BatchGet(false))).Responses?.GetValueOrDefault(Table);
        Same("BatchGetItem", Canon.Items(batched), Canon.Items((await live.Plain.BatchGetItemAsync(BatchGet(true))).Responses?.GetValueOrDefault(Table)),
            Canon.Keys(batched), $"{a},{b}");

        await photon.BatchWriteItemAsync(new BatchWriteItemRequest
        {
            RequestItems = new()
            {
                [Table] =
                [
                    new WriteRequest { PutRequest = new PutRequest { Item = Item(d, 20) } },
                    new WriteRequest { DeleteRequest = new DeleteRequest { Key = Key(b) } },
                ],
            },
        });
        await SameAsync("BatchWriteItem delete invalidates", (await photon.GetItemAsync(Get(b))).Item, b);
        await SameAsync("BatchWriteItem put", (await photon.GetItemAsync(Get(d))).Item, d);

        var transactGet = new TransactGetItemsRequest
        {
            TransactItems = [new TransactGetItem { Get = new Get { TableName = Table, Key = Key(a) } }, new TransactGetItem { Get = new Get { TableName = Table, Key = Key(d) } }],
        };
        var transacted = (await photon.TransactGetItemsAsync(transactGet)).Responses.Select(r => r.Item).ToList();
        Same("TransactGetItems", Canon.Items(transacted), Canon.Items((await live.Plain.TransactGetItemsAsync(transactGet)).Responses.Select(r => r.Item)),
            Canon.Keys(transacted), $"{a},{d}");

        await photon.TransactWriteItemsAsync(new TransactWriteItemsRequest
        {
            TransactItems =
            [
                new TransactWriteItem { Update = new Update { TableName = Table, Key = Key(a), UpdateExpression = "SET v = :v", ExpressionAttributeValues = new() { [":v"] = N(4) } } },
                new TransactWriteItem { Put = new Put { TableName = Table, Item = Item(e, 30) } },
            ],
        });
        await SameAsync("TransactWriteItems invalidates", (await photon.GetItemAsync(Get(a))).Item, a);
        await SameAsync("TransactWriteItems put", (await photon.GetItemAsync(Get(e))).Item, e);

        live.Check("the ten operations were all routed through the cache (no origin call)", live.Origin.Calls == 0, $"origin calls {live.Origin.Calls}");
        var described = await photon.DescribeTableAsync(Table);
        live.Check("DescribeTable passes through to DynamoDB", live.Origin.Calls == 1, $"origin calls {live.Origin.Calls}");
        Same("DescribeTable", described.Table.TableArn, (await live.Plain.DescribeTableAsync(Table)).Table.TableArn);

        live.Check("the cache config was applied", live.Events.Count(1301) > 0, live.Events.Message(1301) ?? "");
        live.Check("the cache config is fully usable (no event 1307)", live.Events.Count(1307) == 0);
        live.Check("HELLO 3 was accepted (no event 1204)", live.Events.Count(1204) == 0);
    }

    private async Task SameAsync(string name, Dictionary<string, AttributeValue>? photon, string key, string detail = "") =>
        Same(name, Canon.Item(photon), Canon.Item((await live.Plain.GetItemAsync(Get(key, consistent: true))).Item), detail);

    private void Same(string name, string photon, string dynamodb, string detail = "") =>
        live.Check(name, photon == dynamodb, photon == dynamodb ? detail : $"Photon {photon}, DynamoDB {dynamodb}");

    /// <summary>Like the other overload, and the reply must hold exactly <paramref name="expectedKeys"/>.</summary>
    private void Same(string name, string photon, string dynamodb, string keys, string expectedKeys)
    {
        var expected = string.Join(",", expectedKeys.Split(',').Order(StringComparer.Ordinal));
        live.Check(name, photon == dynamodb && keys == expected,
            photon != dynamodb ? $"Photon {photon}, DynamoDB {dynamodb}" : keys == expected ? $"keys {keys}" : $"keys {keys}, expected {expected}");
    }

    private GetItemRequest Get(string key, bool consistent = false) => new() { TableName = Table, Key = Key(key), ConsistentRead = consistent };

    private UpdateItemRequest SetV(string key, int v) => new()
    {
        TableName = Table,
        Key = Key(key),
        UpdateExpression = "SET v = :v",
        ExpressionAttributeValues = new() { [":v"] = N(v) },
    };

    private static Dictionary<string, AttributeValue> Key(string key) => new() { ["pk"] = S(key) };

    private static Dictionary<string, AttributeValue> Item(string key, int v) => new()
    {
        ["pk"] = S(key),
        ["v"] = N(v),
        ["tags"] = new AttributeValue { SS = ["x", "y"] },
        ["m"] = new AttributeValue { M = new() { ["n"] = N(v * 10), ["l"] = new AttributeValue { L = [S("z"), new AttributeValue { BOOL = true }] } } },
    };

    private static string? V(Dictionary<string, AttributeValue>? item) => item?.GetValueOrDefault("v")?.N;

    private static AttributeValue S(string s) => new() { S = s };

    private static AttributeValue N(int n) => new() { N = n.ToString(System.Globalization.CultureInfo.InvariantCulture) };
}
