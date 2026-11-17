// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.amazon.elasticache.integrations.resp;

import java.util.Map;
import java.util.Objects;

/** RESP3 map value. */
public final class RespMap implements RespValue {

    private final Map<RespValue, RespValue> entries;

    /**
     * Create a map value.
     *
     * @param entries the map entries in wire order
     */
    public RespMap(Map<RespValue, RespValue> entries) {
        this.entries = entries;
    }

    /**
     * Return the map entries in wire order.
     *
     * @return the map entries in wire order
     */
    public Map<RespValue, RespValue> getEntries() {
        return entries;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RespMap)) return false;
        RespMap respMap = (RespMap) o;
        return Objects.equals(entries, respMap.entries);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(entries);
    }

    @Override
    public String toString() {
        return "RespMap{" + entries + "}";
    }
}
