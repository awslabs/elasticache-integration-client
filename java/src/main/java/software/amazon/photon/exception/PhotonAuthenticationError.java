package software.amazon.photon.exception;

/** The Photon endpoint rejected credentials during the {@code HELLO 3 AUTH} handshake. */
public class PhotonAuthenticationError extends PhotonConnectionError {

    public PhotonAuthenticationError(String message) {
        super(message);
    }
}
