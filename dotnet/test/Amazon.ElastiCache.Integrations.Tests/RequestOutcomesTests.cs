namespace Amazon.ElastiCache.Integrations.Tests;

public class RequestOutcomesTests
{
    private static readonly ElastiCacheIntegrationsResponse Response = new(200, new Dictionary<string, string>(), "{}"u8.ToArray());

    private static RequestReply Rejected(string code) => new RequestReply.Rejected(new ElastiCacheIntegrationsServerException(code, "no"));

    private static ElastiCacheIntegrationsOutcome FromReply(RequestReply reply, bool strict = false, AuthErrorAction auth = AuthErrorAction.Origin) =>
        RequestOutcomes.FromReply(reply, strict, () => auth);

    [Fact]
    public void AServedReplyIsServedUnchanged()
    {
        var outcome = FromReply(new RequestReply.Served(Response));

        Assert.Equal(OutcomeKind.Served, outcome.Kind);
        Assert.Same(Response, outcome.Response);
    }

    [Theory]
    [InlineData("PARSE_ERROR")]
    [InlineData("UNSUPPORTED")]
    [InlineData("UNIMPLEMENTED")]
    [InlineData("REGION_MISMATCH")]
    [InlineData("CONFIG_INVALID")]
    public void ADeterministicRejectGoesToDynamoDBOrFailsWhenStrict(string code)
    {
        Assert.Equal(ElastiCacheIntegrationsOutcome.Origin, FromReply(Rejected(code)));

        var strict = FromReply(Rejected(code), strict: true);
        Assert.Equal(OutcomeKind.Terminal, strict.Kind);
        Assert.Equal(code, Assert.IsType<ElastiCacheIntegrationsServerException>(strict.Error).Code);
    }

    [Fact]
    public void AnAuthErrorFollowsTheAuthWindow()
    {
        var calls = 0;
        Assert.Equal(ElastiCacheIntegrationsOutcome.Origin, RequestOutcomes.FromReply(Rejected("AUTH_ERROR"), false, () =>
        {
            calls++;
            return AuthErrorAction.Origin;
        }));
        Assert.Equal(1, calls);

        var failed = FromReply(Rejected("AUTH_ERROR"), auth: AuthErrorAction.Fail);
        Assert.Equal(OutcomeKind.Terminal, failed.Kind);
        var error = Assert.IsType<ElastiCacheIntegrationsCacheUnavailableException>(failed.Error);
        Assert.Equal("AUTH_ERROR", Assert.IsType<ElastiCacheIntegrationsServerException>(error.InnerException).Code);
    }

    [Theory]
    [InlineData(null, false)]
    [InlineData("UNSUPPORTED", false)]
    [InlineData("UNSUPPORTED", true)]
    [InlineData("ORIGIN_ERROR", false)]
    [InlineData("MOVED", false)]
    public void OnlyAnAuthErrorConsultsTheAuthWindow(string? code, bool strict) =>
        RequestOutcomes.FromReply(code is null ? new RequestReply.Served(Response) : Rejected(code), strict, () => throw new InvalidOperationException("consulted"));

    [Theory]
    [InlineData("ORIGIN_ERROR")]
    [InlineData("INTERNAL_ERROR")]
    [InlineData("SOMETHING_NEW")]
    [InlineData("MOVED")]
    public void OtherCodesAreRetryable(string code)
    {
        var outcome = FromReply(Rejected(code), strict: true);

        Assert.Equal(OutcomeKind.Retryable, outcome.Kind);
        Assert.Equal(code, Assert.IsType<ElastiCacheIntegrationsServerException>(outcome.Error).Code);
    }

    [Fact]
    public void ADeferredReplyIsRetryable()
    {
        var outcome = RequestOutcomes.FromReply(new RequestReply.Deferred("7"), true, () => throw new InvalidOperationException("consulted"));

        Assert.Equal(OutcomeKind.Retryable, outcome.Kind);
        Assert.IsType<ElastiCacheIntegrationsProtocolException>(outcome.Error);
    }

    [Fact]
    public void TheDefaultOutcomeGoesToDynamoDB() => Assert.Equal(ElastiCacheIntegrationsOutcome.Origin, default);

    [Fact]
    public void APendingLimitIsRetryable()
    {
        var error = new ElastiCacheIntegrationsPendingLimitException("full");

        Assert.Equal(ElastiCacheIntegrationsOutcome.Retryable(error), RequestOutcomes.FromException(error));
    }

    [Fact]
    public void ARequestThatMayHaveReachedTheCacheIsRetryable()
    {
        foreach (var error in new Exception[] { new ElastiCacheIntegrationsTimeoutException("t"), new ElastiCacheIntegrationsConnectionLostException("l"), new ElastiCacheIntegrationsProtocolException("p") })
        {
            Assert.Equal(ElastiCacheIntegrationsOutcome.Retryable(error), RequestOutcomes.FromException(error));
        }
    }

    [Fact]
    public void OtherExceptionsAreNotMapped()
    {
        Assert.Null(RequestOutcomes.FromException(new OperationCanceledException()));
        Assert.Null(RequestOutcomes.FromException(new InvalidOperationException()));
    }
}
