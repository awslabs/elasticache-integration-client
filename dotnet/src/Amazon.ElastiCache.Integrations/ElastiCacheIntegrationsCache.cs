// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using System.Diagnostics;
using System.Text.Json;
using Amazon.Runtime;

namespace Amazon.ElastiCache.Integrations;

/// <summary>An authenticated cache session. Instances support concurrent requests.</summary>
public sealed class ElastiCacheIntegrationsCache : IDisposable, IAsyncDisposable
{
    private readonly ElastiCacheIntegrationsClientOptions _options;
    private readonly IamAuthTokenProvider _auth;
    private readonly byte[] _overrides;
    private readonly object _gate = new();
    private readonly CancellationTokenSource _stop = new();
    private readonly SemaphoreSlim _admission;
    private readonly Task _supervisor;
    private ElastiCacheIntegrationsConnection? _connection;
    private TaskCompletionSource<ElastiCacheIntegrationsConnection> _available = NewAvailability();
    private Task? _closeTask;
    private bool _disposed;

    private ElastiCacheIntegrationsCache(ElastiCacheIntegrationsClientOptions options, IamAuthTokenProvider auth,
        byte[] overrides, ElastiCacheIntegrationsConnection connection)
    {
        _options = options;
        _auth = auth;
        _overrides = overrides;
        _connection = connection;
        _admission = new SemaphoreSlim(options.MaxPending, options.MaxPending);
        _supervisor = SuperviseAsync(connection, _stop.Token);
    }

    /// <summary>The current server config as JSON, or null while disconnected.</summary>
    public JsonElement? CurrentConfig
    {
        get
        {
            lock (_gate)
            {
                return !_disposed && _connection is { IsOpen: true } connection ? connection.Config?.Raw : null;
            }
        }
    }

    /// <summary>Opens a TLS connection, authenticates with IAM, and negotiates the cache config.</summary>
    /// <param name="options">Cache endpoint, identity and session settings.</param>
    /// <param name="credentials">AWS credential provider. Use the same provider as the origin SDK client.</param>
    /// <param name="cancellationToken">Cancels startup.</param>
    public static async Task<ElastiCacheIntegrationsCache> OpenAsync(
        ElastiCacheIntegrationsClientOptions options, AWSCredentials credentials, CancellationToken cancellationToken = default)
    {
        OptionsValidator.Validate(options);
        ArgumentNullException.ThrowIfNull(credentials);
        cancellationToken.ThrowIfCancellationRequested();

        var overrides = ElastiCacheIntegrationsProtocol.SerializeOverrides(options.Overrides);
        var auth = new IamAuthTokenProvider(options.CacheName, options.UserId, options.Region, credentials);
        var snapshot = options.Snapshot();
        try
        {
            for (var attempt = 1; ; attempt++)
            {
                try
                {
                    var connection = await ConnectAsync(snapshot, auth, overrides, cancellationToken).ConfigureAwait(false);
                    return new ElastiCacheIntegrationsCache(snapshot, auth, overrides, connection);
                }
                catch (ElastiCacheIntegrationsConnectionException) when (attempt < snapshot.Reconnect.ConnectAttempts && !cancellationToken.IsCancellationRequested)
                {
                    await BackoffAsync(snapshot.Reconnect, attempt, cancellationToken).ConfigureAwait(false);
                }
            }
        }
        catch
        {
            ElastiCacheIntegrationsClientOptions.DisposeAll(snapshot.CaCertificates);
            throw;
        }
    }

    /// <summary>Sends one request to the cache. A failed request is never replayed.</summary>
    public async Task<ElastiCacheIntegrationsResponse> SendAsync(
        ElastiCacheIntegrationsRequest request, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(request);
        cancellationToken.ThrowIfCancellationRequested();
        Task<ElastiCacheIntegrationsConnection>? available = null;
        ElastiCacheIntegrationsConnection? connection;
        lock (_gate)
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            if (!_admission.Wait(0))
            {
                throw new ElastiCacheIntegrationsPendingLimitException($"The cache session already has {_options.MaxPending} requests in flight.");
            }

            connection = _connection;
            if (connection is not { IsOpen: true })
            {
                available = _available.Task;
            }
        }

        try
        {
            if (available is not null)
            {
                if (_options.ReconnectWaitMs == 0)
                {
                    throw new ElastiCacheIntegrationsCacheUnavailableException("The cache is reconnecting.");
                }

                try
                {
                    connection = await available.WaitAsync(TimeSpan.FromMilliseconds(_options.ReconnectWaitMs), cancellationToken).ConfigureAwait(false);
                }
                catch (TimeoutException)
                {
                    throw new ElastiCacheIntegrationsCacheUnavailableException("The cache did not reconnect within the configured wait.");
                }
            }

            return await connection!.RequestAsync(request, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _admission.Release();
        }
    }

    private static async Task<ElastiCacheIntegrationsConnection> ConnectAsync(ElastiCacheIntegrationsClientOptions options,
        IamAuthTokenProvider auth, byte[] overrides, CancellationToken cancellationToken)
    {
        using var deadline = new CancellationTokenSource(options.Reconnect.AttemptTimeoutMs);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(deadline.Token, cancellationToken);
        try
        {
            var connection = await ElastiCacheIntegrationsConnection.OpenAsync(options, auth, overrides, linked.Token).ConfigureAwait(false);
            if (linked.IsCancellationRequested)
            {
                await connection.DisposeAsync().ConfigureAwait(false);
                linked.Token.ThrowIfCancellationRequested();
            }

            return connection;
        }
        catch (ElastiCacheIntegrationsException) when (cancellationToken.IsCancellationRequested)
        {
            throw new OperationCanceledException(cancellationToken);
        }
        catch (OperationCanceledException) when (deadline.IsCancellationRequested && !cancellationToken.IsCancellationRequested)
        {
            throw new ElastiCacheIntegrationsConnectionException("The cache connection attempt timed out.");
        }
    }

    private async Task SuperviseAsync(ElastiCacheIntegrationsConnection connection, CancellationToken stop)
    {
        try
        {
            while (true)
            {
                await MaintainAsync(connection, stop).ConfigureAwait(false);
                stop.ThrowIfCancellationRequested();
                await connection.DisposeAsync().ConfigureAwait(false);

                for (var attempt = 1; ; attempt = Math.Min(attempt + 1, 32))
                {
                    stop.ThrowIfCancellationRequested();
                    try
                    {
                        connection = await ConnectAsync(_options, _auth, _overrides, stop).ConfigureAwait(false);
                        break;
                    }
                    catch (ElastiCacheIntegrationsException) when (!stop.IsCancellationRequested)
                    {
                        await BackoffAsync(_options.Reconnect, attempt, stop).ConfigureAwait(false);
                    }
                }

                lock (_gate)
                {
                    if (!_disposed)
                    {
                        _connection = connection;
                        _available.TrySetResult(connection);
                        _available = NewAvailability();
                        continue;
                    }
                }

                await connection.DisposeAsync().ConfigureAwait(false);
                return;
            }
        }
        catch (OperationCanceledException) when (stop.IsCancellationRequested)
        {
        }
    }

    private async Task MaintainAsync(ElastiCacheIntegrationsConnection connection, CancellationToken stop)
    {
        var authenticated = Stopwatch.GetTimestamp();
        var confirmed = authenticated;
        var refreshed = authenticated;
        var rejectedConfig = false;
        var authInterval = TimeSpan.FromSeconds(_options.ReauthIntervalSeconds);
        var maxConfigAge = TimeSpan.FromSeconds(_options.CachePolicyTimeoutSeconds);
        while (connection.IsOpen)
        {
            stop.ThrowIfCancellationRequested();
            var untilAuth = authInterval - Stopwatch.GetElapsedTime(authenticated);
            var untilRefresh = connection.Config!.RefreshInterval - Stopwatch.GetElapsedTime(refreshed);
            if (untilAuth <= TimeSpan.Zero)
            {
                try
                {
                    await connection.ReauthenticateAsync(CancellationToken.None).ConfigureAwait(false);
                    authenticated = Stopwatch.GetTimestamp();
                }
                catch (ElastiCacheIntegrationsException)
                {
                    connection.Dispose();
                }
            }
            else if (untilRefresh <= TimeSpan.Zero)
            {
                try
                {
                    var config = await connection.RefreshConfigAsync(CancellationToken.None).ConfigureAwait(false);
                    if (config is null && rejectedConfig)
                    {
                        throw new ElastiCacheIntegrationsProtocolException("The cache still holds an unusable config.");
                    }

                    rejectedConfig = false;
                    confirmed = Stopwatch.GetTimestamp();
                }
                catch (ElastiCacheIntegrationsException error)
                {
                    rejectedConfig |= error is ElastiCacheIntegrationsProtocolException;
                    if (Stopwatch.GetElapsedTime(confirmed) >= maxConfigAge)
                    {
                        connection.Dispose();
                    }
                }

                refreshed = Stopwatch.GetTimestamp();
            }
            else
            {
                try
                {
                    var delay = untilAuth < untilRefresh ? untilAuth : untilRefresh;
                    await connection.Closed.WaitAsync(delay, stop).ConfigureAwait(false);
                }
                catch (TimeoutException)
                {
                    // A maintenance operation is due.
                }
            }
        }
    }

    private static Task BackoffAsync(ElastiCacheIntegrationsReconnectPolicy policy, int attempt, CancellationToken cancellationToken)
    {
        var delay = Math.Min((long)policy.BaseDelayMs << Math.Min(attempt - 1, 31), policy.MaxDelayMs);
        var jittered = delay * (1 - policy.Jitter * Random.Shared.NextDouble());
        return Task.Delay(Math.Max(1, (int)jittered), cancellationToken);
    }

    private static TaskCompletionSource<ElastiCacheIntegrationsConnection> NewAvailability() =>
        new(TaskCreationOptions.RunContinuationsAsynchronously);

    /// <summary>Closes the cache connection immediately and signals background work to stop.</summary>
    public void Dispose() => _ = BeginClose(drain: false);

    /// <summary>Stops new requests, drains pending replies within the config timeout, and joins background work.</summary>
    public ValueTask DisposeAsync() => new(BeginClose(drain: true));

    private Task BeginClose(bool drain)
    {
        lock (_gate)
        {
            if (_closeTask is not null)
            {
                return _closeTask;
            }

            _disposed = true;
            _stop.Cancel();
            _available.TrySetException(new ObjectDisposedException(nameof(ElastiCacheIntegrationsCache)));
            _ = _available.Task.Exception;
            var connection = _connection;
            _connection = null;
            return _closeTask = CloseAsync(connection, drain);
        }
    }

    private async Task CloseAsync(ElastiCacheIntegrationsConnection? connection, bool drain)
    {
        try
        {
            if (!drain)
            {
                connection?.Dispose();
            }

            await _supervisor.ConfigureAwait(false);
        }
        finally
        {
            if (connection is not null)
            {
                await connection.DisposeAsync().ConfigureAwait(false);
            }

            _stop.Dispose();
            ElastiCacheIntegrationsClientOptions.DisposeAll(_options.CaCertificates);
        }
    }
}
