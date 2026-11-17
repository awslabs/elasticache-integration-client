// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.amazon.elasticache.integrations.resp;

/**
 * Thrown by {@link RespCodec#decode} when the buffer holds only a partial frame (need more bytes).
 * It is not an {@code ElastiCacheIntegrationsError}, because it is a flow-control signal.
 */
public class IncompleteFrame extends Exception {

    /**
     * Create a signal that more bytes are needed.
     *
     * @param message the detail message
     */
    public IncompleteFrame(String message) {
        super(message);
    }
}
