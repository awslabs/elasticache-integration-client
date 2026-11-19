// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using Microsoft.Extensions.Time.Testing;

namespace Amazon.ElastiCache.Integrations.Tests;

public class TransportTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    private static ElastiCacheIntegrationsClientOptions Options(string endpoint, bool tls = true) => new()
    {
        Endpoint = endpoint,
        CacheName = "my-cache",
        UseTls = tls,
    };

    private static async Task SendPingAsync(Stream stream, MockCache cache)
    {
        await stream.WriteAsync("*1\r\n$4\r\nPING\r\n"u8.ToArray(), Token);
        Assert.Equal(["PING"], await cache.ReadCommandAsync());
    }

    [Fact]
    public async Task PlainTcpWhenTlsIsOff()
    {
        await using var cache = new MockCache();
        var accept = cache.AcceptAsync();

        await using var stream = await Transport.ConnectAsync(Options(cache.Endpoint, tls: false), new ListLogger(), TimeProvider.System, Token);
        await accept;

        await SendPingAsync(stream, cache);
    }

    [Theory]
    [InlineData("localhost", null, "localhost")]
    [InlineData("127.0.0.1", "localhost", "localhost")]
    [InlineData("127.0.0.1", null, null)]
    public async Task TlsTrustsTheGivenRootAndSendsTheServerName(string host, string? serverName, string? sni)
    {
        using var certificate = TestCertificate.Create();
        await using var cache = new MockCache(certificate);
        var accept = cache.AcceptAsync();
        var options = new ElastiCacheIntegrationsClientOptions
        {
            Endpoint = $"{host}:{cache.Port}",
            CacheName = "my-cache",
            TlsServerName = serverName,
            CaCertificates = [certificate],
        };

        await using var stream = await Transport.ConnectAsync(options, new ListLogger(), TimeProvider.System, Token);
        await accept;

        // .NET sends no SNI for an IP address and checks the IP against the certificate.
        Assert.Equal(sni ?? string.Empty, cache.ServerName ?? string.Empty);
        await SendPingAsync(stream, cache);
    }

    [Fact]
    public async Task TlsRejectsACertificateTheSystemDoesNotTrust()
    {
        using var certificate = TestCertificate.Create();
        await using var cache = new MockCache(certificate);
        var accept = cache.AcceptAsync();

        var error = await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionException>(
            () => Transport.ConnectAsync(Options($"localhost:{cache.Port}"), new ListLogger(), TimeProvider.System, Token));

        Assert.IsType<AuthenticationException>(error.InnerException);
        // Under TLS 1.3 the server can finish its side before the client rejects the certificate.
        await Record.ExceptionAsync(() => accept);
    }

    [Fact]
    public async Task TlsRejectsACertificateForAnotherName()
    {
        using var certificate = TestCertificate.Create();
        await using var cache = new MockCache(certificate);
        var accept = cache.AcceptAsync();
        var options = new ElastiCacheIntegrationsClientOptions
        {
            Endpoint = cache.Endpoint,
            CacheName = "my-cache",
            TlsServerName = "other.example.com",
            CaCertificates = [certificate],
        };

        var error = await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionException>(
            () => Transport.ConnectAsync(options, new ListLogger(), TimeProvider.System, Token));

        Assert.IsType<AuthenticationException>(error.InnerException);
        // Under TLS 1.3 the server can finish its side before the client rejects the certificate.
        await Record.ExceptionAsync(() => accept);
    }

    [Fact]
    public async Task InsecureSkipVerifyAcceptsAnyCertificate()
    {
        using var certificate = TestCertificate.Create("not-this-host", loopbackIp: false);
        await using var cache = new MockCache(certificate);
        var accept = cache.AcceptAsync();
        var options = new ElastiCacheIntegrationsClientOptions { Endpoint = cache.Endpoint, CacheName = "my-cache", InsecureSkipVerify = true };

        await using var stream = await Transport.ConnectAsync(options, new ListLogger(), TimeProvider.System, Token);
        await accept;

        await SendPingAsync(stream, cache);
    }

    [Fact]
    public async Task AnyHandshakeFailureIsAConnectionError()
    {
        using var certificate = TestCertificate.Create();
        await using var cache = new MockCache(certificate);
        var accept = cache.AcceptAsync();
        var root = new X509Certificate2(certificate.RawData);
        root.Dispose();
        var options = new ElastiCacheIntegrationsClientOptions { Endpoint = cache.Endpoint, CacheName = "my-cache", CaCertificates = [root] };

        await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionException>(() => Transport.ConnectAsync(options, new ListLogger(), TimeProvider.System, Token));
        await Record.ExceptionAsync(() => accept);
    }

    [Fact]
    public void CustomRootsGoInAChainPolicyWithoutRevocationChecks()
    {
        using var certificate = TestCertificate.Create();
        var options = new ElastiCacheIntegrationsClientOptions { Endpoint = "h:1", CacheName = "c", CaCertificates = [certificate] };

        var tls = Transport.TlsOptions(options, "h", new ListLogger());

        Assert.Equal("h", tls.TargetHost);
        Assert.Equal(SslProtocols.Tls12 | SslProtocols.Tls13, tls.EnabledSslProtocols);
        Assert.Null(tls.RemoteCertificateValidationCallback);
        Assert.Equal(X509ChainTrustMode.CustomRootTrust, tls.CertificateChainPolicy!.TrustMode);
        Assert.Equal(X509RevocationMode.NoCheck, tls.CertificateChainPolicy.RevocationMode);
        Assert.Contains(certificate, tls.CertificateChainPolicy.CustomTrustStore.Cast<X509Certificate2>());
    }

    [Fact]
    public async Task ARefusedPortIsAConnectionError()
    {
        int port;
        await using (var closed = new MockCache())
        {
            port = closed.Port;
        }

        var error = await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionException>(
            () => Transport.ConnectAsync(Options($"127.0.0.1:{port}", tls: false), new ListLogger(), TimeProvider.System, Token));

        Assert.Contains($"127.0.0.1:{port}", error.Message);
    }

    [Fact]
    public async Task AHandshakeThatNeverEndsTimesOutAfterConnectTimeout()
    {
        await using var cache = new MockCache();
        var accept = cache.AcceptAsync();
        var time = new FakeTimeProvider();

        var connect = Transport.ConnectAsync(Options(cache.Endpoint), new ListLogger(), time, Token);
        await accept;
        Assert.Equal(0x16, await cache.ReadByteAsync());
        time.Advance(TimeSpan.FromSeconds(1));

        var error = await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionException>(() => connect);
        Assert.StartsWith("Timed out connecting", error.Message);
    }

    [Fact]
    public async Task CallerCancellationIsNotWrapped()
    {
        await using var cache = new MockCache();
        var accept = cache.AcceptAsync();
        using var cancel = CancellationTokenSource.CreateLinkedTokenSource(Token);

        var connect = Transport.ConnectAsync(Options(cache.Endpoint), new ListLogger(), new FakeTimeProvider(), cancel.Token);
        await accept;
        Assert.Equal(0x16, await cache.ReadByteAsync());
        await cancel.CancelAsync();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => connect);
    }
}
