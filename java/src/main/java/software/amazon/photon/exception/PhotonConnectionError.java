package software.amazon.photon.exception;

/** Connection bring-up failed. */
public class PhotonConnectionError extends PhotonError {

    public PhotonConnectionError(String message) {
        super(message);
    }

    public PhotonConnectionError(String message, Throwable cause) {
        super(message, cause);
    }
}
