using System.Globalization;
using Microsoft.Extensions.Logging;

namespace Amazon.ElastiCache.Integrations;

/// <summary>Stable event ids for client log lines, so a consumer can filter on them.</summary>
internal static class ElastiCacheIntegrationsEvents
{
    public static readonly EventId InsecureSkipVerify = new(1001, nameof(InsecureSkipVerify));
    public static readonly EventId SyncCallToOrigin = new(1002, nameof(SyncCallToOrigin));

    // Re-AUTH and periodic tasks use the 1100 range.
    public static readonly EventId PeriodicFailed = new(1101, nameof(PeriodicFailed));
    public static readonly EventId PeriodicRecovered = new(1102, nameof(PeriodicRecovered));
    public static readonly EventId AuthDegraded = new(1103, nameof(AuthDegraded));
    public static readonly EventId AuthRecovered = new(1104, nameof(AuthRecovered));
    public static readonly EventId AuthWindowExhausted = new(1105, nameof(AuthWindowExhausted));
    public static readonly EventId AuthStale = new(1106, nameof(AuthStale));

    // One connection uses the 1200 range.
    public static readonly EventId ConnectionLost = new(1201, nameof(ConnectionLost));
    public static readonly EventId HeadStalled = new(1202, nameof(HeadStalled));
    public static readonly EventId LatePushDropped = new(1203, nameof(LatePushDropped));
    public static readonly EventId Resp2Fallback = new(1204, nameof(Resp2Fallback));

    // The session uses the 1300 range.
    public static readonly EventId ConfigApplied = new(1301, nameof(ConfigApplied));
    public static readonly EventId BringUpAttemptFailed = new(1302, nameof(BringUpAttemptFailed));
    public static readonly EventId Reconnecting = new(1303, nameof(Reconnecting));
    public static readonly EventId Reconnected = new(1304, nameof(Reconnected));
    public static readonly EventId CacheUnavailable = new(1305, nameof(CacheUnavailable));
    public static readonly EventId ConfigStale = new(1306, nameof(ConfigStale));
    public static readonly EventId ConfigUnusable = new(1307, nameof(ConfigUnusable));
    public static readonly EventId PendingLimitReached = new(1308, nameof(PendingLimitReached));
}

/// <summary>Logger selection and warnings that are written at most once.</summary>
internal static class ElastiCacheIntegrationsLog
{
    public const string Category = "Amazon.ElastiCache.Integrations";

    private static readonly OnceFlag InsecureSkipVerifyWarned = new();

    /// <summary>
    /// The configured factory, else the stderr logger, so warnings are never dropped silently.
    /// A configured logger is wrapped so that its failures never reach the client.
    /// </summary>
    public static ILogger CreateLogger(ILoggerFactory? configured)
    {
        if (configured is not null)
        {
            try
            {
                return new SafeLogger(configured.CreateLogger(Category));
            }
            catch (Exception)
            {
                // A factory that cannot make a logger gets the default one.
            }
        }

        return StderrLoggerFactory.Instance.CreateLogger(Category);
    }

    /// <summary>Warns once per process that certificate checks are off.</summary>
    public static void WarnInsecureSkipVerify(ILogger logger) => WarnInsecureSkipVerify(logger, InsecureSkipVerifyWarned);

    internal static void WarnInsecureSkipVerify(ILogger logger, OnceFlag flag)
    {
        // A logger that drops warnings must not use up the single warning.
        if (logger.IsEnabled(LogLevel.Warning) && flag.TryEnter())
        {
            logger.LogWarning(
                ElastiCacheIntegrationsEvents.InsecureSkipVerify,
                "TLS certificate verification is disabled (InsecureSkipVerify). Expected only against a local or test endpoint.");
        }
    }

    /// <summary>Warns once per flag that a sync SDK call went straight to the origin.</summary>
    public static void WarnSyncCallToOrigin(ILogger logger, OnceFlag flag)
    {
        if (logger.IsEnabled(LogLevel.Warning) && flag.TryEnter())
        {
            logger.LogWarning(
                ElastiCacheIntegrationsEvents.SyncCallToOrigin,
                "A synchronous SDK call bypasses the cache and goes to the origin. Use the async API to use the cache.");
        }
    }
}

/// <summary>A flag that lets exactly one caller through, across threads.</summary>
internal sealed class OnceFlag
{
    private int _entered;

    public bool TryEnter() => Interlocked.Exchange(ref _entered, 1) == 0;
}

/// <summary>
/// Wraps a caller's logger. The client logs on failure paths and in timer callbacks, where an
/// exception from the logger would leave state half-changed or end the process; it is dropped.
/// </summary>
internal sealed class SafeLogger(ILogger inner) : ILogger
{
    public IDisposable? BeginScope<TState>(TState state)
        where TState : notnull
    {
        try
        {
            return inner.BeginScope(state);
        }
        catch (Exception)
        {
            return null;
        }
    }

    public bool IsEnabled(LogLevel logLevel)
    {
        try
        {
            return inner.IsEnabled(logLevel);
        }
        catch (Exception)
        {
            return false;
        }
    }

    public void Log<TState>(
        LogLevel logLevel, EventId eventId, TState state, Exception? exception, Func<TState, Exception?, string> formatter)
    {
        try
        {
            inner.Log(logLevel, eventId, state, exception, formatter);
        }
        catch (Exception)
        {
            // Logging must never add a failure of its own.
        }
    }
}

/// <summary>
/// Writes Information and above to stderr, one line per entry. Never throws: a failed
/// write is dropped. Meant for state changes, not per-request lines.
/// </summary>
internal sealed class StderrLoggerFactory : ILoggerFactory
{
    public static readonly StderrLoggerFactory Instance = new(null);

    // Null means Console.Error, read at write time so Console.SetError is honoured.
    private readonly TextWriter? _writer;
    private readonly object _lock = new();

    internal StderrLoggerFactory(TextWriter? writer) => _writer = writer;

    public ILogger CreateLogger(string categoryName) => new Logger(this, categoryName);

    public void AddProvider(ILoggerProvider provider) =>
        throw new NotSupportedException("The default logger takes no providers; set ElastiCacheIntegrationsClientOptions.LoggerFactory.");

    public void Dispose()
    {
    }

    private void Write(string line)
    {
        var writer = _writer ?? Console.Error;
        lock (_lock)
        {
            writer.WriteLine(line);
            writer.Flush();
        }
    }

    private static string OneLine(string text) => text.ReplaceLineEndings(" ");

    private static string Describe(Exception exception)
    {
        var inner = exception;
        while (inner.InnerException is not null)
        {
            inner = inner.InnerException;
        }

        var text = $"{exception.GetType().FullName}: {exception.Message}";
        return ReferenceEquals(inner, exception) ? text : $"{text} <- {inner.GetType().FullName}: {inner.Message}";
    }

    private sealed class Logger(StderrLoggerFactory owner, string category) : ILogger
    {
        public IDisposable? BeginScope<TState>(TState state)
            where TState : notnull => null;

        public bool IsEnabled(LogLevel logLevel) => logLevel >= LogLevel.Information && logLevel != LogLevel.None;

        public void Log<TState>(
            LogLevel logLevel, EventId eventId, TState state, Exception? exception, Func<TState, Exception?, string> formatter)
        {
            if (!IsEnabled(logLevel))
            {
                return;
            }

            try
            {
                var line = string.Create(
                    CultureInfo.InvariantCulture,
                    $"{DateTimeOffset.UtcNow:O} {logLevel} {category}[{eventId.Id}] {formatter(state, exception)}");
                if (exception is not null)
                {
                    line += $" ({Describe(exception)})";
                }

                owner.Write(OneLine(line));
            }
            catch (Exception)
            {
                // Logging runs on failure paths; it must never add a failure of its own.
            }
        }
    }
}
