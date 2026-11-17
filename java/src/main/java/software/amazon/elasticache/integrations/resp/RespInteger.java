// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.amazon.elasticache.integrations.resp;

/** RESP3 integer value. */
public final class RespInteger implements RespValue {

    private final long value;

    /**
     * Create an integer value.
     *
     * @param value the integer
     */
    public RespInteger(long value) {
        this.value = value;
    }

    /**
     * Return the integer.
     *
     * @return the integer
     */
    public long getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RespInteger)) return false;
        RespInteger that = (RespInteger) o;
        return value == that.value;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(value);
    }

    @Override
    public String toString() {
        return "RespInteger{" + value + "}";
    }
}
