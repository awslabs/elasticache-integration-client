import { beforeEach, describe, expect, it, vi } from "vitest";

const regionConfig = vi.hoisted(() => {
  const provider = vi.fn<() => Promise<string>>();
  return {
    provider,
    loadConfig: vi.fn(() => provider),
  };
});

vi.mock("@smithy/node-config-provider", () => ({
  loadConfig: regionConfig.loadConfig,
}));

const credentials = {
  accessKeyId: "AKIAIOSFODNN7EXAMPLE",
  secretAccessKey: "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
};

describe("default region resolution", () => {
  beforeEach(() => {
    vi.resetModules();
    regionConfig.provider.mockReset();
    regionConfig.loadConfig.mockReset().mockReturnValue(regionConfig.provider);
  });

  it("does not initialize the region provider when a region is supplied", async () => {
    const { buildCacheAuthProvider } = await import("../src/auth.js");

    await buildCacheAuthProvider({
      cacheName: "my-cache",
      region: "us-east-1",
      credentials: async () => credentials,
    });

    expect(regionConfig.loadConfig).not.toHaveBeenCalled();
  });

  it("wraps region provider failures", async () => {
    const { buildCacheAuthProvider } = await import("../src/auth.js");
    regionConfig.provider.mockRejectedValueOnce(new Error("region unavailable"));

    await expect(
      buildCacheAuthProvider({
        cacheName: "my-cache",
        credentials: async () => credentials,
      }),
    ).rejects.toThrow("region is required for IAM authentication");
    expect(regionConfig.loadConfig).toHaveBeenCalledOnce();
  });
});
