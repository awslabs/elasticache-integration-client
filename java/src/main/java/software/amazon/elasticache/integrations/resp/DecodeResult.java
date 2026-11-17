// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.amazon.elasticache.integrations.resp;

/** Result of decoding a RESP value: the value and the position after it. */
public class DecodeResult {

    private final RespValue value;
    private final int position;

    /**
     * Create a decode result.
     *
     * @param value the decoded value
     * @param position the position after the decoded value
     */
    public DecodeResult(RespValue value, int position) {
        this.value = value;
        this.position = position;
    }

    /**
     * Return the decoded value.
     *
     * @return the decoded value
     */
    public RespValue getValue() {
        return value;
    }

    /**
     * Return the position after the decoded value.
     *
     * @return the index of the first byte after the decoded value
     */
    public int getPosition() {
        return position;
    }
}
