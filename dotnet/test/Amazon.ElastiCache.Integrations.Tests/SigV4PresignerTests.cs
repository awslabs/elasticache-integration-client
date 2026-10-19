using Amazon.Runtime;

namespace Amazon.ElastiCache.Integrations.Tests;

public class SigV4PresignerTests
{
    private static readonly DateTimeOffset Now = new(2026, 10, 17, 8, 30, 0, TimeSpan.Zero);

    // Made by python/elasticache_integrations/auth.py with botocore 1.43.51 at Now; Node's SignatureV4 with a host
    // header gives the same signatures.
    public static TheoryData<string, string, string, string, string, string?, string> Vectors => new()
    {
        {
            "my-cache", "my-user", "us-east-1", "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "FwoGZXIvYXdzEBYaDHqa0AP",
            "my-cache/?Action=connect&User=my-user&ResourceType=ServerlessCache&X-Amz-Algorithm=AWS4-HMAC-SHA256"
            + "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20261017%2Fus-east-1%2Felasticache%2Faws4_request&X-Amz-Date=20261017T083000Z"
            + "&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Security-Token=FwoGZXIvYXdzEBYaDHqa0AP"
            + "&X-Amz-Signature=5cd6d1367ef6a6c9c76ef37b1017b7158e33d14213bbed71a2cefab5059e3e9c"
        },
        {
            "my-cache", "default.iam-user", "eu-west-1", "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", null,
            "my-cache/?Action=connect&User=default.iam-user&ResourceType=ServerlessCache&X-Amz-Algorithm=AWS4-HMAC-SHA256"
            + "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20261017%2Feu-west-1%2Felasticache%2Faws4_request&X-Amz-Date=20261017T083000Z"
            + "&X-Amz-Expires=900&X-Amz-SignedHeaders=host"
            + "&X-Amz-Signature=547c1161244e4b15484a427773684cbda918d3f20975acd6d60f990330283356"
        },
        {
            "cache-name-1", "user name+x/y=z", "ap-southeast-2", "AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "token/with+chars=",
            "cache-name-1/?Action=connect&User=user%20name%2Bx%2Fy%3Dz&ResourceType=ServerlessCache&X-Amz-Algorithm=AWS4-HMAC-SHA256"
            + "&X-Amz-Credential=AKIDEXAMPLE%2F20261017%2Fap-southeast-2%2Felasticache%2Faws4_request&X-Amz-Date=20261017T083000Z"
            + "&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Security-Token=token%2Fwith%2Bchars%3D"
            + "&X-Amz-Signature=a9afe9a13d534f356756aac2da9d5e2d76d892a924aed5ae81ea25f8dcdb2881"
        },
        {
            "my-cache", "us\u00e9r~*!", "us-east-1", "AKIDEXAMPLE", "secret", null,
            "my-cache/?Action=connect&User=us%C3%A9r~%2A%21&ResourceType=ServerlessCache&X-Amz-Algorithm=AWS4-HMAC-SHA256"
            + "&X-Amz-Credential=AKIDEXAMPLE%2F20261017%2Fus-east-1%2Felasticache%2Faws4_request&X-Amz-Date=20261017T083000Z"
            + "&X-Amz-Expires=900&X-Amz-SignedHeaders=host"
            + "&X-Amz-Signature=0c5b41fe117983e603a12cb4c7e56203a948c8e34a7e719b431bef6fdaf28661"
        },
    };

    [Theory]
    [MemberData(nameof(Vectors))]
    public void TokenMatchesReferenceVector(string cache, string user, string region, string accessKey, string secretKey, string? sessionToken, string expected)
    {
        var credentials = new ImmutableCredentials(accessKey, secretKey, sessionToken);

        Assert.Equal(expected, SigV4Presigner.PresignConnect(cache, user, region, credentials, Now));
    }

    [Fact]
    public void HostIsLowercased()
    {
        var credentials = new ImmutableCredentials("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", null);

        var token = SigV4Presigner.PresignConnect("My-Cache", "default.iam-user", "eu-west-1", credentials, Now);

        Assert.EndsWith("547c1161244e4b15484a427773684cbda918d3f20975acd6d60f990330283356", token);
        Assert.StartsWith("my-cache/?", token);
    }

    [Fact]
    public void SigningDateAndTimeAreUtc()
    {
        var credentials = new ImmutableCredentials("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", null);

        var token = SigV4Presigner.PresignConnect("my-cache", "default.iam-user", "eu-west-1", credentials, Now.ToOffset(TimeSpan.FromHours(-9)));

        Assert.EndsWith("547c1161244e4b15484a427773684cbda918d3f20975acd6d60f990330283356", token);
    }

    [Theory]
    [InlineData("AZaz09-_.~", "AZaz09-_.~")]
    [InlineData(" +/=&?%", "%20%2B%2F%3D%26%3F%25")]
    [InlineData("\u00e9\u20ac", "%C3%A9%E2%82%AC")]
    public void EncodeUsesTheSigV4UnreservedSet(string value, string expected) =>
        Assert.Equal(expected, SigV4Presigner.Encode(value));
}
