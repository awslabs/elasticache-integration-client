// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using System.Net;
using Amazon.Runtime;

namespace Amazon.ElastiCache.Integrations;

/// <summary>Offers asynchronous SDK requests to an integration handle.</summary>
internal sealed class ElastiCacheIntegrationsHttpHandler : HttpMessageHandler
{
    private readonly ElastiCacheIntegrationsHandle _handle;
    private readonly HttpMessageInvoker _origin;

    public ElastiCacheIntegrationsHttpHandler(
        ElastiCacheIntegrationsHandle handle,
        HttpMessageHandler origin,
        bool disposeOrigin)
    {
        _handle = handle;
        _origin = new HttpMessageInvoker(origin, disposeOrigin);
    }

    protected override async Task<HttpResponseMessage> SendAsync(
        HttpRequestMessage request,
        CancellationToken cancellationToken)
    {
        var response = await _handle.InterceptAsync(request, cancellationToken).ConfigureAwait(false);
        if (response is not null)
        {
            response.RequestMessage ??= request;
            return response;
        }

        return await _origin.SendAsync(request, cancellationToken).ConfigureAwait(false);
    }

    // The integration seam is intentionally async. The net8.0 DynamoDB service
    // APIs use SendAsync; a direct synchronous transport call retains the
    // original SDK origin path instead of blocking on asynchronous interceptor
    // work or failing an otherwise valid request.
    protected override HttpResponseMessage Send(
        HttpRequestMessage request,
        CancellationToken cancellationToken) => _origin.Send(request, cancellationToken);

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _origin.Dispose();
        }

        base.Dispose(disposing);
    }
}

/// <summary>Places the integration handler in the AWS SDK HTTP client path.</summary>
internal sealed class ElastiCacheIntegrationsHttpClientFactory(
    ElastiCacheIntegrationsHandle handle,
    HttpMessageHandler? originHandler) : HttpClientFactory
{
    public override HttpClient CreateHttpClient(IClientConfig clientConfig)
    {
        var handler = originHandler is null
            ? new ElastiCacheIntegrationsHttpHandler(handle, CreateOrigin(clientConfig), disposeOrigin: true)
            : new ElastiCacheIntegrationsHttpHandler(handle, originHandler, disposeOrigin: false);
        var client = new HttpClient(handler, disposeHandler: true);
        if (clientConfig.Timeout is { } timeout)
        {
            client.Timeout = timeout;
        }

        return client;
    }

    public override bool UseSDKHttpClientCaching(IClientConfig clientConfig) => true;

    public override bool DisposeHttpClientsAfterUse(IClientConfig clientConfig) => false;

    public override string? GetConfigUniqueString(IClientConfig clientConfig) => null;

    private static SocketsHttpHandler CreateOrigin(IClientConfig config)
    {
        var handler = new SocketsHttpHandler
        {
            EnableMultipleHttp2Connections = true,
            AllowAutoRedirect = config.AllowAutoRedirect,
            AutomaticDecompression = DecompressionMethods.None,
        };
        if (config.ConnectTimeout is { } connectTimeout)
        {
            handler.ConnectTimeout = connectTimeout;
        }

        if (config.MaxConnectionsPerServer is { } maxConnections)
        {
            handler.MaxConnectionsPerServer = maxConnections;
        }

        if (config.GetWebProxy() is { } proxy)
        {
            handler.Proxy = proxy;
        }

        if (handler.Proxy is not null && config.ProxyCredentials is not null)
        {
            handler.Proxy.Credentials = config.ProxyCredentials;
        }

        return handler;
    }
}
