import { describe, expect, it } from "vitest";

import { STSClient } from "@aws-sdk/nested-clients/sts";
import {
  install,
  ElastiCacheIntegrationsCacheUnavailable,
  ElastiCacheIntegrationsConfigError,
  ElastiCacheIntegrationsError,
  version,
} from "../src/index.js";
import type { ElastiCacheIntegrationsClientConfiguration } from "../src/index.js";

const sts = new STSClient({ region: "us-east-1" });
const clientConfiguration: ElastiCacheIntegrationsClientConfiguration = {
  iamTokenRefreshDelay: 120,
};
const stsOptions: Parameters<typeof install<typeof sts>>[0] = {
  client: sts,
  endpoint: "cache.example.com:6379",
  cacheName: "test-cache",
  clientConfiguration,
};

describe("ElastiCacheIntegrations package entry point", () => {
  it("exports the package version", () => {
    expect(version).toBe("0.0.2-rc.1");
  });

  it("exports install and its type surface", () => {
    expect(install).toBeTypeOf("function");
    expect(ElastiCacheIntegrationsCacheUnavailable).toBeTypeOf("function");
    expect(ElastiCacheIntegrationsConfigError).toBeTypeOf("function");
    expect(ElastiCacheIntegrationsError).toBeTypeOf("function");
    expect(stsOptions.client).toBe(sts);
    expect(stsOptions.clientConfiguration?.iamTokenRefreshDelay).toBe(120);
  });

  it("does not export internal implementation APIs", async () => {
    const entry = await import("../src/index.js");
    expect(Object.keys(entry).sort()).toEqual([
      "ElastiCacheIntegrationsCacheUnavailable",
      "ElastiCacheIntegrationsConfigError",
      "ElastiCacheIntegrationsError",
      "install",
      "version",
    ]);
    for (const name of [
      "ElastiCacheIntegrationsConnectionLost",
      "ElastiCacheIntegrationsConnectionError",
      "ElastiCacheIntegrationsProtocolError",
      "ElastiCacheIntegrationsTimeoutError",
      "ElastiCacheIntegrationsConnector",
      "DEFAULT_USER_ID",
      "IamAuthTokenProvider",
      "TOKEN_EXPIRY_SECONDS",
      "buildCacheAuthProvider",
      "generateIamAuthToken",
      "resolveAuthParams",
      "ElastiCacheIntegrationsRequest",
      "ElastiCacheIntegrationsResponse",
      "RequestMetadata",
      "ResolvedConfig",
      "ElastiCacheIntegrationsHandle",
      "ElastiCacheIntegrationsSession",
      "parseEndpoint",
      "installCacheMiddleware",
      "awsToCacheRequest",
      "cacheToHttpResponse",
    ]) {
      expect(entry).not.toHaveProperty(name);
    }
  });
});
