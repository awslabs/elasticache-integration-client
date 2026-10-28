package software.amazon.elasticache.integrations.session;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncRequestBody.BodyType;
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
public final class ElastiCacheIntegrationsAsyncInterceptor implements ExecutionInterceptor {

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
            int maxConcurrentBodyCollectors) {
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
                        maxConcurrentBodyCollectors);
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
     * marshalled bytes and is carriable.
     */
    private static boolean isCarriableBody(AsyncRequestBody body) {
        return BodyType.BYTES.getName().equals(body.body());
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
