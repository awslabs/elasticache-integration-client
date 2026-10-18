namespace Amazon.Photon.Tests;

public class ErrorsTests
{
    [Fact]
    public void EveryErrorKeepsMessageAndCause()
    {
        var cause = new IOException("socket");
        PhotonException[] errors =
        [
            new PhotonConfigurationException("m", cause),
            new PhotonConnectionException("m", cause),
            new PhotonCacheUnavailableException("m", cause),
            new PhotonConnectionLostException("m", cause),
            new PhotonProtocolException("m", cause),
            new PhotonTimeoutException("m", cause),
            new PhotonPendingLimitException("m", cause),
        ];

        Assert.All(errors, e =>
        {
            Assert.Equal("m", e.Message);
            Assert.Same(cause, e.InnerException);
        });
    }

    [Fact]
    public void CauseDefaultsToNull()
    {
        Assert.Null(new PhotonTimeoutException("m").InnerException);
    }

    [Fact]
    public void CacheUnavailableIsAConnectionError()
    {
        Assert.IsAssignableFrom<PhotonConnectionException>(new PhotonCacheUnavailableException("down"));
    }

    [Fact]
    public void ServerErrorKeepsCodeAndText()
    {
        var error = new PhotonServerException("UNSUPPORTED", "operation not supported");

        Assert.Equal("UNSUPPORTED", error.Code);
        Assert.Equal("operation not supported", error.ServerMessage);
        Assert.Equal("UNSUPPORTED operation not supported", error.Message);
    }

    [Fact]
    public void ServerErrorWithoutTextUsesCodeAsMessage()
    {
        var error = new PhotonServerException("AUTH_ERROR", "");

        Assert.Equal("AUTH_ERROR", error.Message);
        Assert.Equal("", error.ServerMessage);
    }

    [Fact]
    public void ServerErrorWithNullTextUsesCodeAsMessage()
    {
        var error = new PhotonServerException("AUTH_ERROR", null);

        Assert.Equal("AUTH_ERROR", error.Message);
        Assert.Equal("", error.ServerMessage);
    }

    [Theory]
    [InlineData("")]
    [InlineData("-UNSUPPORTED")]
    [InlineData("UNSUPPORTED ")]
    public void ServerErrorRequiresBareCode(string code)
    {
        Assert.Throws<ArgumentException>(() => new PhotonServerException(code, "text"));
    }

    [Fact]
    public void ServerErrorRejectsNullCode()
    {
        Assert.Throws<ArgumentNullException>(() => new PhotonServerException(null!, "text"));
    }
}
