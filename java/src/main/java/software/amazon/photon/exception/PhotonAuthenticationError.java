package software.amazon.photon.exception;

/**
 * The Photon endpoint rejected credentials during AUTH or returned an invalid AUTH reply.
 *
 * <p>A bring-up rejection invalidates that connection attempt. A background re-AUTH rejection
 * leaves the established connection open with its last valid authentication state.
 */
public class PhotonAuthenticationError extends PhotonConnectionError {

    public PhotonAuthenticationError(String message) {
        super(message);
    }
}
