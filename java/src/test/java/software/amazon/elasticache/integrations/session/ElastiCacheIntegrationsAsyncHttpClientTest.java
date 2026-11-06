package software.amazon.elasticache.integrations.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.exception.RetryableException;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.InterceptorContext;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.async.AsyncExecuteRequest;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.async.SdkAsyncHttpResponseHandler;
import software.amazon.awssdk.http.async.SdkHttpContentPublisher;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
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

class ElastiCacheIntegrationsAsyncHttpClientTest {

    private static final byte[] PAYLOAD = "payload".getBytes(StandardCharsets.UTF_8);

    @Test
    void knownOversizedWriteFallsBackBeforeSubscribing() throws Exception {
        RecordingOrigin origin = new RecordingOrigin();
        TrackingPublisher body = new TrackingPublisher("oversized", 9L);
        // The reported 9-byte body exceeds the 4-byte limit; one collector is available.
        ElastiCacheIntegrationsAsyncHttpClient client =
                limitedClient(origin, preparedContext("UpdateItem"), 4, 1);

        client.execute(executeRequest(body)).get(2, TimeUnit.SECONDS);

        // Origin receives the request directly, so Cache never subscribes to its body.
        assertEquals(1, origin.executeCount.get());
        assertEquals(0, body.subscribeCount.get());
        client.close();
    }

    @Test
    void unknownLengthWriteFallsBackBeforeSubscribing() throws Exception {
        RecordingBodyOrigin origin = new RecordingBodyOrigin();
        TrackingPublisher body = new TrackingPublisher("unknown", null);
        // Unknown lengths are not admitted because Cache cannot bound their collection.
        ElastiCacheIntegrationsAsyncHttpClient client =
                limitedClient(origin, preparedContext("UpdateItem"), 4, 1);

        client.execute(executeRequest(body)).get(2, TimeUnit.SECONDS);

        // Only origin subscribes; Cache does not consume any body data.
        assertEquals(1, body.subscribeCount.get());
        assertEquals(0, body.cancelCount.get());
        assertArrayEquals("unknown".getBytes(StandardCharsets.UTF_8), origin.body);
        client.close();
    }

    @Test
    void writeThatExceedsReportedLengthIsCancelledAndReplayedByOrigin() throws Exception {
        RecordingBodyOrigin origin = new RecordingBodyOrigin();
        TrackingPublisher body = new TrackingPublisher("oversized", 4L);
        // The publisher reports 4 bytes but emits 9, so collection enforces the hard limit.
        ElastiCacheIntegrationsAsyncHttpClient client =
                limitedClient(origin, preparedContext("UpdateItem"), 4, 1);

        client.execute(executeRequest(body)).get(2, TimeUnit.SECONDS);

        // Cache cancels its first subscription; origin then subscribes to replay the body.
        assertEquals(2, body.subscribeCount.get());
        assertEquals(1, body.cancelCount.get());
        // Cache requests one chunk at a time instead of unbounded reactive-streams demand.
        assertEquals(1, body.collectorDemand.get());
        assertArrayEquals("oversized".getBytes(StandardCharsets.UTF_8), origin.body);
        client.close();
    }

    @Test
    void collectorSaturationFallsBackWithoutSubscribing() throws Exception {
        RecordingOrigin origin = new RecordingOrigin();
        ElastiCacheIntegrationsRequestContext context = preparedContext();
        BlockingPublisher admittedBody = new BlockingPublisher();
        TrackingPublisher overflowBody = new TrackingPublisher("second", 6L);
        // Both bodies fit under 100 bytes, but the blocked request holds the only collector.
        ElastiCacheIntegrationsAsyncHttpClient client = limitedClient(origin, context, 100, 1);

        CompletableFuture<Void> admitted = client.execute(executeRequest(admittedBody));
        assertTrue(admittedBody.subscribed.await(2, TimeUnit.SECONDS));
        prepareAttempt(context);

        client.execute(executeRequest(overflowBody)).get(2, TimeUnit.SECONDS);

        // The second request bypasses Cache without subscribing while the permit is occupied.
        assertEquals(1, origin.executeCount.get());
        assertEquals(0, overflowBody.subscribeCount.get());
        admitted.cancel(true);
        assertTrue(admittedBody.cancelled.await(2, TimeUnit.SECONDS));
        client.close();
    }

    @Test
    void collectorSaturationOnRoutedWriteIsRetryableWithoutContactingOrigin() throws Exception {
        RecordingOrigin origin = new RecordingOrigin();
        ElastiCacheIntegrationsRequestContext context = preparedContext("UpdateItem");
        BlockingPublisher admittedBody = new BlockingPublisher();
        TrackingPublisher overflowBody = new TrackingPublisher("second", 6L);
        ElastiCacheIntegrationsAsyncHttpClient client = limitedClient(origin, context, 100, 1);

        CompletableFuture<Void> admitted = client.execute(executeRequest(admittedBody));
        assertTrue(admittedBody.subscribed.await(2, TimeUnit.SECONDS));
        prepareAttempt(context, "UpdateItem");

        ExecutionException failure =
                assertThrows(
                        ExecutionException.class,
                        () -> client.execute(executeRequest(overflowBody)).get(2, TimeUnit.SECONDS));

        assertTrue(failure.getCause() instanceof RetryableException);
        assertTrue(failure.getCause().getCause() instanceof ElastiCacheIntegrationsCacheUnavailable);
        assertEquals(0, origin.executeCount.get());
        assertEquals(0, overflowBody.subscribeCount.get());
        admitted.cancel(true);
        assertTrue(admittedBody.cancelled.await(2, TimeUnit.SECONDS));
        client.close();
    }

    @Test
    void executorRejectionOnRoutedWriteIsRetryableWithoutContactingOrigin() {
        RecordingOrigin origin = new RecordingOrigin();
        Executor rejectingExecutor =
                command -> {
                    throw new RejectedExecutionException("at capacity");
                };
        ElastiCacheIntegrationsAsyncHttpClient client =
                new ElastiCacheIntegrationsAsyncHttpClient(
                        new ImmediateSession(),
                        preparedContext("UpdateItem"),
                        origin,
                        false,
                        rejectingExecutor,
                        null);

        ExecutionException failure =
                assertThrows(
                        ExecutionException.class,
                        () -> client.execute(executeRequest()).get(2, TimeUnit.SECONDS));

        assertTrue(failure.getCause() instanceof RetryableException);
        assertTrue(failure.getCause().getCause() instanceof ElastiCacheIntegrationsCacheUnavailable);
        assertEquals(0, origin.executeCount.get());
        client.close();
    }

    @Test
    void executorRejectionOnRoutedReadFallsBackToOrigin() throws Exception {
        RecordingOrigin origin = new RecordingOrigin();
        Executor rejectingExecutor =
                command -> {
                    throw new RejectedExecutionException("at capacity");
                };
        ElastiCacheIntegrationsAsyncHttpClient client =
                new ElastiCacheIntegrationsAsyncHttpClient(
                        new ImmediateSession(),
                        preparedContext("GetItem"),
                        origin,
                        false,
                        rejectingExecutor,
                        null);

        client.execute(executeRequest()).get(2, TimeUnit.SECONDS);

        assertEquals(1, origin.executeCount.get());
        client.close();
    }

    @Test
    void predicateDeclineReplaysBufferedBodyToOrigin() throws Exception {
        RecordingBodyOrigin origin = new RecordingBodyOrigin();
        ElastiCacheIntegrationsAsyncHttpClient client =
                new ElastiCacheIntegrationsAsyncHttpClient(
                        new DecliningSession(), preparedContext(), origin, false, Runnable::run, null);

        client.execute(executeRequest(new TrackingPublisher("declined", 8L))).get(2, TimeUnit.SECONDS);

        assertArrayEquals("declined".getBytes(StandardCharsets.UTF_8), origin.body);
        client.close();
    }

    @Test
    void cancellationReachesActiveElastiCacheIntegrationsRequestWithoutOriginFallback()
            throws Exception {
        BlockingSession session = new BlockingSession();
        RecordingOrigin origin = new RecordingOrigin();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ElastiCacheIntegrationsRequestContext context = preparedContext();
        ElastiCacheIntegrationsAsyncHttpClient client =
                new ElastiCacheIntegrationsAsyncHttpClient(
                        session, context, origin, false, executor, executor);

        CompletableFuture<Void> request = client.execute(executeRequest());
        assertTrue(session.started.await(2, TimeUnit.SECONDS));

        request.cancel(true);
        assertTrue(session.cancelled.await(2, TimeUnit.SECONDS));
        assertTrue(request.isCancelled());
        assertEquals(0, origin.executeCount.get());
        client.close();
    }

    @Test
    void cancellationWhileCollectingBodyCancelsSubscription() throws Exception {
        RecordingOrigin origin = new RecordingOrigin();
        BlockingPublisher body = new BlockingPublisher();
        ElastiCacheIntegrationsAsyncHttpClient client =
                new ElastiCacheIntegrationsAsyncHttpClient(
                        new ImmediateSession(), preparedContext(), origin, false, Runnable::run, null);

        CompletableFuture<Void> request = client.execute(executeRequest(body));
        assertTrue(body.subscribed.await(2, TimeUnit.SECONDS));

        request.cancel(true);

        assertTrue(body.cancelled.await(2, TimeUnit.SECONDS));
        assertTrue(request.isCancelled());
        assertEquals(0, origin.executeCount.get());
        client.close();
    }

    @Test
    void cancellationDuringOriginFallbackCancelsOriginFuture() throws Exception {
        BlockingOrigin origin = new BlockingOrigin();
        ElastiCacheIntegrationsAsyncHttpClient client =
                new ElastiCacheIntegrationsAsyncHttpClient(
                        new DroppedConnectionSession(), preparedContext(), origin, false, Runnable::run, null);

        CompletableFuture<Void> request = client.execute(executeRequest());
        assertTrue(origin.started.await(2, TimeUnit.SECONDS));

        request.cancel(true);

        assertTrue(origin.result.isCancelled());
        assertTrue(request.isCancelled());
        client.close();
    }

    @Test
    void droppedIdempotentTransportFailureDelegatesToOrigin() throws Exception {
        RecordingOrigin origin = new RecordingOrigin();
        ElastiCacheIntegrationsAsyncHttpClient client =
                new ElastiCacheIntegrationsAsyncHttpClient(
                        new DroppedTimeoutSession(), preparedContext(), origin, false, Runnable::run, null);

        client.execute(executeRequest()).get(2, TimeUnit.SECONDS);

        assertEquals(1, origin.executeCount.get());
        client.close();
    }

    @Test
    void nonIdempotentTransportFailureDoesNotContactOrigin() {
        RecordingOrigin origin = new RecordingOrigin();
        ElastiCacheIntegrationsAsyncHttpClient client =
                new ElastiCacheIntegrationsAsyncHttpClient(
                        new TimeoutSession(),
                        preparedContext("UpdateItem"),
                        origin,
                        false,
                        Runnable::run,
                        null);

        ExecutionException failure =
                assertThrows(
                        ExecutionException.class,
                        () -> client.execute(executeRequest()).get(2, TimeUnit.SECONDS));

        assertTrue(failure.getCause() instanceof ElastiCacheIntegrationsTimeoutError);
        assertEquals(0, origin.executeCount.get());
        client.close();
    }

    @Test
    void nonIdempotentRequestNotSentDelegatesToOrigin() throws Exception {
        RecordingOrigin origin = new RecordingOrigin();
        ElastiCacheIntegrationsAsyncHttpClient client =
                new ElastiCacheIntegrationsAsyncHttpClient(
                        new RequestNotSentSession(),
                        preparedContext("UpdateItem"),
                        origin,
                        false,
                        Runnable::run,
                        null);

        client.execute(executeRequest()).get(2, TimeUnit.SECONDS);

        assertEquals(1, origin.executeCount.get());
        client.close();
    }

    @Test
    void malformedResponseDoesNotRetryOrContactOrigin() {
        RecordingOrigin origin = new RecordingOrigin();
        ElastiCacheIntegrationsAsyncHttpClient client =
                new ElastiCacheIntegrationsAsyncHttpClient(
                        new ProtocolFailureSession(),
                        preparedContext("GetItem"),
                        origin,
                        false,
                        Runnable::run,
                        null);

        ExecutionException failure =
                assertThrows(
                        ExecutionException.class,
                        () -> client.execute(executeRequest()).get(2, TimeUnit.SECONDS));

        assertTrue(failure.getCause() instanceof ElastiCacheIntegrationsProtocolError);
        assertEquals(0, origin.executeCount.get());
        client.close();
    }

    @Test
    void nonOriginRejectionDoesNotContactOrigin() {
        RecordingOrigin origin = new RecordingOrigin();
        ElastiCacheIntegrationsAsyncHttpClient client =
                new ElastiCacheIntegrationsAsyncHttpClient(
                        new RejectedSession(), preparedContext("GetItem"), origin, false, Runnable::run, null);

        ExecutionException failure =
                assertThrows(
                        ExecutionException.class,
                        () -> client.execute(executeRequest()).get(2, TimeUnit.SECONDS));

        assertTrue(failure.getCause() instanceof ElastiCacheIntegrationsRejectedError);
        assertEquals(0, origin.executeCount.get());
        client.close();
    }

    @Test
    void idempotentOriginErrorIsRetryableWithoutContactingOrigin() {
        RecordingOrigin origin = new RecordingOrigin();
        ElastiCacheIntegrationsAsyncHttpClient client =
                new ElastiCacheIntegrationsAsyncHttpClient(
                        new OriginErrorSession(),
                        preparedContext("GetItem"),
                        origin,
                        false,
                        Runnable::run,
                        null);

        ExecutionException failure =
                assertThrows(
                        ExecutionException.class,
                        () -> client.execute(executeRequest()).get(2, TimeUnit.SECONDS));

        assertTrue(failure.getCause() instanceof RetryableException);
        assertTrue(failure.getCause().getCause() instanceof ElastiCacheIntegrationsRejectedError);
        assertEquals(0, origin.executeCount.get());
        client.close();
    }

    @Test
    void nonIdempotentOriginErrorIsNonRetryableWithoutContactingOrigin() {
        RecordingOrigin origin = new RecordingOrigin();
        ElastiCacheIntegrationsAsyncHttpClient client =
                new ElastiCacheIntegrationsAsyncHttpClient(
                        new OriginErrorSession(),
                        preparedContext("UpdateItem"),
                        origin,
                        false,
                        Runnable::run,
                        null);

        ExecutionException failure =
                assertThrows(
                        ExecutionException.class,
                        () -> client.execute(executeRequest()).get(2, TimeUnit.SECONDS));

        assertTrue(failure.getCause() instanceof ElastiCacheIntegrationsRejectedError);
        assertEquals(0, origin.executeCount.get());
        client.close();
    }

    @Test
    void expiredFallbackWindowDoesNotContactOrigin() {
        RecordingOrigin origin = new RecordingOrigin();
        ElastiCacheIntegrationsAsyncHttpClient client =
                new ElastiCacheIntegrationsAsyncHttpClient(
                        new CacheUnavailableSession(), preparedContext(), origin, false, Runnable::run, null);

        assertThrows(
                ExecutionException.class, () -> client.execute(executeRequest()).get(2, TimeUnit.SECONDS));
        assertEquals(0, origin.executeCount.get());
        client.close();
    }

    @Test
    void shutdownFailureDoesNotStartIdempotentFallback() {
        RecordingOrigin origin = new RecordingOrigin();
        ElastiCacheIntegrationsAsyncHttpClient client =
                new ElastiCacheIntegrationsAsyncHttpClient(
                        new ClosedFailureSession(), preparedContext(), origin, false, Runnable::run, null);

        assertThrows(
                ExecutionException.class, () -> client.execute(executeRequest()).get(2, TimeUnit.SECONDS));
        assertEquals(0, origin.executeCount.get());
        client.close();
    }

    @Test
    void closeClosesOnlyOwnedResourcesAndIsIdempotent() {
        ImmediateSession session = new ImmediateSession();
        RecordingOrigin callerManaged = new RecordingOrigin();
        ExecutorService callerExecutor = Executors.newSingleThreadExecutor();
        ElastiCacheIntegrationsAsyncHttpClient callerManagedClient =
                new ElastiCacheIntegrationsAsyncHttpClient(
                        session, preparedContext(), callerManaged, false, callerExecutor, null);

        callerManagedClient.close();
        callerManagedClient.close();

        assertEquals(0, callerManaged.closeCount.get());
        assertFalse(callerExecutor.isShutdown());
        callerExecutor.shutdownNow();

        RecordingOrigin owned = new RecordingOrigin();
        ExecutorService ownedExecutor = Executors.newSingleThreadExecutor();
        ElastiCacheIntegrationsAsyncHttpClient ownedClient =
                new ElastiCacheIntegrationsAsyncHttpClient(
                        session, preparedContext(), owned, true, ownedExecutor, ownedExecutor);

        ownedClient.close();
        ownedClient.close();

        assertEquals(1, owned.closeCount.get());
        assertTrue(ownedExecutor.isShutdown());
    }

    @Test
    void streamingBodyPassesThrough() throws Exception {
        RecordingSession session = new RecordingSession();
        RecordingBodyOrigin origin = new RecordingBodyOrigin();
        ExecutorService streamExecutor = Executors.newSingleThreadExecutor();
        try {
            AsyncRequestBody body =
                    AsyncRequestBody.fromInputStream(
                            new ByteArrayInputStream(PAYLOAD), (long) PAYLOAD.length, streamExecutor);

            sendThroughInterceptor(session, origin, body).get(2, TimeUnit.SECONDS);
        } finally {
            streamExecutor.shutdownNow();
        }

        assertEquals(0, session.requestCount.get());
        assertArrayEquals(PAYLOAD, origin.body);
    }

    /** Async bodies other than {@code Bytes} skip Cache, even when their length is known. */
    @ParameterizedTest
    @ValueSource(strings = {"fromFile", "fromPublisher", "custom"})
    void uncarriableBodiesAreDeclined(String factory, @TempDir Path dir) throws Exception {
        RecordingSession session = new RecordingSession();
        RecordingBodyOrigin origin = new RecordingBodyOrigin();
        AsyncRequestBody body;
        switch (factory) {
            case "fromFile":
                body = AsyncRequestBody.fromFile(Files.write(dir.resolve("upload.bin"), PAYLOAD));
                break;
            case "fromPublisher":
                body = AsyncRequestBody.fromPublisher(AsyncRequestBody.fromBytes(PAYLOAD));
                break;
            default:
                body = new CustomBody(AsyncRequestBody.fromBytes(PAYLOAD));
        }

        sendThroughInterceptor(session, origin, body).get(2, TimeUnit.SECONDS);

        assertEquals(0, session.requestCount.get());
        assertArrayEquals(PAYLOAD, origin.body);
    }

    /**
     * {@code Bytes} bodies, and requests with no async body (the DynamoDB shape, whose body the SDK
     * publishes from the marshalled request), are read for Cache.
     */
    @ParameterizedTest
    @CsvSource({"fromBytes, payload", "fromString, payload", "empty, ''", "none, payload"})
    void bodyConversion(String factory, String expected) throws Exception {
        RecordingSession session = new RecordingSession();
        RecordingOrigin origin = new RecordingOrigin();
        AsyncRequestBody body;
        switch (factory) {
            case "fromBytes":
                body = AsyncRequestBody.fromBytes(PAYLOAD);
                break;
            case "fromString":
                body = AsyncRequestBody.fromString("payload");
                break;
            case "empty":
                body = AsyncRequestBody.empty();
                break;
            default:
                body = null;
        }

        sendThroughInterceptor(session, origin, body).get(2, TimeUnit.SECONDS);

        assertEquals(1, session.requestCount.get());
        assertEquals(expected, new String(session.lastBody, StandardCharsets.UTF_8));
        assertEquals(0, origin.executeCount.get());
    }

    /**
     * Drive the async interceptor and its transport in the order the SDK calls them. A null {@code
     * body} has no async request body, and the transport receives {@link #PAYLOAD}.
     */
    private static CompletableFuture<Void> sendThroughInterceptor(
            ElastiCacheIntegrationsSession session, SdkAsyncHttpClient origin, AsyncRequestBody body) {
        ElastiCacheIntegrationsAsyncInterceptor interceptor =
                new ElastiCacheIntegrationsAsyncInterceptor(
                        session, origin, false, Runnable::run, null, 1024, 1, false, null);
        SdkHttpFullRequest httpRequest =
                SdkHttpFullRequest.builder()
                        .method(SdkHttpMethod.POST)
                        .uri(URI.create("https://dynamodb.us-east-1.amazonaws.com/"))
                        .build();
        InterceptorContext context =
                InterceptorContext.builder()
                        .request(PutItemRequest.builder().build())
                        .httpRequest(httpRequest)
                        .asyncRequestBody(body)
                        .build();
        ExecutionAttributes attributes = new ExecutionAttributes();
        interceptor.modifyHttpRequest(context, attributes);
        interceptor.beforeTransmission(context, attributes);
        AsyncRequestBody published = body != null ? body : AsyncRequestBody.fromBytes(PAYLOAD);
        return interceptor
                .getHttpClient()
                .execute(
                        AsyncExecuteRequest.builder()
                                .request(httpRequest)
                                .requestContentPublisher(
                                        new SdkHttpContentPublisher() {
                                            @Override
                                            public Optional<Long> contentLength() {
                                                return published.contentLength();
                                            }

                                            @Override
                                            public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
                                                published.subscribe(subscriber);
                                            }
                                        })
                                .responseHandler(new NoopResponseHandler())
                                .build());
    }

    private static ElastiCacheIntegrationsRequestContext preparedContext() {
        return preparedContext("ListTables");
    }

    private static ElastiCacheIntegrationsRequestContext preparedContext(String operation) {
        ElastiCacheIntegrationsRequestContext context = new ElastiCacheIntegrationsRequestContext();
        prepareAttempt(context, operation);
        return context;
    }

    private static void prepareAttempt(ElastiCacheIntegrationsRequestContext context) {
        prepareAttempt(context, "ListTables");
    }

    private static void prepareAttempt(
            ElastiCacheIntegrationsRequestContext context, String operation) {
        ExecutionAttributes attributes = new ExecutionAttributes();
        context.capture(cacheRequest(operation), attributes);
        context.prepareAttempt(attributes);
    }

    private static ElastiCacheIntegrationsAsyncHttpClient limitedClient(
            SdkAsyncHttpClient origin,
            ElastiCacheIntegrationsRequestContext context,
            int maxBodyBytes,
            int maxCollectors) {
        return new ElastiCacheIntegrationsAsyncHttpClient(
                new ImmediateSession(),
                context,
                origin,
                false,
                Runnable::run,
                null,
                maxBodyBytes,
                maxCollectors);
    }

    private static ElastiCacheIntegrationsRequest cacheRequest(String operation) {
        return new ElastiCacheIntegrationsRequest(
                "POST",
                "https://dynamodb.us-east-1.amazonaws.com/",
                Collections.emptyMap(),
                new RequestMetadata("dynamodb", operation));
    }

    private static AsyncExecuteRequest executeRequest() {
        return executeRequest(new EmptyPublisher());
    }

    private static AsyncExecuteRequest executeRequest(SdkHttpContentPublisher body) {
        return AsyncExecuteRequest.builder()
                .request(
                        SdkHttpFullRequest.builder()
                                .method(SdkHttpMethod.POST)
                                .uri(URI.create("https://dynamodb.us-east-1.amazonaws.com/"))
                                .build())
                .requestContentPublisher(body)
                .responseHandler(new NoopResponseHandler())
                .build();
    }

    private abstract static class OpenSession extends ElastiCacheIntegrationsSession {
        private OpenSession() {
            super(new ElastiCacheIntegrationsConnector("unused", 1, null), "user", "token");
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        boolean isConnectionHealthy() {
            return true;
        }
    }

    private static final class BlockingSession extends OpenSession {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch cancelled = new CountDownLatch(1);

        private BlockingSession() {
            super();
        }

        @Override
        ElastiCacheIntegrationsResponse request(
                ElastiCacheIntegrationsRequest request,
                ElastiCacheIntegrationsRequestCancellation cancellation) {
            started.countDown();
            while (!cancellation.isCancelled()) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            cancelled.countDown();
            throw new ElastiCacheIntegrationsRequestCancellation.CancelledException();
        }
    }

    private static final class RecordingSession extends OpenSession {
        private final AtomicInteger requestCount = new AtomicInteger();
        private volatile byte[] lastBody;

        private RecordingSession() {
            super();
        }

        @Override
        ElastiCacheIntegrationsResponse request(
                ElastiCacheIntegrationsRequest request,
                ElastiCacheIntegrationsRequestCancellation cancellation) {
            requestCount.incrementAndGet();
            lastBody = request.getBody();
            return new ElastiCacheIntegrationsResponse(
                    200, Collections.emptyMap(), "cache".getBytes(StandardCharsets.UTF_8));
        }
    }

    /** A caller's own body type, which the SDK names {@code Unknown}. */
    private static final class CustomBody implements AsyncRequestBody {
        private final AsyncRequestBody delegate;

        private CustomBody(AsyncRequestBody delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<Long> contentLength() {
            return delegate.contentLength();
        }

        @Override
        public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
            delegate.subscribe(subscriber);
        }
    }

    private static final class ImmediateSession extends OpenSession {
        private ImmediateSession() {
            super();
        }
    }

    private static final class DecliningSession extends OpenSession {
        private DecliningSession() {
            super();
        }

        @Override
        ElastiCacheIntegrationsResponse request(
                ElastiCacheIntegrationsRequest request,
                ElastiCacheIntegrationsRequestCancellation cancellation) {
            return null;
        }
    }

    private static class TimeoutSession extends OpenSession {
        private TimeoutSession() {
            super();
        }

        @Override
        ElastiCacheIntegrationsResponse request(
                ElastiCacheIntegrationsRequest request,
                ElastiCacheIntegrationsRequestCancellation cancellation) {
            throw new ElastiCacheIntegrationsTimeoutError("cache timed out");
        }
    }

    private static final class RequestNotSentSession extends OpenSession {
        @Override
        ElastiCacheIntegrationsResponse request(
                ElastiCacheIntegrationsRequest request,
                ElastiCacheIntegrationsRequestCancellation cancellation) {
            throw new ElastiCacheIntegrationsRequestNotSent("admission timed out");
        }
    }

    private static final class DroppedTimeoutSession extends TimeoutSession {
        @Override
        boolean isConnectionHealthy() {
            return false;
        }
    }

    private static final class ProtocolFailureSession extends OpenSession {
        @Override
        boolean isConnectionHealthy() {
            return false;
        }

        @Override
        ElastiCacheIntegrationsResponse request(
                ElastiCacheIntegrationsRequest request,
                ElastiCacheIntegrationsRequestCancellation cancellation) {
            throw new ElastiCacheIntegrationsProtocolError("malformed response");
        }
    }

    private static final class RejectedSession extends OpenSession {
        @Override
        boolean isConnectionHealthy() {
            return false;
        }

        @Override
        ElastiCacheIntegrationsResponse request(
                ElastiCacheIntegrationsRequest request,
                ElastiCacheIntegrationsRequestCancellation cancellation) {
            throw new ElastiCacheIntegrationsRejectedError("UNSUPPORTED", "request rejected");
        }
    }

    private static final class OriginErrorSession extends OpenSession {
        @Override
        ElastiCacheIntegrationsResponse request(
                ElastiCacheIntegrationsRequest request,
                ElastiCacheIntegrationsRequestCancellation cancellation) {
            throw new ElastiCacheIntegrationsRejectedError("ORIGIN_ERROR", "origin unavailable");
        }
    }

    private static final class ClosedFailureSession extends ElastiCacheIntegrationsSession {
        private ClosedFailureSession() {
            super(new ElastiCacheIntegrationsConnector("unused", 1, null), "user", "token");
        }

        @Override
        ElastiCacheIntegrationsResponse request(
                ElastiCacheIntegrationsRequest request,
                ElastiCacheIntegrationsRequestCancellation cancellation) {
            throw new ElastiCacheIntegrationsConnectionLost("session closed");
        }
    }

    private static final class CacheUnavailableSession extends OpenSession {
        @Override
        boolean isConnectionHealthy() {
            return false;
        }

        @Override
        ElastiCacheIntegrationsResponse request(
                ElastiCacheIntegrationsRequest request,
                ElastiCacheIntegrationsRequestCancellation cancellation) {
            throw new ElastiCacheIntegrationsCacheUnavailable("fallback window expired");
        }
    }

    private static final class DroppedConnectionSession extends OpenSession {
        @Override
        boolean isConnectionHealthy() {
            return false;
        }

        @Override
        ElastiCacheIntegrationsResponse request(
                ElastiCacheIntegrationsRequest request,
                ElastiCacheIntegrationsRequestCancellation cancellation) {
            throw new ElastiCacheIntegrationsConnectionLost("cache connection dropped");
        }
    }

    private static final class RecordingOrigin implements SdkAsyncHttpClient {
        private final AtomicInteger executeCount = new AtomicInteger();
        private final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public CompletableFuture<Void> execute(AsyncExecuteRequest request) {
            executeCount.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }

    private static final class BlockingOrigin implements SdkAsyncHttpClient {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CompletableFuture<Void> result = new CompletableFuture<>();

        @Override
        public CompletableFuture<Void> execute(AsyncExecuteRequest request) {
            started.countDown();
            return result;
        }

        @Override
        public void close() {}
    }

    private static final class RecordingBodyOrigin implements SdkAsyncHttpClient {
        private byte[] body;

        @Override
        public CompletableFuture<Void> execute(AsyncExecuteRequest request) {
            CompletableFuture<Void> result = new CompletableFuture<>();
            request
                    .requestContentPublisher()
                    .subscribe(
                            new Subscriber<ByteBuffer>() {
                                private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

                                @Override
                                public void onSubscribe(Subscription subscription) {
                                    subscription.request(Long.MAX_VALUE);
                                }

                                @Override
                                public void onNext(ByteBuffer value) {
                                    byte[] chunk = new byte[value.remaining()];
                                    value.get(chunk);
                                    bytes.write(chunk, 0, chunk.length);
                                }

                                @Override
                                public void onError(Throwable failure) {
                                    result.completeExceptionally(failure);
                                }

                                @Override
                                public void onComplete() {
                                    body = bytes.toByteArray();
                                    result.complete(null);
                                }
                            });
            return result;
        }

        @Override
        public void close() {}
    }

    private static final class TrackingPublisher implements SdkHttpContentPublisher {
        private final byte[] body;
        private final Optional<Long> contentLength;
        private final AtomicInteger subscribeCount = new AtomicInteger();
        private final AtomicInteger cancelCount = new AtomicInteger();
        private final AtomicLong collectorDemand = new AtomicLong();

        private TrackingPublisher(String body, Long contentLength) {
            this.body = body.getBytes(StandardCharsets.UTF_8);
            this.contentLength = Optional.ofNullable(contentLength);
        }

        @Override
        public Optional<Long> contentLength() {
            return contentLength;
        }

        @Override
        public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
            int subscriptionNumber = subscribeCount.incrementAndGet();
            subscriber.onSubscribe(
                    new Subscription() {
                        private boolean emitted;
                        private boolean completed;

                        @Override
                        public void request(long count) {
                            if (completed) {
                                return;
                            }
                            if (subscriptionNumber == 1) {
                                collectorDemand.set(count);
                            }
                            if (!emitted) {
                                emitted = true;
                                subscriber.onNext(ByteBuffer.wrap(body).asReadOnlyBuffer());
                            }
                            if (!completed) {
                                completed = true;
                                subscriber.onComplete();
                            }
                        }

                        @Override
                        public void cancel() {
                            if (!completed) {
                                completed = true;
                                cancelCount.incrementAndGet();
                            }
                        }
                    });
        }
    }

    private static final class BlockingPublisher implements SdkHttpContentPublisher {
        private final CountDownLatch subscribed = new CountDownLatch(1);
        private final CountDownLatch cancelled = new CountDownLatch(1);

        @Override
        public Optional<Long> contentLength() {
            return Optional.of(0L);
        }

        @Override
        public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
            subscriber.onSubscribe(
                    new Subscription() {
                        @Override
                        public void request(long count) {
                            subscribed.countDown();
                        }

                        @Override
                        public void cancel() {
                            cancelled.countDown();
                        }
                    });
        }
    }

    private static final class EmptyPublisher implements SdkHttpContentPublisher {
        @Override
        public Optional<Long> contentLength() {
            return Optional.of(0L);
        }

        @Override
        public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
            subscriber.onSubscribe(
                    new Subscription() {
                        private boolean completed;

                        @Override
                        public void request(long count) {
                            if (!completed) {
                                completed = true;
                                subscriber.onComplete();
                            }
                        }

                        @Override
                        public void cancel() {
                            completed = true;
                        }
                    });
        }
    }

    private static final class NoopResponseHandler implements SdkAsyncHttpResponseHandler {
        @Override
        public void onHeaders(software.amazon.awssdk.http.SdkHttpResponse response) {}

        @Override
        public void onStream(org.reactivestreams.Publisher<ByteBuffer> publisher) {}

        @Override
        public void onError(Throwable failure) {}
    }
}
