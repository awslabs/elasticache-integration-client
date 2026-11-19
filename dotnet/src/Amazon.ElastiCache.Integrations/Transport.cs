// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using Microsoft.Extensions.Logging;

namespace Amazon.ElastiCache.Integrations;

/// <summary>Opens the byte stream to the cache: TCP, then TLS unless it is turned off.</summary>
internal static class Transport
{
    /// <summary>TCP connect and the TLS handshake, together bounded by <see cref="ElastiCacheIntegrationsClientOptions.ConnectTimeout"/>.</summary>
    /// <exception cref="ElastiCacheIntegrationsConnectionException">Connect or handshake failed or timed out.</exception>
    public static async Task<Stream> ConnectAsync(
        ElastiCacheIntegrationsClientOptions options, ILogger logger, TimeProvider time, CancellationToken cancellationToken)
    {
        var (host, port) = OptionsValidator.ParseEndpoint(options.Endpoint);
        using var timeout = new CancellationTokenSource(options.ConnectTimeout, time);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, timeout.Token);
        var socket = new Socket(SocketType.Stream, ProtocolType.Tcp) { NoDelay = true };
        Stream? stream = null;
        var connected = false;
        try
        {
            await socket.ConnectAsync(host, port, linked.Token).ConfigureAwait(false);
            stream = new NetworkStream(socket, ownsSocket: true);
            if (options.UseTls)
            {
                var tls = new SslStream(stream, leaveInnerStreamOpen: false);
                stream = tls;
                await tls.AuthenticateAsClientAsync(TlsOptions(options, host, logger), linked.Token).ConfigureAwait(false);
            }

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

    internal static SslClientAuthenticationOptions TlsOptions(ElastiCacheIntegrationsClientOptions options, string host, ILogger logger)
    {
        var tls = new SslClientAuthenticationOptions
        {
            TargetHost = options.TlsServerName ?? host,
            EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
        };
        // InsecureSkipVerify wins over CaCertificates, as in Node.
        if (options.InsecureSkipVerify)
        {
            ElastiCacheIntegrationsLog.WarnInsecureSkipVerify(logger);
            tls.RemoteCertificateValidationCallback = static (_, _, _, _) => true;
        }
        else if (options.CaCertificates is { } roots)
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
