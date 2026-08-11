package software.amazon.photon.session;

import software.amazon.photon.model.PhotonRequest;

/**
 * Per-client handoff between the synchronous SDK interceptor and HTTP client.
 */
final class PhotonRequestContext {

    private final ThreadLocal<PhotonRequest> currentRequest = new ThreadLocal<>();

    void set(PhotonRequest request) {
        currentRequest.set(request);
    }

    PhotonRequest get() {
        return currentRequest.get();
    }

    void clear() {
        currentRequest.remove();
    }
}
