package software.amazon.photon.exception;

/**
 * The Photon connection was lost while requests were in flight.
 */
public class PhotonConnectionLostError extends PhotonError {

    public PhotonConnectionLostError(String message) {
        super(message);
    }

    public PhotonConnectionLostError(String message, Throwable cause) {
        super(message, cause);
    }
}
