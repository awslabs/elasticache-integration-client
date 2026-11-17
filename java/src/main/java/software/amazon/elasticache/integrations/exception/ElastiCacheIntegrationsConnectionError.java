// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.amazon.elasticache.integrations.exception;

/** Connection bring-up failed. */
public class ElastiCacheIntegrationsConnectionError extends ElastiCacheIntegrationsError {

    /**
     * Create a connection bring-up error.
     *
     * @param message the detail message
     */
    public ElastiCacheIntegrationsConnectionError(String message) {
        super(message);
    }

    /**
     * Create a connection bring-up error with a cause.
     *
     * @param message the detail message
     * @param cause the underlying failure
     */
    public ElastiCacheIntegrationsConnectionError(String message, Throwable cause) {
        super(message, cause);
    }
}
