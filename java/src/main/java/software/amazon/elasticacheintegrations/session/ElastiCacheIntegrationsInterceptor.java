package software.amazon.elasticacheintegrations.session;

import java.util.logging.Logger;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.ContentStreamProvider.ProviderType;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.elasticacheintegrations.model.ElastiCacheIntegrationsRequest;

/**
 * AWS SDK v2 ExecutionInterceptor that redirects requests to the Cache cache.
 *
 * <p>This interceptor hooks into the SDK's request pipeline at {@code modifyHttpRequest}, which
 * runs BEFORE SigV4 signing. The request is serialized to HTTP form but does not yet contain the
 * Authorization header or signature.
 *
 * <p><b>Note on signing timing:</b> Unlike the Python client which hooks at botocore's {@code
 * before-send} (after signing), the AWS SDK for Java v2 does not provide an interceptor hook that
 * runs after signing but before transmission. The only options are {@code modifyHttpRequest}
 * (before signing, can modify) or {@code beforeTransmission} (after signing, read-only). Since we
 * need to forward the request to Cache, we use {@code modifyHttpRequest}. The request sent to Cache
 * will not include AWS signatures, but Cache does not require them for caching purposes.
 *
 * <p>The interceptor hands the unsigned request to {@link ElastiCacheIntegrationsHttpClient}, which
 * returns the Cache response or delegates the signed request to the origin transport when no
 * predicate matches or Cache fails.
 *
 * <p><b>Important:</b> Installing this interceptor without also configuring {@link
 * #getHttpClient()} as the SDK client's HTTP transport does not enable Cache and allows the default
 * transport to contact the origin service. Prefer {@link ElastiCacheIntegrationsClient} unless
 * manual lifecycle control is required.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * ElastiCacheIntegrationsInterceptor interceptor = new ElastiCacheIntegrationsInterceptor(session);
 * DynamoDbClient client = DynamoDbClient.builder()
 *     .overrideConfiguration(c -> c.addExecutionInterceptor(interceptor))
 *     .httpClient(interceptor.getHttpClient())
 *     .build();
 * }</pre>
 *
 * <p>Or use {@link ElastiCacheIntegrationsHandle} for managed attachment/detachment.
 */
public class ElastiCacheIntegrationsInterceptor implements ExecutionInterceptor {

    private static final Logger LOGGER =
            Logger.getLogger(ElastiCacheIntegrationsInterceptor.class.getName());

    private final ElastiCacheIntegrationsRequestContext requestContext;
    private final ElastiCacheIntegrationsHttpClient httpClient;

    /**
     * Create an interceptor that forwards requests to the given session.
     *
     * @param session the Cache session (must be opened before use)
     */
    public ElastiCacheIntegrationsInterceptor(ElastiCacheIntegrationsSession session) {
        this(session, ApacheHttpClient.create(), true);
    }

    /**
     * Create an interceptor with a caller-managed origin HTTP client.
     *
     * @param session the Cache session (must be opened before use)
     * @param originClient transport used when Cache is unavailable; Cache does not close it
     */
    public ElastiCacheIntegrationsInterceptor(
            ElastiCacheIntegrationsSession session, SdkHttpClient originClient) {
        this(session, originClient, false);
    }

    ElastiCacheIntegrationsInterceptor(
            ElastiCacheIntegrationsSession session,
            SdkHttpClient originClient,
            boolean ownsOriginClient) {
        this.requestContext = new ElastiCacheIntegrationsRequestContext();
        this.httpClient =
                new ElastiCacheIntegrationsHttpClient(
                        session, requestContext, originClient, ownsOriginClient);
    }

    /**
     * Get the HTTP client that attempts Cache before the origin service.
     *
     * <p>This transport must be installed together with this interceptor. Using the interceptor with
     * the SDK's default transport bypasses Cache.
     */
    public ElastiCacheIntegrationsHttpClient getHttpClient() {
        return httpClient;
    }

    /**
     * Intercept the request before it's sent to AWS.
     *
     * <p>This method runs BEFORE signing (part of the SDK's interceptor lifecycle). We convert the
     * SDK request to a Cache request and hand it to the synchronous Cache HTTP transport.
     *
     * <p>The request at this stage does not include AWS signatures, but contains the full HTTP
     * representation (method, URL, headers, body) that Cache needs.
     */
    @Override
    public SdkHttpFullRequest modifyHttpRequest(
            Context.ModifyHttpRequest context, ExecutionAttributes executionAttributes) {
        SdkHttpFullRequest sdkRequest = (SdkHttpFullRequest) context.httpRequest();
        if (hasStreamingBody(sdkRequest)) {
            // An InputStream may be readable only once, and a file may be too large to buffer.
            LOGGER.fine("Streaming request body; passing through to the service");
            requestContext.capturePassThrough(executionAttributes);
            return sdkRequest;
        }
        ElastiCacheIntegrationsRequest cacheRequest =
                SdkAdapters.toElastiCacheIntegrationsRequest(sdkRequest, executionAttributes);
        requestContext.capture(cacheRequest, executionAttributes);
        return sdkRequest;
    }

    /**
     * Whether the body streams from an {@code InputStream} or a file, which Cache does not carry.
     * Bodies from bytes, strings and custom providers (including DynamoDB's) are read for Cache.
     */
    private static boolean hasStreamingBody(SdkHttpFullRequest sdkRequest) {
        return sdkRequest
                .contentStreamProvider()
                .map(ContentStreamProvider::name)
                .filter(
                        name ->
                                ProviderType.STREAM.getName().equals(name)
                                        || ProviderType.FILE.getName().equals(name))
                .isPresent();
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
