package software.amazon.photon.exception;

/**
 * Photon explicitly rejected a request with a RESP error reply.
 */
public class PhotonRejectedError extends PhotonError {

    public PhotonRejectedError(String message) {
        super(message);
    }
}
