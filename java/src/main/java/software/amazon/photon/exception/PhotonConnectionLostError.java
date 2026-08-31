package software.amazon.photon.exception;

/**
 * The Photon connection was lost while requests were in flight.
 *
 * <p>This class extends the legacy {@link ConnectionLost} type so existing
 * catch blocks continue to handle connection-loss failures.
 */
public class PhotonConnectionLostError extends ConnectionLost {

    public PhotonConnectionLostError(String message) {
        super(message);
    }

    public PhotonConnectionLostError(String message, Throwable cause) {
        super(message, cause);
    }
}
