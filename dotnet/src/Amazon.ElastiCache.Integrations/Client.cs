// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using Amazon.DynamoDBv2;
using Amazon.Runtime;
using Microsoft.Extensions.Logging;

namespace Amazon.ElastiCache.Integrations;

/// <summary>Builds AWS SDK clients that serve routed calls through the cache.</summary>
public static class ElastiCacheIntegrationsClient
{
    /// <summary>
    /// Connects, authenticates and fetches the cache config, then returns a DynamoDB client that
    /// uses it. Credentials and region are resolved once and shared by the cache session and the SDK client.
    /// </summary>
    /// <param name="options">The cache to use.</param>
    /// <param name="config">The SDK config; The client sets its <see cref="ClientConfig.HttpClientFactory"/>.</param>
    /// <param name="credentials">The credentials; default: the SDK default chain.</param>
    /// <param name="cancellationToken">Cancels the bring-up.</param>
    /// <exception cref="ElastiCacheIntegrationsConfigurationException">Bad options, a region mismatch, a config with its own HttpClientFactory, no credentials, or overrides the cache rejects.</exception>
    /// <exception cref="ElastiCacheIntegrationsConnectionException">Every bring-up attempt failed.</exception>
    /// <exception cref="OperationCanceledException">The caller cancelled.</exception>
    /// <remarks>
    /// The config's region, as the SDK resolves it (including <c>AWS_REGION</c> and the profile), must match
    /// <see cref="ElastiCacheIntegrationsClientOptions.Region"/> when both are set.
    /// </remarks>
    public static async Task<ElastiCacheIntegrationsDynamoDBClient> CreateDynamoDBClientAsync(
        ElastiCacheIntegrationsClientOptions options,
        AmazonDynamoDBConfig? config = null,
        AWSCredentials? credentials = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(options);
        OptionsValidator.Validate(options);
        config ??= new AmazonDynamoDBConfig();
        ElastiCacheIntegrationsHandle.EnsureAttachable(config);
        var region = AuthSetup.ResolveRegion(options.Region, config.RegionEndpoint);

        // Setting RegionEndpoint clears ServiceURL, so a custom endpoint is left alone.
        var setRegion = config.RegionEndpoint is null && config.ServiceURL is null;
        if (setRegion)
        {
            config.RegionEndpoint = region;
        }

        ElastiCacheIntegrationsHandle? handle = null;
        var attached = false;
        try
        {
            var resolved = await AuthSetup.ResolveCredentialsAsync(credentials, config, cancellationToken).ConfigureAwait(false);
            handle = await ElastiCacheIntegrationsHandle.OpenAsync(options, resolved, region, "dynamodb", cancellationToken).ConfigureAwait(false);
            handle.Attach(config);
            attached = true;
            return new ElastiCacheIntegrationsDynamoDBClient(resolved, config, handle);
        }
        catch
        {
            // Leave the caller's config as it was.
            if (setRegion)
            {
                config.RegionEndpoint = null;
            }

            if (attached)
            {
                config.HttpClientFactory = null;
            }

            if (handle is not null)
            {
                await handle.DisposeAsync().ConfigureAwait(false);
            }

            throw;
        }
    }
}

/// <summary>An <see cref="AmazonDynamoDBClient"/> that owns its cache session and closes it on dispose.</summary>
public sealed class ElastiCacheIntegrationsDynamoDBClient : AmazonDynamoDBClient, IAsyncDisposable
{
    internal ElastiCacheIntegrationsDynamoDBClient(AWSCredentials credentials, AmazonDynamoDBConfig config, ElastiCacheIntegrationsHandle handle)
        : base(credentials, config) => Handle = handle;

    /// <summary>The cache session this client uses.</summary>
    public ElastiCacheIntegrationsHandle Handle { get; }

    /// <summary>Closes the session, draining in-flight requests, then disposes the client.</summary>
    public async ValueTask DisposeAsync()
    {
        try
        {
            await Handle.DisposeAsync().ConfigureAwait(false);
        }
        finally
        {
            Dispose();
        }
    }

    /// <summary>Closes the session without waiting, then disposes the client.</summary>
    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            Handle.Dispose();
        }

        base.Dispose(disposing);
    }
}

/// <summary>One cache session, attachable to any number of SDK client configs. Thread safe.</summary>
public sealed class ElastiCacheIntegrationsHandle : IDisposable, IAsyncDisposable
{
    private readonly ElastiCacheIntegrationsSession _session;
    private readonly HttpMessageHandler? _originHandler;
    private readonly ILogger _logger;
    private readonly OnceFlag _syncCallWarned = new();
    private volatile bool _disposed;

    internal ElastiCacheIntegrationsHandle(ElastiCacheIntegrationsSession session, ElastiCacheIntegrationsClientOptions options)
    {
        _session = session;
        _originHandler = options.OriginHandler;
        _logger = ElastiCacheIntegrationsLog.CreateLogger(options.LoggerFactory);
    }

    /// <summary>True while the session has a live connection.</summary>
    public bool IsOpen => !_disposed && _session.IsOpen;

    /// <summary>Connects, authenticates and fetches the cache config.</summary>
    /// <exception cref="ElastiCacheIntegrationsConfigurationException">Bad options, or overrides the cache rejects.</exception>
    /// <exception cref="ElastiCacheIntegrationsConnectionException">Every bring-up attempt failed.</exception>
    public static async Task<ElastiCacheIntegrationsHandle> OpenAsync(
        ElastiCacheIntegrationsClientOptions options,
        AWSCredentials credentials,
        RegionEndpoint region,
        string service = "dynamodb",
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(options);
        ArgumentNullException.ThrowIfNull(credentials);
        ArgumentNullException.ThrowIfNull(region);
        OptionsValidator.Validate(options);
        var auth = AuthSetup.CreateProvider(options, credentials, region);
        var session = await ElastiCacheIntegrationsSession.OpenAsync(options, auth, service, cancellationToken).ConfigureAwait(false);
        return new ElastiCacheIntegrationsHandle(session, options);
    }

    /// <summary>Sets <paramref name="config"/>'s <see cref="ClientConfig.HttpClientFactory"/> to route through this session.</summary>
    /// <exception cref="ElastiCacheIntegrationsConfigurationException">The config already has an HttpClientFactory.</exception>
    /// <exception cref="ObjectDisposedException">The handle is closed.</exception>
    public void Attach(ClientConfig config)
    {
        ArgumentNullException.ThrowIfNull(config);
        ObjectDisposedException.ThrowIf(_disposed, this);
        EnsureAttachable(config);
        config.HttpClientFactory = new ElastiCacheIntegrationsHttpClientFactory(SendAsync, _session.Service, _originHandler, WarnSyncCall);
    }

    /// <summary>Closes the session: requests already sent fail at once, the rest go to the origin. Idempotent.</summary>
    public void Dispose()
    {
        _disposed = true;
        _session.Dispose();
    }

    /// <summary>Closes the session, draining requests already sent; the rest go to the origin. Idempotent.</summary>
    public async ValueTask DisposeAsync()
    {
        _disposed = true;
        await _session.DisposeAsync().ConfigureAwait(false);
    }

    internal static void EnsureAttachable(ClientConfig config)
    {
        if (config.HttpClientFactory is not null)
        {
            throw new ElastiCacheIntegrationsConfigurationException("The SDK config already has an HttpClientFactory; the client cannot wrap it.");
        }
    }

    private void WarnSyncCall() => ElastiCacheIntegrationsLog.WarnSyncCallToOrigin(_logger, _syncCallWarned);

    // Once the handle is closed, attached clients go to the origin, as in Node and Java.
    internal async Task<ElastiCacheIntegrationsOutcome> SendAsync(ElastiCacheIntegrationsRequest request, CancellationToken cancellationToken)
    {
        if (_disposed)
        {
            return ElastiCacheIntegrationsOutcome.Origin;
        }

        try
        {
            var outcome = await _session.RequestAsync(request, cancellationToken).ConfigureAwait(false);
            return _disposed && outcome.Error is ObjectDisposedException ? ElastiCacheIntegrationsOutcome.Origin : outcome;
        }
        catch (ObjectDisposedException) when (_disposed)
        {
            return ElastiCacheIntegrationsOutcome.Origin;
        }
    }
}
