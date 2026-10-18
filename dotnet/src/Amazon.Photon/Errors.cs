namespace Amazon.Photon;

/// <summary>Base class for every error raised by the Photon client.</summary>
public class PhotonException : Exception
{
    /// <summary>Creates the exception with a message and an optional cause.</summary>
    public PhotonException(string message, Exception? innerException = null)
        : base(message, innerException)
    {
    }
}

/// <summary>An option or argument is invalid, or the cache rejected the client config.</summary>
public class PhotonConfigurationException : PhotonException
{
    /// <summary>Creates the exception with a message and an optional cause.</summary>
    public PhotonConfigurationException(string message, Exception? innerException = null)
        : base(message, innerException)
    {
    }
}

/// <summary>A connection bring-up step failed, other than the cache rejecting the client config.</summary>
public class PhotonConnectionException : PhotonException
{
    /// <summary>Creates the exception with a message and an optional cause.</summary>
    public PhotonConnectionException(string message, Exception? innerException = null)
        : base(message, innerException)
    {
    }
}

/// <summary>No cache connection is usable and the fallback window has ended.</summary>
public sealed class PhotonCacheUnavailableException : PhotonConnectionException
{
    /// <summary>Creates the exception with a message and an optional cause.</summary>
    public PhotonCacheUnavailableException(string message, Exception? innerException = null)
        : base(message, innerException)
    {
    }
}

/// <summary>The connection died while a request was in flight.</summary>
public sealed class PhotonConnectionLostException : PhotonException
{
    /// <summary>Creates the exception with a message and an optional cause.</summary>
    public PhotonConnectionLostException(string message, Exception? innerException = null)
        : base(message, innerException)
    {
    }
}

/// <summary>The connection already had <see cref="PhotonClientOptions.MaxPending"/> calls in flight; this one was not sent.</summary>
public sealed class PhotonPendingLimitException : PhotonException
{
    /// <summary>Creates the exception with a message and an optional cause.</summary>
    public PhotonPendingLimitException(string message, Exception? innerException = null)
        : base(message, innerException)
    {
    }
}

/// <summary>A reply was malformed or could not be decoded.</summary>
public sealed class PhotonProtocolException : PhotonException
{
    /// <summary>Creates the exception with a message and an optional cause.</summary>
    public PhotonProtocolException(string message, Exception? innerException = null)
        : base(message, innerException)
    {
    }
}

/// <summary>A request got no reply within its timeout.</summary>
public sealed class PhotonTimeoutException : PhotonException
{
    /// <summary>Creates the exception with a message and an optional cause.</summary>
    public PhotonTimeoutException(string message, Exception? innerException = null)
        : base(message, innerException)
    {
    }
}

/// <summary>The cache answered with a <c>-CODE message</c> error reply.</summary>
public sealed class PhotonServerException : PhotonException
{
    /// <summary>Creates the exception from the error code and the text after it.</summary>
    public PhotonServerException(string code, string? serverMessage)
        : base(string.IsNullOrEmpty(serverMessage) ? code : $"{code} {serverMessage}")
    {
        ArgumentException.ThrowIfNullOrEmpty(code);
        if (code[0] == '-' || code.Any(char.IsWhiteSpace))
        {
            throw new ArgumentException($"Code must be bare, without '-' or whitespace: '{code}'.", nameof(code));
        }

        Code = code;
        ServerMessage = serverMessage ?? string.Empty;
    }

    /// <summary>The error code without the leading <c>-</c>, for example <c>UNSUPPORTED</c>.</summary>
    public string Code { get; }

    /// <summary>The text after the code; empty when the reply has none.</summary>
    public string ServerMessage { get; }
}
