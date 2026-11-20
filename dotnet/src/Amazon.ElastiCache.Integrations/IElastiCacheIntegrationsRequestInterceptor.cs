// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

namespace Amazon.ElastiCache.Integrations;

/// <summary>
/// The seam between the AWS SDK integration and later routing implementations.
/// Return a response to intercept the request, or <see langword="null"/> to send
/// the original request through the SDK's normal origin path.
/// </summary>
public interface IElastiCacheIntegrationsRequestInterceptor : IDisposable, IAsyncDisposable
{
    /// <summary>Optionally intercepts one AWS SDK HTTP request.</summary>
    ValueTask<HttpResponseMessage?> InterceptAsync(
        HttpRequestMessage request,
        CancellationToken cancellationToken);
}
