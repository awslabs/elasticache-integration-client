package software.amazon.photon.session;

import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.photon.model.PhotonRequest;
import software.amazon.photon.model.PhotonResponse;

import java.io.InputStream;
import java.util.Optional;

/**
 * AWS SDK v2 ExecutionInterceptor that redirects requests to the Photon cache.
 *
 * <p>This interceptor hooks into the SDK's request pipeline after the request
 * is fully serialized and SigV4-signed. It converts the SDK request to a
 * Photon request, calls the session, and returns a synthetic SDK response
 * that short-circuits the real network call.
 *
 * <p>The interceptor operates at the {@code modifyHttpResponse} stage, which
 * allows it to return a synthetic response. The SDK's retry and deserialization
 * pipeline then processes this response normally.
 *
 * <p>Usage:
 * <pre>{@code
 * PhotonInterceptor interceptor = new PhotonInterceptor(session);
 * DynamoDbClient client = DynamoDbClient.builder()
 *     .overrideConfiguration(c -> c.addExecutionInterceptor(interceptor))
 *     .build();
 * }</pre>
 *
 * <p>Or use {@link PhotonHandle} for managed attachment/detachment.
 */
public class PhotonInterceptor implements ExecutionInterceptor {

    private final PhotonSession session;

    /**
     * Create an interceptor that forwards requests to the given session.
     *
     * @param session the Photon session (must be opened before use)
     */
    public PhotonInterceptor(PhotonSession session) {
        this.session = session;
    }

    /**
     * Intercept the request before it's sent to AWS.
     *
     * <p>This method is called after signing. We convert the SDK request to
     * a Photon request, send it to the cache, and store the response for
     * {@link #modifyHttpResponse} to return.
     */
    @Override
    public SdkHttpFullRequest modifyHttpRequest(Context.ModifyHttpRequest context,
                                                  ExecutionAttributes executionAttributes) {
        // Convert SDK request to Photon request
        SdkHttpFullRequest sdkRequest = (SdkHttpFullRequest) context.httpRequest();
        PhotonRequest photonRequest = SdkAdapters.toPhotonRequest(sdkRequest, executionAttributes);

        // Send to Photon and get response
        PhotonResponse photonResponse = session.request(photonRequest);

        // Store response in execution attributes for modifyHttpResponse
        executionAttributes.putAttribute(PHOTON_RESPONSE, photonResponse);

        // Return the original request (we'll provide the response in modifyHttpResponse)
        return sdkRequest;
    }

    /**
     * Modify the HTTP response to return our cached response.
     *
     * <p>The SDK calls this after attempting the HTTP request. Since we stored
     * our Photon response in the execution attributes, we return it here to
     * short-circuit the real response.
     */
    @Override
    public SdkHttpResponse modifyHttpResponse(Context.ModifyHttpResponse context,
                                               ExecutionAttributes executionAttributes) {
        PhotonResponse photonResponse = executionAttributes.getAttribute(PHOTON_RESPONSE);
        if (photonResponse != null) {
            return SdkAdapters.toSdkResponse(photonResponse);
        }
        // Shouldn't happen if modifyHttpRequest ran, but fall back to original
        return context.httpResponse();
    }

    /**
     * Modify the response content (body) to return our cached response body.
     */
    @Override
    public Optional<InputStream> modifyHttpResponseContent(Context.ModifyHttpResponse context,
                                                            ExecutionAttributes executionAttributes) {
        PhotonResponse photonResponse = executionAttributes.getAttribute(PHOTON_RESPONSE);
        if (photonResponse != null && photonResponse.getBody() != null) {
            return Optional.of(new java.io.ByteArrayInputStream(photonResponse.getBody()));
        }
        return context.responseBody();
    }

    /**
     * Execution attribute key for storing the Photon response between interceptor methods.
     */
    private static final software.amazon.awssdk.core.interceptor.ExecutionAttribute<PhotonResponse> PHOTON_RESPONSE =
            new software.amazon.awssdk.core.interceptor.ExecutionAttribute<>("PhotonResponse");
}
