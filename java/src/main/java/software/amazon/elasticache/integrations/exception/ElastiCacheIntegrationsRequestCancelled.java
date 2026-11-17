// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.amazon.elasticache.integrations.exception;

/** A Cache request was cancelled, for example because the SDK aborted the attempt. */
public final class ElastiCacheIntegrationsRequestCancelled extends ElastiCacheIntegrationsError {
    /** Create the cancellation signal. */
    public ElastiCacheIntegrationsRequestCancelled() {
        super("Cache request was cancelled");
    }
}
