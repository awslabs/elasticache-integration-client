package software.amazon.photon.session;

import software.amazon.photon.exception.PhotonError;
import software.amazon.photon.model.PhotonRequest;

import java.io.IOException;

/**
 * Shared sync/async policy for replaying a failed Photon request at the origin.
 */
final class PhotonFallbackPolicy {

    private PhotonFallbackPolicy() {
    }

    static boolean shouldFallback(PhotonSession session, PhotonRequest request) {
        return session.isOpen() && Idempotency.isIdempotent(request.getMetadata());
    }

    static IOException noFallback(PhotonError failure) {
        return new IOException(
                "Photon failed for an operation that cannot be replayed safely",
                failure);
    }
}
