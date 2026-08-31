package software.amazon.photon.exception;

/**
 * Raised when a Photon request is rejected while recovery is circuit-broken.
 */
public class PhotonCircuitOpenError extends PhotonConnectionError {
    public PhotonCircuitOpenError(String message) {
        super(message);
    }
}
