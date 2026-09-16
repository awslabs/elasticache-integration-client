package software.amazon.photon.session;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.async.AsyncExecuteRequest;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.async.SdkAsyncHttpResponseHandler;
import software.amazon.awssdk.http.async.SdkHttpContentPublisher;
import software.amazon.photon.connection.PhotonConnector;
import software.amazon.photon.connection.PhotonRequestCancellation;
import software.amazon.photon.exception.PhotonConnectionLost;
import software.amazon.photon.exception.PhotonTimeoutError;
import software.amazon.photon.model.PhotonRequest;
import software.amazon.photon.model.PhotonResponse;
import software.amazon.photon.model.RequestMetadata;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhotonAsyncHttpClientTest {

    @Test
    void knownOversizedBodyFallsBackBeforeSubscribing() throws Exception {
        RecordingOrigin origin = new RecordingOrigin();
        TrackingPublisher body = new TrackingPublisher("oversized", 9L);
        // The reported 9-byte body exceeds the 4-byte limit; one collector is available.
        PhotonAsyncHttpClient client = limitedClient(origin, preparedContext(), 4, 1);

        client.execute(executeRequest(body)).get(2, TimeUnit.SECONDS);

        // Origin receives the request directly, so Photon never subscribes to its body.
        assertEquals(1, origin.executeCount.get());
        assertEquals(0, body.subscribeCount.get());
        client.close();
    }

    @Test
    void unknownLengthBodyFallsBackBeforeSubscribing() throws Exception {
        RecordingBodyOrigin origin = new RecordingBodyOrigin();
        TrackingPublisher body = new TrackingPublisher("unknown", null);
        // Unknown lengths are not admitted because Photon cannot bound their collection.
        PhotonAsyncHttpClient client = limitedClient(origin, preparedContext(), 4, 1);

        client.execute(executeRequest(body)).get(2, TimeUnit.SECONDS);

        // Only origin subscribes; Photon does not consume any body data.
        assertEquals(1, body.subscribeCount.get());
        assertEquals(0, body.cancelCount.get());
        assertArrayEquals("unknown".getBytes(StandardCharsets.UTF_8), origin.body);
        client.close();
    }

    @Test
    void bodyThatExceedsReportedLengthIsCancelledAndReplayedByOrigin() throws Exception {
        RecordingBodyOrigin origin = new RecordingBodyOrigin();
        TrackingPublisher body = new TrackingPublisher("oversized", 4L);
        // The publisher reports 4 bytes but emits 9, so collection enforces the hard limit.
        PhotonAsyncHttpClient client = limitedClient(origin, preparedContext(), 4, 1);

        client.execute(executeRequest(body)).get(2, TimeUnit.SECONDS);

        // Photon cancels its first subscription; origin then subscribes to replay the body.
        assertEquals(2, body.subscribeCount.get());
        assertEquals(1, body.cancelCount.get());
        // Photon requests one chunk at a time instead of unbounded reactive-streams demand.
        assertEquals(1, body.collectorDemand.get());
        assertArrayEquals("oversized".getBytes(StandardCharsets.UTF_8), origin.body);
        client.close();
    }

    @Test
    void collectorSaturationFallsBackWithoutSubscribing() throws Exception {
        RecordingOrigin origin = new RecordingOrigin();
        PhotonRequestContext context = preparedContext();
        BlockingPublisher admittedBody = new BlockingPublisher();
        TrackingPublisher overflowBody = new TrackingPublisher("second", 6L);
        // Both bodies fit under 100 bytes, but the blocked request holds the only collector.
        PhotonAsyncHttpClient client = limitedClient(origin, context, 100, 1);

        CompletableFuture<Void> admitted = client.execute(executeRequest(admittedBody));
        assertTrue(admittedBody.subscribed.await(2, TimeUnit.SECONDS));
        prepareAttempt(context);

        client.execute(executeRequest(overflowBody)).get(2, TimeUnit.SECONDS);

        // The second request bypasses Photon without subscribing while the permit is occupied.
        assertEquals(1, origin.executeCount.get());
        assertEquals(0, overflowBody.subscribeCount.get());
        admitted.cancel(true);
        assertTrue(admittedBody.cancelled.await(2, TimeUnit.SECONDS));
        client.close();
    }

    @Test
    void executorRejectionFallsBackToOrigin() throws Exception {
        RecordingOrigin origin = new RecordingOrigin();
        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("at capacity");
        };
        PhotonAsyncHttpClient client = new PhotonAsyncHttpClient(
                new ImmediateSession(),
                preparedContext(),
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
        PhotonAsyncHttpClient client = new PhotonAsyncHttpClient(
                new DecliningSession(),
                preparedContext(),
                origin,
                false,
                Runnable::run,
                null);

        client.execute(executeRequest(
                new TrackingPublisher("declined", 8L))).get(2, TimeUnit.SECONDS);

        assertArrayEquals("declined".getBytes(StandardCharsets.UTF_8), origin.body);
        client.close();
    }

    @Test
    void cancellationReachesActivePhotonRequestWithoutOriginFallback() throws Exception {
        BlockingSession session = new BlockingSession();
        RecordingOrigin origin = new RecordingOrigin();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        PhotonRequestContext context = preparedContext();
        PhotonAsyncHttpClient client =
                new PhotonAsyncHttpClient(session, context, origin, false, executor, executor);

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
        PhotonAsyncHttpClient client = new PhotonAsyncHttpClient(
                new ImmediateSession(),
                preparedContext(),
                origin,
                false,
                Runnable::run,
                null);

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
        PhotonAsyncHttpClient client = new PhotonAsyncHttpClient(
                new FailingSession(),
                preparedContext(),
                origin,
                false,
                Runnable::run,
                null);

        CompletableFuture<Void> request = client.execute(executeRequest());
        assertTrue(origin.started.await(2, TimeUnit.SECONDS));

        request.cancel(true);

        assertTrue(origin.result.isCancelled());
        assertTrue(request.isCancelled());
        client.close();
    }

    @Test
    void nonIdempotentTransportFailureDoesNotContactOrigin() {
        RecordingOrigin origin = new RecordingOrigin();
        PhotonAsyncHttpClient client = new PhotonAsyncHttpClient(
                new TimeoutSession(),
                preparedContext("UpdateItem"),
                origin,
                false,
                Runnable::run,
                null);

        java.util.concurrent.ExecutionException failure = assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> client.execute(executeRequest()).get(2, TimeUnit.SECONDS));

        assertTrue(failure.getCause() instanceof IOException);
        assertTrue(failure.getCause().getCause() instanceof PhotonTimeoutError);
        assertEquals(0, origin.executeCount.get());
        client.close();
    }

    @Test
    void closeClosesOnlyOwnedResourcesAndIsIdempotent() {
        ImmediateSession session = new ImmediateSession();
        RecordingOrigin callerManaged = new RecordingOrigin();
        ExecutorService callerExecutor = Executors.newSingleThreadExecutor();
        PhotonAsyncHttpClient callerManagedClient = new PhotonAsyncHttpClient(
                session, preparedContext(), callerManaged, false, callerExecutor, null);

        callerManagedClient.close();
        callerManagedClient.close();

        assertEquals(0, callerManaged.closeCount.get());
        assertFalse(callerExecutor.isShutdown());
        callerExecutor.shutdownNow();

        RecordingOrigin owned = new RecordingOrigin();
        ExecutorService ownedExecutor = Executors.newSingleThreadExecutor();
        PhotonAsyncHttpClient ownedClient = new PhotonAsyncHttpClient(
                session, preparedContext(), owned, true, ownedExecutor, ownedExecutor);

        ownedClient.close();
        ownedClient.close();

        assertEquals(1, owned.closeCount.get());
        assertTrue(ownedExecutor.isShutdown());
    }

    private static PhotonRequestContext preparedContext() {
        return preparedContext("ListTables");
    }

    private static PhotonRequestContext preparedContext(String operation) {
        PhotonRequestContext context = new PhotonRequestContext();
        prepareAttempt(context, operation);
        return context;
    }

    private static void prepareAttempt(PhotonRequestContext context) {
        prepareAttempt(context, "ListTables");
    }

    private static void prepareAttempt(PhotonRequestContext context, String operation) {
        ExecutionAttributes attributes = new ExecutionAttributes();
        context.capture(photonRequest(operation), attributes);
        context.prepareAttempt(attributes);
    }

    private static PhotonAsyncHttpClient limitedClient(SdkAsyncHttpClient origin,
                                                       PhotonRequestContext context,
                                                       int maxBodyBytes,
                                                       int maxCollectors) {
        return new PhotonAsyncHttpClient(
                new ImmediateSession(),
                context,
                origin,
                false,
                Runnable::run,
                null,
                maxBodyBytes,
                maxCollectors);
    }

    private static PhotonRequest photonRequest() {
        return photonRequest("ListTables");
    }

    private static PhotonRequest photonRequest(String operation) {
        return new PhotonRequest(
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
                .request(SdkHttpFullRequest.builder()
                        .method(SdkHttpMethod.POST)
                        .uri(URI.create("https://dynamodb.us-east-1.amazonaws.com/"))
                        .build())
                .requestContentPublisher(body)
                .responseHandler(new NoopResponseHandler())
                .build();
    }

    private static final class BlockingSession extends PhotonSession {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch cancelled = new CountDownLatch(1);

        private BlockingSession() {
            super(new PhotonConnector("unused", 1, null), "user", "token");
        }

        @Override
        PhotonResponse request(PhotonRequest request, PhotonRequestCancellation cancellation) {
            started.countDown();
            while (!cancellation.isCancelled()) {
                Thread.yield();
            }
            cancelled.countDown();
            throw new PhotonRequestCancellation.CancelledException();
        }
    }

    private static final class ImmediateSession extends PhotonSession {
        private ImmediateSession() {
            super(new PhotonConnector("unused", 1, null), "user", "token");
        }
    }

    private static final class DecliningSession extends PhotonSession {
        private DecliningSession() {
            super(new PhotonConnector("unused", 1, null), "user", "token");
        }

        @Override
        PhotonResponse request(PhotonRequest request, PhotonRequestCancellation cancellation) {
            return null;
        }
    }

    private static final class FailingSession extends PhotonSession {
        private FailingSession() {
            super(new PhotonConnector("unused", 1, null), "user", "token");
        }

        @Override
        PhotonResponse request(PhotonRequest request, PhotonRequestCancellation cancellation) {
            throw new PhotonConnectionLost("cache unavailable");
        }
    }

    private static final class TimeoutSession extends PhotonSession {
        private TimeoutSession() {
            super(new PhotonConnector("unused", 1, null), "user", "token");
        }

        @Override
        PhotonResponse request(PhotonRequest request, PhotonRequestCancellation cancellation) {
            throw new PhotonTimeoutError("cache timed out");
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
        public void close() {
        }
    }

    private static final class RecordingBodyOrigin implements SdkAsyncHttpClient {
        private byte[] body;

        @Override
        public CompletableFuture<Void> execute(AsyncExecuteRequest request) {
            CompletableFuture<Void> result = new CompletableFuture<>();
            request.requestContentPublisher().subscribe(new Subscriber<ByteBuffer>() {
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
        public void close() {
        }
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
            subscriber.onSubscribe(new Subscription() {
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
            subscriber.onSubscribe(new Subscription() {
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
            subscriber.onSubscribe(new Subscription() {
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
        public void onHeaders(software.amazon.awssdk.http.SdkHttpResponse response) {
        }

        @Override
        public void onStream(org.reactivestreams.Publisher<ByteBuffer> publisher) {
        }

        @Override
        public void onError(Throwable failure) {
        }
    }
}
