namespace Amazon.ElastiCache.Integrations.Tests;

public class ServerErrorsTests
{
    [Theory]
    [InlineData("PARSE_ERROR", "Rejected")]
    [InlineData("UNSUPPORTED", "Rejected")]
    [InlineData("UNIMPLEMENTED", "Rejected")]
    [InlineData("REGION_MISMATCH", "Rejected")]
    [InlineData("CONFIG_INVALID", "Rejected")]
    [InlineData("AUTH_ERROR", "Auth")]
    [InlineData("MOVED", "ConnectionDrop")]
    [InlineData("NOAUTH", "ConnectionDrop")]
    [InlineData("ORIGIN_ERROR", "Origin")]
    [InlineData("INTERNAL_ERROR", "Rejected")]
    [InlineData("ERR", "Rejected")]
    [InlineData("CLUSTERDOWN", "Rejected")]
    [InlineData("ELASTICACHE_INTEGRATIONS_AUTH_ERROR", "Rejected")]
    [InlineData("unsupported", "Rejected")]
    [InlineData("", "Rejected")]
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
