using System.Runtime.InteropServices;
using Amazon.ElastiCache.Integrations.LiveValidation;

// Checks the ElastiCache Integrations client against a real cache. Settings and credentials come from the environment.
string[] modes = ["ops", "auth", "push", "fas", "cleanup"];
var mode = args.FirstOrDefault() ?? "ops";
if (!modes.Contains(mode))
{
    Console.Error.WriteLine($"Unknown mode '{mode}'. Modes: {string.Join(", ", modes)}.");
    return 2;
}

// Mode cleanup deletes the items of an earlier run, named by ELASTICACHE_INTEGRATIONS_RUN.
var run = mode == "cleanup" ? Environment.GetEnvironmentVariable("ELASTICACHE_INTEGRATIONS_RUN") : null;
if (mode == "cleanup" && string.IsNullOrEmpty(run))
{
    Console.Error.WriteLine("Set ELASTICACHE_INTEGRATIONS_RUN to the run to clean up.");
    return 2;
}

using var stop = new CancellationTokenSource();
using var sigint = PosixSignalRegistration.Create(PosixSignal.SIGINT, Stop);
using var sigterm = PosixSignalRegistration.Create(PosixSignal.SIGTERM, Stop);

await using var live = LiveContext.Create(stop.Token, run);
live.Log($"mode {mode}, run {live.Run}, cache {live.CacheName}, table {live.Table}, region {live.Region.SystemName}");
try
{
    switch (mode)
    {
        case "ops":
            await new OperationsScenario(live).RunAsync();
            break;
        case "auth":
            await new AuthScenario(live).RunAsync();
            break;
        case "push":
            await new PushScenario(live).RunAsync();
            break;
        case "fas":
            await new FasScenario(live).RunAsync();
            break;
        case "cleanup":
            break;
    }
}
catch (Exception e)
{
    live.Check("the run completed", false, stop.IsCancellationRequested ? "stopped by a signal" : $"{e.GetType().Name}: {e.Message}");
}

try
{
    await live.CleanupAsync();
}
catch (Exception e)
{
    live.Check("cleanup", false, $"{e.GetType().Name}: {e.Message}; run again with ELASTICACHE_INTEGRATIONS_RUN={live.Run} and mode cleanup");
}

live.Log(live.Passed ? "ALL CHECKS PASSED" : "SOME CHECKS FAILED");
return live.Passed ? 0 : 1;

// The first signal ends the run and still cleans up; a second one ends the process.
void Stop(PosixSignalContext context)
{
    context.Cancel = !stop.IsCancellationRequested;
    stop.Cancel();
}
