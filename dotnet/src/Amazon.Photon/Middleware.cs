using System.Net;
using System.Net.Http.Headers;
using System.Runtime.ExceptionServices;
using Amazon.Runtime;

namespace Amazon.Photon;

/// <summary>Converts between the SDK's HTTP messages and Photon requests and responses.</summary>
internal static class PhotonHttpAdapter
{
    private const string TargetHeader = "X-Amz-Target";

    // The SigV4 headers. The cache signs its own origin call.
    private static readonly HashSet<string> SigningHeaders = ["authorization", "x-amz-date", "x-amz-security-token"];

    // Framing headers describe the cached body, so they come from it, not from the cache.
    private static readonly HashSet<string> FramingHeaders = new(StringComparer.OrdinalIgnoreCase) { "content-length", "transfer-encoding" };

    /// <summary>
    /// The request to send to the cache, or null when it must go to the origin as it is:
    /// no absolute URI, no single <c>X-Amz-Target</c> operation, or a streaming body.
    /// </summary>
    public static async ValueTask<PhotonRequest?> TryCreateAsync(HttpRequestMessage request, string service, CancellationToken cancellationToken)
    {
        if (request.RequestUri is not { IsAbsoluteUri: true } uri || Operation(request.Headers) is not { } operation)
        {
            return null;
        }

        var body = ReadOnlyMemory<byte>.Empty;
        if (request.Content is { } content)
        {
            // Any other content is a streaming body.
            if (content is not (ByteArrayContent or ReadOnlyMemoryContent))
            {
                return null;
            }

            body = await content.ReadAsByteArrayAsync(cancellationToken).ConfigureAwait(false);
        }

        var headers = new Dictionary<string, string>(StringComparer.Ordinal);
        AddHeaders(headers, request.Headers);
        if (request.Content is not null)
        {
            AddHeaders(headers, request.Content.Headers);
        }

        headers.TryAdd("host", uri.Authority);
        return new PhotonRequest(request.Method.Method, uri.AbsoluteUri, headers, body, new RequestMetadata(service, operation));
    }

    /// <summary>The cached response as the SDK would have received it from the origin.</summary>
    public static HttpResponseMessage ToHttpResponse(PhotonResponse response, HttpRequestMessage request)
    {
        var message = new HttpResponseMessage((HttpStatusCode)response.StatusCode)
        {
            RequestMessage = request,
            Content = new ReadOnlyMemoryContent(response.Body),
        };
        foreach (var (name, value) in response.Headers)
        {
            if (FramingHeaders.Contains(name))
            {
                continue;
            }

            if (!message.Headers.TryAddWithoutValidation(name, value))
            {
                message.Content.Headers.TryAddWithoutValidation(name, value);
            }
        }

        return message;
    }

    // "DynamoDB_20120810.GetItem" -> "GetItem".
    private static string? Operation(HttpRequestHeaders headers)
    {
        if (!headers.TryGetValues(TargetHeader, out var values) || values.ToArray() is not [var target])
        {
            return null;
        }

        var operation = target[(target.LastIndexOf('.') + 1)..].Trim();
        return operation.Length == 0 ? null : operation;
    }

    private static void AddHeaders(Dictionary<string, string> into, HttpHeaders headers)
    {
        foreach (var (name, values) in headers)
        {
            var lower = name.ToLowerInvariant();
            if (!SigningHeaders.Contains(lower))
            {
                into[lower] = string.Join(", ", values);
            }
        }
    }
}

/// <summary>Sends one request through the Photon session.</summary>
internal delegate Task<PhotonOutcome> PhotonSend(PhotonRequest request, CancellationToken cancellationToken);

/// <summary>
/// The SDK's HTTP handler: routed requests go through Photon, everything else to the origin
/// with the SDK's signed request. Thread safe.
/// </summary>
internal sealed class PhotonHttpHandler : HttpMessageHandler
{
    private readonly PhotonSend _send;
    private readonly string _service;
    private readonly HttpMessageInvoker _origin;
    private readonly Action _onSyncCall;

    public PhotonHttpHandler(PhotonSend send, string service, HttpMessageHandler origin, bool disposeOrigin, Action onSyncCall)
    {
        _send = send;
        _service = service;
        _origin = new HttpMessageInvoker(origin, disposeOrigin);
        _onSyncCall = onSyncCall;
    }

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        var photonRequest = await PhotonHttpAdapter.TryCreateAsync(request, _service, cancellationToken).ConfigureAwait(false);
        if (photonRequest is null)
        {
            return await _origin.SendAsync(request, cancellationToken).ConfigureAwait(false);
        }

        var outcome = await _send(photonRequest, cancellationToken).ConfigureAwait(false);
        switch (outcome.Kind)
        {
            case OutcomeKind.Served:
                return PhotonHttpAdapter.ToHttpResponse(outcome.Response!, request);
            case OutcomeKind.Retryable:
                // The SDK retries an HttpRequestException; each retry is signed again and re-enters Photon.
                throw new HttpRequestException(outcome.Error!.Message, outcome.Error);
            case OutcomeKind.Terminal:
                ExceptionDispatchInfo.Throw(outcome.Error!);
                break;
        }

        return await _origin.SendAsync(request, cancellationToken).ConfigureAwait(false);
    }

    // Sync calls go to the origin, so no thread blocks on Photon's async work.
    protected override HttpResponseMessage Send(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        _onSyncCall();
        return _origin.Send(request, cancellationToken);
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _origin.Dispose();
        }

        base.Dispose(disposing);
    }
}

/// <summary>The <see cref="ClientConfig.HttpClientFactory"/> that puts Photon in front of the origin.</summary>
internal sealed class PhotonHttpClientFactory(PhotonSend send, string service, HttpMessageHandler? origin, Action onSyncCall) : HttpClientFactory
{
    public override HttpClient CreateHttpClient(IClientConfig clientConfig)
    {
        // A caller's origin handler is shared by every HttpClient, so none of them disposes it.
        var handler = origin is null
            ? new PhotonHttpHandler(send, service, CreateOrigin(clientConfig), disposeOrigin: true, onSyncCall)
            : new PhotonHttpHandler(send, service, origin, disposeOrigin: false, onSyncCall);
        var client = new HttpClient(handler, disposeHandler: true);
        if (clientConfig.Timeout is { } timeout)
        {
            client.Timeout = timeout;
        }

        return client;
    }

    // Cached per SDK client, so disposing the client disposes its HttpClients.
    public override bool UseSDKHttpClientCaching(IClientConfig clientConfig) => true;

    public override bool DisposeHttpClientsAfterUse(IClientConfig clientConfig) => false;

    public override string? GetConfigUniqueString(IClientConfig clientConfig) => null;

    /// <summary>The handler the SDK builds when it has no factory.</summary>
    internal static SocketsHttpHandler CreateOrigin(IClientConfig config)
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
