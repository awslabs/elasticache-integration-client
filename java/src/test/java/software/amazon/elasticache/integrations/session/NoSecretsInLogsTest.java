package software.amazon.elasticache.integrations.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.elasticache.integrations.mock.MockCache;
import software.amazon.elasticache.integrations.mock.MockTlsServer;
import software.amazon.elasticache.integrations.mock.Reply;
import software.amazon.elasticache.integrations.resp.RespError;

/**
 * The client never logs, or puts in an exception, credentials, the IAM token or customer data.
 *
 * <p>Every scenario runs a real DynamoDB client against the mock cache with marker values in the
 * credentials, the request and the response, and with the client's loggers at {@link Level#ALL}. It
 * then checks that no marker, no part of the IAM token the cache received, and nothing shaped like
 * an AWS key or SigV4 signature appears in the client's log output or in any exception it raised.
 */
class NoSecretsInLogsTest {

    private static final String ACCESS_KEY = "AKIDLEAKCANARYACCESS";
    private static final String SECRET_KEY = "LEAKCANARYSECRETKEY";
    private static final String SESSION_TOKEN = "LEAKCANARYSESSIONTOKEN";
    private static final String REQUEST_VALUE = "LEAKCANARYREQUEST";
    private static final String RESPONSE_VALUE = "LEAKCANARYRESPONSE";
    private static final List<String> MARKERS =
            Arrays.asList(ACCESS_KEY, SECRET_KEY, SESSION_TOKEN, REQUEST_VALUE, RESPONSE_VALUE);

    /** What a real secret looks like, in case one reaches a log some way the markers do not cover. */
    private static final Pattern SECRET_SHAPES =
            Pattern.compile(
                    "(?i)(?:AKIA|ASIA)[A-Z0-9]{16}"
                            + "|X-Amz-(?:Signature|Credential|Security-Token)|AWS4-HMAC-SHA256");

    private static final AwsCredentialsProvider CREDENTIALS =
            StaticCredentialsProvider.create(
                    AwsSessionCredentials.create(ACCESS_KEY, SECRET_KEY, SESSION_TOKEN));

    /**
     * The parent of the client's loggers ({@code software.amazon.elasticache.integrations}) and of
     * the IAM toolkit's package ({@code software.amazon.elasticache.auth}). Held in a field so the
     * level set on it is not lost to garbage collection.
     */
    private static final Logger LOGGER = Logger.getLogger("software.amazon.elasticache");

    enum Scenario {
        SERVED,
        REJECTED_REQUEST,
        TIMED_OUT,
        DROPPED,
        AUTH_REJECTED
    }

    @TempDir Path tempDir;

    private MockCache cache;
    private MockTlsServer server;
    private Path caBundle;
    private CapturingHandler logs;
    private Level previousLevel;
    private final List<Throwable> errors = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        cache = new MockCache();
        server = new MockTlsServer(cache);
        caBundle = tempDir.resolve("ca.pem");
        server.writeCaBundle(caBundle);

        logs = new CapturingHandler();
        logs.setLevel(Level.ALL);
        previousLevel = LOGGER.getLevel();
        LOGGER.setLevel(Level.ALL);
        LOGGER.addHandler(logs);
    }

    @AfterEach
    void tearDown() throws Exception {
        LOGGER.removeHandler(logs);
        LOGGER.setLevel(previousLevel);
        if (server != null) {
            server.close();
        }
    }

    @ParameterizedTest
    @EnumSource(Scenario.class)
    void syncClientKeepsSecretsAndCustomerDataOutOfLogsAndErrors(Scenario scenario) {
        run(scenario, false);
        assertNothingLeaked();
    }

    @ParameterizedTest
    @EnumSource(Scenario.class)
    void asyncClientKeepsSecretsAndCustomerDataOutOfLogsAndErrors(Scenario scenario) {
        run(scenario, true);
        assertNothingLeaked();
    }

    private void run(Scenario scenario, boolean async) {
        ElastiCacheIntegrationsClientConfiguration.Builder configuration =
                ElastiCacheIntegrationsClientConfiguration.builder()
                        .connectionTimeoutMs(1000)
                        // No origin fallback, so the client's own error reaches the caller.
                        .fallbackDurationSeconds(0);
        switch (scenario) {
            case SERVED:
                cache.setReply(
                        Reply.ok(
                                200,
                                Collections.singletonMap("content-type", "application/x-amz-json-1.0"),
                                "{\"Item\":{\"v\":{\"S\":\"" + RESPONSE_VALUE + "\"}}}"));
                break;
            case REJECTED_REQUEST:
                cache.setReply(Reply.error("ORIGIN_ERROR", "origin rejected"));
                break;
            case TIMED_OUT:
                cache.setFailureMode(MockCache.FailureMode.HANG);
                cache.setFailureCommand("aws.request");
                configuration.cacheRequestTimeoutMs(200);
                break;
            case DROPPED:
                cache.setFailureMode(MockCache.FailureMode.DROP);
                cache.setFailureCommand("aws.request");
                break;
            case AUTH_REJECTED:
                cache.setAuthReplyFunction(
                        (userId, token) ->
                                new RespError("WRONGPASS", "invalid username-password pair or user is disabled"));
                break;
        }

        GetItemRequest request =
                GetItemRequest.builder()
                        .tableName("my-table")
                        .key(Collections.singletonMap("id", AttributeValue.builder().s(REQUEST_VALUE).build()))
                        .build();
        GetItemResponse response = null;
        try (Installed client = install(configuration.build(), async)) {
            response = client.getItem(request);
        } catch (RuntimeException e) {
            errors.add(e);
        }

        if (scenario == Scenario.SERVED) {
            assertTrue(errors.isEmpty(), () -> "the served request failed:\n" + errorText());
            assertEquals(RESPONSE_VALUE, response.item().get("v").s());
        } else {
            assertFalse(errors.isEmpty(), "the " + scenario + " scenario raised nothing");
        }
    }

    private void assertNothingLeaked() {
        List<String> tokens = new ArrayList<>();
        for (MockCache.AuthAttempt attempt : new ArrayList<>(cache.getAuthAttempts())) {
            tokens.add(attempt.getToken());
        }
        assertFalse(
                tokens.isEmpty(), "the scenario never authenticated, so the IAM token went unchecked");

        String output = logs.text() + "\n" + errorText();
        List<String> secrets = new ArrayList<>(MARKERS);
        for (String token : tokens) {
            secrets.addAll(tokenSecrets(token));
        }
        for (String secret : secrets) {
            assertFalse(
                    output.contains(secret),
                    () -> secret.substring(0, Math.min(24, secret.length())) + "... leaked:\n" + output);
        }
        assertFalse(
                SECRET_SHAPES.matcher(output).find(), () -> "secret-shaped text leaked:\n" + output);
    }

    /** Everything an application could log about the errors: text, messages, causes, suppressed. */
    private String errorText() {
        StringBuilder out = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable error : errors) {
            describe(error, out, seen);
            out.append(stackTrace(error));
        }
        return out.toString();
    }

    private static void describe(Throwable error, StringBuilder out, Set<Throwable> seen) {
        if (error == null || !seen.add(error)) {
            return;
        }
        out.append(error).append('\n').append(error.getMessage()).append('\n');
        describe(error.getCause(), out, seen);
        for (Throwable suppressed : error.getSuppressed()) {
            describe(suppressed, out, seen);
        }
    }

    private static String stackTrace(Throwable error) {
        StringWriter out = new StringWriter();
        error.printStackTrace(new PrintWriter(out, true));
        return out.toString();
    }

    /**
     * The token and each long value in its query string, raw and decoded: the signature, credential
     * and session token. Logging any one of them on its own is a leak too.
     */
    private static List<String> tokenSecrets(String token) {
        List<String> secrets = new ArrayList<>();
        secrets.add(token);
        int query = token.indexOf('?');
        if (query < 0) {
            return secrets;
        }
        for (String pair : token.substring(query + 1).split("&")) {
            int equals = pair.indexOf('=');
            if (equals < 0) {
                continue;
            }
            String raw = pair.substring(equals + 1);
            for (String value : Arrays.asList(raw, decode(raw))) {
                if (value.length() >= 16) {
                    secrets.add(value);
                }
            }
        }
        return secrets;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    private ElastiCacheIntegrationsClient.Builder cacheBuilder(
            ElastiCacheIntegrationsClientConfiguration configuration) {
        return ElastiCacheIntegrationsClient.builder()
                .endpoint("localhost:" + server.getPort())
                .cacheName("my-cache")
                .caCerts(caBundle)
                .clientConfiguration(configuration);
    }

    private Installed install(
            ElastiCacheIntegrationsClientConfiguration configuration, boolean async) {
        URI unreachableOrigin = URI.create("http://127.0.0.1:1");
        if (async) {
            DynamoDbAsyncClient client =
                    cacheBuilder(configuration)
                            .installAsync(
                                    DynamoDbAsyncClient.builder()
                                            .region(Region.US_EAST_1)
                                            .credentialsProvider(CREDENTIALS)
                                            .endpointOverride(unreachableOrigin)
                                            .overrideConfiguration(o -> o.retryPolicy(RetryPolicy.none())));
            return new Installed() {
                @Override
                public GetItemResponse getItem(GetItemRequest request) {
                    try {
                        return client.getItem(request).get(10, TimeUnit.SECONDS);
                    } catch (ExecutionException e) {
                        throw new CompletionException(e.getCause());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    } catch (TimeoutException e) {
                        throw new AssertionError("the request did not complete", e);
                    }
                }

                @Override
                public void close() {
                    client.close();
                }
            };
        }
        DynamoDbClient client =
                cacheBuilder(configuration)
                        .install(
                                DynamoDbClient.builder()
                                        .region(Region.US_EAST_1)
                                        .credentialsProvider(CREDENTIALS)
                                        .endpointOverride(unreachableOrigin)
                                        .overrideConfiguration(o -> o.retryPolicy(RetryPolicy.none())));
        return new Installed() {
            @Override
            public GetItemResponse getItem(GetItemRequest request) {
                return client.getItem(request);
            }

            @Override
            public void close() {
                client.close();
            }
        };
    }

    /** The sync or async DynamoDB client under test. */
    private interface Installed extends AutoCloseable {
        GetItemResponse getItem(GetItemRequest request);

        @Override
        void close();
    }

    /** Collects every record as a log formatter would print it, with any exception in full. */
    private static final class CapturingHandler extends Handler {
        private final SimpleFormatter formatter = new SimpleFormatter();
        private final List<String> records = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void publish(LogRecord record) {
            StringBuilder out =
                    new StringBuilder()
                            .append(record.getLoggerName())
                            .append(' ')
                            .append(record.getLevel())
                            .append(' ')
                            .append(formatter.formatMessage(record))
                            .append(' ')
                            .append(Arrays.toString(record.getParameters()))
                            .append('\n');
            if (record.getThrown() != null) {
                describe(record.getThrown(), out, Collections.newSetFromMap(new IdentityHashMap<>()));
                out.append(stackTrace(record.getThrown()));
            }
            records.add(out.toString());
        }

        String text() {
            synchronized (records) {
                return String.join("\n", records);
            }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    }
}
