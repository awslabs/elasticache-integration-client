// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using Amazon.DynamoDBv2;
using Amazon.Runtime;

namespace Amazon.ElastiCache.Integrations;

/// <summary>Installs an HTTP interceptor into AWS SDK DynamoDB clients.</summary>
public static class ElastiCacheIntegrationsClient
{
    /// <summary>
    /// Installs <paramref name="interceptor"/> into a DynamoDB configuration.
    /// The returned handle owns the interceptor; the caller continues to own
    /// <paramref name="originHandler"/>.
    /// </summary>
    public static ElastiCacheIntegrationsHandle Install(
        AmazonDynamoDBConfig config,
        IElastiCacheIntegrationsRequestInterceptor interceptor,
        HttpMessageHandler? originHandler = null)
    {
        ArgumentNullException.ThrowIfNull(config);
        ArgumentNullException.ThrowIfNull(interceptor);

        var handle = new ElastiCacheIntegrationsHandle(interceptor, originHandler);
        try
        {
            handle.Attach(config);
            return handle;
        }
        catch
        {
            handle.Dispose();
            throw;
        }
    }

    /// <summary>
    /// Creates a DynamoDB client that owns its installed integration.
    /// Credentials remain the AWS SDK's responsibility and are not used by the
    /// integration foundation.
    /// </summary>
    public static ElastiCacheIntegrationsDynamoDBClient CreateDynamoDBClient(
        AWSCredentials credentials,
        AmazonDynamoDBConfig config,
        IElastiCacheIntegrationsRequestInterceptor interceptor,
        HttpMessageHandler? originHandler = null)
    {
        ArgumentNullException.ThrowIfNull(credentials);
        ArgumentNullException.ThrowIfNull(config);
        ArgumentNullException.ThrowIfNull(interceptor);

        var handle = Install(config, interceptor, originHandler);
        try
        {
            return new ElastiCacheIntegrationsDynamoDBClient(credentials, config, handle);
        }
        catch
        {
            config.HttpClientFactory = null;
            handle.Dispose();
            throw;
        }
    }
}

/// <summary>An AWS SDK DynamoDB client that owns its integration handle.</summary>
public sealed class ElastiCacheIntegrationsDynamoDBClient : AmazonDynamoDBClient, IAsyncDisposable
{
    internal ElastiCacheIntegrationsDynamoDBClient(
        AWSCredentials credentials,
        AmazonDynamoDBConfig config,
        ElastiCacheIntegrationsHandle handle)
        : base(credentials, config) => Handle = handle;

    /// <summary>The installed integration owned by this client.</summary>
    public ElastiCacheIntegrationsHandle Handle { get; }

    /// <summary>Drains active interception calls before disposing the SDK client.</summary>
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

    /// <inheritdoc />
    protected override void Dispose(bool disposing)
    {
        try
        {
            if (disposing)
            {
                Handle.Dispose();
            }
        }
        finally
        {
            base.Dispose(disposing);
        }
    }
}
