// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.amazon.elasticache.integrations.exception;

/** Base class for all Cache errors. */
public class ElastiCacheIntegrationsError extends RuntimeException {

    /**
     * Create a Cache error.
     *
     * @param message the detail message
     */
    public ElastiCacheIntegrationsError(String message) {
        super(message);
    }

    /**
     * Create a Cache error with a cause.
     *
     * @param message the detail message
     * @param cause the underlying failure
     */
    public ElastiCacheIntegrationsError(String message, Throwable cause) {
        super(message, cause);
    }
}
