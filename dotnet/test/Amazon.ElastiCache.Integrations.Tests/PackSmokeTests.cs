// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using System.Diagnostics;

namespace Amazon.ElastiCache.Integrations.Tests;

/// <summary>Runs the console app that <c>scripts/pack-smoke.sh</c> builds from the packed package.</summary>
public class PackSmokeTests
{
    private const string AppVariable = "ELASTICACHE_INTEGRATIONS_PACK_SMOKE_APP";

    public static bool AppBuilt => !string.IsNullOrEmpty(Environment.GetEnvironmentVariable(AppVariable));

    [Fact(Skip = $"Run by scripts/pack-smoke.sh, which sets {AppVariable}.", SkipUnless = nameof(AppBuilt))]
    public async Task InstalledPackageServesAGetItemFromTheCache()
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(TestContext.Current.CancellationToken);
        timeout.CancelAfter(TimeSpan.FromMinutes(1));
        await using var server = new CacheServer { OnRequest = _ => SdkEndToEndTests.Cached("""{"Item":{"pk":{"S":"a"},"v":{"N":"1"}}}""") };

        // The child inherits this process's environment, which SdkEnvironment has already isolated.
        var start = new ProcessStartInfo(Environment.GetEnvironmentVariable("DOTNET_HOST_PATH") ?? "dotnet")
        {
            ArgumentList = { Environment.GetEnvironmentVariable(AppVariable)!, server.Endpoint },
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        using var app = Process.Start(start)!;
        var output = app.StandardOutput.ReadToEndAsync(timeout.Token);
        var errors = app.StandardError.ReadToEndAsync(timeout.Token);
        try
        {
            await app.WaitForExitAsync(timeout.Token);
        }
        catch (OperationCanceledException)
        {
            app.Kill(entireProcessTree: true);
            throw;
        }

        Assert.True(app.ExitCode == 0, $"exit {app.ExitCode}\n{await output}\n{await errors}");
        Assert.Contains("served v=1", await output);
        Assert.Contains(server.Requests, request => request.Any(argument => argument.Contains("op=GetItem", StringComparison.Ordinal)));
    }
}
