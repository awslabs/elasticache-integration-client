import software.amazon.elasticache.auth.ElastiCacheIamAuthTokenProvider;
import software.amazon.photon.session.PhotonClient;

public final class PhotonConsumerSmoke {
    private PhotonConsumerSmoke() {}

    public static void main(String[] args) {
        if (!"software.amazon.photon.session".equals(PhotonClient.class.getPackage().getName())) {
            throw new AssertionError("PhotonClient was not loaded from the published artifact");
        }
        if (!"software.amazon.elasticache.auth".equals(
                ElastiCacheIamAuthTokenProvider.class.getPackage().getName())) {
            throw new AssertionError("The bundled IAM toolkit was not loadable");
        }
    }
}
