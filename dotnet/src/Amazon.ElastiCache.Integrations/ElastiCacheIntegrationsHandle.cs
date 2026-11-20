// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using Amazon.DynamoDBv2;

namespace Amazon.ElastiCache.Integrations;

/// <summary>
/// A thread-safe interceptor installation that can be attached to multiple
/// DynamoDB configurations.
/// </summary>
public sealed class ElastiCacheIntegrationsHandle : IDisposable, IAsyncDisposable
{
    private readonly object _gate = new();
    private readonly IElastiCacheIntegrationsRequestInterceptor _interceptor;
    private readonly HttpMessageHandler? _originHandler;
    private readonly TaskCompletionSource _disposed = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private TaskCompletionSource? _drained;
    private int _active;
    private volatile bool _accepting = true;

    internal ElastiCacheIntegrationsHandle(
        IElastiCacheIntegrationsRequestInterceptor interceptor,
        HttpMessageHandler? originHandler)
    {
        _interceptor = interceptor;
        _originHandler = originHandler;
    }

    /// <summary>True after disposal has started.</summary>
    public bool IsDisposed => !_accepting;

    /// <summary>Attaches this integration before an SDK client is constructed.</summary>
    public void Attach(AmazonDynamoDBConfig config)
    {
        ArgumentNullException.ThrowIfNull(config);
        lock (_gate)
        {
            ObjectDisposedException.ThrowIf(!_accepting, this);
            if (config.HttpClientFactory is not null)
            {
                throw new ElastiCacheIntegrationsConfigurationException(
                    "The DynamoDB config already has an HttpClientFactory; the integration cannot replace it.");
            }

            config.HttpClientFactory = new ElastiCacheIntegrationsHttpClientFactory(this, _originHandler);
        }
    }

    /// <summary>
    /// Stops new interception calls and disposes the owned interceptor
    /// immediately. Attached SDK clients subsequently use their origin path.
    /// </summary>
    public void Dispose()
    {
        lock (_gate)
        {
            if (!_accepting)
            {
                return;
            }

            _accepting = false;
        }

        try
        {
            _interceptor.Dispose();
            _disposed.TrySetResult();
        }
        catch (Exception exception)
        {
            _disposed.TrySetException(exception);
            throw;
        }
    }

    /// <summary>
    /// Stops new interception calls, drains active calls, and then disposes the
    /// owned interceptor asynchronously.
    /// </summary>
    public ValueTask DisposeAsync()
    {
        Task? drain = null;
        var start = false;
        lock (_gate)
        {
            if (_accepting)
            {
                _accepting = false;
                start = true;
                if (_active > 0)
                {
                    _drained = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
                    drain = _drained.Task;
                }
            }
        }

        if (start)
        {
            _ = DisposeAfterDrainAsync(drain);
        }

        return new ValueTask(_disposed.Task);
    }

    internal async ValueTask<HttpResponseMessage?> InterceptAsync(
        HttpRequestMessage request,
        CancellationToken cancellationToken)
    {
        if (!TryEnter())
        {
            return null;
        }

        try
        {
            return await _interceptor.InterceptAsync(request, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            Exit();
        }
    }

    private bool TryEnter()
    {
        lock (_gate)
        {
            if (!_accepting)
            {
                return false;
            }

            _active++;
            return true;
        }
    }

    private void Exit()
    {
        TaskCompletionSource? drained = null;
        lock (_gate)
        {
            _active--;
            if (_active == 0)
            {
                drained = _drained;
            }
        }

        drained?.TrySetResult();
    }

    private async Task DisposeAfterDrainAsync(Task? drain)
    {
        try
        {
            if (drain is not null)
            {
                await drain.ConfigureAwait(false);
            }

            await _interceptor.DisposeAsync().ConfigureAwait(false);
            _disposed.TrySetResult();
        }
        catch (Exception exception)
        {
            _disposed.TrySetException(exception);
        }
    }
}
