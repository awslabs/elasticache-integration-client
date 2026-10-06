package software.amazon.photon.exception;

/** The resolved config carried a predicate this client cannot evaluate. */
public class PhotonConfigUnevaluable extends PhotonError {

    public PhotonConfigUnevaluable(String message) {
        super(message);
    }

    public PhotonConfigUnevaluable(String message, Throwable cause) {
        super(message, cause);
    }
}
