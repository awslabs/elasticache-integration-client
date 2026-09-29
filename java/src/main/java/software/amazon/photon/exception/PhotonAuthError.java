package software.amazon.photon.exception;

/**
 * A background re-authentication was rejected or returned an invalid reply.
 */
public class PhotonAuthError extends PhotonError {

    public PhotonAuthError(String message) {
        super(message);
    }
}
