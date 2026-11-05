package software.amazon.elasticache.integrations.session;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.logging.Logger;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.elasticache.integrations.model.ElastiCacheIntegrationsRequest;

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
 * <p><b>Internal:</b> {@link ElastiCacheIntegrationsClient.Builder#install} installs this
 * interceptor together with {@link #getHttpClient()}. Using the interceptor with the SDK's default
 * transport does not enable Cache and lets that transport contact the origin service.
 */
class ElastiCacheIntegrationsInterceptor implements ExecutionInterceptor {

    private static final String FILE_PROVIDER =
            "software.amazon.awssdk.core.internal.sync.FileContentStreamProvider";
    private static final String BUFFERING_PROVIDER =
            "software.amazon.awssdk.core.internal.sync.BufferingContentStreamProvider";
    private static final String REQUEST_BODY_LAMBDA =
            "software.amazon.awssdk.core.sync.RequestBody$$Lambda";
    private static final Method PROVIDER_NAME_METHOD = findProviderNameMethod();

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
        this(session, originClient, ownsOriginClient, false, null);
    }

    ElastiCacheIntegrationsInterceptor(
            ElastiCacheIntegrationsSession session,
            SdkHttpClient originClient,
            boolean ownsOriginClient,
            boolean ownsSession,
            ManagedIamAuthProvider ownedAuthProvider) {
        this.requestContext = new ElastiCacheIntegrationsRequestContext();
        this.httpClient =
                new ElastiCacheIntegrationsHttpClient(
                        session,
                        requestContext,
                        originClient,
                        ownsOriginClient,
                        ownsSession,
                        ownedAuthProvider);
    }

    /**
     * Get the HTTP client that attempts Cache before the origin service.
     *
     * <p>This transport must be installed together with this interceptor. Using the interceptor with
     * the SDK's default transport bypasses Cache.
     *
     * @return the HTTP client to install with this interceptor
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
        if (!isCarriableBody(sdkRequest)) {
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
     * Whether Cache can carry the body. A body that streams from an {@code InputStream} or a file
     * cannot, including {@code RequestBody.fromContentProvider} without a length, which the SDK wraps
     * as a stream. Bodies from bytes, strings and custom providers with a length (including
     * DynamoDB's) are read for Cache.
     */
    private static boolean isCarriableBody(SdkHttpFullRequest sdkRequest) {
        return !sdkRequest
                .contentStreamProvider()
                .map(ElastiCacheIntegrationsInterceptor::isStreamOrFile)
                .orElse(false);
    }

    /**
     * AWS SDK 2.31.64 added {@code ContentStreamProvider.name()}. Invoke it reflectively so the
     * client can still run with earlier SDK releases. Before that API existed, file and
     * unknown-length providers had stable implementation classes, while {@code
     * RequestBody.fromInputStream} produced a lambda that captured an {@link InputStream}.
     */
    private static boolean isStreamOrFile(ContentStreamProvider provider) {
        Optional<String> providerName = providerName(provider);
        if (providerName.isPresent()) {
            return "Stream".equals(providerName.get()) || "File".equals(providerName.get());
        }

        String className = provider.getClass().getName();
        if (FILE_PROVIDER.equals(className) || BUFFERING_PROVIDER.equals(className)) {
            return true;
        }
        if (!className.startsWith(REQUEST_BODY_LAMBDA)) {
            return false;
        }
        for (Field field : provider.getClass().getDeclaredFields()) {
            if (InputStream.class.isAssignableFrom(field.getType())) {
                return true;
            }
        }
        return false;
    }

    private static Optional<String> providerName(ContentStreamProvider provider) {
        if (PROVIDER_NAME_METHOD == null) {
            return Optional.empty();
        }

        try {
            return Optional.of((String) PROVIDER_NAME_METHOD.invoke(provider));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot inspect the SDK request body", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException("Cannot inspect the SDK request body", cause);
        }
    }

    private static Method findProviderNameMethod() {
        try {
            return ContentStreamProvider.class.getMethod("name");
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
