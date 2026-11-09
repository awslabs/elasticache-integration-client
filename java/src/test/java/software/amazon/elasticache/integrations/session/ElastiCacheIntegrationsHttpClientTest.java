package software.amazon.elasticache.integrations.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.InterceptorContext;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpFullResponse;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.utils.IoUtils;
import software.amazon.elasticache.integrations.connection.ElastiCacheIntegrationsConnector;
import software.amazon.elasticache.integrations.connection.ElastiCacheIntegrationsRequestCancellation;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsCacheUnavailable;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsConnectionLost;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsProtocolError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsRejectedError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsRequestNotSent;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsTimeoutError;
import software.amazon.elasticache.integrations.model.ElastiCacheIntegrationsRequest;
import software.amazon.elasticache.integrations.model.ElastiCacheIntegrationsResponse;
import software.amazon.elasticache.integrations.model.RequestMetadata;

class ElastiCacheIntegrationsHttpClientTest {

    @Test
    void cacheSuccessDoesNotContactOrigin() throws Exception {
        StubSession session = new StubSession();
        session.response =
                new ElastiCacheIntegrationsResponse(
                        200, Collections.emptyMap(), "cache".getBytes(StandardCharsets.UTF_8));
        RecordingHttpClient origin = new RecordingHttpClient();
        ElastiCacheIntegrationsHttpClient client = newClient(session, origin, false);

        HttpExecuteResponse response = client.prepareRequest(signedRequest()).call();

        assertEquals(200, response.httpResponse().statusCode());
        assertEquals(0, origin.prepareCount.get());
    }

    @Test
    void droppedIdempotentTransportFailureDelegatesToOrigin() throws Exception {
        StubSession session = new StubSession();
        session.healthy = false;
        session.failure = new ElastiCacheIntegrationsTimeoutError("cache timed out");
        RecordingHttpClient origin = new RecordingHttpClient();
        ElastiCacheIntegrationsHttpClient client = newClient(session, origin, false);

        HttpExecuteResponse response = client.prepareRequest(signedRequest()).call();

        assertEquals(201, response.httpResponse().statusCode());
        assertEquals(1, origin.prepareCount.get());
    }

    @Test
    void nonIdempotentTransportFailureDoesNotContactOrigin() {
        StubSession session = new StubSession();
        session.failure = new ElastiCacheIntegrationsTimeoutError("cache timed out");
        RecordingHttpClient origin = new RecordingHttpClient();
        ElastiCacheIntegrationsHttpClient client = newClient(session, origin, false, "UpdateItem");

        ElastiCacheIntegrationsTimeoutError failure =
                assertThrows(
                        ElastiCacheIntegrationsTimeoutError.class,
                        () -> client.prepareRequest(signedRequest()).call());

        assertSame(session.failure, failure);
        assertEquals(0, origin.prepareCount.get());
    }

    @Test
    void nonIdempotentRequestNotSentDelegatesToOrigin() throws Exception {
        StubSession session = new StubSession();
        session.failure = new ElastiCacheIntegrationsRequestNotSent("admission timed out");
        RecordingHttpClient origin = new RecordingHttpClient();
        ElastiCacheIntegrationsHttpClient client = newClient(session, origin, false, "UpdateItem");

        HttpExecuteResponse response = client.prepareRequest(signedRequest()).call();

        assertEquals(201, response.httpResponse().statusCode());
        assertEquals(1, origin.prepareCount.get());
    }

    @Test
    void healthyConnectionLostIsNotMarkedRetryable() {
        StubSession session = new StubSession();
        session.failure = new ElastiCacheIntegrationsConnectionLost("socket died");
        RecordingHttpClient origin = new RecordingHttpClient();
        ElastiCacheIntegrationsHttpClient client = newClient(session, origin, false, "GetItem");

        ElastiCacheIntegrationsConnectionLost failure =
                assertThrows(
                        ElastiCacheIntegrationsConnectionLost.class,
                        () -> client.prepareRequest(signedRequest()).call());

        assertSame(session.failure, failure);
        assertEquals(0, origin.prepareCount.get());
    }

    @Test
    void malformedResponseDoesNotRetryOrContactOrigin() {
        StubSession session = new StubSession();
        session.healthy = false;
        session.failure = new ElastiCacheIntegrationsProtocolError("malformed response");
        RecordingHttpClient origin = new RecordingHttpClient();
        ElastiCacheIntegrationsHttpClient client = newClient(session, origin, false, "GetItem");

        ElastiCacheIntegrationsProtocolError failure =
                assertThrows(
                        ElastiCacheIntegrationsProtocolError.class,
                        () -> client.prepareRequest(signedRequest()).call());

        assertSame(session.failure, failure);
        assertEquals(0, origin.prepareCount.get());
    }

    @Test
    void nonOriginRejectionDoesNotContactOrigin() {
        StubSession session = new StubSession();
        session.healthy = false;
        session.failure = new ElastiCacheIntegrationsRejectedError("UNSUPPORTED", "request rejected");
        RecordingHttpClient origin = new RecordingHttpClient();
        ElastiCacheIntegrationsHttpClient client = newClient(session, origin, false, "GetItem");

        ElastiCacheIntegrationsRejectedError failure =
                assertThrows(
                        ElastiCacheIntegrationsRejectedError.class,
                        () -> client.prepareRequest(signedRequest()).call());

        assertSame(session.failure, failure);
        assertEquals(0, origin.prepareCount.get());
    }

    @Test
    void idempotentOriginErrorIsRetryableWithoutContactingOrigin() {
        StubSession session = new StubSession();
        session.failure =
                new ElastiCacheIntegrationsRejectedError("ORIGIN_ERROR", "origin unavailable");
        RecordingHttpClient origin = new RecordingHttpClient();
        ElastiCacheIntegrationsHttpClient client = newClient(session, origin, false, "GetItem");

        IOException failure =
                assertThrows(IOException.class, () -> client.prepareRequest(signedRequest()).call());

        assertSame(session.failure, failure.getCause());
        assertEquals(0, origin.prepareCount.get());
    }

    @Test
    void nonIdempotentOriginErrorIsNonRetryableWithoutContactingOrigin() {
        StubSession session = new StubSession();
        session.failure =
                new ElastiCacheIntegrationsRejectedError("ORIGIN_ERROR", "origin unavailable");
        RecordingHttpClient origin = new RecordingHttpClient();
        ElastiCacheIntegrationsHttpClient client = newClient(session, origin, false, "UpdateItem");

        ElastiCacheIntegrationsRejectedError failure =
                assertThrows(
                        ElastiCacheIntegrationsRejectedError.class,
                        () -> client.prepareRequest(signedRequest()).call());

        assertSame(session.failure, failure);
        assertEquals(0, origin.prepareCount.get());
    }

    @Test
    void expiredFallbackWindowDoesNotContactOrigin() {
        StubSession session = new StubSession();
        session.healthy = false;
        session.failure = new ElastiCacheIntegrationsCacheUnavailable("fallback window expired");
        RecordingHttpClient origin = new RecordingHttpClient();
        ElastiCacheIntegrationsHttpClient client = newClient(session, origin, false);

        assertThrows(IOException.class, () -> client.prepareRequest(signedRequest()).call());
        assertEquals(0, origin.prepareCount.get());
    }

    @Test
    void shutdownFailureDoesNotStartIdempotentFallback() {
        StubSession session = new StubSession();
        session.open = false;
        session.failure = new ElastiCacheIntegrationsConnectionLost("session closed");
        RecordingHttpClient origin = new RecordingHttpClient();
        ElastiCacheIntegrationsHttpClient client = newClient(session, origin, false);

        ElastiCacheIntegrationsConnectionLost failure =
                assertThrows(
                        ElastiCacheIntegrationsConnectionLost.class,
                        () -> client.prepareRequest(signedRequest()).call());
        assertSame(session.failure, failure);
        assertEquals(0, origin.prepareCount.get());
    }

    @Test
    void predicateDeclineDelegatesExactSignedRequest() throws Exception {
        StubSession session = new StubSession();
        session.response = null;
        RecordingHttpClient origin = new RecordingHttpClient();
        ElastiCacheIntegrationsHttpClient client = newClient(session, origin, false);
        HttpExecuteRequest signedRequest = signedRequest();

        HttpExecuteResponse response = client.prepareRequest(signedRequest).call();

        assertEquals(201, response.httpResponse().statusCode());
        assertSame(signedRequest, origin.request.get());
        assertEquals(1, session.requestCount.get());
    }

    @Test
    void streamingBodyPassesThrough() throws Exception {
        StubSession session = new StubSession();
        RecordingHttpClient origin = new RecordingHttpClient();
        RequestBody body = RequestBody.fromInputStream(readOnce("payload"), 7);

        HttpExecuteResponse response =
                sendThroughInterceptor(session, origin, body.contentStreamProvider());

        assertEquals(201, response.httpResponse().statusCode());
        assertEquals(0, session.requestCount.get());
        assertEquals("payload", originBody(origin));
    }

    /** Bodies the SDK names {@code Stream} or {@code File} skip Cache and reach the origin intact. */
    @ParameterizedTest
    @ValueSource(strings = {"fromInputStream", "fromFile", "fromContentProviderWithoutLength"})
    void uncarriableBodiesAreDeclined(String factory, @TempDir Path dir) throws Exception {
        StubSession session = new StubSession();
        RecordingHttpClient origin = new RecordingHttpClient();
        byte[] payload = "payload".getBytes(StandardCharsets.UTF_8);
        RequestBody body;
        switch (factory) {
            case "fromInputStream":
                body = RequestBody.fromInputStream(readOnce("payload"), payload.length);
                break;
            case "fromFile":
                body = RequestBody.fromFile(Files.write(dir.resolve("upload.bin"), payload));
                break;
            default:
                body =
                        RequestBody.fromContentProvider(
                                () -> new ByteArrayInputStream(payload), "application/octet-stream");
        }

        sendThroughInterceptor(session, origin, body.contentStreamProvider());

        assertEquals(0, session.requestCount.get());
        assertEquals("payload", originBody(origin));
    }

    /**
     * Bodies named {@code Bytes}, and custom providers with a known length (the shape DynamoDB's
     * marshaller produces), are read for Cache.
     */
    @ParameterizedTest
    @CsvSource({
        "fromBytes, payload",
        "fromString, payload",
        "empty, ''",
        "fromContentProvider, payload"
    })
    void bodyConversion(String factory, String expected) throws Exception {
        StubSession session = new StubSession();
        session.response =
                new ElastiCacheIntegrationsResponse(
                        200, Collections.emptyMap(), "cache".getBytes(StandardCharsets.UTF_8));
        RecordingHttpClient origin = new RecordingHttpClient();
        byte[] payload = "payload".getBytes(StandardCharsets.UTF_8);
        RequestBody body;
        switch (factory) {
            case "fromBytes":
                body = RequestBody.fromBytes(payload);
                break;
            case "fromString":
                body = RequestBody.fromString("payload");
                break;
            case "empty":
                body = RequestBody.empty();
                break;
            default:
                body =
                        RequestBody.fromContentProvider(
                                () -> new ByteArrayInputStream(payload),
                                payload.length,
                                "application/octet-stream");
        }

        HttpExecuteResponse response =
                sendThroughInterceptor(session, origin, body.contentStreamProvider());

        assertEquals(200, response.httpResponse().statusCode());
        assertEquals(expected, new String(session.lastRequest.getBody(), StandardCharsets.UTF_8));
        assertEquals(0, origin.prepareCount.get());
    }

    @Test
    void unexpectedRuntimeExceptionPropagates() {
        StubSession session = new StubSession();
        session.failure = new IllegalStateException("bug");
        RecordingHttpClient origin = new RecordingHttpClient();
        ElastiCacheIntegrationsHttpClient client = newClient(session, origin, false);

        assertThrows(IllegalStateException.class, () -> client.prepareRequest(signedRequest()).call());
        assertEquals(0, origin.prepareCount.get());
    }

    @Test
    void abortBeforeCallDoesNotContactCacheOrOrigin() {
        StubSession session = new StubSession();
        RecordingHttpClient origin = new RecordingHttpClient();
        ElastiCacheIntegrationsHttpClient client = newClient(session, origin, false);
        ExecutableHttpRequest request = client.prepareRequest(signedRequest());

        request.abort();

        assertThrows(IOException.class, request::call);
        assertEquals(0, session.requestCount.get());
        assertEquals(0, origin.prepareCount.get());
    }

    @Test
    void abortDuringElastiCacheIntegrationsRequestReturnsWithoutContactingOrigin() throws Exception {
        StubSession session = new StubSession();
        session.blockUntilCancelled = true;
        RecordingHttpClient origin = new RecordingHttpClient();
        ElastiCacheIntegrationsRequestContext context = new ElastiCacheIntegrationsRequestContext();
        ElastiCacheIntegrationsHttpClient client =
                new ElastiCacheIntegrationsHttpClient(session, context, origin, false);
        prepare(context);
        ExecutableHttpRequest request = client.prepareRequest(signedRequest());
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread caller =
                new Thread(
                        () -> {
                            try {
                                request.call();
                            } catch (Throwable throwable) {
                                failure.set(throwable);
                            } finally {
                                context.clear();
                            }
                        });
        caller.start();
        assertTrue(session.requestStarted.await(2, TimeUnit.SECONDS));

        request.abort();
        caller.join(2000);

        assertTrue(failure.get() instanceof IOException);
        assertEquals(0, origin.prepareCount.get());
        assertFalse(caller.isAlive());
    }

    @Test
    void abortReachesActiveOriginRequest() throws Exception {
        StubSession session = new StubSession();
        session.healthy = false;
        session.failure = new ElastiCacheIntegrationsConnectionLost("cache connection dropped");
        RecordingHttpClient origin = new RecordingHttpClient();
        origin.blockCall = true;
        ElastiCacheIntegrationsRequestContext context = new ElastiCacheIntegrationsRequestContext();
        ElastiCacheIntegrationsHttpClient client =
                new ElastiCacheIntegrationsHttpClient(session, context, origin, false);
        prepare(context);
        ExecutableHttpRequest request = client.prepareRequest(signedRequest());
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread caller =
                new Thread(
                        () -> {
                            try {
                                request.call();
                            } catch (Throwable throwable) {
                                failure.set(throwable);
                            } finally {
                                context.clear();
                            }
                        });
        caller.start();
        assertTrue(origin.callStarted.await(2, TimeUnit.SECONDS));

        request.abort();
        caller.join(2000);

        assertTrue(origin.aborted.get());
        assertNull(failure.get());
        assertFalse(caller.isAlive());
    }

    @Test
    void closeOnlyClosesOwnedOriginOnce() {
        StubSession session = new StubSession();
        RecordingHttpClient callerManaged = new RecordingHttpClient();
        ElastiCacheIntegrationsHttpClient callerManagedClient =
                newClient(session, callerManaged, false);
        callerManagedClient.close();
        callerManagedClient.close();
        assertEquals(0, callerManaged.closeCount.get());

        RecordingHttpClient owned = new RecordingHttpClient();
        ElastiCacheIntegrationsHttpClient ownedClient = newClient(session, owned, true);
        ownedClient.close();
        ownedClient.close();
        assertEquals(1, owned.closeCount.get());
    }

    /** Drive the sync interceptor and its transport in the order the SDK calls them. */
    private static HttpExecuteResponse sendThroughInterceptor(
            ElastiCacheIntegrationsSession session, SdkHttpClient origin, ContentStreamProvider body)
            throws IOException {
        ElastiCacheIntegrationsInterceptor interceptor =
                new ElastiCacheIntegrationsInterceptor(session, origin);
        SdkHttpFullRequest httpRequest =
                SdkHttpFullRequest.builder()
                        .method(SdkHttpMethod.POST)
                        .uri(URI.create("https://dynamodb.us-east-1.amazonaws.com/"))
                        .contentStreamProvider(body)
                        .build();
        InterceptorContext context =
                InterceptorContext.builder()
                        .request(PutItemRequest.builder().build())
                        .httpRequest(httpRequest)
                        .build();
        ExecutionAttributes attributes = new ExecutionAttributes();
        interceptor.modifyHttpRequest(context, attributes);
        interceptor.beforeTransmission(context, attributes);
        return interceptor
                .getHttpClient()
                .prepareRequest(
                        HttpExecuteRequest.builder().request(httpRequest).contentStreamProvider(body).build())
                .call();
    }

    /** A stream that, like a socket or pipe, can be read only once. */
    private static InputStream readOnce(String content) {
        return new FilterInputStream(
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
            @Override
            public boolean markSupported() {
                return false;
            }
        };
    }

    private static String originBody(RecordingHttpClient origin) throws IOException {
        try (InputStream in = origin.request.get().contentStreamProvider().get().newStream()) {
            return new String(IoUtils.toByteArray(in), StandardCharsets.UTF_8);
        }
    }

    private static ElastiCacheIntegrationsHttpClient newClient(
            ElastiCacheIntegrationsSession session, SdkHttpClient origin, boolean ownsOrigin) {
        return newClient(session, origin, ownsOrigin, "ListTables");
    }

    private static ElastiCacheIntegrationsHttpClient newClient(
            ElastiCacheIntegrationsSession session,
            SdkHttpClient origin,
            boolean ownsOrigin,
            String operation) {
        ElastiCacheIntegrationsRequestContext context = new ElastiCacheIntegrationsRequestContext();
        prepare(context, operation);
        return new ElastiCacheIntegrationsHttpClient(session, context, origin, ownsOrigin);
    }

    private static void prepare(ElastiCacheIntegrationsRequestContext context) {
        prepare(context, "ListTables");
    }

    private static void prepare(ElastiCacheIntegrationsRequestContext context, String operation) {
        ExecutionAttributes attributes = new ExecutionAttributes();
        context.capture(cacheRequest(operation), attributes);
        context.prepareAttempt(attributes);
    }

    private static ElastiCacheIntegrationsRequest cacheRequest(String operation) {
        return new ElastiCacheIntegrationsRequest(
                "POST",
                "https://dynamodb.us-east-1.amazonaws.com/",
                Collections.emptyMap(),
                new byte[0],
                new RequestMetadata("dynamodb", operation));
    }

    private static HttpExecuteRequest signedRequest() {
        SdkHttpFullRequest request =
                SdkHttpFullRequest.builder()
                        .method(SdkHttpMethod.POST)
                        .uri(URI.create("https://dynamodb.us-east-1.amazonaws.com/"))
                        .putHeader("Authorization", "signed-value")
                        .build();
        return HttpExecuteRequest.builder().request(request).build();
    }

    private static final class StubSession extends ElastiCacheIntegrationsSession {
        private ElastiCacheIntegrationsResponse response;
        private RuntimeException failure;
        private boolean open = true;
        private boolean healthy = true;
        private final AtomicInteger requestCount = new AtomicInteger();
        private volatile ElastiCacheIntegrationsRequest lastRequest;
        private final CountDownLatch requestStarted = new CountDownLatch(1);
        private volatile boolean blockUntilCancelled;

        private StubSession() {
            super(new ElastiCacheIntegrationsConnector("unused", 1, null), "user", "token");
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        boolean isConnectionHealthy() {
            return healthy;
        }

        @Override
        public ElastiCacheIntegrationsResponse request(ElastiCacheIntegrationsRequest request) {
            return request(request, new ElastiCacheIntegrationsRequestCancellation());
        }

        @Override
        ElastiCacheIntegrationsResponse request(
                ElastiCacheIntegrationsRequest request,
                ElastiCacheIntegrationsRequestCancellation cancellation) {
            requestCount.incrementAndGet();
            lastRequest = request;
            requestStarted.countDown();
            if (blockUntilCancelled) {
                while (!cancellation.isCancelled()) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                }
                throw new ElastiCacheIntegrationsRequestCancellation.CancelledException();
            }
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }

    private static final class RecordingHttpClient implements SdkHttpClient {
        private final AtomicInteger prepareCount = new AtomicInteger();
        private final AtomicInteger closeCount = new AtomicInteger();
        private final java.util.concurrent.atomic.AtomicReference<HttpExecuteRequest> request =
                new java.util.concurrent.atomic.AtomicReference<>();
        private final AtomicBoolean aborted = new AtomicBoolean();
        private final CountDownLatch callStarted = new CountDownLatch(1);
        private volatile boolean blockCall;

        @Override
        public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
            prepareCount.incrementAndGet();
            this.request.set(request);
            return new ExecutableHttpRequest() {
                @Override
                public HttpExecuteResponse call() {
                    callStarted.countDown();
                    while (blockCall && !aborted.get()) {
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                    }
                    return HttpExecuteResponse.builder()
                            .response(SdkHttpFullResponse.builder().statusCode(201).build())
                            .responseBody(AbortableInputStream.create(new ByteArrayInputStream(new byte[0])))
                            .build();
                }

                @Override
                public void abort() {
                    aborted.set(true);
                }
            };
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }
}
