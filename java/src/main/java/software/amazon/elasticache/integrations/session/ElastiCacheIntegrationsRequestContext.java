// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.amazon.elasticache.integrations.session;

import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.elasticache.integrations.model.ElastiCacheIntegrationsRequest;

/** Per-client, per-attempt handoff between an SDK interceptor and HTTP transport. */
final class ElastiCacheIntegrationsRequestContext {

    private static final ExecutionAttribute<ElastiCacheIntegrationsRequest> AWS_REQUEST =
            new ExecutionAttribute<>("ElastiCacheIntegrationsRequest");
    private static final ExecutionAttribute<Boolean> PASS_THROUGH =
            new ExecutionAttribute<>("ElastiCacheIntegrationsPassThrough");

    // Instance fields so that two clients on one thread keep separate attempts.
    @SuppressWarnings("ThreadLocalUsage")
    private final ThreadLocal<ElastiCacheIntegrationsRequest> currentAttempt = new ThreadLocal<>();

    @SuppressWarnings("ThreadLocalUsage")
    private final ThreadLocal<Boolean> currentPassThrough = new ThreadLocal<>();

    void capture(ElastiCacheIntegrationsRequest request, ExecutionAttributes executionAttributes) {
        executionAttributes.putAttribute(AWS_REQUEST, request);
    }

    /** Mark a request to pass through to the origin without Cache, such as a streaming body. */
    void capturePassThrough(ExecutionAttributes executionAttributes) {
        executionAttributes.putAttribute(PASS_THROUGH, true);
    }

    void prepareAttempt(ExecutionAttributes executionAttributes) {
        ElastiCacheIntegrationsRequest request = executionAttributes.getAttribute(AWS_REQUEST);
        if (request == null) {
            currentAttempt.remove();
        } else {
            currentAttempt.set(request);
        }
        currentPassThrough.set(Boolean.TRUE.equals(executionAttributes.getAttribute(PASS_THROUGH)));
    }

    ElastiCacheIntegrationsRequest takeAttempt() {
        ElastiCacheIntegrationsRequest request = currentAttempt.get();
        currentAttempt.remove();
        return request;
    }

    /** Whether the prepared attempt passes through to the origin without Cache. */
    boolean takePassThrough() {
        boolean passThrough = Boolean.TRUE.equals(currentPassThrough.get());
        currentPassThrough.remove();
        return passThrough;
    }

    void clear() {
        currentAttempt.remove();
        currentPassThrough.remove();
    }
}
