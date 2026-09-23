package software.amazon.photon.exception;

/**
 * Photon has no usable cache connection and its origin-fallback window ended.
 */
public final class PhotonCacheUnavailable extends PhotonConnectionError {
    public PhotonCacheUnavailable(String message) {
        super(message);
    }
}
