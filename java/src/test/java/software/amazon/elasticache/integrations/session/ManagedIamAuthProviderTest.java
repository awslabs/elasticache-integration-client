package software.amazon.elasticache.integrations.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkServiceClientConfiguration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.endpoints.EndpointProvider;
import software.amazon.awssdk.http.auth.spi.scheme.AuthScheme;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;
import software.amazon.awssdk.identity.spi.AwsSessionCredentialsIdentity;
import software.amazon.awssdk.identity.spi.IdentityProvider;
import software.amazon.awssdk.identity.spi.ResolveIdentityRequest;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbServiceClientConfiguration;
import software.amazon.elasticache.integrations.auth.AuthCredentials;
import software.amazon.elasticache.integrations.auth.IamAuthTokenProvider;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsError;

class ManagedIamAuthProviderTest {
    private static final String ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE";
    private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    private static final String SESSION_TOKEN = "sessiontokenvalue";

    @Test
    void bindToAdaptsSessionIdentityProvider() {
        IdentityProvider<AwsCredentialsIdentity> identityProvider =
                identityProvider(
                        AwsSessionCredentialsIdentity.create(ACCESS_KEY, SECRET_KEY, SESSION_TOKEN));
        ManagedIamAuthProvider provider = deferredProvider();

        try {
            provider.bindTo(awsConfiguration(identityProvider, Region.US_EAST_1));

            AuthCredentials credentials = provider.get();
            assertTrue(credentials.getToken().contains(ACCESS_KEY));
            assertTrue(credentials.getToken().contains("X-Amz-Security-Token=" + SESSION_TOKEN));
        } finally {
            provider.close();
        }
    }

    @Test
    void bindToUnwrapsIdentityResolutionFailure() {
        IllegalStateException resolutionFailure = new IllegalStateException("identity failed");
        IdentityProvider<AwsCredentialsIdentity> identityProvider =
                new IdentityProvider<AwsCredentialsIdentity>() {
                    @Override
                    public Class<AwsCredentialsIdentity> identityType() {
                        return AwsCredentialsIdentity.class;
                    }

                    @Override
                    public CompletableFuture<? extends AwsCredentialsIdentity> resolveIdentity(
                            ResolveIdentityRequest request) {
                        CompletableFuture<AwsCredentialsIdentity> failure = new CompletableFuture<>();
                        failure.completeExceptionally(resolutionFailure);
                        return failure;
                    }
                };
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                ManagedIamAuthProvider.asCredentialsProvider(identityProvider)
                                        .resolveCredentials());

        assertSame(resolutionFailure, error);
    }

    @Test
    void deferredProviderRejectsUseBeforeBindAndSecondBind() {
        ManagedIamAuthProvider provider = deferredProvider();

        ElastiCacheIntegrationsError getError =
                assertThrows(ElastiCacheIntegrationsError.class, provider::get);
        assertEquals("IAM auth provider is not initialized", getError.getMessage());

        ElastiCacheIntegrationsError refreshError =
                assertThrows(ElastiCacheIntegrationsError.class, provider::refresh);
        assertEquals("IAM auth provider is not initialized", refreshError.getMessage());

        provider.bindTo(
                awsConfiguration(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)),
                        Region.US_EAST_1));
        ElastiCacheIntegrationsError secondBindError =
                assertThrows(
                        ElastiCacheIntegrationsError.class,
                        () ->
                                provider.bindTo(
                                        awsConfiguration(
                                                StaticCredentialsProvider.create(
                                                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)),
                                                Region.US_EAST_1)));
        assertTrue(secondBindError.getMessage().contains("already initialized"));

        provider.close();
        assertThrows(ElastiCacheIntegrationsError.class, provider::get);
    }

    @Test
    void bindToRejectsNonAwsServiceConfiguration() {
        ManagedIamAuthProvider provider = deferredProvider();

        ElastiCacheIntegrationsError error =
                assertThrows(
                        ElastiCacheIntegrationsError.class,
                        () -> provider.bindTo(new NonAwsServiceConfiguration()));

        assertTrue(error.getMessage().contains("AWS SDK client configuration is required"));
        provider.close();
    }

    private static ManagedIamAuthProvider deferredProvider() {
        return new ManagedIamAuthProvider(
                IamAuthTokenProvider.builder()
                        .cacheName("my-cache")
                        .region(Region.US_EAST_1)
                        .credentialsProvider(
                                StaticCredentialsProvider.create(
                                        AwsBasicCredentials.create("AKIAUNUSEDPROVIDER", "unused-provider-secret")))
                        .build(),
                true);
    }

    private static DynamoDbServiceClientConfiguration awsConfiguration(
            IdentityProvider<? extends AwsCredentialsIdentity> identityProvider, Region region) {
        return DynamoDbServiceClientConfiguration.builder()
                .region(region)
                .credentialsProvider(identityProvider)
                .build();
    }

    private static IdentityProvider<AwsCredentialsIdentity> identityProvider(
            AwsCredentialsIdentity identity) {
        return new IdentityProvider<AwsCredentialsIdentity>() {
            @Override
            public Class<AwsCredentialsIdentity> identityType() {
                return AwsCredentialsIdentity.class;
            }

            @Override
            public CompletableFuture<? extends AwsCredentialsIdentity> resolveIdentity(
                    ResolveIdentityRequest request) {
                return CompletableFuture.completedFuture(identity);
            }
        };
    }

    private static final class NonAwsServiceConfiguration extends SdkServiceClientConfiguration {
        private NonAwsServiceConfiguration() {
            super(new NonAwsServiceConfigurationBuilder());
        }
    }

    private static final class NonAwsServiceConfigurationBuilder
            implements SdkServiceClientConfiguration.Builder {
        @Override
        public ClientOverrideConfiguration overrideConfiguration() {
            return null;
        }

        @Override
        public URI endpointOverride() {
            return null;
        }

        @Override
        public EndpointProvider endpointProvider() {
            return null;
        }

        @Override
        public Map<String, AuthScheme<?>> authSchemes() {
            return Collections.emptyMap();
        }

        @Override
        public SdkServiceClientConfiguration build() {
            return new NonAwsServiceConfiguration();
        }
    }
}
