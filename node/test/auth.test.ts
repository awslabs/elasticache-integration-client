import { describe, expect, it, vi } from "vitest";

import { CredentialsProviderError } from "@smithy/core/config";

import {
  DEFAULT_USER_ID,
  IamAuthTokenProvider,
  buildCacheAuthProvider,
  generateIamAuthToken,
  resolveAuthParams,
} from "../src/auth.js";
import { ElastiCacheIntegrationsConnector } from "../src/connection.js";

const credentials = {
  accessKeyId: "AKIAIOSFODNN7EXAMPLE",
  secretAccessKey: "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
  sessionToken: "FwoGZXIvYXdzEBYaDHqa0AP",
};

const rotatedCredentials = {
  accessKeyId: "ASIAROTATEDCREDENTIAL",
  secretAccessKey: "rotated-secret-access-key",
  sessionToken: "rotated-session-token",
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

  it("normalizes credential provider failures without exposing details", async () => {
    const failingCredentials = async () => {
      throw new Error("secret=credential-material");
    };

    const error = await generateIamAuthToken({
      cacheName: "my-cache",
      userId: "my-user",
      region: "us-east-1",
      credentials: failingCredentials,
    }).catch((reason: unknown) => reason);

    expect(error).toHaveProperty(
      "message",
      "No AWS credentials available for IAM auth token generation",
    );
    expect(String(error)).not.toContain("credential-material");
  });

  it("uses the toolkit validation and lowercase normalization", async () => {
    const token = await generateIamAuthToken({
      cacheName: "My-Cache",
      userId: "IAM-User",
      region: "us-east-1",
      credentials: async () => credentials,
    });

    expect(token).toMatch(/^my-cache\//);
    expect(token).toContain("User=iam-user");

    await expect(
      generateIamAuthToken({
        cacheName: "bad/cache",
        userId: "iam-user",
        region: "us-east-1",
        credentials: async () => credentials,
      }),
    ).rejects.toThrow("serverlessCacheName");
  });

  it("reads the SDK client's credentials without forcing a refresh", async () => {
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

    expect(credentialProvider).toHaveBeenNthCalledWith(1);
    expect(credentialProvider).toHaveBeenNthCalledWith(2);
  });
});

// --- IamAuthTokenProvider tests ----------------------------------------------

describe("IamAuthTokenProvider", () => {
  it("mints a fresh token for every authentication call", async () => {
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

  it("can be passed directly to the connector", async () => {
    const provider = new IamAuthTokenProvider({
      cacheName: "my-cache",
      userId: "my-user",
      region: "us-east-1",
      credentials: async () => credentials,
    });
    const connector = new ElastiCacheIntegrationsConnector("my-cache", 443, {
      tls: false,
      authProvider: provider,
    });
    const execute = vi
      .spyOn(connector, "execute")
      .mockResolvedValue(new Map([[Buffer.from("proto"), 3]]));

    await connector.handshake();

    expect(execute).toHaveBeenCalledWith(
      "HELLO",
      Buffer.from("3"),
      Buffer.from("AUTH"),
      Buffer.from("my-user"),
      expect.any(Buffer),
    );
  });
});

// --- buildCacheAuthProvider tests --------------------------------------------

describe("buildCacheAuthProvider", () => {
  it("returns a connector-compatible callback", async () => {
    const provider = await buildCacheAuthProvider({
      cacheName: "my-cache",
      region: "eu-west-1",
      credentials: async () => credentials,
    });

    const [userId, token] = await provider();

    expect(userId).toBe(DEFAULT_USER_ID);
    expect(token).toContain("Action=connect");
    expect(token).toContain("X-Amz-Credential=");
    provider.close();
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
      provider.close();
    } finally {
      vi.unstubAllEnvs();
    }
  });

  it("caches the token and re-reads rotated credentials on refresh", async () => {
    const clientCredentials = vi
      .fn()
      .mockResolvedValueOnce(credentials)
      .mockResolvedValueOnce(rotatedCredentials);

    const provider = await buildCacheAuthProvider({
      cacheName: "my-cache",
      region: "us-east-1",
      credentials: clientCredentials,
    });

    const [, firstToken] = await provider();
    const [, cachedToken] = await provider();
    const [, refreshedToken] = await provider.refreshAuth();

    expect(clientCredentials).toHaveBeenCalledTimes(2);
    expect(clientCredentials).toHaveBeenNthCalledWith(1);
    expect(clientCredentials).toHaveBeenNthCalledWith(2);
    expect(firstToken).toContain(credentials.accessKeyId);
    expect(firstToken).not.toContain(rotatedCredentials.accessKeyId);
    expect(cachedToken).toBe(firstToken);
    expect(refreshedToken).toContain(rotatedCredentials.accessKeyId);
    expect(refreshedToken).not.toContain(credentials.accessKeyId);
    expect(refreshedToken).not.toBe(firstToken);
    provider.close();
  });

  it("signs the first token with already-resolved credentials, then re-reads them", async () => {
    const clientCredentials = vi.fn().mockResolvedValue(rotatedCredentials);

    const provider = await buildCacheAuthProvider({
      cacheName: "my-cache",
      region: "us-east-1",
      credentials: clientCredentials,
      resolvedCredentials: credentials,
    });

    const [, firstToken] = await provider();
    expect(clientCredentials).not.toHaveBeenCalled();
    const [, refreshedToken] = await provider.refreshAuth();

    expect(firstToken).toContain(credentials.accessKeyId);
    expect(refreshedToken).toContain(rotatedCredentials.accessKeyId);
    expect(clientCredentials).toHaveBeenCalledExactlyOnceWith();
    provider.close();
  });

  it("installs a background-refreshed token for later authentication calls", async () => {
    vi.useFakeTimers();
    const credentialProvider = vi
      .fn()
      .mockResolvedValueOnce(credentials)
      .mockResolvedValue(rotatedCredentials);
    const provider = await buildCacheAuthProvider({
      cacheName: "my-cache",
      region: "us-east-1",
      credentials: credentialProvider,
    });

    try {
      const [, firstToken] = await provider();

      await vi.advanceTimersByTimeAsync(300_000);
      const [, refreshedToken] = await provider();

      expect(firstToken).toContain(credentials.accessKeyId);
      expect(refreshedToken).toContain(rotatedCredentials.accessKeyId);
      expect(refreshedToken).not.toBe(firstToken);
      expect(credentialProvider).toHaveBeenCalledTimes(2);
    } finally {
      provider.close();
      vi.useRealTimers();
    }
  });

  it("rejects a refresh interval outside the toolkit safety margin", async () => {
    await expect(
      buildCacheAuthProvider({
        cacheName: "my-cache",
        region: "us-east-1",
        credentials: async () => credentials,
        refreshAfterSeconds: 880,
      }),
    ).rejects.toThrow("refreshAfterSeconds");
  });
});
