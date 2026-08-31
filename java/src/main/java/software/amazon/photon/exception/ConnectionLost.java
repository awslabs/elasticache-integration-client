package software.amazon.photon.exception;

/**
 * Socket died mid-flight.
 *
 * @deprecated use {@link PhotonConnectionLostError}
 */
@Deprecated
public class ConnectionLost extends PhotonError {

    public ConnectionLost(String message) {
        super(message);
    }

    public ConnectionLost(String message, Throwable cause) {
        super(message, cause);
    }
}
