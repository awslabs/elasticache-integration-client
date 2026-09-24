import { describe, expect, it } from "vitest";

import { STSClient } from "@aws-sdk/nested-clients/sts";
import type { STSClientConfig } from "@aws-sdk/nested-clients/sts";
import {
  client,
  PhotonCacheUnavailable,
  PhotonConfigError,
  PhotonError,
  version,
} from "../src/index.js";
import type { PhotonClientOptions } from "../src/index.js";

type StsClient = InstanceType<typeof STSClient>;
const stsConstructor: new (config: STSClientConfig) => StsClient = STSClient;
const stsOptions: PhotonClientOptions<STSClientConfig> = {
  endpoint: "cache.example.com:6379",
  cacheName: "test-cache",
  region: "us-east-1",
  iamRefreshAfterSeconds: 120,
};

describe("Photon package entry point", () => {
  it("exports the package version", () => {
    expect(version).toBe("0.0.1");
  });

  it("exports the public client factory and type surface", () => {
    expect(client).toBeTypeOf("function");
    expect(PhotonCacheUnavailable).toBeTypeOf("function");
    expect(PhotonConfigError).toBeTypeOf("function");
    expect(PhotonError).toBeTypeOf("function");
    expect(stsConstructor).toBe(STSClient);
    expect(stsOptions.region).toBe("us-east-1");
    expect(stsOptions.iamRefreshAfterSeconds).toBe(120);
  });

  it("does not export internal implementation APIs", async () => {
    const entry = await import("../src/index.js");
    expect(Object.keys(entry).sort()).toEqual([
      "PhotonCacheUnavailable",
      "PhotonConfigError",
      "PhotonError",
      "client",
      "version",
    ]);
    for (const name of [
      "PhotonConnectionLost",
      "PhotonConnectionError",
      "PhotonProtocolError",
      "PhotonTimeoutError",
      "PhotonConnector",
      "DEFAULT_USER_ID",
      "IamAuthTokenProvider",
      "TOKEN_EXPIRY_SECONDS",
      "buildCacheAuthProvider",
      "generateIamAuthToken",
      "resolveAuthParams",
      "PhotonRequest",
      "PhotonResponse",
      "RequestMetadata",
      "ResolvedConfig",
      "PhotonHandle",
      "PhotonSession",
      "parseEndpoint",
      "installPhotonMiddleware",
      "awsToPhotonRequest",
      "photonToHttpResponse",
    ]) {
      expect(entry).not.toHaveProperty(name);
    }
  });
});
