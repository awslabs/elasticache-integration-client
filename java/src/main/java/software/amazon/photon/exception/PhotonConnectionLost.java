package software.amazon.photon.exception;

/** The Photon connection was lost while requests were in flight. */
public class PhotonConnectionLost extends PhotonError {

    public PhotonConnectionLost(String message) {
        super(message);
    }

    public PhotonConnectionLost(String message, Throwable cause) {
        super(message, cause);
    }
}
