// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.amazon.elasticache.integrations.resp;

/**
 * Marker interface for all RESP3 value types. Implementations: RespBulkString, RespInteger,
 * RespArray, RespMap, RespPush, RespError, RespNull.
 */
public interface RespValue {}
