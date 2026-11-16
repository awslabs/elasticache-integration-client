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
