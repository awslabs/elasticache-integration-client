import { afterEach, describe, expect, it, vi } from "vitest";

import { buildCacheAuthProvider } from "../src/auth.js";

const credentials = {
  accessKeyId: "AKIAIOSFODNN7EXAMPLE",
  secretAccessKey: "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
};

describe("default region resolution", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it("uses an explicitly supplied region", async () => {
    vi.stubEnv("AWS_REGION", "us-west-2");

    const provider = await buildCacheAuthProvider({
      cacheName: "my-cache",
      region: "us-east-1",
      credentials: async () => credentials,
    });

    const [, token] = await provider();
    expect(token).toContain("us-east-1");
    expect(token).not.toContain("us-west-2");
    provider.close();
  });

  it("supports AWS_DEFAULT_REGION through the toolkit", async () => {
    vi.stubEnv("AWS_REGION", "");
    vi.stubEnv("AWS_DEFAULT_REGION", "ap-southeast-2");

    const provider = await buildCacheAuthProvider({
      cacheName: "my-cache",
      credentials: async () => credentials,
    });

    const [, token] = await provider();
    expect(token).toContain("ap-southeast-2");
    provider.close();
  });
});
