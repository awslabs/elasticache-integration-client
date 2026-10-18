using Amazon.Runtime;

namespace Amazon.Photon.LiveValidation;

/// <summary>Counts credential reads, and can hand out a wrong secret key so the client signs a token the cache refuses.</summary>
internal sealed class SwitchableCredentials(AWSCredentials inner) : AWSCredentials
{
    private int _reads;
    private volatile bool _broken;

    public int Reads => Volatile.Read(ref _reads);

    public bool Broken { get => _broken; set => _broken = value; }

    public override ImmutableCredentials GetCredentials() => Wrap(inner.GetCredentials());

    public override async Task<ImmutableCredentials> GetCredentialsAsync() => Wrap(await inner.GetCredentialsAsync().ConfigureAwait(false));

    private ImmutableCredentials Wrap(ImmutableCredentials credentials)
    {
        Interlocked.Increment(ref _reads);
        return _broken ? new ImmutableCredentials(credentials.AccessKey, "x" + credentials.SecretKey, credentials.Token) : credentials;
    }
}
