// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.amazon.elasticache.integrations.session;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.elasticache.integrations.model.ElastiCacheIntegrationsRequest;

/**
 * AWS SDK v2 interceptor used with {@link ElastiCacheIntegrationsAsyncHttpClient}.
 *
 * <p>The unsigned request and SDK operation metadata are retained in the per-execution SDK
 * attributes. Each HTTP attempt is handed to the async transport immediately before transmission.
 */
final class ElastiCacheIntegrationsAsyncInterceptor implements ExecutionInterceptor {

    private static final String BYTE_BUFFERS_ASYNC_REQUEST_BODY =
            "software.amazon.awssdk.core.internal.async.ByteBuffersAsyncRequestBody";
    private static final Method BODY_METHOD = findBodyMethod();

    private static final Logger LOGGER =
            Logger.getLogger(ElastiCacheIntegrationsAsyncInterceptor.class.getName());

    private final ElastiCacheIntegrationsRequestContext requestContext;
    private final ElastiCacheIntegrationsAsyncHttpClient httpClient;

    ElastiCacheIntegrationsAsyncInterceptor(
            ElastiCacheIntegrationsSession session,
            SdkAsyncHttpClient originClient,
            boolean ownsOriginClient,
            Executor executor,
            ExecutorService ownedExecutor,
            int maxBufferedRequestBytes,
            int maxConcurrentBodyCollectors,
            boolean ownsSession,
            ManagedIamAuthProvider ownedAuthProvider) {
        this.requestContext = new ElastiCacheIntegrationsRequestContext();
        this.httpClient =
                new ElastiCacheIntegrationsAsyncHttpClient(
                        session,
                        requestContext,
                        originClient,
                        ownsOriginClient,
                        executor,
                        ownedExecutor,
                        maxBufferedRequestBytes,
                        maxConcurrentBodyCollectors,
                        ownsSession,
                        ownedAuthProvider);
    }

    /**
     * Get the async HTTP transport that attempts Cache before the origin service.
     *
     * @return the async HTTP client to install with this interceptor
     */
    public ElastiCacheIntegrationsAsyncHttpClient getHttpClient() {
        return httpClient;
    }

    @Override
    public SdkHttpFullRequest modifyHttpRequest(
            Context.ModifyHttpRequest context, ExecutionAttributes executionAttributes) {
        SdkHttpFullRequest sdkRequest = (SdkHttpFullRequest) context.httpRequest();
        if (!context
                .asyncRequestBody()
                .map(ElastiCacheIntegrationsAsyncInterceptor::isCarriableBody)
                .orElse(true)) {
            // A stream, file, or publisher may be readable only once, or too large to buffer.
            LOGGER.fine("Streaming request body; passing through to the service");
            requestContext.capturePassThrough(executionAttributes);
            return sdkRequest;
        }
        ElastiCacheIntegrationsRequest cacheRequest =
                SdkAdapters.toElastiCacheIntegrationsRequest(sdkRequest, executionAttributes, new byte[0]);
        requestContext.capture(cacheRequest, executionAttributes);
        return sdkRequest;
    }

    /**
     * Whether an async body is bytes, which Cache buffers. Streams, files, publishers, and a caller's
     * own body types pass through. A request with no async body, such as DynamoDB's, publishes its
     * marshalled bytes and is carriable. Later AWS SDK releases added {@code
     * AsyncRequestBody.body()}, so older releases are identified by the stable byte-buffer
     * implementation class.
     */
    private static boolean isCarriableBody(AsyncRequestBody body) {
        if (BODY_METHOD == null) {
            return BYTE_BUFFERS_ASYNC_REQUEST_BODY.equals(body.getClass().getName());
        }
        try {
            return "Bytes".equals(BODY_METHOD.invoke(body));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot inspect the SDK async request body", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException("Cannot inspect the SDK async request body", cause);
        }
    }

    private static Method findBodyMethod() {
        try {
            return AsyncRequestBody.class.getMethod("body");
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    @Override
    public void beforeTransmission(
            Context.BeforeTransmission context, ExecutionAttributes executionAttributes) {
        requestContext.prepareAttempt(executionAttributes);
    }

    @Override
    public void afterExecution(
            Context.AfterExecution context, ExecutionAttributes executionAttributes) {
        requestContext.clear();
    }

    @Override
    public void onExecutionFailure(
            Context.FailedExecution context, ExecutionAttributes executionAttributes) {
        requestContext.clear();
    }
}
