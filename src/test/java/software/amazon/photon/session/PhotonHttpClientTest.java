package software.amazon.photon.session;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpFullResponse;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.photon.connection.PhotonConnection;
import software.amazon.photon.exception.ConnectionLost;
import software.amazon.photon.model.PhotonRequest;
import software.amazon.photon.model.PhotonResponse;
import software.amazon.photon.model.RequestMetadata;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhotonHttpClientTest {

    @Test
    void photonSuccessDoesNotContactOrigin() throws Exception {
        StubSession session = new StubSession();
        session.response = new PhotonResponse(200, Collections.emptyMap(), "photon".getBytes());
        RecordingHttpClient origin = new RecordingHttpClient();
        PhotonHttpClient client = newClient(session, origin, false);

        HttpExecuteResponse response = client.prepareRequest(signedRequest()).call();

        assertEquals(200, response.httpResponse().statusCode());
        assertEquals(0, origin.prepareCount.get());
    }

    @Test
    void photonFailureDelegatesExactSignedRequest() throws Exception {
        StubSession session = new StubSession();
        session.failure = new ConnectionLost("cache unavailable");
        RecordingHttpClient origin = new RecordingHttpClient();
        PhotonHttpClient client = newClient(session, origin, false);
        HttpExecuteRequest signedRequest = signedRequest();

        HttpExecuteResponse response = client.prepareRequest(signedRequest).call();

        assertEquals(201, response.httpResponse().statusCode());
        assertSame(signedRequest, origin.request.get());
        assertEquals("signed-value",
                origin.request.get().httpRequest().firstMatchingHeader("Authorization").orElse(null));
    }

    @Test
    void unexpectedRuntimeExceptionPropagates() {
        StubSession session = new StubSession();
        session.failure = new IllegalStateException("bug");
        RecordingHttpClient origin = new RecordingHttpClient();
        PhotonHttpClient client = newClient(session, origin, false);

        assertThrows(IllegalStateException.class,
                () -> client.prepareRequest(signedRequest()).call());
        assertEquals(0, origin.prepareCount.get());
    }

    @Test
    void abortReachesActiveOriginRequest() throws Exception {
        StubSession session = new StubSession();
        session.failure = new ConnectionLost("cache unavailable");
        RecordingHttpClient origin = new RecordingHttpClient();
        PhotonHttpClient client = newClient(session, origin, false);
        ExecutableHttpRequest request = client.prepareRequest(signedRequest());

        request.abort();
        request.call();

        assertTrue(origin.aborted.get());
    }

    @Test
    void closeOnlyClosesOwnedOriginOnce() {
        StubSession session = new StubSession();
        RecordingHttpClient callerManaged = new RecordingHttpClient();
        PhotonHttpClient callerManagedClient = newClient(session, callerManaged, false);
        callerManagedClient.close();
        callerManagedClient.close();
        assertEquals(0, callerManaged.closeCount.get());

        RecordingHttpClient owned = new RecordingHttpClient();
        PhotonHttpClient ownedClient = newClient(session, owned, true);
        ownedClient.close();
        ownedClient.close();
        assertEquals(1, owned.closeCount.get());
    }

    private static PhotonHttpClient newClient(PhotonSession session, SdkHttpClient origin,
                                               boolean ownsOrigin) {
        PhotonRequestContext context = new PhotonRequestContext();
        context.set(new PhotonRequest(
                "POST",
                "https://dynamodb.us-east-1.amazonaws.com/",
                Collections.emptyMap(),
                new byte[0],
                new RequestMetadata("dynamodb", "ListTables")));
        return new PhotonHttpClient(session, context, origin, ownsOrigin);
    }

    private static HttpExecuteRequest signedRequest() {
        SdkHttpFullRequest request = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.POST)
                .uri(URI.create("https://dynamodb.us-east-1.amazonaws.com/"))
                .putHeader("Authorization", "signed-value")
                .build();
        return HttpExecuteRequest.builder().request(request).build();
    }

    private static final class StubSession extends PhotonSession {
        private PhotonResponse response;
        private RuntimeException failure;

        private StubSession() {
            super(new PhotonConnection("unused", 1, null), "user", "token");
        }

        @Override
        public PhotonResponse request(PhotonRequest request) {
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

        @Override
        public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
            prepareCount.incrementAndGet();
            this.request.set(request);
            return new ExecutableHttpRequest() {
                @Override
                public HttpExecuteResponse call() {
                    return HttpExecuteResponse.builder()
                            .response(SdkHttpFullResponse.builder().statusCode(201).build())
                            .responseBody(AbortableInputStream.create(
                                    new ByteArrayInputStream(new byte[0])))
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
