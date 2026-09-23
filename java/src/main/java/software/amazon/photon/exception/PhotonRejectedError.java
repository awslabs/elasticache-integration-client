package software.amazon.photon.exception;

/**
 * Photon explicitly rejected a request with a RESP error reply.
 */
public class PhotonRejectedError extends PhotonError {

    private final String code;

    public PhotonRejectedError(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
