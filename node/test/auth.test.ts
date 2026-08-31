import { describe, expect, it, vi } from "vitest";

import { CredentialsProviderError } from "@smithy/core/config";

import {
  DEFAULT_USER_ID,
  IamAuthTokenProvider,
  buildCacheAuthProvider,
  generateIamAuthToken,
  resolveAuthParams,
} from "../src/auth.js";
import { PhotonConnection } from "../src/connection.js";

const credentials = {
  accessKeyId: "AKIAIOSFODNN7EXAMPLE",
  secretAccessKey: "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
  sessionToken: "FwoGZXIvYXdzEBYaDHqa0AP",
};

// --- resolveAuthParams tests -------------------------------------------------

describe("resolveAuthParams", () => {
  it("applies the default IAM user", () => {
    expect(resolveAuthParams({ cacheName: "my-cache", region: "us-east-1" })).toEqual({
      cacheName: "my-cache",
      userId: DEFAULT_USER_ID,
      region: "us-east-1",
    });
  });

  it("rejects a missing cache name", () => {
    expect(() => resolveAuthParams({ cacheName: "", region: "us-east-1" })).toThrow(
      "cacheName is required",
    );
  });

  it("rejects a missing region", () => {
    expect(() => resolveAuthParams({ cacheName: "my-cache", region: "" })).toThrow(
      "region is required",
    );
  });
});

// --- generateIamAuthToken tests ----------------------------------------------

describe("generateIamAuthToken", () => {
  it("creates a scheme-free ElastiCache SigV4 token", async () => {
    const token = await generateIamAuthToken({
      cacheName: "my-cache",
      userId: "my-user",
      region: "us-east-1",
      credentials: async () => credentials,
    });

    expect(token).not.toMatch(/^http:\/\//);
    expect(token).toMatch(/^my-cache\//);
    expect(token).toContain("Action=connect");
    expect(token).toContain("User=my-user");
    expect(token).toContain("ResourceType=ServerlessCache");
    expect(token).toContain("X-Amz-Credential=");
    expect(token).toContain("X-Amz-Signature=");
    expect(token).toContain("X-Amz-Expires=900");
  });

  it("reports missing credentials without exposing token material", async () => {
    const missingCredentials = async () => {
      throw new CredentialsProviderError("Could not load credentials from any providers", {
        tryNextLink: false,
      });
    };

    await expect(
      generateIamAuthToken({
        cacheName: "my-cache",
        userId: "my-user",
        region: "us-east-1",
        credentials: missingCredentials,
      }),
    ).rejects.toThrow("No AWS credentials");
  });

  it("wraps unexpected credential and signing failures", async () => {
    const failingCredentials = async () => {
      throw new Error("secret=credential-material");
    };

    const error = await generateIamAuthToken({
      cacheName: "my-cache",
      userId: "my-user",
      region: "us-east-1",
      credentials: failingCredentials,
    }).catch((reason: unknown) => reason);

    expect(error).toHaveProperty("message", "Failed to generate IAM auth token");
    expect(String(error)).not.toContain("credential-material");
  });

  it("requests fresh credentials for every token", async () => {
    const credentialProvider = vi.fn(async () => credentials);

    await generateIamAuthToken({
      cacheName: "my-cache",
      userId: "my-user",
      region: "us-east-1",
      credentials: credentialProvider,
    });
    await generateIamAuthToken({
      cacheName: "my-cache",
      userId: "my-user",
      region: "us-east-1",
      credentials: credentialProvider,
    });

    expect(credentialProvider).toHaveBeenNthCalledWith(1, { forceRefresh: true });
    expect(credentialProvider).toHaveBeenNthCalledWith(2, { forceRefresh: true });
  });
});

// --- IamAuthTokenProvider tests ----------------------------------------------

describe("IamAuthTokenProvider", () => {
  it("mints a fresh token on every call", async () => {
    const credentialProvider = vi.fn(async () => credentials);
    const provider = new IamAuthTokenProvider({
      cacheName: "my-cache",
      userId: "my-user",
      region: "us-east-1",
      credentials: credentialProvider,
    });

    const first = await provider.getAuth();
    const second = await provider.getAuth();

    expect(first[0]).toBe("my-user");
    expect(first[1]).toContain("X-Amz-Signature=");
    expect(second[0]).toBe("my-user");
    expect(second[1]).toContain("X-Amz-Signature=");
    expect(credentialProvider).toHaveBeenCalledTimes(2);
  });

  it("can be passed directly to the connection", async () => {
    const provider = new IamAuthTokenProvider({
      cacheName: "my-cache",
      userId: "my-user",
      region: "us-east-1",
      credentials: async () => credentials,
    });
    const connection = new PhotonConnection("my-cache", 443, {
      tls: false,
      authProvider: provider,
    });
    const execute = vi.spyOn(connection, "execute").mockResolvedValue(Buffer.from("OK"));

    await connection.authenticate();

    expect(execute).toHaveBeenCalledWith("AUTH", Buffer.from("my-user"), expect.any(Buffer));
  });
});

// --- buildCacheAuthProvider tests --------------------------------------------

describe("buildCacheAuthProvider", () => {
  it("returns a connection-compatible callback", async () => {
    const provider = await buildCacheAuthProvider({
      cacheName: "my-cache",
      region: "eu-west-1",
      credentials: async () => credentials,
    });

    const [userId, token] = await provider();

    expect(userId).toBe(DEFAULT_USER_ID);
    expect(token).toContain("Action=connect");
    expect(token).toContain("X-Amz-Credential=");
  });

  it("resolves the region from the Node AWS configuration chain", async () => {
    vi.stubEnv("AWS_REGION", "ap-southeast-2");
    try {
      const provider = await buildCacheAuthProvider({
        cacheName: "my-cache",
        credentials: async () => credentials,
      });

      const [, token] = await provider();

      expect(token).toContain("ap-southeast-2");
    } finally {
      vi.unstubAllEnvs();
    }
  });
});
