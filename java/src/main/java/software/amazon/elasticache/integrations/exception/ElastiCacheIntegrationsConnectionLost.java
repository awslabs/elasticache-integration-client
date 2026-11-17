// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.amazon.elasticache.integrations.exception;

/** The socket died mid-flight, and pending requests are failed. */
public class ElastiCacheIntegrationsConnectionLost extends ElastiCacheIntegrationsError {

    /**
     * Create an error for a lost connection.
     *
     * @param message the detail message
     */
    public ElastiCacheIntegrationsConnectionLost(String message) {
        super(message);
    }

    /**
     * Create an error for a lost connection with a cause.
     *
     * @param message the detail message
     * @param cause the underlying failure
     */
    public ElastiCacheIntegrationsConnectionLost(String message, Throwable cause) {
        super(message, cause);
    }
}
