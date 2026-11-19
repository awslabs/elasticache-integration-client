// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using System.Buffers;
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
    private readonly X509Certificate2? _certificate;
    private Stream? _stream;
    private ReplyReader? _reader;

    public MockCache(X509Certificate2? certificate = null)
    {
        _certificate = certificate;
        _listener.Start();
    }

    public int Port => ((IPEndPoint)_listener.LocalEndpoint).Port;

    public string Endpoint => $"127.0.0.1:{Port}";

    /// <summary>The SNI name the client sent, once a TLS connection is accepted.</summary>
    public string? ServerName { get; private set; }

    private static CancellationToken Token => TestContext.Current.CancellationToken;

    public async Task AcceptAsync()
    {
        var socket = await _listener.AcceptSocketAsync(Token);
        Stream stream = new NetworkStream(socket, ownsSocket: true);
        _stream = stream;
        if (_certificate is not null)
        {
            var tls = new SslStream(stream);
            _stream = tls;
            await tls.AuthenticateAsServerAsync(
                new SslServerAuthenticationOptions
                {
                    ServerCertificateSelectionCallback = (_, name) =>
                    {
                        ServerName = name;
                        return _certificate;
                    },
                },
                Token);
        }

        _reader = new ReplyReader(_stream, int.MaxValue);
    }

    /// <summary>Reads at least one raw byte, such as the start of a TLS ClientHello.</summary>
    public async Task<byte> ReadByteAsync()
    {
        var buffer = new byte[1];
        await _stream!.ReadExactlyAsync(buffer, Token);
        return buffer[0];
    }

    /// <summary>The next command, as its text arguments.</summary>
    public async Task<string[]> ReadCommandAsync()
    {
        var value = await _reader!.ReadAsync(Token);
        var command = Assert.IsType<RespArray>(value);
        return [.. command.Items.Select(item => Encoding.UTF8.GetString(Assert.IsType<RespBlobString>(item).Value.Span))];
    }

    public Task SendAsync(string raw) => SendAsync(Encoding.UTF8.GetBytes(raw));

    public Task SendAsync(RespValue value)
    {
        var buffer = new ArrayBufferWriter<byte>();
        RespEncoder.WriteValue(buffer, value);
        return SendAsync(buffer.WrittenSpan.ToArray());
    }

    public async Task SendAsync(byte[] bytes)
    {
        await _stream!.WriteAsync(bytes, Token);
        await _stream.FlushAsync(Token);
    }

    /// <summary>The reply a cache gives a served request.</summary>
    public static RespValue Served(string body, int status = 200) =>
        new RespArray([new RespInteger(status), new RespBlobString("content-type:application/json"u8.ToArray()), new RespBlobString(Encoding.UTF8.GetBytes(body))]);

    public static RespPush Push(string requestId, RespValue reply) =>
        new([new RespBlobString("aws.response"u8.ToArray()), new RespBlobString(Encoding.UTF8.GetBytes(requestId)), reply]);

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
