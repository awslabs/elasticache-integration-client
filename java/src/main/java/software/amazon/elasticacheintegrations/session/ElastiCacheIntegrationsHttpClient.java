package software.amazon.elasticacheintegrations.session;

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
import software.amazon.elasticacheintegrations.connection.ElastiCacheIntegrationsRequestCancellation;
import software.amazon.elasticacheintegrations.exception.ElastiCacheIntegrationsError;
import software.amazon.elasticacheintegrations.model.ElastiCacheIntegrationsRequest;
import software.amazon.elasticacheintegrations.model.ElastiCacheIntegrationsResponse;

/**
 * Synchronous SDK HTTP transport that attempts Cache before the origin service.
 *
 * <p>The unsigned request captured by {@link ElastiCacheIntegrationsInterceptor} is sent to Cache.
 * Predicate declines delegate to the origin before Cache sees the request. After sending, direct
 * fallback is limited to replay-safe operations whose Cache connection has dropped; retryable
 * healthy-connection failures are returned to the SDK.
 */
public final class ElastiCacheIntegrationsHttpClient implements SdkHttpClient {

    private static final String CLIENT_NAME = "java-cache";

    private final ElastiCacheIntegrationsSession session;
    private final ElastiCacheIntegrationsRequestContext requestContext;
    private final SdkHttpClient originClient;
    private final boolean ownsOriginClient;
    private final AtomicBoolean closed = new AtomicBoolean();

    ElastiCacheIntegrationsHttpClient(
            ElastiCacheIntegrationsSession session,
            ElastiCacheIntegrationsRequestContext requestContext,
            SdkHttpClient originClient,
            boolean ownsOriginClient) {
        this.session = session;
        this.requestContext = requestContext;
        this.originClient = originClient;
        this.ownsOriginClient = ownsOriginClient;
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
                } catch (ElastiCacheIntegrationsRequestCancellation.CancelledException cancelled) {
                    throw new IOException("Request was aborted", cancelled);
                } catch (ElastiCacheIntegrationsError cacheFailure) {
                    if (aborted.get()) {
                        throw new IOException("Request was aborted", cacheFailure);
                    }
                    if (!ElastiCacheIntegrationsFallbackPolicy.shouldFallback(
                            session, capturedRequest, cacheFailure)) {
                        if (ElastiCacheIntegrationsFallbackPolicy.shouldRetry(
                                session, capturedRequest, cacheFailure)) {
                            throw new IOException("Cache request failed", cacheFailure);
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
        if (ownsOriginClient) {
            originClient.close();
        }
    }
}
