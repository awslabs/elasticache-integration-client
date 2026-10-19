using System.Diagnostics;
using Amazon.DynamoDBv2.Model;
using Amazon.Runtime;

namespace Amazon.ElastiCache.Integrations.LiveValidation;

/// <summary>
/// Measures how long the cache's FAS lasts: no re-AUTH, one miss per poll, until a miss fails. The
/// cache may relay DynamoDB's <c>ExpiredTokenException</c>, or answer <c>-AUTH_ERROR</c>, which the
/// client logs as event 1103 and sends to DynamoDB. The items are written up front, so polling needs
/// no credentials of its own and can run past their expiry.
/// </summary>
internal sealed class FasScenario(LiveContext live)
{
    // How long to keep polling for AUTH_ERROR after the first failed miss.
    private static readonly TimeSpan AfterFailure = TimeSpan.FromMinutes(5);

    public async Task RunAsync()
    {
        var limit = TimeSpan.FromMinutes(Number("ELASTICACHE_INTEGRATIONS_FAS_MINUTES", 80));
        var poll = TimeSpan.FromSeconds(Number("ELASTICACHE_INTEGRATIONS_FAS_POLL_SECONDS", 15));
        var polls = (int)(limit / poll) + 1;
        await SeedAsync(polls);

        // Under 12 h, the most the client accepts.
        var reauth = Number("ELASTICACHE_INTEGRATIONS_FAS_REAUTH_MINUTES", 0) is var minutes and > 0 and < 720 ? TimeSpan.FromMinutes(minutes) : TimeSpan.FromHours(11);
        var viaCache = await live.OpenAsync(new EnvironmentVariablesAWSCredentials(), reauthInterval: reauth);
        var since = Stopwatch.StartNew();
        live.Log($"authenticated at {DateTime.UtcNow:O}, credentials expire {Environment.GetEnvironmentVariable("ELASTICACHE_INTEGRATIONS_CREDENTIALS_EXPIRE") ?? "unknown"}; " +
            $"re-AUTH every {reauth.TotalMinutes:F0} min, polling every {poll.TotalSeconds:F0} s for up to {limit.TotalMinutes:F0} min");

        TimeSpan? lastRouted = null;
        (TimeSpan At, string Error)? firstFailure = null;
        for (var i = 0; i < polls && !(since.Elapsed > firstFailure?.At + AfterFailure); i++)
        {
            var origin = live.Origin.Calls;
            Exception? failed = null;
            GetItemResponse? reply = null;
            try
            {
                reply = await viaCache.GetItemAsync(new GetItemRequest { TableName = live.Table, Key = new() { ["pk"] = new() { S = Key(i) } } });
            }
            catch (Exception e) when (!live.Stop.IsCancellationRequested)
            {
                failed = e;
            }

            if (live.Events.Count(1103) > 0)
            {
                live.Log($"first AUTH_ERROR at {DateTime.UtcNow:O}, {since.Elapsed:hh\\:mm\\:ss} after AUTH");
                break;
            }

            if (failed is null && live.Origin.Calls == origin && reply?.Item?.GetValueOrDefault("pk")?.S == Key(i))
            {
                lastRouted = since.Elapsed;
            }
            else
            {
                var error = failed is null ? $"origin calls {live.Origin.Calls - origin}" : $"{failed.GetType().Name}: {failed.Message}";
                live.Log($"poll {i} failed {since.Elapsed:hh\\:mm\\:ss} after AUTH: {error}");
                firstFailure ??= (since.Elapsed, error);
            }

            if (i % 20 == 0)
            {
                live.Log($"poll {i} at {since.Elapsed:hh\\:mm\\:ss} after AUTH");
            }

            await Task.Delay(poll, live.Stop);
        }

        var last = lastRouted is { } t ? t.ToString("hh\\:mm\\:ss") : "never";
        var stable = live.Events.Count(1201) == 0 && live.Events.Count(1303) == 0;
        live.Check("the connection stayed up, so the FAS was never replaced", stable,
            $"ConnectionLost {live.Events.Count(1201)}, Reconnecting {live.Events.Count(1303)}");
        if (firstFailure is null && live.Events.Count(1103) == 0)
        {
            live.Log($"no failure in {since.Elapsed:hh\\:mm\\:ss}: the FAS outlived the run; last miss routed through the cache {last} after AUTH");
            return;
        }

        live.Log($"FAS ended: last miss routed through the cache {last} after AUTH; " +
            $"first failure {(firstFailure is { } f ? $"{f.At:hh\\:mm\\:ss} after AUTH ({f.Error})" : "none")}; " +
            $"AUTH_ERROR (1103) {(live.Events.Count(1103) > 0 ? "logged" : "not logged")}");
        if (live.Events.Count(1103) > 0)
        {
            var recovered = await live.WaitUntilAsync(() => live.Events.Count(1104) > 0, TimeSpan.FromSeconds(10));
            live.Log($"re-AUTH after AUTH_ERROR {(recovered ? "succeeded" : "did not succeed within 10 s")}");
        }
    }

    private string Key(int i) => live.Key($"fas-{i}");

    private async Task SeedAsync(int count)
    {
        for (var first = 0; first < count; first += 25)
        {
            var writes = Enumerable.Range(first, Math.Min(25, count - first))
                .Select(i => new WriteRequest { PutRequest = new PutRequest { Item = new() { ["pk"] = new() { S = Key(i) } } } })
                .ToList();
            var request = new BatchWriteItemRequest { RequestItems = new() { [live.Table] = writes } };
            for (var attempt = 0; request.RequestItems.Count > 0; attempt++)
            {
                if (attempt > 0)
                {
                    if (attempt > 8)
                    {
                        throw new InvalidOperationException("DynamoDB kept returning unprocessed items.");
                    }

                    await Task.Delay(TimeSpan.FromMilliseconds(100 << attempt), live.Stop);
                }

                request.RequestItems = (await live.Plain.BatchWriteItemAsync(request)).UnprocessedItems ?? [];
            }
        }

        // The cache's reads are eventually consistent; give the writes time to reach every replica.
        await Task.Delay(TimeSpan.FromSeconds(2), live.Stop);
        live.Log($"wrote {count} items for the polls");
    }

    private static int Number(string name, int fallback) =>
        int.TryParse(Environment.GetEnvironmentVariable(name), out var value) && value > 0 ? value : fallback;
}
