package software.amazon.photon.session;

import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpFullResponse;
import software.amazon.photon.exception.PhotonError;
import software.amazon.photon.model.PhotonRequest;
import software.amazon.photon.model.PhotonResponse;

import java.io.ByteArrayInputStream;

/**
 * Synchronous SDK HTTP transport that serves responses from Photon.
 *
 * <p>No origin HTTP request is performed. The unsigned request captured by
 * {@link PhotonInterceptor} is forwarded to the Photon session instead.
 */
public final class PhotonHttpClient implements SdkHttpClient {

    private final PhotonSession session;
    private final PhotonRequestContext requestContext;

    PhotonHttpClient(PhotonSession session, PhotonRequestContext requestContext) {
        this.session = session;
        this.requestContext = requestContext;
    }

    @Override
    public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
        return new ExecutableHttpRequest() {
            @Override
            public HttpExecuteResponse call() {
                PhotonRequest photonRequest = requestContext.get();
                if (photonRequest == null) {
                    throw new PhotonError("Photon request was not captured before HTTP execution");
                }

                PhotonResponse photonResponse = session.request(photonRequest);
                SdkHttpFullResponse sdkResponse = SdkAdapters.toSdkResponse(photonResponse);
                HttpExecuteResponse.Builder response = HttpExecuteResponse.builder()
                        .response(sdkResponse);
                byte[] body = photonResponse.getBody();
                if (body != null && body.length > 0) {
                    response.responseBody(AbortableInputStream.create(new ByteArrayInputStream(body)));
                }
                return response.build();
            }

            @Override
            public void abort() {
                // The SDK may retry after aborting an attempt. Execution completion
                // or failure hooks clear the request after all attempts finish.
            }
        };
    }

    @Override
    public String clientName() {
        return "photon";
    }

    @Override
    public void close() {
        requestContext.clear();
    }
}
