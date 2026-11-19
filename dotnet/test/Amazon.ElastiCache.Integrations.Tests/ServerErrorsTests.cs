// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

namespace Amazon.ElastiCache.Integrations.Tests;

public class ServerErrorsTests
{
    [Theory]
    [InlineData("PARSE_ERROR", "Deterministic")]
    [InlineData("UNSUPPORTED", "Deterministic")]
    [InlineData("UNIMPLEMENTED", "Deterministic")]
    [InlineData("REGION_MISMATCH", "Deterministic")]
    [InlineData("CONFIG_INVALID", "Deterministic")]
    [InlineData("AUTH_ERROR", "Auth")]
    [InlineData("MOVED", "ConnectionDrop")]
    [InlineData("NOAUTH", "ConnectionDrop")]
    [InlineData("ORIGIN_ERROR", "Retryable")]
    [InlineData("INTERNAL_ERROR", "Retryable")]
    [InlineData("ERR", "Retryable")]
    [InlineData("CLUSTERDOWN", "Retryable")]
    [InlineData("ELASTICACHE_INTEGRATIONS_AUTH_ERROR", "Retryable")]
    [InlineData("unsupported", "Retryable")]
    [InlineData("", "Retryable")]
    public void ClassifiesEveryCode(string code, string kind)
    {
        Assert.Equal(Enum.Parse<ServerErrorKind>(kind), ServerErrors.Classify(code));
    }

    [Fact]
    public void KeepsCodeAndMessage()
    {
        var e = ServerErrors.ToException(new RespError("REGION_MISMATCH", "request is for eu-west-1, cache serves us-east-1"));
        Assert.Equal("REGION_MISMATCH", e.Code);
        Assert.Equal("request is for eu-west-1, cache serves us-east-1", e.ServerMessage);
        Assert.Equal("REGION_MISMATCH request is for eu-west-1, cache serves us-east-1", e.Message);
    }

    [Fact]
    public void KeepsABlobError()
    {
        var e = ServerErrors.ToException(new RespError("ORIGIN_ERROR", "timed out", IsBlob: true));
        Assert.Equal(("ORIGIN_ERROR", "timed out"), (e.Code, e.ServerMessage));
    }

    [Theory]
    [InlineData("", "", "", "ERR")]
    [InlineData("", "text ", "text ", "ERR text ")]
    [InlineData("-X", "y", "-X y", "ERR -X y")]
    [InlineData("A\tB", "", "A\tB", "ERR A\tB")]
    public void UsesErrForAnUnusableCode(string code, string message, string serverMessage, string text)
    {
        var e = ServerErrors.ToException(new RespError(code, message));
        Assert.Equal("ERR", e.Code);
        Assert.Equal(serverMessage, e.ServerMessage);
        Assert.Equal(text, e.Message);
    }
}
