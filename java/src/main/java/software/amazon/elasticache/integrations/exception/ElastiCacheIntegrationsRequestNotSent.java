// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.amazon.elasticache.integrations.exception;

/** A request exhausted its admission budget before any bytes were sent to the cache. */
public class ElastiCacheIntegrationsRequestNotSent extends ElastiCacheIntegrationsError {

    /**
     * Create an error for a request that was not sent to the cache.
     *
     * @param message the detail message
     */
    public ElastiCacheIntegrationsRequestNotSent(String message) {
        super(message);
    }
}
