package software.amazon.photon.exception;

/** Per-request timeout. */
public class PhotonTimeoutError extends PhotonError {

    public PhotonTimeoutError(String message) {
        super(message);
    }

    public PhotonTimeoutError(String message, Throwable cause) {
        super(message, cause);
    }
}
