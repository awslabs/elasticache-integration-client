package software.amazon.photon.session;

import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.photon.model.PhotonRequest;

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
 * <p>The interceptor hands the unsigned request to {@link PhotonHttpClient}, which
 * returns the Photon response without making an origin HTTP request.
 *
 * <p>Usage:
 * <pre>{@code
 * PhotonInterceptor interceptor = new PhotonInterceptor(session);
 * DynamoDbClient client = DynamoDbClient.builder()
 *     .overrideConfiguration(c -> c.addExecutionInterceptor(interceptor))
 *     .httpClient(interceptor.getHttpClient())
 *     .build();
 * }</pre>
 *
 * <p>Or use {@link PhotonHandle} for managed attachment/detachment.
 */
public class PhotonInterceptor implements ExecutionInterceptor {

    private final PhotonRequestContext requestContext;
    private final PhotonHttpClient httpClient;

    /**
     * Create an interceptor that forwards requests to the given session.
     *
     * @param session the Photon session (must be opened before use)
     */
    public PhotonInterceptor(PhotonSession session) {
        this.requestContext = new PhotonRequestContext();
        this.httpClient = new PhotonHttpClient(session, requestContext);
    }

    /**
     * Get the HTTP client that short-circuits origin requests through Photon.
     */
    public PhotonHttpClient getHttpClient() {
        return httpClient;
    }

    /**
     * Intercept the request before it's sent to AWS.
     *
     * <p>This method runs BEFORE signing (part of the SDK's interceptor lifecycle).
     * We convert the SDK request to a Photon request and hand it to the
     * synchronous Photon HTTP transport.
     *
     * <p>The request at this stage does not include AWS signatures, but contains
     * the full HTTP representation (method, URL, headers, body) that Photon needs.
     */
    @Override
    public SdkHttpFullRequest modifyHttpRequest(Context.ModifyHttpRequest context,
                                                  ExecutionAttributes executionAttributes) {
        SdkHttpFullRequest sdkRequest = (SdkHttpFullRequest) context.httpRequest();
        PhotonRequest photonRequest = SdkAdapters.toPhotonRequest(sdkRequest, executionAttributes);
        requestContext.set(photonRequest);
        return sdkRequest;
    }

    @Override
    public void afterExecution(Context.AfterExecution context,
                               ExecutionAttributes executionAttributes) {
        requestContext.clear();
    }

    @Override
    public void onExecutionFailure(Context.FailedExecution context,
                                   ExecutionAttributes executionAttributes) {
        requestContext.clear();
    }
}
