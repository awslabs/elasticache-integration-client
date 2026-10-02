package software.amazon.photon.session;

import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.photon.model.PhotonRequest;

/** Per-client, per-attempt handoff between an SDK interceptor and HTTP transport. */
final class PhotonRequestContext {

    private static final ExecutionAttribute<PhotonRequest> PHOTON_REQUEST =
            new ExecutionAttribute<>("PhotonRequest");

    private final ThreadLocal<PhotonRequest> currentAttempt = new ThreadLocal<>();

    void capture(PhotonRequest request, ExecutionAttributes executionAttributes) {
        executionAttributes.putAttribute(PHOTON_REQUEST, request);
    }

    void prepareAttempt(ExecutionAttributes executionAttributes) {
        PhotonRequest request = executionAttributes.getAttribute(PHOTON_REQUEST);
        if (request == null) {
            currentAttempt.remove();
        } else {
            currentAttempt.set(request);
        }
    }

    PhotonRequest takeAttempt() {
        PhotonRequest request = currentAttempt.get();
        currentAttempt.remove();
        return request;
    }

    void clear() {
        currentAttempt.remove();
    }
}
