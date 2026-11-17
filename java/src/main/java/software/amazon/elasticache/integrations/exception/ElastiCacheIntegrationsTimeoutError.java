// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.amazon.elasticache.integrations.exception;

/** A request exceeded its per-request timeout. */
public class ElastiCacheIntegrationsTimeoutError extends ElastiCacheIntegrationsError {

    /**
     * Create a request timeout error.
     *
     * @param message the detail message
     */
    public ElastiCacheIntegrationsTimeoutError(String message) {
        super(message);
    }

    /**
     * Create a request timeout error with a cause.
     *
     * @param message the detail message
     * @param cause the underlying failure
     */
    public ElastiCacheIntegrationsTimeoutError(String message, Throwable cause) {
        super(message, cause);
    }
}
