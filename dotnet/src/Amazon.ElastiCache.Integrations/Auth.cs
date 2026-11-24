// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using Amazon.Runtime;

namespace Amazon.ElastiCache.Integrations;

/// <summary>Mints a fresh IAM token, from freshly read credentials, for every <c>AUTH</c>.</summary>
internal sealed class IamAuthTokenProvider
{
    internal const string NoCredentialsMessage = "No AWS credentials available for IAM auth token generation";
    internal const string FailedMessage = "Failed to generate IAM auth token";

    private readonly string _cacheName;
    private readonly string _userId;
    private readonly string _region;
    private readonly AWSCredentials _credentials;
    private readonly TimeProvider _time;
    private Task<ImmutableCredentials>? _read;

    public IamAuthTokenProvider(string cacheName, string userId, string region, AWSCredentials credentials, TimeProvider? timeProvider = null)
    {
        _cacheName = Required(cacheName, "cacheName");
        _userId = Required(userId, "userId");
        _region = Required(region, "region");
        _credentials = credentials ?? throw new ArgumentNullException(nameof(credentials));
        _time = timeProvider ?? TimeProvider.System;
    }

    public async ValueTask<(string UserId, string Token)> GetAuthAsync(CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        ImmutableCredentials? resolved;
        try
        {
            // The SDK's credential read takes no cancellation token. Reuse an unfinished read
            // after a timeout so reconnect attempts cannot accumulate credential fetches.
            if (_read is null || _read.IsCompleted)
            {
                _read = _credentials.GetCredentialsAsync();
                _ = _read.ContinueWith(static task => _ = task.Exception, CancellationToken.None,
                    TaskContinuationOptions.OnlyOnFaulted | TaskContinuationOptions.ExecuteSynchronously, TaskScheduler.Default);
            }

            resolved = await _read.WaitAsync(cancellationToken).ConfigureAwait(false);
        }
        catch (Exception) when (!cancellationToken.IsCancellationRequested)
        {
            throw new ElastiCacheIntegrationsConnectionException(FailedMessage);
        }

        if (resolved is null)
        {
            throw new ElastiCacheIntegrationsConnectionException(NoCredentialsMessage);
        }

        return (_userId, SigV4Presigner.PresignConnect(_cacheName, _userId, _region, resolved, _time.GetUtcNow()));
    }

    public override string ToString() => $"IamAuthTokenProvider(CacheName={_cacheName}, UserId={_userId}, Region={_region})";

    internal static string Required(string? value, string name) =>
        string.IsNullOrWhiteSpace(value) ? throw new ElastiCacheIntegrationsConfigurationException($"{name} is required for IAM authentication.") : value;
}

/// <summary>SigV4 query presign of the ElastiCache <c>connect</c> action, used as the <c>AUTH</c> password.</summary>
internal static class SigV4Presigner
{
    /// <summary>Token lifetime: the most ElastiCache accepts.</summary>
    public const int TokenExpirySeconds = 900;

    private const string Service = "elasticache";
    private const string Algorithm = "AWS4-HMAC-SHA256";

    // SHA-256 of an empty body.
    private const string EmptyPayloadHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /// <summary>Returns <c>host/?Action=connect&amp;…&amp;X-Amz-Signature=…</c>, the presigned URL without its scheme.</summary>
    public static string PresignConnect(string cacheName, string userId, string region, ImmutableCredentials credentials, DateTimeOffset now)
    {
        var host = cacheName.ToLowerInvariant();
        var utc = now.UtcDateTime;
        var date = utc.ToString("yyyyMMdd", CultureInfo.InvariantCulture);
        var timestamp = utc.ToString("yyyyMMdd'T'HHmmss'Z'", CultureInfo.InvariantCulture);
        var scope = $"{date}/{region}/{Service}/aws4_request";

        // Python's order, so a token compares equal to its string. Keys need no encoding.
        List<(string Key, string Value)> query =
        [
            ("Action", "connect"),
            ("User", Encode(userId)),
            ("ResourceType", "ServerlessCache"),
            ("X-Amz-Algorithm", Algorithm),
            ("X-Amz-Credential", Encode($"{credentials.AccessKey}/{scope}")),
            ("X-Amz-Date", timestamp),
            ("X-Amz-Expires", TokenExpirySeconds.ToString(CultureInfo.InvariantCulture)),
            ("X-Amz-SignedHeaders", "host"),
        ];
        if (credentials.UseToken)
        {
            query.Add(("X-Amz-Security-Token", Encode(credentials.Token)));
        }

        var canonicalQuery = Join(query.OrderBy(p => p.Key, StringComparer.Ordinal));
        var canonicalRequest = $"GET\n/\n{canonicalQuery}\nhost:{host}\n\nhost\n{EmptyPayloadHash}";
        var stringToSign = $"{Algorithm}\n{timestamp}\n{scope}\n{Hex(SHA256.HashData(Encoding.UTF8.GetBytes(canonicalRequest)))}";

        var key = Hmac(Encoding.UTF8.GetBytes("AWS4" + credentials.SecretKey), date);
        key = Hmac(Hmac(Hmac(key, region), Service), "aws4_request");
        var signature = Hex(Hmac(key, stringToSign));

        return $"{host}/?{Join(query)}&X-Amz-Signature={signature}";
    }

    /// <summary>SigV4 URI encoding: UTF-8, every byte outside <c>A-Za-z0-9-_.~</c> as upper-case <c>%XX</c>.</summary>
    internal static string Encode(string value)
    {
        var builder = new StringBuilder(value.Length);
        foreach (var b in Encoding.UTF8.GetBytes(value))
        {
            if (b is (>= (byte)'A' and <= (byte)'Z') or (>= (byte)'a' and <= (byte)'z') or (>= (byte)'0' and <= (byte)'9')
                or (byte)'-' or (byte)'_' or (byte)'.' or (byte)'~')
            {
                builder.Append((char)b);
            }
            else
            {
                builder.Append('%').Append(b.ToString("X2", CultureInfo.InvariantCulture));
            }
        }

        return builder.ToString();
    }

    private static string Join(IEnumerable<(string Key, string Value)> pairs) =>
        string.Join('&', pairs.Select(p => $"{p.Key}={p.Value}"));

    private static byte[] Hmac(byte[] key, string data) => HMACSHA256.HashData(key, Encoding.UTF8.GetBytes(data));

    private static string Hex(byte[] bytes) => Convert.ToHexString(bytes).ToLowerInvariant();
}
