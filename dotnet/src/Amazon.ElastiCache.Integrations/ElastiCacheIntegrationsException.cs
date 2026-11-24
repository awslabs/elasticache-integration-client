// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

namespace Amazon.ElastiCache.Integrations;

/// <summary>The base exception for ElastiCache Integrations setup failures.</summary>
public class ElastiCacheIntegrationsException : Exception
{
    /// <summary>Creates an exception with a message.</summary>
    public ElastiCacheIntegrationsException(string message)
        : base(message)
    {
    }
}

/// <summary>The requested AWS SDK configuration cannot install the integration.</summary>
public sealed class ElastiCacheIntegrationsConfigurationException : ElastiCacheIntegrationsException
{
    /// <summary>Creates a configuration exception with a message.</summary>
    public ElastiCacheIntegrationsConfigurationException(string message)
        : base(message)
    {
    }
}
