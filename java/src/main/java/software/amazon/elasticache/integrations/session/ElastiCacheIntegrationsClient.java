package software.amazon.elasticache.integrations.session;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.awscore.AwsServiceClientConfiguration;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.core.SdkClient;
import software.amazon.awssdk.core.SdkServiceClientConfiguration;
import software.amazon.awssdk.core.client.builder.SdkAsyncClientBuilder;
import software.amazon.awssdk.core.client.builder.SdkDefaultClientBuilder;
import software.amazon.awssdk.core.client.builder.SdkSyncClientBuilder;
import software.amazon.awssdk.core.client.config.ClientOption;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.client.config.SdkClientConfiguration;
import software.amazon.awssdk.core.client.config.SdkClientOption;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;
import software.amazon.awssdk.identity.spi.AwsSessionCredentialsIdentity;
import software.amazon.awssdk.identity.spi.IdentityProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.utils.AttributeMap;
import software.amazon.elasticache.integrations.auth.IamAuthTokenProvider;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsConfigError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsError;

/**
 * Installs ElastiCache Integrations into an application's AWS SDK client builder.
 *
 * <p>The application builds its SDK client as it always does, with its own region, credentials,
 * override configuration and everything else, and hands the builder to {@link Builder#install} or
 * {@link Builder#installAsync}. The returned client has the same API and serves eligible requests
 * from the cache. The cache is authenticated with the region and credentials the client resolved.
 *
 * <pre>{@code
 * DynamoDbClient ddb = ElastiCacheIntegrationsClient.builder()
 *     .endpoint("my-cache:6379")
 *     .cacheName("my-cache")
 *     .install(DynamoDbClient.builder().region(Region.US_EAST_1));
 *
 * ddb.getItem(...);  // unchanged
 * ddb.close();       // disconnects the cache, then closes the client as usual
 * }</pre>
 */
public final class ElastiCacheIntegrationsClient {

    private static final String NO_REGION =
            "The AWS SDK client has no region, so the cache's IAM token cannot be signed. "
                    + "Create the client with a region.";
    private static final String NO_CREDENTIALS =
            "This AWS SDK client carries no credentials, so the cache cannot be authenticated. "
                    + "A client built with AnonymousCredentialsProvider is not supported.";
    private static final String UNRESOLVED_CREDENTIALS =
            "The AWS SDK client's credentials provider could not resolve credentials, so the cache "
                    + "cannot be authenticated: ";

    private ElastiCacheIntegrationsClient() {
        // Factory class
    }

    /**
     * Create a new builder for installing the cache into an SDK client builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for the cache connection options and the install step. */
    public static final class Builder {
        private String endpoint;
        private String cacheName;
        private String userId;
        private boolean verify = true;
        private Path caCerts;
        private ElastiCacheIntegrationsClientConfiguration clientConfiguration =
                ElastiCacheIntegrationsClientConfiguration.defaults();
        private SdkHttpClient originHttpClient;
        private SdkAsyncHttpClient originAsyncHttpClient;
        private Executor asyncExecutor;
        private int asyncMaxBufferedRequestBytes =
                ElastiCacheIntegrationsAsyncHandle.DEFAULT_MAX_BUFFERED_REQUEST_BYTES;
        private int asyncMaxConcurrentBodyCollectors =
                ElastiCacheIntegrationsAsyncHandle.DEFAULT_MAX_CONCURRENT_BODY_COLLECTORS;
        private Consumer<ElastiCacheIntegrationsHandle> handleObserver;
        private Consumer<ElastiCacheIntegrationsAsyncHandle> asyncHandleObserver;

        private Builder() {}

        /**
         * Set the cache endpoint as host:port (required), for example {@code "cache.example.com:6379"}.
         *
         * <p>Supports IPv6 addresses in bracket notation, for example {@code "[::1]:6379"}.
         *
         * @param endpoint the cache endpoint as {@code host:port}
         * @return this builder
         */
        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        /** Set the ElastiCache Serverless cache name (required). */
        public Builder cacheName(String cacheName) {
            this.cacheName = cacheName;
            return this;
        }

        /** Set the ElastiCache IAM user id (default: {@code default.iam-user}). */
        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        /**
         * Set whether to verify the cache's TLS certificate and host name (default: true). False is for
         * self-signed test rigs only.
         */
        public Builder verify(boolean verify) {
            this.verify = verify;
            return this;
        }

        /**
         * Set a PEM CA bundle file to verify the cache's certificate against, instead of the system
         * trust store (default: the system trust store). The host name is still checked.
         */
        public Builder caCerts(Path caCerts) {
            this.caCerts = caCerts;
            return this;
        }

        /** Set the cache behavior options. Null means all defaults, which is also the default. */
        public Builder clientConfiguration(
                ElastiCacheIntegrationsClientConfiguration clientConfiguration) {
            this.clientConfiguration =
                    clientConfiguration == null
                            ? ElastiCacheIntegrationsClientConfiguration.defaults()
                            : clientConfiguration;
            return this;
        }

        /**
         * Set the HTTP client that sends requests to the origin service when the cache does not serve
         * them.
         *
         * <p>Install replaces the transport of the SDK builder, and the SDK has no getter to read back
         * one the application already set, so pass that client here. Install rejects an SDK builder
         * that already has an {@code httpClient} or {@code httpClientBuilder}. The SDK client does not
         * close this client. When unset, install creates the SDK Apache HTTP client and closes it with
         * the SDK client.
         *
         * @param originHttpClient the HTTP client for origin requests
         * @return this builder
         */
        public Builder originHttpClient(SdkHttpClient originHttpClient) {
            this.originHttpClient = originHttpClient;
            return this;
        }

        /**
         * Set the asynchronous HTTP client for origin requests, as {@link #originHttpClient} does for
         * synchronous clients. When unset, install creates the SDK Netty HTTP client.
         *
         * @param originAsyncHttpClient the asynchronous HTTP client for origin requests
         * @return this builder
         */
        public Builder originAsyncHttpClient(SdkAsyncHttpClient originAsyncHttpClient) {
            this.originAsyncHttpClient = originAsyncHttpClient;
            return this;
        }

        /**
         * Set the caller-managed executor the asynchronous transport uses for blocking cache socket
         * requests. The SDK client does not shut it down.
         *
         * @param asyncExecutor the executor for blocking cache requests
         * @return this builder
         */
        public Builder asyncExecutor(Executor asyncExecutor) {
            this.asyncExecutor = asyncExecutor;
            return this;
        }

        /**
         * Set the maximum asynchronous request body the cache buffers (default: 16 MiB).
         *
         * @param asyncMaxBufferedRequestBytes the body size limit in bytes. A larger body goes to the
         *     origin.
         * @return this builder
         */
        public Builder asyncMaxBufferedRequestBytes(int asyncMaxBufferedRequestBytes) {
            this.asyncMaxBufferedRequestBytes = asyncMaxBufferedRequestBytes;
            return this;
        }

        /**
         * Set the maximum asynchronous request bodies collected concurrently (default: 8).
         *
         * @param asyncMaxConcurrentBodyCollectors the limit on bodies collected at once
         * @return this builder
         */
        public Builder asyncMaxConcurrentBodyCollectors(int asyncMaxConcurrentBodyCollectors) {
            this.asyncMaxConcurrentBodyCollectors = asyncMaxConcurrentBodyCollectors;
            return this;
        }

        /** For testing: receives each synchronous handle as soon as it is created. */
        Builder handleObserver(Consumer<ElastiCacheIntegrationsHandle> handleObserver) {
            this.handleObserver = handleObserver;
            return this;
        }

        /** For testing: receives each asynchronous handle as soon as it is created. */
        Builder asyncHandleObserver(Consumer<ElastiCacheIntegrationsAsyncHandle> asyncHandleObserver) {
            this.asyncHandleObserver = asyncHandleObserver;
            return this;
        }

        /**
         * Build the application's synchronous SDK client with the cache installed.
         *
         * <p>The builder keeps its own configuration. Install adds the cache interceptor to a copy of
         * its override configuration and replaces its HTTP transport. It returns after the cache
         * session is open: connected, authenticated with {@code HELLO 3 AUTH}, and configured with
         * {@code aws.config}. Closing the returned client disconnects the cache first.
         *
         * <p>A builder that already has an {@code httpClient} or {@code httpClientBuilder} fails,
         * because install would replace its transport settings. Build that HTTP client, pass it to
         * {@link #originHttpClient}, and remove it from the builder.
         *
         * @param sdkClientBuilder the application's client builder, for example {@code
         *     DynamoDbClient.builder().region(Region.US_EAST_1)}
         * @return the built client
         * @throws ElastiCacheIntegrationsConfigError if an option or the client cannot be used
         * @throws ElastiCacheIntegrationsError if the cache connection or authentication fails
         */
        public <B extends AwsClientBuilder<B, C> & SdkSyncClientBuilder<B, C>, C extends SdkClient>
                C install(B sdkClientBuilder) {
            requireBuilder(sdkClientBuilder);
            EndpointParser.ParsedEndpoint cacheEndpoint = validateOptions();
            rejectHttpClientBuilder(
                    sdkClientBuilder,
                    "CONFIGURED_SYNC_HTTP_CLIENT_BUILDER",
                    "httpClientBuilder",
                    "originHttpClient");
            SSLContext sslContext = CacheTls.create(verify, caCerts);

            ManagedIamAuthProvider auth = new ManagedIamAuthProvider();
            ElastiCacheIntegrationsHandle handle;
            try {
                handle =
                        ElastiCacheIntegrationsHandle.builder()
                                .host(cacheEndpoint.getHost())
                                .port(cacheEndpoint.getPort())
                                .sslContext(sslContext)
                                .managedAuthProvider(auth)
                                .overrides(clientConfiguration.getCachePolicyOverrides())
                                .requestTimeoutMs(requestTimeoutMs())
                                .reconnectPolicy(reconnectPolicy())
                                .stalenessDurationSeconds(clientConfiguration.getCachePolicyTimeoutSeconds())
                                .originHttpClient(originHttpClient)
                                .build();
            } catch (RuntimeException | Error failure) {
                auth.close();
                throw failure;
            }
            if (handleObserver != null) {
                handleObserver.accept(handle);
            }

            ClientOverrideConfiguration original = sdkClientBuilder.overrideConfiguration();
            C client;
            try {
                client =
                        sdkClientBuilder
                                .overrideConfiguration(withInterceptor(original, handle.getInterceptor()))
                                .httpClientBuilder(new PrebuiltHttpClient(handle.getHttpClient()))
                                .build();
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(handle, failure);
                ElastiCacheIntegrationsConfigError translated =
                        translateBuildFailure(failure, "originHttpClient");
                if (translated != null) {
                    throw translated;
                }
                throw failure;
            } finally {
                // The built client holds the cache; the application's builder goes back to how it was,
                // so it can build or install another client.
                sdkClientBuilder
                        .overrideConfiguration(original)
                        .httpClientBuilder((SdkHttpClient.Builder<?>) null);
            }

            try {
                bindAndOpen(client, auth, handle::open);
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(client, failure);
                throw failure;
            }
            return client;
        }

        /**
         * Build the application's asynchronous SDK client with the cache installed.
         *
         * <p>It does for an asynchronous builder, for example {@code DynamoDbAsyncClient.builder()},
         * what {@link #install} does for a synchronous one. A builder that already has an {@code
         * httpClient} or {@code httpClientBuilder} fails. Build that HTTP client and pass it to {@link
         * #originAsyncHttpClient}.
         *
         * @param sdkClientBuilder the application's asynchronous client builder
         * @return the built client
         * @throws ElastiCacheIntegrationsConfigError if an option or the client cannot be used
         * @throws ElastiCacheIntegrationsError if the cache connection or authentication fails
         */
        public <B extends AwsClientBuilder<B, C> & SdkAsyncClientBuilder<B, C>, C extends SdkClient>
                C installAsync(B sdkClientBuilder) {
            requireBuilder(sdkClientBuilder);
            EndpointParser.ParsedEndpoint cacheEndpoint = validateOptions();
            if (asyncMaxBufferedRequestBytes <= 0) {
                throw new ElastiCacheIntegrationsConfigError(
                        "asyncMaxBufferedRequestBytes must be positive, got " + asyncMaxBufferedRequestBytes);
            }
            if (asyncMaxConcurrentBodyCollectors <= 0) {
                throw new ElastiCacheIntegrationsConfigError(
                        "asyncMaxConcurrentBodyCollectors must be positive, got "
                                + asyncMaxConcurrentBodyCollectors);
            }
            rejectHttpClientBuilder(
                    sdkClientBuilder,
                    "CONFIGURED_ASYNC_HTTP_CLIENT_BUILDER",
                    "asyncHttpClientBuilder",
                    "originAsyncHttpClient");
            SSLContext sslContext = CacheTls.create(verify, caCerts);

            ManagedIamAuthProvider auth = new ManagedIamAuthProvider();
            ElastiCacheIntegrationsAsyncHandle handle;
            try {
                handle =
                        ElastiCacheIntegrationsAsyncHandle.builder()
                                .host(cacheEndpoint.getHost())
                                .port(cacheEndpoint.getPort())
                                .sslContext(sslContext)
                                .managedAuthProvider(auth)
                                .overrides(clientConfiguration.getCachePolicyOverrides())
                                .requestTimeoutMs(requestTimeoutMs())
                                .reconnectPolicy(reconnectPolicy())
                                .stalenessDurationSeconds(clientConfiguration.getCachePolicyTimeoutSeconds())
                                .originHttpClient(originAsyncHttpClient)
                                .executor(asyncExecutor)
                                .maxBufferedRequestBytes(asyncMaxBufferedRequestBytes)
                                .maxConcurrentBodyCollectors(asyncMaxConcurrentBodyCollectors)
                                .build();
            } catch (RuntimeException | Error failure) {
                auth.close();
                throw failure;
            }
            if (asyncHandleObserver != null) {
                asyncHandleObserver.accept(handle);
            }

            ClientOverrideConfiguration original = sdkClientBuilder.overrideConfiguration();
            C client;
            try {
                client =
                        sdkClientBuilder
                                .overrideConfiguration(withInterceptor(original, handle.getInterceptor()))
                                .httpClientBuilder(new PrebuiltAsyncHttpClient(handle.getHttpClient()))
                                .build();
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(handle, failure);
                ElastiCacheIntegrationsConfigError translated =
                        translateBuildFailure(failure, "originAsyncHttpClient");
                if (translated != null) {
                    throw translated;
                }
                throw failure;
            } finally {
                // The built client holds the cache; the application's builder goes back to how it was,
                // so it can build or install another client.
                sdkClientBuilder
                        .overrideConfiguration(original)
                        .httpClientBuilder((SdkAsyncHttpClient.Builder<?>) null);
            }

            try {
                bindAndOpen(client, auth, handle::open);
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(client, failure);
                throw failure;
            }
            return client;
        }

        private static void requireBuilder(Object sdkClientBuilder) {
            if (sdkClientBuilder == null) {
                throw new NullPointerException("sdkClientBuilder");
            }
        }

        private EndpointParser.ParsedEndpoint validateOptions() {
            if (endpoint == null || endpoint.isEmpty()) {
                throw new ElastiCacheIntegrationsConfigError("endpoint is required");
            }
            if (cacheName == null || cacheName.isEmpty()) {
                throw new ElastiCacheIntegrationsConfigError("cacheName is required");
            }
            validateCacheNameAndUserId();
            try {
                return EndpointParser.parseEndpoint(endpoint);
            } catch (ElastiCacheIntegrationsError invalid) {
                throw new ElastiCacheIntegrationsConfigError(invalid.getMessage(), invalid);
            }
        }

        /**
         * Check {@code cacheName} and {@code userId} with the IAM toolkit's rules before any client is
         * built, through the same provider builder that {@link #bindAndOpen} uses. The region and
         * credentials here are placeholders: the real ones come from the built SDK client, and the
         * toolkit does not use them to check the names.
         */
        private void validateCacheNameAndUserId() {
            try {
                IamAuthTokenProvider.builder()
                        .cacheName(cacheName)
                        .userId(userId)
                        .region(Region.US_EAST_1)
                        .credentialsProvider(AnonymousCredentialsProvider.create())
                        .build();
            } catch (ElastiCacheIntegrationsError invalid) {
                throw new ElastiCacheIntegrationsConfigError(invalid.getMessage(), invalid);
            }
        }

        private int requestTimeoutMs() {
            // The configuration bounds the timeout to Integer.MAX_VALUE.
            return Math.toIntExact(clientConfiguration.getCacheRequestTimeoutMs());
        }

        private ReconnectPolicy reconnectPolicy() {
            return ReconnectPolicy.builder()
                    .connectionTimeoutMs(clientConfiguration.getConnectionTimeoutMs())
                    .fallbackDurationSeconds(clientConfiguration.getFallbackDurationSeconds())
                    .build();
        }

        /**
         * Read the built client's region and credentials, create the token manager from them, then open
         * the session.
         */
        private void bindAndOpen(SdkClient client, ManagedIamAuthProvider auth, Runnable open) {
            SdkServiceClientConfiguration serviceConfiguration = client.serviceClientConfiguration();
            if (!(serviceConfiguration instanceof AwsServiceClientConfiguration)) {
                throw new ElastiCacheIntegrationsConfigError(
                        "The SDK client is not an AWS SDK client, so the cache's IAM token cannot be signed.");
            }
            AwsServiceClientConfiguration aws = (AwsServiceClientConfiguration) serviceConfiguration;
            Region region = aws.region();
            if (region == null) {
                throw new ElastiCacheIntegrationsConfigError(NO_REGION);
            }
            IdentityProvider<? extends AwsCredentialsIdentity> credentials = aws.credentialsProvider();
            if (credentials == null) {
                credentials = legacyCredentialsProvider(client);
            }
            if (credentials == null || credentials instanceof AnonymousCredentialsProvider) {
                throw new ElastiCacheIntegrationsConfigError(NO_CREDENTIALS);
            }
            AwsCredentialsProvider credentialsProvider = asAwsCredentialsProvider(credentials);
            // Resolve once now, so a client with no usable credentials fails here, as it does in
            // Python. The token manager would retry the failure past the connection timeout and
            // hide the SDK's error.
            try {
                credentialsProvider.resolveCredentials();
            } catch (RuntimeException e) {
                throw new ElastiCacheIntegrationsConfigError(UNRESOLVED_CREDENTIALS + e.getMessage(), e);
            }

            IamAuthTokenProvider.Builder iam =
                    IamAuthTokenProvider.builder()
                            .cacheName(cacheName)
                            .userId(userId)
                            .region(region)
                            .credentialsProvider(credentialsProvider);
            Long refreshDelaySeconds = clientConfiguration.getIamTokenRefreshDelaySeconds();
            if (refreshDelaySeconds != null) {
                iam.refreshAfter(Duration.ofSeconds(refreshDelaySeconds));
            }
            auth.bind(iam.build());
            open.run();
        }

        /**
         * SDK 2.21.0 through 2.21.21 can leave the new service-client credentials view empty even
         * though the built client configuration contains the resolved provider used for signing.
         */
        @SuppressWarnings("unchecked")
        private static IdentityProvider<? extends AwsCredentialsIdentity> legacyCredentialsProvider(
                SdkClient client) {
            try {
                Field configurationField = declaredField(client.getClass(), "clientConfiguration");
                configurationField.setAccessible(true);
                SdkClientConfiguration configuration =
                        (SdkClientConfiguration) configurationField.get(client);
                Class<?> awsClientOption =
                        Class.forName("software.amazon.awssdk.awscore.client.config.AwsClientOption");
                for (String optionName :
                        new String[] {"CREDENTIALS_IDENTITY_PROVIDER", "CREDENTIALS_PROVIDER"}) {
                    Field optionField;
                    try {
                        optionField = awsClientOption.getField(optionName);
                    } catch (NoSuchFieldException absent) {
                        continue;
                    }
                    Object configured = configuration.option((ClientOption<?>) optionField.get(null));
                    if (configured instanceof IdentityProvider) {
                        return (IdentityProvider<? extends AwsCredentialsIdentity>) configured;
                    }
                }
                return null;
            } catch (ReflectiveOperationException | RuntimeException unreadable) {
                throw new ElastiCacheIntegrationsConfigError(
                        "ElastiCache Integrations cannot read the AWS SDK client's resolved"
                                + " credentials provider.",
                        unreadable);
            }
        }

        private static Field declaredField(Class<?> type, String fieldName)
                throws NoSuchFieldException {
            Class<?> current = type;
            while (current != null) {
                try {
                    return current.getDeclaredField(fieldName);
                } catch (NoSuchFieldException absent) {
                    current = current.getSuperclass();
                }
            }
            throw new NoSuchFieldException(fieldName);
        }
    }

    /**
     * Adapt the client's identity provider to the credentials provider the IAM toolkit takes. Each
     * call resolves the identity again, so rotated credentials reach each newly signed token.
     */
    private static AwsCredentialsProvider asAwsCredentialsProvider(
            IdentityProvider<? extends AwsCredentialsIdentity> provider) {
        if (provider instanceof AwsCredentialsProvider) {
            return (AwsCredentialsProvider) provider;
        }
        return () -> {
            AwsCredentialsIdentity identity;
            try {
                identity = provider.resolveIdentity().join();
            } catch (CompletionException e) {
                throw e.getCause() instanceof RuntimeException ? (RuntimeException) e.getCause() : e;
            }
            AwsCredentials credentials;
            if (identity instanceof AwsSessionCredentialsIdentity) {
                AwsSessionCredentialsIdentity session = (AwsSessionCredentialsIdentity) identity;
                credentials =
                        AwsSessionCredentials.create(
                                session.accessKeyId(), session.secretAccessKey(), session.sessionToken());
            } else {
                credentials =
                        AwsBasicCredentials.create(identity.accessKeyId(), identity.secretAccessKey());
            }
            return credentials;
        };
    }

    private static ClientOverrideConfiguration withInterceptor(
            ClientOverrideConfiguration existing, ExecutionInterceptor interceptor) {
        // Copy the builder's own configuration so its interceptors, retries, timeouts and metrics
        // survive, then add the cache interceptor.
        return existing.toBuilder().addExecutionInterceptor(interceptor).build();
    }

    /**
     * Return the configuration error for an SDK build failure the application can fix, or null for
     * any other failure. The SDK rejects a builder that has both an HTTP client and an HTTP client
     * builder, and fails to build a client when no region resolves.
     */
    private static ElastiCacheIntegrationsConfigError translateBuildFailure(
            Throwable failure, String originOption) {
        if (failure instanceof SdkClientException
                && failure.getMessage() != null
                && failure.getMessage().startsWith("Unable to load region")) {
            return new ElastiCacheIntegrationsConfigError(NO_REGION, failure);
        }
        if (failure instanceof IllegalArgumentException
                && failure.getMessage() != null
                && failure.getMessage().contains("can't both be configured")
                && failure.getMessage().toLowerCase(Locale.ROOT).contains("httpclientbuilder")) {
            return new ElastiCacheIntegrationsConfigError(
                    "This AWS SDK client builder already has an httpClient, which ElastiCache Integrations"
                            + " must replace. Remove it from the builder and pass that client as "
                            + originOption
                            + " instead.",
                    failure);
        }
        return null;
    }

    /**
     * Refuse an SDK builder that already has an HTTP client builder. Install replaces it, and the SDK
     * has no getter to read it back, so its {@code ProxyConfiguration}, TLS, timeout, and pool
     * settings would be lost without an error. SDK 2.21.0 through 2.21.21 keep the builder in a
     * private field; newer versions keep it as a client-configuration option. This check reads either
     * layout by reflection so the integration does not link against an option constant that old SDKs
     * lack. A builder this check cannot read, such as a wrapper around an SDK builder or an SDK
     * version that moved both locations, is refused too, because install cannot tell whether it would
     * lose a transport.
     */
    static void rejectHttpClientBuilder(
            Object sdkClientBuilder, String optionName, String legacyFieldName, String originOption) {
        if (!(sdkClientBuilder instanceof SdkDefaultClientBuilder)) {
            throw new ElastiCacheIntegrationsConfigError(
                    uninspectable(sdkClientBuilder.getClass().getName(), originOption));
        }
        Object configured;
        try {
            configured = configuredHttpClientBuilder(sdkClientBuilder, optionName, legacyFieldName);
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            throw new ElastiCacheIntegrationsConfigError(
                    uninspectable(sdkClientBuilder.getClass().getName(), originOption), unreadable);
        }
        if (configured != null) {
            throw new ElastiCacheIntegrationsConfigError(
                    "This AWS SDK client builder already has an httpClientBuilder, which ElastiCache"
                            + " Integrations must replace. Build the HTTP client, pass it as "
                            + originOption
                            + ", and remove it from the builder.");
        }
    }

    private static Object configuredHttpClientBuilder(
            Object sdkClientBuilder, String optionName, String legacyFieldName)
            throws ReflectiveOperationException {
        try {
            Field legacyField = SdkDefaultClientBuilder.class.getDeclaredField(legacyFieldName);
            legacyField.setAccessible(true);
            return legacyField.get(sdkClientBuilder);
        } catch (NoSuchFieldException newerSdk) {
            Field configurationField =
                    SdkDefaultClientBuilder.class.getDeclaredField("clientConfiguration");
            configurationField.setAccessible(true);
            SdkClientConfiguration.Builder configuration =
                    (SdkClientConfiguration.Builder) configurationField.get(sdkClientBuilder);
            SdkClientOption<?> option =
                    (SdkClientOption<?>) SdkClientOption.class.getField(optionName).get(null);
            return configuration.option(option);
        }
    }

    private static String uninspectable(String builderClass, String originOption) {
        return "ElastiCache Integrations cannot check whether the AWS SDK client builder "
                + builderClass
                + " already has an httpClientBuilder, which install would replace. Use the builder"
                + " that the SDK client's builder() method returns, and pass any HTTP client as "
                + originOption
                + ".";
    }

    private static void closeAfterFailure(AutoCloseable owner, Throwable failure) {
        try {
            owner.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    /** Hands the SDK the transport that install already built. The SDK closes it with the client. */
    private static final class PrebuiltHttpClient
            implements SdkHttpClient.Builder<PrebuiltHttpClient> {
        private final SdkHttpClient client;

        private PrebuiltHttpClient(SdkHttpClient client) {
            this.client = client;
        }

        @Override
        public SdkHttpClient build() {
            return client;
        }

        @Override
        public SdkHttpClient buildWithDefaults(AttributeMap serviceDefaults) {
            return client;
        }
    }

    /** Asynchronous counterpart of {@link PrebuiltHttpClient}. */
    private static final class PrebuiltAsyncHttpClient
            implements SdkAsyncHttpClient.Builder<PrebuiltAsyncHttpClient> {
        private final SdkAsyncHttpClient client;

        private PrebuiltAsyncHttpClient(SdkAsyncHttpClient client) {
            this.client = client;
        }

        @Override
        public SdkAsyncHttpClient build() {
            return client;
        }

        @Override
        public SdkAsyncHttpClient buildWithDefaults(AttributeMap serviceDefaults) {
            return client;
        }
    }
}
