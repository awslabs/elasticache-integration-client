// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.amazon.elasticache.integrations.exception;

/**
 * An option or the application's SDK client cannot be used to install ElastiCache Integrations, for
 * example a non-positive timeout, a client with no region, or a client builder that already has an
 * httpClient.
 */
public class ElastiCacheIntegrationsConfigError extends ElastiCacheIntegrationsError {

    /**
     * Create a configuration error.
     *
     * @param message the error message
     */
    public ElastiCacheIntegrationsConfigError(String message) {
        super(message);
    }

    /**
     * Create a configuration error with a cause.
     *
     * @param message the error message
     * @param cause the underlying failure
     */
    public ElastiCacheIntegrationsConfigError(String message, Throwable cause) {
        super(message, cause);
    }
}
