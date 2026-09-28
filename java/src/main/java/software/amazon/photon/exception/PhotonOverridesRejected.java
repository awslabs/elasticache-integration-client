package software.amazon.photon.exception;

/**
 * The proxy rejected the {@code photon.config} overrides ({@code CONFIG_INVALID}).
 */
public class PhotonOverridesRejected extends PhotonError {

    public PhotonOverridesRejected(String message) {
        super(message);
    }
}
