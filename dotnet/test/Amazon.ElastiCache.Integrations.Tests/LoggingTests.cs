// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using System.Collections.Concurrent;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace Amazon.ElastiCache.Integrations.Tests;

public class LoggingTests
{
    [Fact]
    public void DefaultLoggerIsStderr()
    {
        var logger = ElastiCacheIntegrationsLog.CreateLogger(null);

        Assert.True(logger.IsEnabled(LogLevel.Warning));
        Assert.True(logger.IsEnabled(LogLevel.Information));
        Assert.False(logger.IsEnabled(LogLevel.Debug));
    }

    [Fact]
    public void ConfiguredFactoryIsUsed()
    {
        var logger = ElastiCacheIntegrationsLog.CreateLogger(NullLoggerFactory.Instance);

        Assert.False(logger.IsEnabled(LogLevel.Critical));
    }

    [Fact]
    public void AConfiguredLoggerThatThrowsIsContained()
    {
        var logger = ElastiCacheIntegrationsLog.CreateLogger(new SingleLoggerFactory(new ThrowingLogger()));

        logger.LogWarning(ElastiCacheIntegrationsEvents.ConnectionLost, "dropped");
        Assert.False(logger.IsEnabled(LogLevel.Warning));
        Assert.Null(logger.BeginScope("scope"));
    }

    [Fact]
    public void AFactoryThatCannotCreateALoggerGetsTheStderrLogger()
    {
        var logger = ElastiCacheIntegrationsLog.CreateLogger(new FailingLoggerFactory());

        Assert.True(logger.IsEnabled(LogLevel.Warning));
        Assert.False(logger.IsEnabled(LogLevel.Debug));
    }

    [Fact]
    public void StderrLoggerWritesOneLinePerEntry()
    {
        var output = new StringWriter();
        var logger = new StderrLoggerFactory(output).CreateLogger(ElastiCacheIntegrationsLog.Category);

        logger.LogDebug("hidden");
        logger.LogWarning(ElastiCacheIntegrationsEvents.ConfigStale, new InvalidOperationException("bad"), "stale {Index}", 2);
        logger.Log(LogLevel.None, "never");

        var line = Assert.Single(output.ToString().Split(Environment.NewLine, StringSplitOptions.RemoveEmptyEntries));
        Assert.EndsWith(" Warning Amazon.ElastiCache.Integrations[1306] stale 2 (System.InvalidOperationException: bad)", line);
        Assert.True(DateTimeOffset.TryParse(line.Split(' ')[0], out _));
    }

    [Fact]
    public void StderrLoggerKeepsEachEntryOnOneLine()
    {
        var output = new StringWriter();
        var logger = new StderrLoggerFactory(output).CreateLogger(ElastiCacheIntegrationsLog.Category);
        var error = new HttpRequestException("send\nfailed", new IOException("reset\r\nby peer"));

        logger.LogWarning(error, "line one\nline two {Text}", "a\rb");

        var line = Assert.Single(output.ToString().Split(Environment.NewLine, StringSplitOptions.RemoveEmptyEntries));
        Assert.EndsWith(
            "line one line two a b (System.Net.Http.HttpRequestException: send failed <- System.IO.IOException: reset by peer)",
            line);
    }

    [Fact]
    public void StderrLoggerNeverThrows()
    {
        var closed = new StringWriter();
        closed.Dispose();
        var logger = new StderrLoggerFactory(closed).CreateLogger(ElastiCacheIntegrationsLog.Category);

        logger.LogWarning("dropped");
        logger.Log<string>(LogLevel.Warning, default, "state", null, (_, _) => throw new FormatException());
    }

    [Fact]
    public void StderrFactoryTakesNoProviders()
    {
        using var factory = new StderrLoggerFactory(TextWriter.Null);

        Assert.Throws<NotSupportedException>(() => factory.AddProvider(NullLoggerProvider.Instance));
    }

    [Fact]
    public void StderrLoggerIsThreadSafe()
    {
        var output = new StringWriter();
        var logger = new StderrLoggerFactory(output).CreateLogger(ElastiCacheIntegrationsLog.Category);

        Parallel.For(0, 200, i => logger.LogInformation("entry {Index}", i));

        var lines = output.ToString().Split(Environment.NewLine, StringSplitOptions.RemoveEmptyEntries);
        Assert.Equal(200, lines.Length);
        Assert.All(lines, l => Assert.Contains(" Information Amazon.ElastiCache.Integrations[0] entry ", l));
        Assert.Equal(Enumerable.Range(0, 200), lines.Select(l => int.Parse(l.Split(' ')[^1])).Order());
    }

    [Fact]
    public void InsecureSkipVerifyWarnsOnce()
    {
        var output = new StringWriter();
        var logger = new StderrLoggerFactory(output).CreateLogger(ElastiCacheIntegrationsLog.Category);
        var flag = new OnceFlag();

        Parallel.For(0, 50, _ => ElastiCacheIntegrationsLog.WarnInsecureSkipVerify(logger, flag));

        var line = Assert.Single(output.ToString().Split(Environment.NewLine, StringSplitOptions.RemoveEmptyEntries));
        Assert.Contains("Warning Amazon.ElastiCache.Integrations[1001] TLS certificate verification is disabled", line);
    }

    [Fact]
    public void DisabledLoggerDoesNotUseUpTheWarning()
    {
        var flag = new OnceFlag();

        ElastiCacheIntegrationsLog.WarnInsecureSkipVerify(NullLogger.Instance, flag);

        Assert.True(flag.TryEnter());
    }

    [Fact]
    public void OnceFlagLetsOneCallerThrough()
    {
        var flag = new OnceFlag();
        var entered = 0;

        Parallel.For(0, 100, _ =>
        {
            if (flag.TryEnter())
            {
                Interlocked.Increment(ref entered);
            }
        });

        Assert.Equal(1, entered);
    }
}

/// <summary>A logger that fails every call, as a broken logging provider does.</summary>
internal sealed class ThrowingLogger(bool enabled = false) : ILogger
{
    private readonly ConcurrentQueue<EventId> _attempted = new();

    /// <summary>The event ids of every log call, recorded before it throws.</summary>
    public EventId[] Attempted => [.. _attempted];

    public IDisposable? BeginScope<TState>(TState state)
        where TState : notnull => throw new InvalidOperationException("scope failed");

    public bool IsEnabled(LogLevel logLevel) => enabled ? true : throw new InvalidOperationException("IsEnabled failed");

    public void Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception? exception, Func<TState, Exception?, string> formatter)
    {
        _attempted.Enqueue(eventId);
        throw new InvalidOperationException("log failed");
    }
}

/// <summary>A logger factory that cannot create a logger.</summary>
internal sealed class FailingLoggerFactory : ILoggerFactory
{
    public void AddProvider(ILoggerProvider provider)
    {
    }

    public ILogger CreateLogger(string categoryName) => throw new InvalidOperationException("no logger");

    public void Dispose()
    {
    }
}
