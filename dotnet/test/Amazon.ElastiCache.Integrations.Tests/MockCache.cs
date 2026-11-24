// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using System.Globalization;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;

namespace Amazon.ElastiCache.Integrations.Tests;

/// <summary>A self-signed certificate for <paramref name="dnsName"/> and, unless turned off, 127.0.0.1. Made per call.</summary>
internal static class TestCertificate
{
    public static X509Certificate2 Create(string dnsName = "localhost", bool loopbackIp = true)
    {
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var request = new CertificateRequest($"CN={dnsName}", key, HashAlgorithmName.SHA256);
        var names = new SubjectAlternativeNameBuilder();
        names.AddDnsName(dnsName);
        if (loopbackIp)
        {
            names.AddIpAddress(IPAddress.Loopback);
        }

        request.CertificateExtensions.Add(names.Build());
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension([new Oid("1.3.6.1.5.5.7.3.1")], critical: false));
        using var certificate = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddDays(1));

        // SslStream needs the private key in a form every platform can load.
        return new X509Certificate2(certificate.Export(X509ContentType.Pfx));
    }
}

/// <summary>
/// A scripted cache on a loopback TcpListener. A test accepts one connection, reads the
/// commands the client sends, and writes whatever replies it wants.
/// </summary>
internal sealed class MockCache : IAsyncDisposable
{
    private readonly TcpListener _listener = new(IPAddress.Loopback, 0);
    private readonly X509Certificate2 _certificate;
    private Stream? _stream;
    private ReplyReader? _reader;

    public MockCache(X509Certificate2 certificate)
    {
        _certificate = certificate;
        _listener.Start();
    }

    public int Port => ((IPEndPoint)_listener.LocalEndpoint).Port;

    public string Endpoint => $"127.0.0.1:{Port}";

    public bool HasPendingConnection => _listener.Pending();

    private static CancellationToken Token => TestContext.Current.CancellationToken;

    public async Task AcceptAsync()
    {
        if (_stream is not null)
        {
            await _stream.DisposeAsync();
        }

        var socket = await _listener.AcceptSocketAsync(Token);
        var tls = new SslStream(new NetworkStream(socket, ownsSocket: true));
        _stream = tls;
        await tls.AuthenticateAsServerAsync(new SslServerAuthenticationOptions { ServerCertificate = _certificate }, Token);
        _reader = new ReplyReader(tls, int.MaxValue);
    }

    public async Task WaitForCloseAsync()
    {
        var count = await _stream!.ReadAsync(new byte[1], Token);
        Assert.Equal(0, count);
    }

    /// <summary>The next command, as its text arguments.</summary>
    public async Task<string[]> ReadCommandAsync()
    {
        var value = await _reader!.ReadAsync(Token);
        var command = Assert.IsType<RespArray>(value);
        return [.. command.Items.Select(item => Encoding.UTF8.GetString(Assert.IsType<RespBlobString>(item).Value.Span))];
    }

    public async Task SendAsync(string frame)
    {
        await _stream!.WriteAsync(Encoding.UTF8.GetBytes(frame), Token);
        await _stream.FlushAsync(Token);
    }

    public static string Bulk(string body) =>
        FormattableString.Invariant($"${Encoding.UTF8.GetByteCount(body)}\r\n{body}\r\n");

    public static string Served(string body, int status = 200) =>
        $"*3\r\n:{status.ToString(CultureInfo.InvariantCulture)}\r\n{Bulk("content-type:application/json")}{Bulk(body)}";

    public static string Push(string requestId, string reply) => $">3\r\n$12\r\naws.response\r\n{Bulk(requestId)}{reply}";

    /// <summary>Closes the accepted connection: the client sees end of stream.</summary>
    public void Drop() => _stream?.Dispose();

    public async ValueTask DisposeAsync()
    {
        if (_stream is not null)
        {
            await _stream.DisposeAsync();
        }

        _listener.Stop();
        _listener.Dispose();
    }
}
