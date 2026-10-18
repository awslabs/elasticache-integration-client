using System.Text.Json;

namespace Amazon.Photon.Tests;

public class ModelsTests
{
    private static readonly Dictionary<string, string> NoHeaders = [];
    private static readonly RequestMetadata GetItem = new("dynamodb", "GetItem");

    [Fact]
    public void MetadataComparesByValue()
    {
        Assert.Equal(GetItem, new RequestMetadata("dynamodb", "GetItem"));
    }

    [Fact]
    public void MetadataLowercasesService()
    {
        Assert.Equal("dynamodb", new RequestMetadata("DynamoDB", "GetItem").Service);
    }

    [Theory]
    [InlineData("", "GetItem")]
    [InlineData("dynamodb", "")]
    public void MetadataRequiresServiceAndOperation(string service, string operation)
    {
        Assert.Throws<ArgumentException>(() => new RequestMetadata(service, operation));
    }

    [Fact]
    public void RequestKeepsItsParts()
    {
        var body = "{}"u8.ToArray();
        var headers = new Dictionary<string, string> { ["x-amz-target"] = "DynamoDB_20120810.GetItem" };
        var request = new PhotonRequest("POST", "https://dynamodb.us-east-1.amazonaws.com/", headers, body, GetItem);

        Assert.Equal("POST", request.Method);
        Assert.Equal("https://dynamodb.us-east-1.amazonaws.com/", request.Url);
        Assert.Same(headers, request.Headers);
        Assert.Equal(body, request.Body.ToArray());
        Assert.Same(GetItem, request.Metadata);
    }

    [Fact]
    public void RequestRejectsMissingParts()
    {
        Assert.Throws<ArgumentException>(() => new PhotonRequest("", "/", NoHeaders, default, GetItem));
        Assert.Throws<ArgumentException>(() => new PhotonRequest("POST", "", NoHeaders, default, GetItem));
        Assert.Throws<ArgumentNullException>(() => new PhotonRequest("POST", "/", null!, default, GetItem));
        Assert.Throws<ArgumentNullException>(() => new PhotonRequest("POST", "/", NoHeaders, default, null!));
    }

    [Theory]
    [InlineData(100)]
    [InlineData(400)]
    [InlineData(599)]
    public void ResponseAcceptsHttpStatus(int status)
    {
        Assert.Equal(status, new PhotonResponse(status, NoHeaders, default).StatusCode);
    }

    [Theory]
    [InlineData(99)]
    [InlineData(600)]
    public void ResponseRejectsNonHttpStatus(int status)
    {
        Assert.Throws<ArgumentOutOfRangeException>(() => new PhotonResponse(status, NoHeaders, default));
    }

    [Fact]
    public void ResponseRejectsNullHeaders()
    {
        Assert.Throws<ArgumentNullException>(() => new PhotonResponse(200, null!, default));
    }

    [Fact]
    public void ConfigOutlivesItsDocument()
    {
        ResolvedConfig config;
        using (var document = JsonDocument.Parse("""{"version":"1.0","refresh_interval":60}"""))
        {
            config = new ResolvedConfig("1.0", TimeSpan.FromSeconds(60), document.RootElement);
        }

        Assert.Equal(60, config.Raw.GetProperty("refresh_interval").GetInt32());
        Assert.Equal(TimeSpan.FromSeconds(60), config.RefreshInterval);
    }

    [Fact]
    public void ConfigMustBeAnObject()
    {
        using var document = JsonDocument.Parse("[]");

        Assert.Throws<ArgumentException>(() => new ResolvedConfig(null, null, document.RootElement));
    }
}
