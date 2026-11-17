// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

/** ElastiCacheIntegrations client package, public API. */
export { install } from "./client.js";
export type { ElastiCacheIntegrationsClientConfiguration } from "./client.js";
export {
  ElastiCacheIntegrationsAuthError,
  ElastiCacheIntegrationsCacheUnavailable,
  ElastiCacheIntegrationsConfigError,
  ElastiCacheIntegrationsConfigUnevaluable,
  ElastiCacheIntegrationsConnectionError,
  ElastiCacheIntegrationsConnectionLost,
  ElastiCacheIntegrationsError,
  ElastiCacheIntegrationsOriginError,
  ElastiCacheIntegrationsOverridesRejected,
  ElastiCacheIntegrationsProtocolError,
  ElastiCacheIntegrationsRejectedError,
  ElastiCacheIntegrationsRequestNotSent,
  ElastiCacheIntegrationsTimeoutError,
} from "./errors.js";

export const version = "0.0.1";
