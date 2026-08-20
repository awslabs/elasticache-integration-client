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
import software.amazon.photon.connection.PhotonConnection;
import software.amazon.photon.connection.PhotonRequestCancellation;
import software.amazon.photon.model.PhotonRequest;
import software.amazon.photon.model.PhotonResponse;
import software.amazon.photon.model.RequestMetadata;

import java.net.URI;
import java.nio.ByteBuffer;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhotonAsyncHttpClientTest {

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
        PhotonRequestContext context = new PhotonRequestContext();
        ExecutionAttributes attributes = new ExecutionAttributes();
        context.capture(photonRequest(), attributes);
        context.prepareAttempt(attributes);
        return context;
    }

    private static PhotonRequest photonRequest() {
        return new PhotonRequest(
                "POST",
                "https://dynamodb.us-east-1.amazonaws.com/",
                Collections.emptyMap(),
                new RequestMetadata("dynamodb", "ListTables"));
    }

    private static AsyncExecuteRequest executeRequest() {
        return AsyncExecuteRequest.builder()
                .request(SdkHttpFullRequest.builder()
                        .method(SdkHttpMethod.POST)
                        .uri(URI.create("https://dynamodb.us-east-1.amazonaws.com/"))
                        .build())
                .requestContentPublisher(new EmptyPublisher())
                .responseHandler(new NoopResponseHandler())
                .build();
    }

    private static final class BlockingSession extends PhotonSession {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch cancelled = new CountDownLatch(1);

        private BlockingSession() {
            super(new PhotonConnection("unused", 1, null), "user", "token");
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
            super(new PhotonConnection("unused", 1, null), "user", "token");
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
