// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.amazon.elasticache.integrations.resp;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** RESP3 bulk string value (byte array). */
public final class RespBulkString implements RespValue {

    private final byte[] value;

    /**
     * Create a bulk string value.
     *
     * @param value the raw bytes
     */
    public RespBulkString(byte[] value) {
        this.value = value;
    }

    /**
     * Return the raw bytes.
     *
     * @return the raw bytes
     */
    public byte[] getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RespBulkString)) return false;
        RespBulkString that = (RespBulkString) o;
        return Arrays.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return "RespBulkString{" + new String(value, StandardCharsets.UTF_8) + "}";
    }
}
