// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;

namespace Amazon.ElastiCache.Integrations;

/// <summary>Opens the byte stream to the cache: TCP and TLS.</summary>
internal static class Transport
{
    /// <summary>TCP connect and the TLS handshake, together bounded by <see cref="ElastiCacheIntegrationsClientOptions.ConnectTimeoutMs"/>.</summary>
    /// <exception cref="ElastiCacheIntegrationsConnectionException">Connect or handshake failed or timed out.</exception>
    public static async Task<Stream> ConnectAsync(
        ElastiCacheIntegrationsClientOptions options, CancellationToken cancellationToken)
    {
        var (host, port) = OptionsValidator.ParseEndpoint(options.Endpoint);
        using var timeout = new CancellationTokenSource(options.ConnectTimeoutMs);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, timeout.Token);
        var socket = new Socket(SocketType.Stream, ProtocolType.Tcp) { NoDelay = true };
        Stream? stream = null;
        var connected = false;
        try
        {
            await socket.ConnectAsync(host, port, linked.Token).ConfigureAwait(false);
            stream = new NetworkStream(socket, ownsSocket: true);
            var tls = new SslStream(stream, leaveInnerStreamOpen: false);
            stream = tls;
            await tls.AuthenticateAsClientAsync(TlsOptions(options, host), linked.Token).ConfigureAwait(false);

            connected = true;
            return stream;
        }
        catch (OperationCanceledException e) when (timeout.IsCancellationRequested && !cancellationToken.IsCancellationRequested)
        {
            throw new ElastiCacheIntegrationsConnectionException($"Timed out connecting to {host}:{port}.", e);
        }
        catch (Exception e) when (e is not OperationCanceledException)
        {
            throw new ElastiCacheIntegrationsConnectionException($"Failed to connect to {host}:{port}.", e);
        }
        finally
        {
            if (!connected)
            {
                if (stream is not null)
                {
                    await stream.DisposeAsync().ConfigureAwait(false);
                }

                socket.Dispose();
            }
        }
    }

    internal static SslClientAuthenticationOptions TlsOptions(ElastiCacheIntegrationsClientOptions options, string host)
    {
        var tls = new SslClientAuthenticationOptions
        {
            TargetHost = options.TlsServerName ?? host,
            EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
        };
        if (options.CaCertificates is { } roots)
        {
            // SslStream skips revocation checks by default; a custom chain policy keeps that default.
            var policy = new X509ChainPolicy
            {
                TrustMode = X509ChainTrustMode.CustomRootTrust,
                RevocationMode = X509RevocationMode.NoCheck,
            };
            policy.CustomTrustStore.AddRange(roots);
            tls.CertificateChainPolicy = policy;
        }

        return tls;
    }
}
