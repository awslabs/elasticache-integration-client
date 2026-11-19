// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

namespace Amazon.ElastiCache.Integrations;

/// <summary>Base class for every error raised by the ElastiCache Integrations client.</summary>
public class ElastiCacheIntegrationsException : Exception
{
    /// <summary>Creates the exception with a message and an optional cause.</summary>
    public ElastiCacheIntegrationsException(string message, Exception? innerException = null)
        : base(message, innerException)
    {
    }
}

/// <summary>An option or argument is invalid, or the cache rejected the client config.</summary>
public class ElastiCacheIntegrationsConfigurationException : ElastiCacheIntegrationsException
{
    /// <summary>Creates the exception with a message and an optional cause.</summary>
    public ElastiCacheIntegrationsConfigurationException(string message, Exception? innerException = null)
        : base(message, innerException)
    {
    }
}

/// <summary>A connection bring-up step failed, other than the cache rejecting the client config.</summary>
public class ElastiCacheIntegrationsConnectionException : ElastiCacheIntegrationsException
{
    /// <summary>Creates the exception with a message and an optional cause.</summary>
    public ElastiCacheIntegrationsConnectionException(string message, Exception? innerException = null)
        : base(message, innerException)
    {
    }
}

/// <summary>No cache connection is usable and the fallback window has ended.</summary>
public sealed class ElastiCacheIntegrationsCacheUnavailableException : ElastiCacheIntegrationsConnectionException
{
    /// <summary>Creates the exception with a message and an optional cause.</summary>
    public ElastiCacheIntegrationsCacheUnavailableException(string message, Exception? innerException = null)
        : base(message, innerException)
    {
    }
}

/// <summary>The connection died while a request was in flight.</summary>
public sealed class ElastiCacheIntegrationsConnectionLostException : ElastiCacheIntegrationsException
{
    /// <summary>Creates the exception with a message and an optional cause.</summary>
    public ElastiCacheIntegrationsConnectionLostException(string message, Exception? innerException = null)
        : base(message, innerException)
    {
    }
}

/// <summary>The connection already had <see cref="ElastiCacheIntegrationsClientOptions.MaxPending"/> calls in flight; this one was not sent.</summary>
public sealed class ElastiCacheIntegrationsPendingLimitException : ElastiCacheIntegrationsException
{
    /// <summary>Creates the exception with a message and an optional cause.</summary>
    public ElastiCacheIntegrationsPendingLimitException(string message, Exception? innerException = null)
        : base(message, innerException)
    {
    }
}

/// <summary>A reply was malformed or could not be decoded.</summary>
public sealed class ElastiCacheIntegrationsProtocolException : ElastiCacheIntegrationsException
{
    /// <summary>Creates the exception with a message and an optional cause.</summary>
    public ElastiCacheIntegrationsProtocolException(string message, Exception? innerException = null)
        : base(message, innerException)
    {
    }
}

/// <summary>A request got no reply within its timeout.</summary>
public sealed class ElastiCacheIntegrationsTimeoutException : ElastiCacheIntegrationsException
{
    /// <summary>Creates the exception with a message and an optional cause.</summary>
    public ElastiCacheIntegrationsTimeoutException(string message, Exception? innerException = null)
        : base(message, innerException)
    {
    }
}

/// <summary>The cache answered with a <c>-CODE message</c> error reply.</summary>
public sealed class ElastiCacheIntegrationsServerException : ElastiCacheIntegrationsException
{
    /// <summary>Creates the exception from the error code and the text after it.</summary>
    public ElastiCacheIntegrationsServerException(string code, string? serverMessage)
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
