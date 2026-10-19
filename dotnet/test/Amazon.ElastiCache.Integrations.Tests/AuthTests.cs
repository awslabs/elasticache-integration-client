using Amazon.Runtime;
using Microsoft.Extensions.Time.Testing;

namespace Amazon.ElastiCache.Integrations.Tests;

public class IamAuthTokenProviderTests
{
    private static readonly DateTimeOffset Now = new(2026, 10, 17, 8, 30, 0, TimeSpan.Zero);

    private static readonly ImmutableCredentials Keys =
        new("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "FwoGZXIvYXdzEBYaDHqa0AP");

    [Fact]
    public async Task ReturnsUserAndTokenSignedAtTheClockTime()
    {
        var provider = new IamAuthTokenProvider("my-cache", "my-user", "us-east-1", new FakeCredentials(() => Keys), new FakeTimeProvider(Now));

        var (user, token) = await provider.GetAuthAsync(TestContext.Current.CancellationToken);

        Assert.Equal("my-user", user);
        Assert.Equal(SigV4Presigner.PresignConnect("my-cache", "my-user", "us-east-1", Keys, Now), token);
        Assert.EndsWith("5cd6d1367ef6a6c9c76ef37b1017b7158e33d14213bbed71a2cefab5059e3e9c", token);
    }

    [Fact]
    public async Task ReadsCredentialsForEveryToken()
    {
        var calls = 0;
        var credentials = new FakeCredentials(() => new ImmutableCredentials($"AKID{++calls}", "secret", null));
        var provider = new IamAuthTokenProvider("my-cache", "u", "us-east-1", credentials, new FakeTimeProvider(Now));

        var (_, first) = await provider.GetAuthAsync(TestContext.Current.CancellationToken);
        var (_, second) = await provider.GetAuthAsync(TestContext.Current.CancellationToken);

        Assert.Equal(2, calls);
        Assert.Contains("X-Amz-Credential=AKID1%2F", first);
        Assert.Contains("X-Amz-Credential=AKID2%2F", second);
    }

    [Fact]
    public async Task CredentialFailureIsWrappedWithoutItsText()
    {
        var cause = new InvalidOperationException("secret=credential-material");
        var provider = new IamAuthTokenProvider("my-cache", "u", "us-east-1", new FakeCredentials(() => throw cause));

        var error = await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionException>(() => provider.GetAuthAsync(TestContext.Current.CancellationToken).AsTask());

        Assert.Equal(IamAuthTokenProvider.FailedMessage, error.Message);
        Assert.Same(cause, error.InnerException);
    }

    [Fact]
    public async Task NullCredentialsMeanNoCredentials()
    {
        var provider = new IamAuthTokenProvider("my-cache", "u", "us-east-1", new FakeCredentials(() => null!));

        var error = await Assert.ThrowsAsync<ElastiCacheIntegrationsConnectionException>(() => provider.GetAuthAsync(TestContext.Current.CancellationToken).AsTask());

        Assert.Equal(IamAuthTokenProvider.NoCredentialsMessage, error.Message);
    }

    [Fact]
    public async Task CancellationStopsAPendingCredentialRead()
    {
        var never = new TaskCompletionSource<ImmutableCredentials>();
        var provider = new IamAuthTokenProvider("my-cache", "u", "us-east-1", new FakeCredentials(() => Keys, never.Task));
        using var cts = new CancellationTokenSource();

        var pending = provider.GetAuthAsync(cts.Token).AsTask();
        cts.Cancel();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => pending);
    }

    [Theory]
    [InlineData("", "u", "r", "cacheName")]
    [InlineData("c", " ", "r", "userId")]
    [InlineData("c", "u", "", "region")]
    public void BlankParametersAreRejected(string cache, string user, string region, string name)
    {
        var error = Assert.Throws<ElastiCacheIntegrationsConfigurationException>(() => new IamAuthTokenProvider(cache, user, region, new FakeCredentials(() => Keys)));

        Assert.Equal($"{name} is required for IAM authentication.", error.Message);
    }

    [Fact]
    public void ToStringHasNoSecrets()
    {
        var text = new IamAuthTokenProvider("my-cache", "u", "us-east-1", new FakeCredentials(() => Keys)).ToString();

        Assert.Equal("IamAuthTokenProvider(CacheName=my-cache, UserId=u, Region=us-east-1)", text);
    }

    private sealed class FakeCredentials(Func<ImmutableCredentials> get, Task<ImmutableCredentials>? pending = null) : AWSCredentials
    {
        public override ImmutableCredentials GetCredentials() => get();

        public override Task<ImmutableCredentials> GetCredentialsAsync() => pending ?? Task.FromResult(get());
    }
}

public class AuthSetupTests
{
    private static readonly RegionEndpoint NoDefault = null!;

    [Fact]
    public void OptionsRegionWinsWithoutReadingTheDefault()
    {
        var region = AuthSetup.ResolveRegion("eu-west-1", null, () => throw new InvalidOperationException("must not be called"));

        Assert.Equal("eu-west-1", region.SystemName);
    }

    [Fact]
    public void ConfigRegionIsUsedWhenOptionsHaveNone()
    {
        Assert.Same(RegionEndpoint.APSoutheast2, AuthSetup.ResolveRegion(null, RegionEndpoint.APSoutheast2, () => RegionEndpoint.USEast1));
    }

    [Fact]
    public void MatchingRegionsAreAccepted()
    {
        Assert.Equal("us-east-1", AuthSetup.ResolveRegion("us-east-1", RegionEndpoint.USEast1).SystemName);
    }

    [Fact]
    public void DifferentRegionsAreRejected()
    {
        var error = Assert.Throws<ElastiCacheIntegrationsConfigurationException>(() => AuthSetup.ResolveRegion("eu-west-1", RegionEndpoint.USEast1));

        Assert.Contains("'eu-west-1'", error.Message);
        Assert.Contains("'us-east-1'", error.Message);
    }

    [Fact]
    public void DefaultChainIsTheLastChoice()
    {
        Assert.Same(RegionEndpoint.EUCentral1, AuthSetup.ResolveRegion(null, null, () => RegionEndpoint.EUCentral1));
    }

    [Fact]
    public void DefaultChainFailureMeansRegionRequired()
    {
        var cause = new InvalidOperationException("region unavailable");

        var error = Assert.Throws<ElastiCacheIntegrationsConfigurationException>(() => AuthSetup.ResolveRegion(null, null, () => throw cause));

        Assert.Equal(AuthSetup.RegionRequiredMessage, error.Message);
        Assert.Same(cause, error.InnerException);
    }

    [Fact]
    public void NoDefaultRegionMeansRegionRequired()
    {
        var error = Assert.Throws<ElastiCacheIntegrationsConfigurationException>(() => AuthSetup.ResolveRegion(null, null, () => NoDefault));

        Assert.Equal(AuthSetup.RegionRequiredMessage, error.Message);
    }

    [Fact]
    public async Task GivenCredentialsAreUsedAsIs()
    {
        var credentials = new BasicAWSCredentials("AKID", "secret");

        Assert.Same(credentials, await AuthSetup.ResolveCredentialsAsync(credentials, null, TestContext.Current.CancellationToken));
    }

    [Fact]
    public async Task DefaultChainIsUsedWhenNoneAreGiven()
    {
        var credentials = new BasicAWSCredentials("AKID", "secret");

        var resolved = await AuthSetup.ResolveCredentialsAsync(null, null, TestContext.Current.CancellationToken, (_, _) => Task.FromResult<AWSCredentials>(credentials));

        Assert.Same(credentials, resolved);
    }

    [Fact]
    public async Task DefaultChainFailureMeansNoCredentials()
    {
        var cause = new AmazonClientException("Failed to resolve AWS credentials");

        var error = await Assert.ThrowsAsync<ElastiCacheIntegrationsConfigurationException>(() =>
            AuthSetup.ResolveCredentialsAsync(null, null, TestContext.Current.CancellationToken, (_, _) => throw cause));

        Assert.Equal(IamAuthTokenProvider.NoCredentialsMessage, error.Message);
        Assert.Same(cause, error.InnerException);
    }

    [Fact]
    public async Task NullFromTheDefaultChainMeansNoCredentials()
    {
        var error = await Assert.ThrowsAsync<ElastiCacheIntegrationsConfigurationException>(() =>
            AuthSetup.ResolveCredentialsAsync(null, null, TestContext.Current.CancellationToken, (_, _) => Task.FromResult<AWSCredentials>(null!)));

        Assert.Equal(IamAuthTokenProvider.NoCredentialsMessage, error.Message);
    }

    [Fact]
    public async Task CallerCancellationPassesThrough()
    {
        using var cts = new CancellationTokenSource();
        cts.Cancel();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() =>
            AuthSetup.ResolveCredentialsAsync(null, null, cts.Token, (_, ct) => Task.FromCanceled<AWSCredentials>(ct)));
    }

    [Fact]
    public async Task OtherCancellationMeansNoCredentials()
    {
        var error = await Assert.ThrowsAsync<ElastiCacheIntegrationsConfigurationException>(() =>
            AuthSetup.ResolveCredentialsAsync(null, null, TestContext.Current.CancellationToken, (_, _) => throw new TaskCanceledException("IMDS timeout")));

        Assert.IsType<TaskCanceledException>(error.InnerException);
    }

    [Fact]
    public void CustomAuthProviderReplacesIam()
    {
        var custom = new StaticAuthProvider();
        var options = new ElastiCacheIntegrationsClientOptions { Endpoint = "h:6379", CacheName = "c", AuthProvider = custom };

        Assert.Same(custom, AuthSetup.CreateProvider(options, new BasicAWSCredentials("AKID", "secret"), RegionEndpoint.USEast1));
    }

    [Fact]
    public void IamProviderUsesTheOptionsAndRegion()
    {
        var options = new ElastiCacheIntegrationsClientOptions { Endpoint = "h:6379", CacheName = "my-cache", UserId = "my-user" };

        var provider = AuthSetup.CreateProvider(options, new BasicAWSCredentials("AKID", "secret"), RegionEndpoint.USWest2);

        Assert.Equal("IamAuthTokenProvider(CacheName=my-cache, UserId=my-user, Region=us-west-2)", provider.ToString());
    }

    private sealed class StaticAuthProvider : IElastiCacheIntegrationsAuthProvider
    {
        public ValueTask<(string UserId, string Token)> GetAuthAsync(CancellationToken cancellationToken) => new(("u", "t"));
    }
}
