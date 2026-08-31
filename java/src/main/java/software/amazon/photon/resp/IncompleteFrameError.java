package software.amazon.photon.resp;

/**
 * Raised when a buffer contains only part of a RESP frame.
 *
 * <p>This class extends the legacy {@link IncompleteFrame} type so existing
 * catch blocks remain compatible.
 */
public class IncompleteFrameError extends IncompleteFrame {

    public IncompleteFrameError(String message) {
        super(message);
    }
}
