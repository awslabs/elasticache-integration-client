import software.amazon.elasticache.auth.ElastiCacheIamAuthTokenProvider;
import software.amazon.elasticache.integrations.session.ElastiCacheIntegrationsClient;

public final class ElastiCacheIntegrationsConsumerSmoke {
    private ElastiCacheIntegrationsConsumerSmoke() {}

    public static void main(String[] args) {
        if (!"software.amazon.elasticache.integrations.session".equals(ElastiCacheIntegrationsClient.class.getPackage().getName())) {
            throw new AssertionError("ElastiCacheIntegrationsClient was not loaded from the published artifact");
        }
        if (!"software.amazon.elasticache.auth".equals(
                ElastiCacheIamAuthTokenProvider.class.getPackage().getName())) {
            throw new AssertionError("The IAM toolkit dependency was not loadable");
        }
    }
}
