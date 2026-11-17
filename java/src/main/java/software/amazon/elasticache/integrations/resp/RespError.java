// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.amazon.elasticache.integrations.resp;

import java.util.Objects;

/** A normalized RESP error reply ({@code -CODE message}). */
public final class RespError implements RespValue {

    private final String code;
    private final String message;

    /**
     * Create an error value.
     *
     * @param code the error code, the first word of the error line
     * @param message the rest of the error line, or empty
     */
    public RespError(String code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * Return the error code.
     *
     * @return the error code, such as {@code ERR}
     */
    public String getCode() {
        return code;
    }

    /**
     * Return the error message.
     *
     * @return the text after the error code, or empty
     */
    public String getMessage() {
        return message;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RespError)) return false;
        RespError respError = (RespError) o;
        return Objects.equals(code, respError.code) && Objects.equals(message, respError.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, message);
    }

    @Override
    public String toString() {
        return "RespError{code='" + code + "', message='" + message + "'}";
    }
}
