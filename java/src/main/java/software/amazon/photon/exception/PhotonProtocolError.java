package software.amazon.photon.exception;

/** Malformed RESP frame - garbage data received. */
public class PhotonProtocolError extends PhotonError {

    public PhotonProtocolError(String message) {
        super(message);
    }

    public PhotonProtocolError(String message, Throwable cause) {
        super(message, cause);
    }
}
