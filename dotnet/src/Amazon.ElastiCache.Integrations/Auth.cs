using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using Amazon.Runtime;
using Amazon.Runtime.Credentials;

namespace Amazon.ElastiCache.Integrations;

/// <summary>Mints a fresh IAM token, from freshly read credentials, for every <c>AUTH</c>.</summary>
internal sealed class IamAuthTokenProvider : IElastiCacheIntegrationsAuthProvider
{
    internal const string NoCredentialsMessage = "No AWS credentials available for IAM auth token generation";
    internal const string FailedMessage = "Failed to generate IAM auth token";

    private readonly string _cacheName;
    private readonly string _userId;
    private readonly string _region;
    private readonly AWSCredentials _credentials;
    private readonly TimeProvider _time;

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
        ImmutableCredentials? resolved;
        Task<ImmutableCredentials>? read = null;
        try
        {
            read = _credentials.GetCredentialsAsync();
            resolved = await read.WaitAsync(cancellationToken).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            // The SDK read cannot be cancelled; observe a later fault so it is not reported as unobserved.
            _ = read?.ContinueWith(static t => _ = t.Exception, CancellationToken.None,
                TaskContinuationOptions.OnlyOnFaulted | TaskContinuationOptions.ExecuteSynchronously, TaskScheduler.Default);
            throw;
        }
        catch (Exception e)
        {
            throw new ElastiCacheIntegrationsConnectionException(FailedMessage, e);
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

/// <summary>Picks the region, the credentials and the auth provider for a session.</summary>
internal static class AuthSetup
{
    internal const string RegionRequiredMessage = "region is required for IAM authentication.";

    /// <summary><paramref name="optionsRegion"/>, else the SDK config's region, else the SDK default chain.</summary>
    public static RegionEndpoint ResolveRegion(string? optionsRegion, RegionEndpoint? configRegion, Func<RegionEndpoint?>? defaultRegion = null)
    {
        if (optionsRegion is not null)
        {
            if (configRegion is not null && configRegion.SystemName != optionsRegion)
            {
                throw new ElastiCacheIntegrationsConfigurationException(
                    $"Region '{optionsRegion}' does not match the DynamoDB client region '{configRegion.SystemName}'.");
            }

            return RegionEndpoint.GetBySystemName(optionsRegion);
        }

        if (configRegion is not null)
        {
            return configRegion;
        }

        RegionEndpoint? region;
        try
        {
            region = (defaultRegion ?? (() => FallbackRegionFactory.GetRegionEndpoint()))();
        }
        catch (Exception e)
        {
            throw new ElastiCacheIntegrationsConfigurationException(RegionRequiredMessage, e);
        }

        return region ?? throw new ElastiCacheIntegrationsConfigurationException(RegionRequiredMessage);
    }

    // One resolver per process, so its cache and file watchers are shared.
    private static readonly Lazy<DefaultAWSCredentialsIdentityResolver> DefaultResolver = new(() => new DefaultAWSCredentialsIdentityResolver());

    /// <summary>The given credentials, else the SDK default chain.</summary>
    public static async Task<AWSCredentials> ResolveCredentialsAsync(AWSCredentials? credentials, IClientConfig? config, CancellationToken cancellationToken,
        Func<IClientConfig?, CancellationToken, Task<AWSCredentials>>? defaultChain = null)
    {
        if (credentials is not null)
        {
            return credentials;
        }

        try
        {
            var resolved = await (defaultChain ?? ((c, ct) => DefaultResolver.Value.ResolveIdentityAsync(c, ct)))(config, cancellationToken)
                .ConfigureAwait(false);
            return resolved ?? throw new ElastiCacheIntegrationsConfigurationException(IamAuthTokenProvider.NoCredentialsMessage);
        }
        catch (ElastiCacheIntegrationsConfigurationException)
        {
            throw;
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception e)
        {
            throw new ElastiCacheIntegrationsConfigurationException(IamAuthTokenProvider.NoCredentialsMessage, e);
        }
    }

    /// <summary><see cref="ElastiCacheIntegrationsClientOptions.AuthProvider"/> when set, else IAM tokens.</summary>
    public static IElastiCacheIntegrationsAuthProvider CreateProvider(ElastiCacheIntegrationsClientOptions options, AWSCredentials credentials, RegionEndpoint region, TimeProvider? timeProvider = null) =>
        options.AuthProvider ?? new IamAuthTokenProvider(options.CacheName, options.UserId, region.SystemName, credentials, timeProvider);
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
