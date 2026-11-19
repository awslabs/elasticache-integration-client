// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using System.Collections.Concurrent;
using System.Diagnostics;
using Amazon.DynamoDBv2;
using Amazon.DynamoDBv2.Model;
using Amazon.Runtime;
using Microsoft.Extensions.Logging;

namespace Amazon.ElastiCache.Integrations.LiveValidation;

/// <summary>Settings, clients and check results for one live run.</summary>
internal sealed class LiveContext : IAsyncDisposable
{
    private readonly Stopwatch _clock = Stopwatch.StartNew();
    private readonly List<ElastiCacheIntegrationsDynamoDBClient> _cacheClients = [];
    private int _failures;

    private LiveContext(CancellationToken stop, string? run)
    {
        Stop = stop;
        Endpoint = Setting("ELASTICACHE_INTEGRATIONS_ENDPOINT");
        CacheName = Setting("ELASTICACHE_INTEGRATIONS_CACHE_NAME");
        Table = Environment.GetEnvironmentVariable("ELASTICACHE_INTEGRATIONS_TABLE") ?? "elasticache-integrations-test";
        Region = RegionEndpoint.GetBySystemName(Setting("AWS_REGION"));
        Run = run ?? Guid.NewGuid().ToString("N")[..8];
        Plain = new AmazonDynamoDBClient(new EnvironmentVariablesAWSCredentials(), new AmazonDynamoDBConfig { RegionEndpoint = Region });
    }

    public string Endpoint { get; }
    public string CacheName { get; }
    public string Table { get; }
    public RegionEndpoint Region { get; }

    /// <summary>Random per run, in every key, so no earlier run's cached Query or Scan is reused.</summary>
    public string Run { get; }

    public AmazonDynamoDBClient Plain { get; }
    public OriginCounter Origin { get; } = new();
    public EventLog Events { get; } = new();
    public bool Passed => _failures == 0;

    /// <summary>Cancelled on SIGINT or SIGTERM, so a long run still cleans up.</summary>
    public CancellationToken Stop { get; }

    /// <summary>A context for a new run, or for the earlier run <paramref name="run"/> to clean up.</summary>
    public static LiveContext Create(CancellationToken stop, string? run = null) => new(stop, run);

    public string Key(string name) => $"dotnet-live-{Run}-{name}";

    /// <summary>A cache client on the shared settings, closed with this context.</summary>
    public async Task<ElastiCacheIntegrationsDynamoDBClient> OpenAsync(AWSCredentials credentials, string? cacheName = null, TimeSpan? reauthInterval = null)
    {
        var options = new ElastiCacheIntegrationsClientOptions
        {
            Endpoint = Endpoint,
            CacheName = cacheName ?? CacheName,
            Region = Region.SystemName,
            ReauthInterval = reauthInterval ?? TimeSpan.FromMinutes(30),
            OriginHandler = Origin,
            LoggerFactory = Events,
        };
        var client = await ElastiCacheIntegrationsClient.CreateDynamoDBClientAsync(options, new AmazonDynamoDBConfig { RegionEndpoint = Region }, credentials)
            .ConfigureAwait(false);
        _cacheClients.Add(client);
        return client;
    }

    /// <summary>
    /// Writes a fresh item with the plain client, then reads it through <paramref name="viaCache"/>: a miss.
    /// Reports whether the reply matches DynamoDB and whether it was routed through the cache, with no origin call.
    /// </summary>
    public async Task<(bool Same, bool Routed, double Ms)> MissAsync(AmazonDynamoDBClient viaCache, string name)
    {
        var key = new Dictionary<string, AttributeValue> { ["pk"] = new() { S = Key(name) } };
        var item = new Dictionary<string, AttributeValue>(key) { ["v"] = new() { N = "1" } };
        await Plain.PutItemAsync(new PutItemRequest { TableName = Table, Item = item }).ConfigureAwait(false);

        // The cache's read is eventually consistent; give the write time to reach every replica.
        await Task.Delay(TimeSpan.FromSeconds(1), Stop).ConfigureAwait(false);
        var origin = Origin.Calls;
        var (reply, ms) = await TimeAsync(() => viaCache.GetItemAsync(new GetItemRequest { TableName = Table, Key = key })).ConfigureAwait(false);
        return (Canon.Item(reply.Item) == Canon.Item(item), Origin.Calls == origin, ms);
    }

    /// <summary>Waits until <paramref name="done"/> is true, polling every 100 ms; false on timeout.</summary>
    public async Task<bool> WaitUntilAsync(Func<bool> done, TimeSpan timeout)
    {
        var start = Stopwatch.GetTimestamp();
        while (!done())
        {
            if (Stopwatch.GetElapsedTime(start) > timeout)
            {
                return false;
            }

            await Task.Delay(100, Stop).ConfigureAwait(false);
        }

        return true;
    }

    public bool Check(string name, bool ok, string detail = "")
    {
        if (!ok)
        {
            _failures++;
        }

        Log($"{(ok ? "PASS" : "FAIL")} {name}{(detail.Length > 0 ? $" ({detail})" : "")}");
        return ok;
    }

    public void Log(string line) => Console.WriteLine($"[{_clock.Elapsed.TotalSeconds,8:F1}s] {line}");

    /// <summary>Runs <paramref name="call"/> and returns its result with its time in milliseconds.</summary>
    public static async Task<(T Result, double Ms)> TimeAsync<T>(Func<Task<T>> call)
    {
        var start = Stopwatch.GetTimestamp();
        var result = await call().ConfigureAwait(false);
        return (result, Stopwatch.GetElapsedTime(start).TotalMilliseconds);
    }

    /// <summary>Deletes every item this run wrote, with the plain client.</summary>
    public async Task CleanupAsync()
    {
        var deleted = 0;
        Dictionary<string, AttributeValue>? start = null;
        do
        {
            var page = await Plain.ScanAsync(new ScanRequest
            {
                TableName = Table,
                ConsistentRead = true,
                FilterExpression = "begins_with(pk, :p)",
                ExpressionAttributeValues = new() { [":p"] = new AttributeValue { S = Key("") } },
                ExclusiveStartKey = start,
            }).ConfigureAwait(false);
            foreach (var item in page.Items ?? [])
            {
                await Plain.DeleteItemAsync(new DeleteItemRequest { TableName = Table, Key = new() { ["pk"] = item["pk"] } }).ConfigureAwait(false);
                deleted++;
            }

            start = page.LastEvaluatedKey is { Count: > 0 } next ? next : null;
        }
        while (start is not null);

        Log($"cleanup: deleted {deleted} items");
    }

    public async ValueTask DisposeAsync()
    {
        try
        {
            foreach (var client in _cacheClients)
            {
                try
                {
                    await client.DisposeAsync().ConfigureAwait(false);
                }
                catch (Exception e)
                {
                    Log($"closing a cache client failed: {e.GetType().Name}: {e.Message}");
                }
            }
        }
        finally
        {
            Plain.Dispose();
            Origin.Dispose();
        }
    }

    private static string Setting(string name) =>
        Environment.GetEnvironmentVariable(name) is { Length: > 0 } value ? value : throw new InvalidOperationException($"Set {name}.");
}

/// <summary>The handler for calls the client sends to DynamoDB itself; a call that leaves it unchanged was served by the cache.</summary>
internal sealed class OriginCounter() : DelegatingHandler(new SocketsHttpHandler())
{
    private int _calls;

    public int Calls => Volatile.Read(ref _calls);

    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        Interlocked.Increment(ref _calls);
        return base.SendAsync(request, cancellationToken);
    }
}

/// <summary>Records every client event id at Debug and up, and prints Information and up.</summary>
internal sealed class EventLog : ILoggerFactory, ILogger
{
    private readonly ConcurrentQueue<(int Id, string Message, Exception? Error)> _events = new();

    public int Count(int id) => _events.Count(e => e.Id == id);

    /// <summary>Events with this id whose message and exception match.</summary>
    public int Count(int id, Func<string, Exception?, bool> match) => _events.Count(e => e.Id == id && match(e.Message, e.Error));

    public string? Message(int id) => _events.FirstOrDefault(e => e.Id == id).Message;

    public string? Message(int id, Func<string, Exception?, bool> match) => _events.FirstOrDefault(e => e.Id == id && match(e.Message, e.Error)).Message;

    public ILogger CreateLogger(string categoryName) => this;

    public void AddProvider(ILoggerProvider provider)
    {
    }

    public void Dispose()
    {
    }

    public IDisposable? BeginScope<TState>(TState state)
        where TState : notnull => null;

    public bool IsEnabled(LogLevel logLevel) => logLevel >= LogLevel.Debug;

    public void Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception? exception, Func<TState, Exception?, string> formatter)
    {
        var message = formatter(state, exception);
        _events.Enqueue((eventId.Id, message, exception));
        if (logLevel >= LogLevel.Information)
        {
            Console.WriteLine($"  eci {logLevel} {eventId.Id} {eventId.Name}: {message}{(exception is null ? "" : $" [{exception.GetType().Name}: {exception.Message}]")}");
        }
    }
}
