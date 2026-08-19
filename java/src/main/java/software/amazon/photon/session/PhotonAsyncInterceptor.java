package software.amazon.photon.session;

import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.photon.model.PhotonRequest;

/**
 * AWS SDK v2 interceptor used with {@link PhotonAsyncHttpClient}.
 *
 * <p>The unsigned request and SDK operation metadata are retained in the
 * per-execution SDK attributes. Each HTTP attempt is handed to the async
 * transport immediately before transmission.
 */
public final class PhotonAsyncInterceptor implements ExecutionInterceptor {

    private final PhotonRequestContext requestContext;
    private final PhotonAsyncHttpClient httpClient;

    PhotonAsyncInterceptor(PhotonSession session,
                           software.amazon.awssdk.http.async.SdkAsyncHttpClient originClient,
                           boolean ownsOriginClient,
                           java.util.concurrent.Executor executor,
                           java.util.concurrent.ExecutorService ownedExecutor) {
        this.requestContext = new PhotonRequestContext();
        this.httpClient = new PhotonAsyncHttpClient(
                session, requestContext, originClient, ownsOriginClient, executor, ownedExecutor);
    }

    /**
     * Get the async HTTP transport that attempts Photon before the origin service.
     */
    public PhotonAsyncHttpClient getHttpClient() {
        return httpClient;
    }

    @Override
    public SdkHttpFullRequest modifyHttpRequest(Context.ModifyHttpRequest context,
                                                ExecutionAttributes executionAttributes) {
        SdkHttpFullRequest sdkRequest = (SdkHttpFullRequest) context.httpRequest();
        PhotonRequest photonRequest =
                SdkAdapters.toPhotonRequest(sdkRequest, executionAttributes, new byte[0]);
        requestContext.capture(photonRequest, executionAttributes);
        return sdkRequest;
    }

    @Override
    public void beforeTransmission(Context.BeforeTransmission context,
                                   ExecutionAttributes executionAttributes) {
        requestContext.prepareAttempt(executionAttributes);
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
