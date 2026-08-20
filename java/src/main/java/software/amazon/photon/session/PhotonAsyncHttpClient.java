package software.amazon.photon.session;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Asynchronous SDK HTTP transport that attempts Photon before the origin service.
 */
public final class PhotonAsyncHttpClient implements SdkAsyncHttpClient {

    private final PhotonSession session;
    private final PhotonRequestContext requestContext;
    private final SdkAsyncHttpClient originClient;
    private final boolean ownsOriginClient;
    private final Executor executor;
    private final ExecutorService ownedExecutor;
    private final AtomicBoolean closed = new AtomicBoolean();

    PhotonAsyncHttpClient(PhotonSession session,
                          PhotonRequestContext requestContext,
                          SdkAsyncHttpClient originClient,
                          boolean ownsOriginClient,
                          Executor executor,
                          ExecutorService ownedExecutor) {
        this.session = session;
        this.requestContext = requestContext;
        this.originClient = originClient;
        this.ownsOriginClient = ownsOriginClient;
        this.executor = executor;
        this.ownedExecutor = ownedExecutor;
    }

    @Override
    public CompletableFuture<Void> execute(AsyncExecuteRequest request) {
        PhotonRequest capturedRequest = requestContext.takeAttempt();
        if (capturedRequest == null) {
            return failed(request.responseHandler(),
                    new IllegalStateException("Photon request was not captured before HTTP execution"));
        }

        CompletableFuture<Void> result = new CompletableFuture<>();
        CompletableFuture<byte[]> bodyFuture = collect(request.requestContentPublisher());
        PhotonRequestCancellation cancellation = new PhotonRequestCancellation();
        AtomicReference<CompletableFuture<?>> active = new AtomicReference<>(bodyFuture);

        bodyFuture.whenComplete((body, bodyFailure) -> {
            if (bodyFailure != null) {
                fail(result, request.responseHandler(), unwrap(bodyFailure));
                return;
            }
            if (result.isCancelled()) {
                return;
            }

            PhotonRequest photonRequest = withBody(capturedRequest, body);
            CompletableFuture<PhotonResponse> photonFuture;
            try {
                photonFuture = CompletableFuture.supplyAsync(
                        () -> session.request(photonRequest, cancellation), executor);
            } catch (RejectedExecutionException capacityFailure) {
                fallbackToOrigin(result, request, body, active);
                return;
            } catch (Throwable submissionFailure) {
                fail(result, request.responseHandler(), submissionFailure);
                return;
            }
            active.set(photonFuture);
            photonFuture.whenComplete((response, photonFailure) -> {
                if (result.isCancelled()) {
                    return;
                }
                if (photonFailure == null) {
                    deliver(result, request.responseHandler(), response);
                    return;
                }

                Throwable cause = unwrap(photonFailure);
                if (cause instanceof PhotonError) {
                    fallbackToOrigin(result, request, body, active);
                } else {
                    fail(result, request.responseHandler(), cause);
                }
            });
        });

        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) {
                cancellation.cancel();
                bodyFuture.cancel(true);
                CompletableFuture<?> activeFuture = active.get();
                if (activeFuture != null) {
                    activeFuture.cancel(true);
                }
            }
        });
        return result;
    }

    private void fallbackToOrigin(CompletableFuture<Void> result,
                                  AsyncExecuteRequest request,
                                  byte[] body,
                                  AtomicReference<CompletableFuture<?>> active) {
        if (result.isCancelled()) {
            return;
        }

        CompletableFuture<Void> originFuture;
        try {
            originFuture = originClient.execute(copyWithBody(request, body));
        } catch (Throwable originFailure) {
            fail(result, request.responseHandler(), originFailure);
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

    @Override
    public String clientName() {
        return "photon";
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

    private static CompletableFuture<byte[]> collect(Publisher<ByteBuffer> publisher) {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        AtomicReference<Subscription> subscription = new AtomicReference<>();
        publisher.subscribe(new Subscriber<ByteBuffer>() {
            private final ByteArrayOutputStream body = new ByteArrayOutputStream();

            @Override
            public void onSubscribe(Subscription value) {
                if (!subscription.compareAndSet(null, value)) {
                    value.cancel();
                    return;
                }
                value.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer bytes) {
                byte[] chunk = new byte[bytes.remaining()];
                bytes.get(chunk);
                body.write(chunk, 0, chunk.length);
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
                                "Reactive streams demand must be positive"));
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
