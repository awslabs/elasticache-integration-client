using Amazon.DynamoDBv2;
using Amazon.Runtime;
using Microsoft.Extensions.Logging;

namespace Amazon.Photon;

/// <summary>Builds AWS SDK clients that serve routed calls through Photon.</summary>
public static class PhotonClient
{
    /// <summary>
    /// Connects, authenticates and fetches the cache config, then returns a DynamoDB client that
    /// uses it. Credentials and region are resolved once and shared by Photon and the client.
    /// </summary>
    /// <param name="options">The cache to use.</param>
    /// <param name="config">The SDK config; Photon sets its <see cref="ClientConfig.HttpClientFactory"/>.</param>
    /// <param name="credentials">The credentials; default: the SDK default chain.</param>
    /// <param name="cancellationToken">Cancels the bring-up.</param>
    /// <exception cref="PhotonConfigurationException">Bad options, a region mismatch, a config with its own HttpClientFactory, no credentials, or overrides the cache rejects.</exception>
    /// <exception cref="PhotonConnectionException">Every bring-up attempt failed.</exception>
    /// <exception cref="OperationCanceledException">The caller cancelled.</exception>
    /// <remarks>
    /// The config's region, as the SDK resolves it (including <c>AWS_REGION</c> and the profile), must match
    /// <see cref="PhotonClientOptions.Region"/> when both are set.
    /// </remarks>
    public static async Task<PhotonDynamoDBClient> CreateDynamoDBClientAsync(
        PhotonClientOptions options,
        AmazonDynamoDBConfig? config = null,
        AWSCredentials? credentials = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(options);
        OptionsValidator.Validate(options);
        config ??= new AmazonDynamoDBConfig();
        PhotonHandle.EnsureAttachable(config);
        var region = AuthSetup.ResolveRegion(options.Region, config.RegionEndpoint);

        // Setting RegionEndpoint clears ServiceURL, so a custom endpoint is left alone.
        var setRegion = config.RegionEndpoint is null && config.ServiceURL is null;
        if (setRegion)
        {
            config.RegionEndpoint = region;
        }

        PhotonHandle? handle = null;
        var attached = false;
        try
        {
            var resolved = await AuthSetup.ResolveCredentialsAsync(credentials, config, cancellationToken).ConfigureAwait(false);
            handle = await PhotonHandle.OpenAsync(options, resolved, region, "dynamodb", cancellationToken).ConfigureAwait(false);
            handle.Attach(config);
            attached = true;
            return new PhotonDynamoDBClient(resolved, config, handle);
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

/// <summary>An <see cref="AmazonDynamoDBClient"/> that owns its Photon session and closes it on dispose.</summary>
public sealed class PhotonDynamoDBClient : AmazonDynamoDBClient, IAsyncDisposable
{
    internal PhotonDynamoDBClient(AWSCredentials credentials, AmazonDynamoDBConfig config, PhotonHandle handle)
        : base(credentials, config) => Handle = handle;

    /// <summary>The Photon session this client uses.</summary>
    public PhotonHandle Handle { get; }

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

/// <summary>One Photon session, attachable to any number of SDK client configs. Thread safe.</summary>
public sealed class PhotonHandle : IDisposable, IAsyncDisposable
{
    private readonly PhotonSession _session;
    private readonly HttpMessageHandler? _originHandler;
    private readonly ILogger _logger;
    private readonly OnceFlag _syncCallWarned = new();
    private volatile bool _disposed;

    internal PhotonHandle(PhotonSession session, PhotonClientOptions options)
    {
        _session = session;
        _originHandler = options.OriginHandler;
        _logger = PhotonLog.CreateLogger(options.LoggerFactory);
    }

    /// <summary>True while the session has a live connection.</summary>
    public bool IsOpen => !_disposed && _session.IsOpen;

    /// <summary>Connects, authenticates and fetches the cache config.</summary>
    /// <exception cref="PhotonConfigurationException">Bad options, or overrides the cache rejects.</exception>
    /// <exception cref="PhotonConnectionException">Every bring-up attempt failed.</exception>
    public static async Task<PhotonHandle> OpenAsync(
        PhotonClientOptions options,
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
        var session = await PhotonSession.OpenAsync(options, auth, service, cancellationToken).ConfigureAwait(false);
        return new PhotonHandle(session, options);
    }

    /// <summary>Sets <paramref name="config"/>'s <see cref="ClientConfig.HttpClientFactory"/> to route through this session.</summary>
    /// <exception cref="PhotonConfigurationException">The config already has an HttpClientFactory.</exception>
    /// <exception cref="ObjectDisposedException">The handle is closed.</exception>
    public void Attach(ClientConfig config)
    {
        ArgumentNullException.ThrowIfNull(config);
        ObjectDisposedException.ThrowIf(_disposed, this);
        EnsureAttachable(config);
        config.HttpClientFactory = new PhotonHttpClientFactory(SendAsync, _session.Service, _originHandler, WarnSyncCall);
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
            throw new PhotonConfigurationException("The SDK config already has an HttpClientFactory; Photon cannot wrap it.");
        }
    }

    private void WarnSyncCall() => PhotonLog.WarnSyncCallToOrigin(_logger, _syncCallWarned);

    // Once the handle is closed, attached clients go to the origin, as in Node and Java.
    internal async Task<PhotonOutcome> SendAsync(PhotonRequest request, CancellationToken cancellationToken)
    {
        if (_disposed)
        {
            return PhotonOutcome.Origin;
        }

        try
        {
            var outcome = await _session.RequestAsync(request, cancellationToken).ConfigureAwait(false);
            return _disposed && outcome.Error is ObjectDisposedException ? PhotonOutcome.Origin : outcome;
        }
        catch (ObjectDisposedException) when (_disposed)
        {
            return PhotonOutcome.Origin;
        }
    }
}
