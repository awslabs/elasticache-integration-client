/** ElastiCacheIntegrations client package, public API. */
export { install } from "./client.js";
export type { ElastiCacheIntegrationsClientConfiguration } from "./client.js";
export type { ReconnectPolicyOptions } from "./session.js";
export {
  ElastiCacheIntegrationsCacheUnavailable,
  ElastiCacheIntegrationsConfigError,
  ElastiCacheIntegrationsError,
} from "./errors.js";

export const version = "0.0.1";
