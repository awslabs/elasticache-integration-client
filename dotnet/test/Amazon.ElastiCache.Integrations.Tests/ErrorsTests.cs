// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

namespace Amazon.ElastiCache.Integrations.Tests;

public class ErrorsTests
{
    [Fact]
    public void EveryErrorKeepsMessageAndCause()
    {
        var cause = new IOException("socket");
        ElastiCacheIntegrationsException[] errors =
        [
            new ElastiCacheIntegrationsConfigurationException("m", cause),
            new ElastiCacheIntegrationsConnectionException("m", cause),
            new ElastiCacheIntegrationsCacheUnavailableException("m", cause),
            new ElastiCacheIntegrationsConnectionLostException("m", cause),
            new ElastiCacheIntegrationsProtocolException("m", cause),
            new ElastiCacheIntegrationsTimeoutException("m", cause),
            new ElastiCacheIntegrationsPendingLimitException("m", cause),
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
        Assert.Null(new ElastiCacheIntegrationsTimeoutException("m").InnerException);
    }

    [Fact]
    public void CacheUnavailableIsAConnectionError()
    {
        Assert.IsAssignableFrom<ElastiCacheIntegrationsConnectionException>(new ElastiCacheIntegrationsCacheUnavailableException("down"));
    }

    [Fact]
    public void ServerErrorKeepsCodeAndText()
    {
        var error = new ElastiCacheIntegrationsServerException("UNSUPPORTED", "operation not supported");

        Assert.Equal("UNSUPPORTED", error.Code);
        Assert.Equal("operation not supported", error.ServerMessage);
        Assert.Equal("UNSUPPORTED operation not supported", error.Message);
    }

    [Fact]
    public void ServerErrorWithoutTextUsesCodeAsMessage()
    {
        var error = new ElastiCacheIntegrationsServerException("AUTH_ERROR", "");

        Assert.Equal("AUTH_ERROR", error.Message);
        Assert.Equal("", error.ServerMessage);
    }

    [Fact]
    public void ServerErrorWithNullTextUsesCodeAsMessage()
    {
        var error = new ElastiCacheIntegrationsServerException("AUTH_ERROR", null);

        Assert.Equal("AUTH_ERROR", error.Message);
        Assert.Equal("", error.ServerMessage);
    }

    [Theory]
    [InlineData("")]
    [InlineData("-UNSUPPORTED")]
    [InlineData("UNSUPPORTED ")]
    public void ServerErrorRequiresBareCode(string code)
    {
        Assert.Throws<ArgumentException>(() => new ElastiCacheIntegrationsServerException(code, "text"));
    }

    [Fact]
    public void ServerErrorRejectsNullCode()
    {
        Assert.Throws<ArgumentNullException>(() => new ElastiCacheIntegrationsServerException(null!, "text"));
    }
}
