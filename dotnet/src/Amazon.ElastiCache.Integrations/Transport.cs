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
        ElastiCacheIntegrationsClientOptions options, ILogger logger, TimeProvider time, CancellationToken cancellationToken) =>
        (await ConnectSocketAsync(options, logger, time, cancellationToken).ConfigureAwait(false)).Stream;

    /// <inheritdoc cref="ConnectAsync"/>
    /// <returns>The stream, and the socket under it.</returns>
    public static async Task<(Stream Stream, Socket Socket)> ConnectSocketAsync(
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
            return (stream, socket);
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

/// <summary>
/// Holds back read wake-ups while a large value arrives, with <c>SO_RCVLOWAT</c>: the socket reports readable
/// only once that many bytes are queued. The cache can send a large reply as hundreds of small TCP segments,
/// and each wake-up of an async read costs thread pool work. Not thread safe: the reader task owns it.
/// </summary>
internal sealed class ReceiveLowWater(Socket socket)
{
    /// <summary>Bytes of the value the TLS layer may already hold, read from the socket but not yet returned: a few records.</summary>
    internal const int TlsMargin = 64 * 1024;

    /// <summary>The smallest threshold worth setting.</summary>
    internal const int MinThreshold = 16 * 1024;

    internal const int MaxThreshold = 1024 * 1024;

    private bool _unsupported;

    /// <summary>The threshold in force; 1 is the socket default.</summary>
    internal int Current { get; private set; } = 1;

    /// <summary>Called before each read with the bytes the value still needs, 0 when unknown.</summary>
    public void Update(long missing)
    {
        var target = Target(missing, Current);
        if (target == Current || _unsupported)
        {
            return;
        }

        try
        {
            socket.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReceiveLowWater, target);
            Current = target;
        }
        catch (Exception e) when (e is SocketException or PlatformNotSupportedException or ObjectDisposedException)
        {
            // Windows has no SO_RCVLOWAT; a closed socket fails the read that follows.
            _unsupported = true;
        }
    }

    /// <summary>
    /// Never above the bytes still to reach the socket (<paramref name="missing"/> less <see cref="TlsMargin"/>),
    /// so the read always completes. A new threshold is half of that, so a value takes a few wake-ups
    /// and a few system calls whatever its size.
    /// </summary>
    internal static int Target(long missing, int current)
    {
        var allowed = missing - TlsMargin;
        if (current > 1 && current <= allowed)
        {
            return current;
        }

        return allowed >= 2L * MinThreshold ? (int)Math.Min(allowed / 2, MaxThreshold)
            : allowed >= MinThreshold ? (int)allowed
            : 1;
    }
}
