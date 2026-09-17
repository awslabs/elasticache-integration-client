package software.amazon.photon.session;

import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpFullResponse;
import software.amazon.photon.exception.PhotonError;
import software.amazon.photon.connection.PhotonRequestCancellation;
import software.amazon.photon.model.PhotonRequest;
import software.amazon.photon.model.PhotonResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Synchronous SDK HTTP transport that attempts Photon before the origin service.
 *
 * <p>The unsigned request captured by {@link PhotonInterceptor} is sent to
 * Photon. Predicate declines and explicit Photon rejections delegate to the
 * origin. Indeterminate failures do so only for replay-safe operations while
 * the session remains open.
 */
public final class PhotonHttpClient implements SdkHttpClient {

    private static final String CLIENT_NAME = "java-photon";

    private final PhotonSession session;
    private final PhotonRequestContext requestContext;
    private final SdkHttpClient originClient;
    private final boolean ownsOriginClient;
    private final AtomicBoolean closed = new AtomicBoolean();

    PhotonHttpClient(PhotonSession session, PhotonRequestContext requestContext,
                     SdkHttpClient originClient, boolean ownsOriginClient) {
        this.session = session;
        this.requestContext = requestContext;
        this.originClient = originClient;
        this.ownsOriginClient = ownsOriginClient;
    }

    @Override
    public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
        PhotonRequest capturedRequest = requestContext.takeAttempt();
        return new ExecutableHttpRequest() {
            private final AtomicReference<ExecutableHttpRequest> activeOriginRequest = new AtomicReference<>();
            private final AtomicReference<PhotonRequestCancellation> activePhotonRequest = new AtomicReference<>();
            private final AtomicBoolean aborted = new AtomicBoolean();

            @Override
            public HttpExecuteResponse call() throws IOException {
                if (aborted.get()) {
                    throw new IOException("Request was aborted");
                }

                if (capturedRequest == null) {
                    throw new IllegalStateException("Photon request was not captured before HTTP execution");
                }

                PhotonRequestCancellation photonCancellation = new PhotonRequestCancellation();
                activePhotonRequest.set(photonCancellation);
                if (aborted.get()) {
                    photonCancellation.cancel();
                }

                PhotonResponse photonResponse;
                try {
                    photonResponse = session.request(capturedRequest, photonCancellation);
                } catch (PhotonRequestCancellation.CancelledException cancelled) {
                    throw new IOException("Request was aborted", cancelled);
                } catch (PhotonError photonFailure) {
                    if (aborted.get()) {
                        throw new IOException("Request was aborted", photonFailure);
                    }
                    if (!PhotonFallbackPolicy.shouldFallback(
                            session, capturedRequest, photonFailure)) {
                        throw PhotonFallbackPolicy.noFallback(photonFailure);
                    }
                    return callOrigin();
                } finally {
                    activePhotonRequest.compareAndSet(photonCancellation, null);
                }

                if (aborted.get()) {
                    throw new IOException("Request was aborted");
                }
                if (photonResponse == null) {
                    return callOrigin();
                }
                SdkHttpFullResponse sdkResponse = SdkAdapters.toSdkResponse(photonResponse);
                HttpExecuteResponse.Builder response = HttpExecuteResponse.builder()
                        .response(sdkResponse);
                byte[] body = photonResponse.getBody();
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
                PhotonRequestCancellation photonRequest = activePhotonRequest.get();
                if (photonRequest != null) {
                    photonRequest.cancel();
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
