using Amazon.Runtime;

namespace Amazon.ElastiCache.Integrations.LiveValidation;

/// <summary>A periodic re-AUTH keeps the connection serving, and a rejected re-AUTH leaves the cache's FAS in place.</summary>
internal sealed class AuthScenario(LiveContext live)
{
    private static readonly TimeSpan Interval = TimeSpan.FromSeconds(15);
    private static readonly TimeSpan Idle = TimeSpan.FromSeconds(50);

    public async Task RunAsync()
    {
        var credentials = new SwitchableCredentials(new EnvironmentVariablesAWSCredentials());
        var viaCache = await live.OpenAsync(credentials, reauthInterval: Interval);

        // No call runs and nothing reconnects while idle, so every credential read is a re-AUTH.
        var reads = credentials.Reads;
        await Task.Delay(Idle, live.Stop);
        var reauths = credentials.Reads - reads;
        var expected = (int)(Idle / Interval);
        live.Check("periodic re-AUTH runs and succeeds", reauths >= expected - 1 && reauths <= expected + 1 && live.Events.Count(1101, Reauth) == 0 && Clean(),
            $"{reauths} re-AUTHs in {Idle.TotalSeconds:F0} s every {Interval.TotalSeconds:F0} s, expected about {expected}; " +
            $"failures {live.Events.Count(1101, Reauth)}, ConnectionLost {live.Events.Count(1201)}, Reconnecting {live.Events.Count(1303)}");
        await MissAsync(viaCache, "after-reauth", "a miss after periodic re-AUTH");

        credentials.Broken = true;
        var refused = await live.WaitUntilAsync(() => live.Events.Count(1101, Refused) > 0, Interval * 2);
        live.Check("the cache refuses a re-AUTH token with a bad signature (WRONGPASS)", refused, live.Events.Message(1101, Refused) ?? "no refusal logged");
        for (var i = 0; i < 3; i++)
        {
            await MissAsync(viaCache, $"after-refused-{i}", $"miss {i + 1} after the refused re-AUTH");
        }

        live.Check("the connection stays up and authenticated", viaCache.Handle.IsOpen && live.Events.Count(1103) == 0 && Clean(),
            $"open {viaCache.Handle.IsOpen}, AuthDegraded {live.Events.Count(1103)}, ConnectionLost {live.Events.Count(1201)}, failed re-AUTHs {live.Events.Count(1101, Reauth)}");

        credentials.Broken = false;
        var recovered = await live.WaitUntilAsync(() => live.Events.Count(1102, Reauth) > 0, TimeSpan.FromSeconds(10));
        live.Check("re-AUTH recovers with good credentials", recovered, live.Events.Message(1102, Reauth) ?? "no recovery logged");
        await MissAsync(viaCache, "after-recovery", "a miss after re-AUTH recovered");
    }

    private bool Clean() => live.Events.Count(1201) == 0 && live.Events.Count(1303) == 0;

    // 1101 and 1102 also come from the config refresh runner.
    private static bool Reauth(string message, Exception? error) => message.StartsWith("Re-AUTH", StringComparison.Ordinal);

    private static bool Refused(string message, Exception? error) => Reauth(message, error) && error is ElastiCacheIntegrationsServerException { Code: "WRONGPASS" };

    private async Task MissAsync(ElastiCacheIntegrationsDynamoDBClient viaCache, string name, string check)
    {
        var (same, routed, ms) = await live.MissAsync(viaCache, name);
        live.Check($"{check} is routed through the cache", same && routed, $"same {same}, no origin call {routed}, {ms:F1} ms");
    }
}
