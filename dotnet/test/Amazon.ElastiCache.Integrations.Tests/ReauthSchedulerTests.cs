// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using Microsoft.Extensions.Time.Testing;

namespace Amazon.ElastiCache.Integrations.Tests;

public class ReauthSchedulerTests
{
    private static ElastiCacheIntegrationsClientOptions Options(TimeSpan? fallback = null) => new()
    {
        Endpoint = "h:6379",
        CacheName = "c",
        ReauthInterval = TimeSpan.FromMinutes(30),
        FallbackDuration = fallback ?? TimeSpan.FromMinutes(5),
        Reconnect = new ElastiCacheIntegrationsReconnectPolicy { Jitter = 0 },
    };

    [Fact]
    public async Task ReauthFailingPastTheFasLifetimeIsStaleUntilAnAuthSucceeds()
    {
        var time = new RecordingTimeProvider();
        var calls = 0;
        var logger = new ListLogger();
        await using var scheduler = new ReauthScheduler(_ =>
        {
            Interlocked.Increment(ref calls);
            return Task.FromException<bool>(new ElastiCacheIntegrationsConnectionException("AUTH rejected"));
        }, Options(), logger, time);
        scheduler.Start();
        await WaitUntilAsync(() => time.Armed(due => due == TimeSpan.FromMinutes(30)) > 0);
        Assert.False(scheduler.IsStale);

        time.Advance(ReauthScheduler.FasLifetime);
        await WaitUntilAsync(() => logger.Ids.Contains(ElastiCacheIntegrationsEvents.AuthStale));

        Assert.True(scheduler.IsStale);
        scheduler.LogStale();
        Assert.Single(logger.Ids, id => id == ElastiCacheIntegrationsEvents.AuthStale);

        scheduler.OnAuthenticated();
        Assert.False(scheduler.IsStale);
    }

    private static async Task WaitUntilAsync(Func<bool> condition)
    {
        for (var i = 0; i < 500 && !condition(); i++)
        {
            await Task.Delay(10, TestContext.Current.CancellationToken);
        }

        Assert.True(condition());
    }
}
