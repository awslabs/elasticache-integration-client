using System.Text.Json;

namespace Amazon.ElastiCache.Integrations.Tests;

public class RequestOutcomesTests
{
    private static readonly ElastiCacheIntegrationsResponse Response = new(200, new Dictionary<string, string>(), "{}"u8.ToArray());

    private static RequestReply Rejected(string code) => new RequestReply.Rejected(new ElastiCacheIntegrationsServerException(code, "no"));

    private static ElastiCacheIntegrationsOutcome FromReply(RequestReply reply, bool replayable = true, bool connectionOpen = true) =>
        RequestOutcomes.FromReply(reply, replayable, connectionOpen);

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
    [InlineData("INTERNAL_ERROR")]
    [InlineData("SOMETHING_NEW")]
    public void ARejectionFailsForEveryOperation(string code)
    {
        foreach (var replayable in new[] { true, false })
        {
            var outcome = FromReply(Rejected(code), replayable);

            Assert.Equal(OutcomeKind.Terminal, outcome.Kind);
            Assert.Equal(code, Assert.IsType<ElastiCacheIntegrationsServerException>(outcome.Error).Code);
        }
    }

    [Fact]
    public void AnOriginErrorIsRetryableOnlyWhenReplayable()
    {
        Assert.Equal(OutcomeKind.Retryable, FromReply(Rejected("ORIGIN_ERROR")).Kind);

        var outcome = FromReply(Rejected("ORIGIN_ERROR"), replayable: false);
        Assert.Equal(OutcomeKind.Terminal, outcome.Kind);
        Assert.Equal("ORIGIN_ERROR", Assert.IsType<ElastiCacheIntegrationsServerException>(outcome.Error).Code);

        Assert.Equal(OutcomeKind.Terminal, FromReply(Rejected("ORIGIN_ERROR"), connectionOpen: false).Kind);
    }

    [Theory]
    [InlineData("MOVED")]
    [InlineData("NOAUTH")]
    public void ACommandTheCacheDidNotRunIsRetryableForEveryOperation(string code)
    {
        Assert.Equal(OutcomeKind.Retryable, FromReply(Rejected(code)).Kind);
        Assert.Equal(OutcomeKind.Retryable, FromReply(Rejected(code), replayable: false).Kind);
    }

    [Fact]
    public void AnAuthErrorGoesToDynamoDBForEveryOperation()
    {
        Assert.Equal(ElastiCacheIntegrationsOutcome.Origin, FromReply(Rejected("AUTH_ERROR")));
        Assert.Equal(ElastiCacheIntegrationsOutcome.Origin, FromReply(Rejected("AUTH_ERROR"), replayable: false));
    }

    [Fact]
    public void ADeferredReplyIsIndeterminate()
    {
        var retried = FromReply(new RequestReply.Deferred("7"));
        Assert.Equal(OutcomeKind.Retryable, retried.Kind);
        Assert.IsType<ElastiCacheIntegrationsProtocolException>(retried.Error);

        Assert.Equal(OutcomeKind.Terminal, FromReply(new RequestReply.Deferred("7"), replayable: false).Kind);

        // Only ORIGIN_ERROR needs the connection open; a corrupt reply is retried like a lost connection.
        Assert.Equal(OutcomeKind.Retryable, FromReply(new RequestReply.Deferred("7"), connectionOpen: false).Kind);
    }

    [Fact]
    public void TheDefaultOutcomeGoesToDynamoDB() => Assert.Equal(ElastiCacheIntegrationsOutcome.Origin, default);

    [Fact]
    public void APendingLimitIsRetryableForEveryOperation()
    {
        var error = new ElastiCacheIntegrationsPendingLimitException("full");

        Assert.Equal(ElastiCacheIntegrationsOutcome.Retryable(error), RequestOutcomes.FromException(error, replayable: true));
        Assert.Equal(ElastiCacheIntegrationsOutcome.Retryable(error), RequestOutcomes.FromException(error, replayable: false));
    }

    [Fact]
    public void ARequestThatMayHaveRunIsRetryableOnlyWhenReplayable()
    {
        foreach (var error in new Exception[] { new ElastiCacheIntegrationsTimeoutException("t"), new ElastiCacheIntegrationsConnectionLostException("l"), new ElastiCacheIntegrationsProtocolException("p") })
        {
            Assert.Equal(ElastiCacheIntegrationsOutcome.Retryable(error), RequestOutcomes.FromException(error, replayable: true));
            Assert.Equal(ElastiCacheIntegrationsOutcome.Terminal(error), RequestOutcomes.FromException(error, replayable: false));
        }
    }

    [Fact]
    public void OtherExceptionsAreNotMapped()
    {
        Assert.Null(RequestOutcomes.FromException(new OperationCanceledException(), true));
        Assert.Null(RequestOutcomes.FromException(new InvalidOperationException(), true));
    }

    [Fact]
    public void TheEmbeddedCatalogIsTheSharedFile()
    {
        using var shared = JsonDocument.Parse(File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, "shared", "idempotent-operations.json")));
        var expected = shared.RootElement.EnumerateObject()
            .ToDictionary(s => s.Name, s => s.Value.EnumerateArray().Select(o => o.GetString()!).Order(StringComparer.Ordinal).ToList());

        Assert.Equal(expected, IdempotentOperations.All.ToDictionary(s => s.Key, s => s.Value.Order(StringComparer.Ordinal).ToList()));
    }

    [Theory]
    [InlineData("dynamodb", "GetItem", true)]
    [InlineData("DynamoDB", "Query", true)]
    [InlineData("dynamodb", "TransactGetItems", true)]
    [InlineData("dynamodb", "PutItem", false)]
    [InlineData("dynamodb", "UpdateItem", false)]
    [InlineData("dynamodb", "DeleteItem", false)]
    [InlineData("dynamodb", "BatchWriteItem", false)]
    [InlineData("dynamodb", "TransactWriteItems", false)]
    [InlineData("dynamodb", "getitem", false)]
    [InlineData("s3", "GetItem", false)]
    public void ListsOnlyReplaySafeOperations(string service, string operation, bool listed) =>
        Assert.Equal(listed, IdempotentOperations.Contains(new RequestMetadata(service, operation)));
}
