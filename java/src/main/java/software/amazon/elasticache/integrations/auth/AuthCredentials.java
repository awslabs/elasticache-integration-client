// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.amazon.elasticache.integrations.auth;

import java.util.Objects;

/**
 * Credentials for Cache cache authentication: a user ID and token pair.
 *
 * <p>Used by {@link IamAuthTokenProvider} to return both the user ID and the generated IAM auth
 * token for the {@code HELLO 3 AUTH} handshake.
 *
 * <p><b>Internal:</b> this class is not part of the supported API. It is public only because the
 * {@code connection} package uses it.
 */
public final class AuthCredentials {

    private final String userId;
    private final String token;

    /**
     * Create authentication credentials.
     *
     * @param userId the user ID for inline AUTH (e.g., "default" or an IAM user)
     * @param token the authentication token (password or IAM presigned token)
     */
    public AuthCredentials(String userId, String token) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.token = Objects.requireNonNull(token, "token must not be null");
    }

    /**
     * Get the user ID.
     *
     * @return the user ID
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Get the authentication token.
     *
     * @return the authentication token
     */
    public String getToken() {
        return token;
    }

    @Override
    public String toString() {
        // Intentionally don't include token in toString to avoid accidental logging
        return "AuthCredentials{userId='" + userId + "'}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AuthCredentials)) return false;
        AuthCredentials that = (AuthCredentials) o;
        return userId.equals(that.userId) && token.equals(that.token);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, token);
    }
}
