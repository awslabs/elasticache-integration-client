package software.amazon.elasticache.integrations.session;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpFullResponse;
import software.amazon.elasticache.integrations.connection.ElastiCacheIntegrationsRequestCancellation;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsRequestCancelled;
import software.amazon.elasticache.integrations.model.ElastiCacheIntegrationsRequest;
import software.amazon.elasticache.integrations.model.ElastiCacheIntegrationsResponse;

/**
 * Synchronous SDK HTTP transport that attempts Cache before the origin service.
 *
 * <p>The unsigned request captured by {@link ElastiCacheIntegrationsInterceptor} is sent to Cache.
 * Predicate declines delegate to the origin before Cache sees the request. After sending, transport
 * failures for replay-safe operations are returned to the SDK as retryable so the next attempt
 * applies the session's reconnect wait and fallback window.
 */
final class ElastiCacheIntegrationsHttpClient implements SdkHttpClient {

    private static final String CLIENT_NAME = "java-cache";

    private final ElastiCacheIntegrationsSession session;
    private final ElastiCacheIntegrationsRequestContext requestContext;
    private final SdkHttpClient originClient;
    private final boolean ownsOriginClient;
    private final boolean ownsSession;
    private final ManagedIamAuthProvider ownedAuthProvider;
    private final AtomicBoolean closed = new AtomicBoolean();

    ElastiCacheIntegrationsHttpClient(
            ElastiCacheIntegrationsSession session,
            ElastiCacheIntegrationsRequestContext requestContext,
            SdkHttpClient originClient,
            boolean ownsOriginClient) {
        this(session, requestContext, originClient, ownsOriginClient, false, null);
    }

    /**
     * Create a transport that also owns the session and token manager behind it. {@link #close()}
     * closes the session, then the token manager, then the origin transport if it is owned.
     */
    ElastiCacheIntegrationsHttpClient(
            ElastiCacheIntegrationsSession session,
            ElastiCacheIntegrationsRequestContext requestContext,
            SdkHttpClient originClient,
            boolean ownsOriginClient,
            boolean ownsSession,
            ManagedIamAuthProvider ownedAuthProvider) {
        this.session = session;
        this.requestContext = requestContext;
        this.originClient = originClient;
        this.ownsOriginClient = ownsOriginClient;
        this.ownsSession = ownsSession;
        this.ownedAuthProvider = ownedAuthProvider;
    }

    @Override
    public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
        boolean passThrough = requestContext.takePassThrough();
        ElastiCacheIntegrationsRequest capturedRequest = requestContext.takeAttempt();
        return new ExecutableHttpRequest() {
            private final AtomicReference<ExecutableHttpRequest> activeOriginRequest =
                    new AtomicReference<>();
            private final AtomicReference<ElastiCacheIntegrationsRequestCancellation>
                    activeElastiCacheIntegrationsRequest = new AtomicReference<>();
            private final AtomicBoolean aborted = new AtomicBoolean();

            @Override
            public HttpExecuteResponse call() throws IOException {
                if (aborted.get()) {
                    throw new IOException("Request was aborted");
                }

                if (passThrough) {
                    return callOrigin();
                }
                if (capturedRequest == null) {
                    throw new IllegalStateException("Cache request was not captured before HTTP execution");
                }

                ElastiCacheIntegrationsRequestCancellation cacheCancellation =
                        new ElastiCacheIntegrationsRequestCancellation();
                activeElastiCacheIntegrationsRequest.set(cacheCancellation);
                if (aborted.get()) {
                    cacheCancellation.cancel();
                }

                ElastiCacheIntegrationsResponse cacheResponse;
                try {
                    cacheResponse = session.request(capturedRequest, cacheCancellation);
                } catch (ElastiCacheIntegrationsRequestCancelled cancelled) {
                    throw new IOException("Request was aborted", cancelled);
                } catch (ElastiCacheIntegrationsError cacheFailure) {
                    if (aborted.get()) {
                        IOException named = ElastiCacheIntegrationsFallbackPolicy.namedFailure(cacheFailure);
                        throw new IOException("Request was aborted", named != null ? named : cacheFailure);
                    }
                    if (!ElastiCacheIntegrationsFallbackPolicy.shouldFallback(
                            session, capturedRequest, cacheFailure)) {
                        if (ElastiCacheIntegrationsFallbackPolicy.shouldRetry(
                                session, capturedRequest, cacheFailure)) {
                            IOException named = ElastiCacheIntegrationsFallbackPolicy.namedFailure(cacheFailure);
                            throw named != null ? named : new IOException("Cache request failed", cacheFailure);
                        }
                        throw cacheFailure;
                    }
                    return callOrigin();
                } finally {
                    activeElastiCacheIntegrationsRequest.compareAndSet(cacheCancellation, null);
                }

                if (aborted.get()) {
                    throw new IOException("Request was aborted");
                }
                if (cacheResponse == null) {
                    return callOrigin();
                }
                SdkHttpFullResponse sdkResponse = SdkAdapters.toSdkResponse(cacheResponse);
                HttpExecuteResponse.Builder response = HttpExecuteResponse.builder().response(sdkResponse);
                byte[] body = cacheResponse.getBody();
                if (body != null && body.length > 0) {
                    response.responseBody(AbortableInputStream.create(new ByteArrayInputStream(body)));
                }
                return response.build();
            }

            private HttpExecuteResponse callOrigin() throws IOException {
                ExecutableHttpRequest originRequest = originClient.prepareRequest(request);
                activeOriginRequest.set(originRequest);
                if (aborted.get()) {
                    originRequest.abort();
                    throw new IOException("Request was aborted");
                }
                return originRequest.call();
            }

            @Override
            public void abort() {
                aborted.set(true);
                ElastiCacheIntegrationsRequestCancellation cacheRequest =
                        activeElastiCacheIntegrationsRequest.get();
                if (cacheRequest != null) {
                    cacheRequest.cancel();
                }
                ExecutableHttpRequest originRequest = activeOriginRequest.get();
                if (originRequest != null) {
                    originRequest.abort();
                }
            }
        };
    }

    @Override
    public String clientName() {
        return CLIENT_NAME;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        requestContext.clear();
        try {
            if (ownsSession) {
                session.close();
            }
        } finally {
            try {
                if (ownedAuthProvider != null) {
                    ownedAuthProvider.close();
                }
            } finally {
                if (ownsOriginClient) {
                    originClient.close();
                }
            }
        }
    }
}
