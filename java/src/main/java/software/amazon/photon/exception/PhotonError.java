package software.amazon.photon.exception;

/**
 * Base exception for all Photon errors.
 */
public class PhotonError extends RuntimeException {

    public PhotonError(String message) {
        super(message);
    }

    public PhotonError(String message, Throwable cause) {
        super(message, cause);
    }
}
