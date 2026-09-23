package software.amazon.photon.auth;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.photon.exception.PhotonError;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IamAuthTokenManagerTest {
    private static final String FIRST_ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE";
    private static final String ROTATED_ACCESS_KEY = "ASIAROTATEDCREDENTIAL";

    @Test
    void getCachesTokenBeforeRefreshPoint() {
        AtomicInteger credentialCalls = new AtomicInteger();
        IamAuthTokenManager manager = manager(
                countingCredentials(credentialCalls, null),
                Duration.ofMinutes(5));
        try {
            AuthCredentials first = manager.get();
            AuthCredentials second = manager.get();

            assertEquals(1, credentialCalls.get());
            assertEquals(first, second);
            assertEquals("iam-user", first.getUserId());
            assertTrue(first.getToken().contains(FIRST_ACCESS_KEY));
        } finally {
            manager.close();
        }
    }

    @Test
    void refreshesInBackgroundWithRotatedAwsCredentials() throws Exception {
        AtomicInteger credentialCalls = new AtomicInteger();
        CountDownLatch refreshStarted = new CountDownLatch(1);
        IamAuthTokenManager manager = manager(
                countingCredentials(credentialCalls, refreshStarted),
                Duration.ofMillis(10));
        try {
            AuthCredentials first = manager.get();

            assertTrue(refreshStarted.await(5, TimeUnit.SECONDS));
            AuthCredentials refreshed = awaitRotatedCredentials(manager);

            assertTrue(first.getToken().contains(FIRST_ACCESS_KEY));
            assertFalse(first.getToken().contains(ROTATED_ACCESS_KEY));
            assertTrue(refreshed.getToken().contains(ROTATED_ACCESS_KEY));
            assertFalse(refreshed.getToken().contains(FIRST_ACCESS_KEY));
            assertTrue(credentialCalls.get() >= 2);
        } finally {
            manager.close();
        }
    }

    @Test
    void closeStopsCredentialAccess() {
        IamAuthTokenManager manager = manager(
                countingCredentials(new AtomicInteger(), null),
                Duration.ofMinutes(5));
        manager.get();
        manager.close();

        PhotonError error = assertThrows(PhotonError.class, manager::get);
        assertTrue(error.getMessage().contains("refreshed IAM auth token"));
    }

    @Test
    void builderUsesToolkitValidationAndNormalization() {
        IamAuthTokenManager manager = manager(
                countingCredentials(new AtomicInteger(), null),
                Duration.ofMinutes(5));
        try {
            assertEquals("my-cache", manager.getCacheName());
            assertEquals("iam-user", manager.getUserId());
            assertEquals(Region.US_EAST_1, manager.getRegion());
            assertFalse(manager.toString().contains("X-Amz-Signature"));
        } finally {
            manager.close();
        }

        PhotonError error = assertThrows(PhotonError.class, () ->
                IamAuthTokenManager.builder()
                        .cacheName("bad/cache")
                        .region(Region.US_EAST_1)
                        .build());
        assertTrue(error.getMessage().contains("serverlessCacheName"));
    }

    @Test
    void builderRejectsInvalidRefreshInterval() {
        PhotonError error = assertThrows(PhotonError.class, () ->
                IamAuthTokenManager.builder()
                        .cacheName("my-cache")
                        .region(Region.US_EAST_1)
                        .refreshAfter(Duration.ofMinutes(15))
                        .build());

        assertTrue(error.getCause().getMessage().contains("refreshAfter"));
    }

    private static IamAuthTokenManager manager(
            AwsCredentialsProvider credentialsProvider, Duration refreshAfter) {
        return IamAuthTokenManager.builder()
                .cacheName("My-Cache")
                .userId("IAM-User")
                .region(Region.US_EAST_1)
                .credentialsProvider(credentialsProvider)
                .refreshAfter(refreshAfter)
                .build();
    }

    private static AwsCredentialsProvider countingCredentials(
            AtomicInteger calls, CountDownLatch refreshStarted) {
        return () -> {
            int call = calls.incrementAndGet();
            if (call > 1 && refreshStarted != null) {
                refreshStarted.countDown();
            }
            return AwsBasicCredentials.create(
                    call == 1 ? FIRST_ACCESS_KEY : ROTATED_ACCESS_KEY,
                    call == 1 ? "first-secret" : "rotated-secret");
        };
    }

    private static AuthCredentials awaitRotatedCredentials(
            IamAuthTokenManager manager) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        AuthCredentials credentials = manager.get();
        while (!credentials.getToken().contains(ROTATED_ACCESS_KEY)
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
            credentials = manager.get();
        }
        return credentials;
    }
}
