// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.amazon.elasticache.integrations.exception;

/**
 * The cache rejected credentials during the {@code HELLO 3 AUTH} handshake or a background re-AUTH,
 * or returned an invalid authentication reply.
 *
 * <p>A bring-up rejection invalidates that connection attempt. A background re-AUTH rejection
 * leaves the established connection open with its last valid authentication state.
 */
public class ElastiCacheIntegrationsAuthError extends ElastiCacheIntegrationsError {

    /**
     * Create an authentication error.
     *
     * @param message the detail message
     */
    public ElastiCacheIntegrationsAuthError(String message) {
        super(message);
    }
}
