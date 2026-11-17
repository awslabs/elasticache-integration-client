// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.core.SdkClient;
import software.amazon.awssdk.core.client.builder.SdkAsyncClientBuilder;
import software.amazon.awssdk.core.client.builder.SdkSyncClientBuilder;
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

    // Never called: install and installAsync connect to the cache. Compiling these calls checks
    // that the published artifact exposes the supported entry points, and the SDK builder types
    // they take, to a consumer.
    static <B extends AwsClientBuilder<B, C> & SdkSyncClientBuilder<B, C>, C extends SdkClient>
            C install(B sdkClientBuilder) {
        return ElastiCacheIntegrationsClient.builder()
                .endpoint("smoke-test.invalid:6379")
                .cacheName("smoke-test")
                .install(sdkClientBuilder);
    }

    static <B extends AwsClientBuilder<B, C> & SdkAsyncClientBuilder<B, C>, C extends SdkClient>
            C installAsync(B sdkClientBuilder) {
        return ElastiCacheIntegrationsClient.builder()
                .endpoint("smoke-test.invalid:6379")
                .cacheName("smoke-test")
                .installAsync(sdkClientBuilder);
    }
}
