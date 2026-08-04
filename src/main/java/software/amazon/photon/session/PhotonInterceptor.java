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
 * <p>This interceptor hooks into the SDK's request pipeline at {@code modifyHttpRequest},
 * which runs BEFORE SigV4 signing. The request is serialized to HTTP form but does not
 * yet contain the Authorization header or signature.
 *
 * <p><b>Note on signing timing:</b> Unlike the Python client which hooks at botocore's
 * {@code before-send} (after signing), the AWS SDK for Java v2 does not provide an
 * interceptor hook that runs after signing but before transmission. The only options
 * are {@code modifyHttpRequest} (before signing, can modify) or {@code beforeTransmission}
 * (after signing, read-only). Since we need to forward the request to Photon, we use
 * {@code modifyHttpRequest}. The request sent to Photon will not include AWS signatures,
 * but Photon does not require them for caching purposes.
 *
 * <p>The interceptor stores the Photon response and returns it via {@code modifyHttpResponse},
 * which allows it to return a synthetic response. The SDK's retry and deserialization
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
     * <p>This method runs BEFORE signing (part of the SDK's interceptor lifecycle).
     * We convert the SDK request to a Photon request and send it to the cache.
     * The response is stored for {@link #modifyHttpResponse} to return.
     *
     * <p>The request at this stage does not include AWS signatures, but contains
     * the full HTTP representation (method, URL, headers, body) that Photon needs.
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
