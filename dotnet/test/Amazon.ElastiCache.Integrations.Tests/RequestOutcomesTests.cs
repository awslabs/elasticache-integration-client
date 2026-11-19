// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

namespace Amazon.ElastiCache.Integrations.Tests;

public class RequestOutcomesTests
{
    private static readonly ElastiCacheIntegrationsResponse Response = new(200, new Dictionary<string, string>(), "{}"u8.ToArray());

    private static RequestReply Rejected(string code) => new RequestReply.Rejected(new ElastiCacheIntegrationsServerException(code, "no"));

    private static ElastiCacheIntegrationsOutcome FromReply(RequestReply reply, bool strict = false) =>
        RequestOutcomes.FromReply(reply, strict);

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
    public void AnAuthErrorGoesToDynamoDBForEveryOperation()
    {
        Assert.Equal(ElastiCacheIntegrationsOutcome.Origin, FromReply(Rejected("AUTH_ERROR")));
        Assert.Equal(ElastiCacheIntegrationsOutcome.Origin, FromReply(Rejected("AUTH_ERROR"), strict: true));
    }

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
        var outcome = FromReply(new RequestReply.Deferred("7"));

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
