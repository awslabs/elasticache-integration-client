package software.amazon.photon.resp;

/**
 * Raised when a buffer contains only part of a RESP frame.
 */
public class IncompleteFrameError extends Exception {

    public IncompleteFrameError(String message) {
        super(message);
    }
}
