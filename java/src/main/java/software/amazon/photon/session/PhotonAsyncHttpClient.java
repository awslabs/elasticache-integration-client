package software.amazon.photon.session;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import software.amazon.awssdk.core.exception.RetryableException;
import software.amazon.awssdk.http.SdkHttpFullResponse;
import software.amazon.awssdk.http.async.AsyncExecuteRequest;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.async.SdkAsyncHttpResponseHandler;
import software.amazon.awssdk.http.async.SdkHttpContentPublisher;
import software.amazon.photon.connection.PhotonRequestCancellation;
import software.amazon.photon.exception.PhotonError;
import software.amazon.photon.model.PhotonRequest;
import software.amazon.photon.model.PhotonResponse;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Asynchronous SDK HTTP transport that attempts Photon before the origin service.
 */
public final class PhotonAsyncHttpClient implements SdkAsyncHttpClient {

    private static final String CLIENT_NAME = "java-photon";

    private final PhotonSession session;
    private final PhotonRequestContext requestContext;
    private final SdkAsyncHttpClient originClient;
    private final boolean ownsOriginClient;
    private final Executor executor;
    private final ExecutorService ownedExecutor;
    private final int maxBufferedRequestBytes;
    private final Semaphore bodyCollectorPermits;
    private final AtomicBoolean closed = new AtomicBoolean();

    PhotonAsyncHttpClient(PhotonSession session,
                          PhotonRequestContext requestContext,
                          SdkAsyncHttpClient originClient,
                          boolean ownsOriginClient,
                          Executor executor,
                          ExecutorService ownedExecutor) {
        this(session,
                requestContext,
                originClient,
                ownsOriginClient,
                executor,
                ownedExecutor,
                PhotonAsyncHandle.DEFAULT_MAX_BUFFERED_REQUEST_BYTES,
                PhotonAsyncHandle.DEFAULT_MAX_CONCURRENT_BODY_COLLECTORS);
    }

    PhotonAsyncHttpClient(PhotonSession session,
                          PhotonRequestContext requestContext,
                          SdkAsyncHttpClient originClient,
                          boolean ownsOriginClient,
                          Executor executor,
                          ExecutorService ownedExecutor,
                          int maxBufferedRequestBytes,
                          int maxConcurrentBodyCollectors) {
        this.session = session;
        this.requestContext = requestContext;
        this.originClient = originClient;
        this.ownsOriginClient = ownsOriginClient;
        this.executor = executor;
        this.ownedExecutor = ownedExecutor;
        this.maxBufferedRequestBytes = maxBufferedRequestBytes;
        this.bodyCollectorPermits = new Semaphore(maxConcurrentBodyCollectors);
    }

    @Override
    public CompletableFuture<Void> execute(AsyncExecuteRequest request) {
        // The interceptor records one unsigned Photon request per SDK attempt.
        // Consume it synchronously before this method crosses any thread boundary.
        PhotonRequest capturedRequest = requestContext.takeAttempt();
        if (capturedRequest == null) {
            return failed(request.responseHandler(),
                    new IllegalStateException("Photon request was not captured before HTTP execution"));
        }
        return new RequestExecution(request, capturedRequest).start();
    }

    private final class RequestExecution {
        private final AsyncExecuteRequest request;
        private final PhotonRequest capturedRequest;
        private final CompletableFuture<Void> result = new CompletableFuture<>();
        private final PhotonRequestCancellation cancellation = new PhotonRequestCancellation();
        private final AtomicReference<CompletableFuture<?>> active = new AtomicReference<>();

        private RequestExecution(AsyncExecuteRequest request, PhotonRequest capturedRequest) {
            this.request = request;
            this.capturedRequest = capturedRequest;
        }

        private CompletableFuture<Void> start() {
            registerCancellation();

            SdkHttpContentPublisher publisher = request.requestContentPublisher();
            boolean tooLargeToBuffer = publisher.contentLength()
                    .map(length -> length > maxBufferedRequestBytes)
                    .orElse(true);
            if (tooLargeToBuffer || !bodyCollectorPermits.tryAcquire()) {
                // Do not subscribe when the request cannot fit within the configured
                // buffering budget. The origin transport consumes the original body.
                fallbackToOrigin(request);
                return result;
            }

            collectBody(publisher);
            return result;
        }

        private void collectBody(SdkHttpContentPublisher publisher) {
            // Buffer an admitted body once so Photon can use the bytes and origin
            // fallback can replay them.
            CompletableFuture<byte[]> bodyFuture = collect(publisher, maxBufferedRequestBytes);
            active.set(bodyFuture);
            bodyFuture.whenComplete((body, bodyFailure) -> {
                bodyCollectorPermits.release();
                handleCollectedBody(body, bodyFailure);
            });
        }

        private void handleCollectedBody(byte[] body, Throwable bodyFailure) {
            if (bodyFailure != null) {
                Throwable cause = unwrap(bodyFailure);
                if (cause instanceof BodyLimitExceededException) {
                    fallbackToOrigin(request);
                } else {
                    fail(result, request.responseHandler(), cause);
                }
                return;
            }
            if (result.isCancelled()) {
                return;
            }
            submitToPhoton(body);
        }

        private void submitToPhoton(byte[] body) {
            PhotonRequest photonRequest = withBody(capturedRequest, body);
            CompletableFuture<PhotonResponse> photonFuture;
            try {
                // PhotonSession is blocking, so keep it off the SDK event-loop thread.
                photonFuture = CompletableFuture.supplyAsync(
                        () -> session.request(photonRequest, cancellation), executor);
            } catch (RejectedExecutionException capacityFailure) {
                // Executor saturation means Photon cannot accept this attempt.
                fallbackToOrigin(copyWithBody(request, body));
                return;
            } catch (Throwable submissionFailure) {
                fail(result, request.responseHandler(), submissionFailure);
                return;
            }
            active.set(photonFuture);
            photonFuture.whenComplete((response, photonFailure) -> {
                handlePhotonCompletion(body, response, photonFailure);
            });
        }

        private void handlePhotonCompletion(byte[] body,
                                            PhotonResponse response,
                                            Throwable photonFailure) {
            if (result.isCancelled()) {
                return;
            }
            if (photonFailure == null) {
                if (response == null) {
                    fallbackToOrigin(copyWithBody(request, body));
                } else {
                    deliver(result, request.responseHandler(), response);
                }
                return;
            }

            Throwable cause = unwrap(photonFailure);
            if (cause instanceof PhotonError) {
                PhotonError photonError = (PhotonError) cause;
                if (PhotonFallbackPolicy.shouldFallback(
                        session, capturedRequest, photonError)) {
                    fallbackToOrigin(copyWithBody(request, body));
                } else if (PhotonFallbackPolicy.shouldRetry(
                        session, capturedRequest, photonError)) {
                    fail(result, request.responseHandler(),
                            RetryableException.create("Photon request failed", cause));
                } else {
                    fail(result, request.responseHandler(), photonError);
                }
            } else {
                fail(result, request.responseHandler(), cause);
            }
        }

        private void registerCancellation() {
            // Cancellation can occur during body collection, Photon execution,
            // or origin fallback. The active reference identifies the phase.
            result.whenComplete((ignored, failure) -> {
                if (result.isCancelled()) {
                    cancellation.cancel();
                    CompletableFuture<?> activeFuture = active.get();
                    if (activeFuture != null) {
                        activeFuture.cancel(true);
                    }
                }
            });
        }

        private void fallbackToOrigin(AsyncExecuteRequest originRequest) {
            if (result.isCancelled()) {
                return;
            }

            CompletableFuture<Void> originFuture;
            try {
                originFuture = originClient.execute(originRequest);
            } catch (Throwable originFailure) {
                fail(result, originRequest.responseHandler(), originFailure);
                return;
            }

            active.set(originFuture);
            if (result.isCancelled()) {
                originFuture.cancel(true);
                return;
            }
            originFuture.whenComplete((ignored, originFailure) -> {
                if (originFailure == null) {
                    result.complete(null);
                } else {
                    result.completeExceptionally(unwrap(originFailure));
                }
            });
        }
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
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
    }

    private static PhotonRequest withBody(PhotonRequest request, byte[] body) {
        return new PhotonRequest(
                request.getMethod(),
                request.getUrl(),
                request.getHeaders(),
                body,
                request.getMetadata());
    }

    private static AsyncExecuteRequest copyWithBody(AsyncExecuteRequest request, byte[] body) {
        AsyncExecuteRequest.Builder builder = AsyncExecuteRequest.builder()
                .request(request.request())
                .requestContentPublisher(new ByteArrayPublisher(body))
                .responseHandler(request.responseHandler())
                .fullDuplex(request.fullDuplex())
                .httpExecutionAttributes(request.httpExecutionAttributes());
        request.metricCollector().ifPresent(builder::metricCollector);
        return builder.build();
    }

    private static CompletableFuture<byte[]> collect(Publisher<ByteBuffer> publisher,
                                                     int maxBufferedRequestBytes) {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        AtomicReference<Subscription> subscription = new AtomicReference<>();
        try {
            publisher.subscribe(new Subscriber<ByteBuffer>() {
                private final ByteArrayOutputStream body = new ByteArrayOutputStream();

                @Override
                public void onSubscribe(Subscription value) {
                    if (!subscription.compareAndSet(null, value)) {
                        value.cancel();
                        return;
                    }
                    value.request(1);
                }

                @Override
                public void onNext(ByteBuffer bytes) {
                    int chunkLength = bytes.remaining();
                    if (chunkLength > maxBufferedRequestBytes - body.size()) {
                        Subscription value = subscription.get();
                        if (value != null) {
                            value.cancel();
                        }
                        result.completeExceptionally(new BodyLimitExceededException());
                        return;
                    }

                    byte[] chunk = new byte[chunkLength];
                    bytes.get(chunk);
                    body.write(chunk, 0, chunk.length);

                    Subscription value = subscription.get();
                    if (value != null && !result.isDone()) {
                        value.request(1);
                    }
                }

                @Override
                public void onError(Throwable failure) {
                    result.completeExceptionally(failure);
                }

                @Override
                public void onComplete() {
                    result.complete(body.toByteArray());
                }
            });
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) {
                Subscription value = subscription.get();
                if (value != null) {
                    value.cancel();
                }
            }
        });
        return result;
    }

    private static final class BodyLimitExceededException extends RuntimeException {
        private BodyLimitExceededException() {
            super("Async request body exceeds the Photon buffering limit");
        }
    }

    private static void deliver(CompletableFuture<Void> result,
                                SdkAsyncHttpResponseHandler handler,
                                PhotonResponse photonResponse) {
        try {
            SdkHttpFullResponse response = SdkAdapters.toSdkResponse(photonResponse);
            handler.onHeaders(response);
            handler.onStream(new ByteArrayPublisher(photonResponse.getBody()));
            result.complete(null);
        } catch (Throwable failure) {
            fail(result, handler, failure);
        }
    }

    private static CompletableFuture<Void> failed(SdkAsyncHttpResponseHandler handler,
                                                   Throwable failure) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        fail(result, handler, failure);
        return result;
    }

    private static void fail(CompletableFuture<Void> result,
                             SdkAsyncHttpResponseHandler handler,
                             Throwable failure) {
        if (failure instanceof CancellationException) {
            result.cancel(false);
            return;
        }
        try {
            handler.onError(failure);
        } catch (Throwable handlerFailure) {
            failure.addSuppressed(handlerFailure);
        }
        result.completeExceptionally(failure);
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof CompletionException && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    private static final class ByteArrayPublisher implements SdkHttpContentPublisher {
        private final byte[] body;

        private ByteArrayPublisher(byte[] body) {
            this.body = body != null ? body : new byte[0];
        }

        @Override
        public Optional<Long> contentLength() {
            return Optional.of((long) body.length);
        }

        @Override
        public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
            subscriber.onSubscribe(new Subscription() {
                private boolean completed;

                @Override
                public synchronized void request(long count) {
                    if (completed) {
                        return;
                    }
                    if (count <= 0) {
                        completed = true;
                        subscriber.onError(new IllegalArgumentException(
                                "Subscription request count must be positive"));
                        return;
                    }
                    completed = true;
                    if (body.length > 0) {
                        subscriber.onNext(ByteBuffer.wrap(body).asReadOnlyBuffer());
                    }
                    subscriber.onComplete();
                }

                @Override
                public synchronized void cancel() {
                    completed = true;
                }
            });
        }
    }
}
