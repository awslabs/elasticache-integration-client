using System.Diagnostics;
using Amazon.DynamoDBv2.Model;
using Amazon.Runtime;

namespace Amazon.Photon.LiveValidation;

/// <summary>With HELLO 3 and async replies, a hit sent after a slow miss on the same connection finishes first.</summary>
internal sealed class PushScenario(LiveContext live)
{
    private const int Trials = 20;

    // Long enough for the miss to be written first: a whole hit round trip takes about 1 ms.
    private static readonly TimeSpan HitDelay = TimeSpan.FromMilliseconds(1);

    // A large item makes the miss slow enough to be overtaken.
    private static readonly string Payload = new('p', 200 * 1024);

    public async Task RunAsync()
    {
        var photon = await live.OpenAsync(new EnvironmentVariablesAWSCredentials());
        live.Check("HELLO 3 was accepted (no event 1204)", live.Events.Count(1204) == 0);

        // A stale read proves the hit key is cached.
        var filled = live.Key("filled");
        await Put(filled, "1");
        await Task.Delay(TimeSpan.FromSeconds(1), live.Stop);
        var fill = (await photon.GetItemAsync(Get(filled))).Item?.GetValueOrDefault("v")?.N;
        await Put(filled, "2");
        await Task.Delay(TimeSpan.FromSeconds(2), live.Stop);
        var cached = (await photon.GetItemAsync(Get(filled))).Item?.GetValueOrDefault("v")?.N;
        live.Check("the hit key is served from the cache", fill == "1" && cached == "1", $"fill v={fill}, Photon v={cached}, DynamoDB v=2");

        // Written ahead, so the eventually consistent miss reads find every item.
        for (var i = 0; i < Trials; i++)
        {
            await Put(live.Key($"fresh-{i}"), "1", Payload);
        }

        await Task.Delay(TimeSpan.FromSeconds(2), live.Stop);
        var hitFirst = 0;
        var missMs = new List<double>();
        var hitMs = new List<double>();
        for (var i = 0; i < Trials; i++)
        {
            var fresh = live.Key($"fresh-{i}");
            var origin = live.Origin.Calls;
            var miss = LiveContext.TimeAsync(() => photon.GetItemAsync(Get(fresh)));
            Spin(HitDelay);
            var hit = LiveContext.TimeAsync(() => photon.GetItemAsync(Get(filled)));
            hitFirst += await Task.WhenAny(miss, hit) == hit ? 1 : 0;
            var (missReply, missTime) = await miss;
            var (hitReply, hitTime) = await hit;
            missMs.Add(missTime);
            hitMs.Add(hitTime);
            if (live.Origin.Calls != origin || missReply.Item?.GetValueOrDefault("data")?.S != Payload || hitReply.Item?.GetValueOrDefault("v")?.N != "1")
            {
                live.Check($"trial {i} is routed through the cache and matches DynamoDB", false,
                    $"origin calls {live.Origin.Calls - origin}, miss has data {missReply.Item?.ContainsKey("data")}, hit v={hitReply.Item?.GetValueOrDefault("v")?.N}");
            }
        }

        // The miss must take far longer than the gap, or the hit could have been written first.
        live.Check("a hit overtakes an earlier miss: async pushes arrive", hitFirst >= Trials * 3 / 4 && Median(missMs) > 4 * HitDelay.TotalMilliseconds,
            $"hit first in {hitFirst}/{Trials}, hit started {HitDelay.TotalMilliseconds:F0} ms after the miss, " +
            $"median miss {Median(missMs):F1} ms, median hit {Median(hitMs):F1} ms");
        live.Check("no late push was dropped (no event 1203)", live.Events.Count(1203) == 0);
    }

    private Task<PutItemResponse> Put(string key, string v, string? data = null)
    {
        var item = new Dictionary<string, AttributeValue> { ["pk"] = new() { S = key }, ["v"] = new() { N = v } };
        if (data is not null)
        {
            item["data"] = new() { S = data };
        }

        return live.Plain.PutItemAsync(new PutItemRequest { TableName = live.Table, Item = item });
    }

    private GetItemRequest Get(string key) => new() { TableName = live.Table, Key = new() { ["pk"] = new() { S = key } } };

    // Task.Delay can take several milliseconds to resume; a spin is exact.
    private static void Spin(TimeSpan delay)
    {
        var start = Stopwatch.GetTimestamp();
        while (Stopwatch.GetElapsedTime(start) < delay)
        {
            Thread.SpinWait(20);
        }
    }

    private static double Median(List<double> values) => values.Order().ElementAt(values.Count / 2);
}
