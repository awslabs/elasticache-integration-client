package software.amazon.photon.session;

import software.amazon.photon.exception.PhotonError;
import software.amazon.photon.exception.PhotonCacheUnavailable;
import software.amazon.photon.exception.PhotonConnectionError;
import software.amazon.photon.exception.PhotonConnectionLost;
import software.amazon.photon.exception.PhotonRejectedError;
import software.amazon.photon.exception.PhotonTimeoutError;
import software.amazon.photon.model.PhotonRequest;

/**
 * Shared sync/async policy for handling a failed Photon request.
 */
final class PhotonFallbackPolicy {

    private static final String ORIGIN_ERROR = "ORIGIN_ERROR";

    private PhotonFallbackPolicy() {
    }

    static boolean shouldFallback(PhotonSession session, PhotonRequest request,
                                  PhotonError failure) {
        return session.isOpen()
                && !session.isConnectionHealthy()
                && !(failure instanceof PhotonCacheUnavailable)
                && !(failure instanceof PhotonRejectedError)
                && isTransportFailure(failure)
                && Idempotency.isIdempotent(request.getMetadata());
    }

    static boolean shouldRetry(PhotonSession session, PhotonRequest request,
                               PhotonError failure) {
        if (failure instanceof PhotonCacheUnavailable) {
            return session.isOpen();
        }
        if (!session.isConnectionHealthy()
                || !Idempotency.isIdempotent(request.getMetadata())) {
            return false;
        }
        if (failure instanceof PhotonRejectedError) {
            return ORIGIN_ERROR.equals(((PhotonRejectedError) failure).getCode());
        }
        return isTransportFailure(failure);
    }

    private static boolean isTransportFailure(PhotonError failure) {
        return failure instanceof PhotonConnectionLost
                || failure instanceof PhotonTimeoutError
                || failure instanceof PhotonConnectionError;
    }
}
