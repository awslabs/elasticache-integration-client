import { describe, expect, it, vi } from "vitest";

import { constructStack } from "@smithy/core/client";
import type { AwsCredentialIdentity, AwsCredentialIdentityProvider } from "@smithy/types";

import { PhotonError } from "../src/errors.js";

const mocks = vi.hoisted(() => ({
  buildCacheAuthProvider: vi.fn(),
  connection: vi.fn(),
  session: { open: vi.fn(), destroy: vi.fn() },
  sessionConstructor: vi.fn(),
  handle: { install: vi.fn() },
  handleConstructor: vi.fn(),
}));

vi.mock("../src/auth.js", () => ({
  buildCacheAuthProvider: mocks.buildCacheAuthProvider,
}));

vi.mock("../src/connection.js", () => ({
  PhotonConnector: class {
    constructor(...args: unknown[]) {
      mocks.connection(...args);
    }
  },
}));

vi.mock("../src/session.js", async () => {
  const actual = (await vi.importActual("../src/session.js")) as {
    parseEndpoint: (endpoint: string) => [string, number];
  };
  return {
    ...actual,
    PhotonSession: class {
      constructor(...args: unknown[]) {
        mocks.sessionConstructor(...args);
        return mocks.session;
      }
    },
  };
});

vi.mock("../src/middleware.js", () => ({
  PhotonHandle: class {
    constructor(...args: unknown[]) {
      mocks.handleConstructor(...args);
      mocks.handle.install();
      return mocks.handle;
    }
  },
}));

import { client } from "../src/client.js";

type FakeConfig = {
  region?: string;
  credentials?: AwsCredentialIdentity;
};

const credentials = {
  accessKeyId: "AKIAEXAMPLE",
  secretAccessKey: "secret-example",
};

const generatedAuthProvider = Object.assign(
  vi.fn(async () => ["iam-user", "iam-token"] as [string, string]),
  {
    refreshAuth: vi.fn(async () => ["iam-user", "refreshed-token"] as [string, string]),
    close: vi.fn(),
  },
);

const createdClients: FakeClient[] = [];

class FakeClient {
  readonly middlewareStack = constructStack<object, object>();
  readonly config: { credentials: AwsCredentialIdentityProvider };
  readonly constructorConfig: FakeConfig;
  readonly destroy = vi.fn();

  constructor(config: FakeConfig) {
    this.constructorConfig = config;
    const credentials = config.credentials!;
    this.config = { credentials: async () => credentials };
    createdClients.push(this);
  }
}

function setup() {
  createdClients.length = 0;
  for (const mock of [
    mocks.buildCacheAuthProvider,
    mocks.connection,
    mocks.session.open,
    mocks.session.destroy,
    mocks.sessionConstructor,
    mocks.handle.install,
    mocks.handleConstructor,
    generatedAuthProvider,
    generatedAuthProvider.refreshAuth,
    generatedAuthProvider.close,
  ]) {
    mock.mockReset();
  }
  generatedAuthProvider.mockResolvedValue(["iam-user", "iam-token"]);
  generatedAuthProvider.refreshAuth.mockResolvedValue(["iam-user", "refreshed-token"]);
  mocks.buildCacheAuthProvider.mockResolvedValue(generatedAuthProvider);
  mocks.session.open.mockResolvedValue(undefined);
}

const options = {
  endpoint: "cache.example.com:6379",
  cacheName: "my-cache",
  region: "us-east-1",
  userId: "iam-user",
  overrides: { timeout: 42 },
  clientConfig: { credentials },
  connectorOptions: { tls: false, requestTimeout: 1234 },
};

describe("client", () => {
  it("creates an ordinary SDK client and initializes Photon before returning", async () => {
    setup();

    const sdkClient = await client("dynamodb", FakeClient, options);

    expect(sdkClient).toBeInstanceOf(FakeClient);
    expect(sdkClient.constructorConfig).toEqual({
      credentials,
      region: "us-east-1",
    });
    expect(mocks.buildCacheAuthProvider).toHaveBeenCalledWith({
      cacheName: "my-cache",
      credentials: expect.any(Function),
      region: "us-east-1",
      userId: "iam-user",
    });
    const authOptions = mocks.buildCacheAuthProvider.mock.calls[0]![0];
    await expect(authOptions.credentials()).resolves.toBe(credentials);
    const connectorFactory = mocks.sessionConstructor.mock.calls[0]![0] as (() => unknown) & {
      close?: () => void;
    };
    connectorFactory();
    expect(mocks.connection).toHaveBeenCalledWith("cache.example.com", 6379, {
      tls: false,
      requestTimeout: 1234,
      authProvider: generatedAuthProvider,
    });
    connectorFactory.close?.();
    expect(generatedAuthProvider.close).toHaveBeenCalledOnce();
    expect(mocks.sessionConstructor).toHaveBeenCalledWith(
      expect.any(Function),
      { timeout: 42 },
      {
        configStalenessThresholdMs: 3_600_000,
      },
    );
    expect(mocks.session.open).toHaveBeenCalledOnce();
    expect(mocks.handleConstructor).toHaveBeenCalledWith(sdkClient, mocks.session, "dynamodb");
    expect(mocks.handle.install).toHaveBeenCalledOnce();
  });

  it("allows region and userId to resolve from the environment", async () => {
    setup();

    const sdkClient = await client("dynamodb", FakeClient, {
      endpoint: options.endpoint,
      cacheName: options.cacheName,
      clientConfig: { credentials },
    });

    expect(sdkClient.constructorConfig).toEqual({
      credentials,
    });
    expect(mocks.buildCacheAuthProvider).toHaveBeenCalledWith({
      cacheName: "my-cache",
      credentials: expect.any(Function),
    });
    expect(mocks.sessionConstructor).toHaveBeenCalledWith(
      expect.any(Function),
      {},
      {
        configStalenessThresholdMs: 3_600_000,
      },
    );
  });

  it("passes reconnect policy to the session", async () => {
    setup();
    const reconnectPolicy = {
      connectAttempts: 2,
      connectionTimeoutMs: 50,
      baseDelayMs: 5,
      maxDelayMs: 20,
      jitter: 0.1,
    };

    await client("dynamodb", FakeClient, { ...options, reconnectPolicy });

    expect(mocks.sessionConstructor).toHaveBeenCalledWith(
      expect.any(Function),
      { timeout: 42 },
      {
        ...reconnectPolicy,
        configStalenessThresholdMs: 3_600_000,
      },
    );
  });

  it("passes a custom IAM refresh interval to the managed auth provider", async () => {
    setup();

    await client("dynamodb", FakeClient, {
      ...options,
      iamRefreshAfterSeconds: 120,
    });

    expect(mocks.buildCacheAuthProvider).toHaveBeenCalledWith({
      cacheName: "my-cache",
      credentials: expect.any(Function),
      refreshAfterSeconds: 120,
      region: "us-east-1",
      userId: "iam-user",
    });
  });

  it("converts the public fallback duration from seconds to milliseconds", async () => {
    setup();

    await client("dynamodb", FakeClient, { ...options, fallbackDuration: 60 });

    expect(mocks.sessionConstructor).toHaveBeenCalledWith(
      expect.any(Function),
      { timeout: 42 },
      {
        fallbackDurationMs: 60_000,
        configStalenessThresholdMs: 3_600_000,
      },
    );
  });

  it.each([0.5, Number.MAX_SAFE_INTEGER])(
    "rejects unsupported fallback duration %s",
    async (fallbackDuration) => {
      setup();

      await expect(
        client("dynamodb", FakeClient, { ...options, fallbackDuration }),
      ).rejects.toThrow("fallbackDuration must be a whole number of seconds");
    },
  );

  it("uses a supplied authProvider instead of generating a token", async () => {
    setup();
    const authProvider = () => ["injected-user", "injected-token"] as [string, string];

    await client("dynamodb", FakeClient, {
      endpoint: options.endpoint,
      cacheName: options.cacheName,
      clientConfig: { credentials },
      connectorOptions: { tls: false, authProvider },
    });

    expect(mocks.buildCacheAuthProvider).not.toHaveBeenCalled();
    const connectorFactory = mocks.sessionConstructor.mock.calls[0]![0] as () => unknown;
    connectorFactory();
    expect(mocks.connection).toHaveBeenCalledWith("cache.example.com", 6379, {
      tls: false,
      authProvider,
    });
    expect(mocks.sessionConstructor).toHaveBeenCalledWith(
      expect.any(Function),
      {},
      {
        configStalenessThresholdMs: 3_600_000,
      },
    );
  });

  it("plumbs a custom configStalenessThreshold to the session as milliseconds", async () => {
    setup();

    await client("dynamodb", FakeClient, { ...options, configStalenessThreshold: 120 });

    expect(mocks.sessionConstructor).toHaveBeenCalledWith(expect.anything(), expect.anything(), {
      configStalenessThresholdMs: 120_000,
    });
  });

  it.each([0, -1, 1.5, NaN, Infinity, 2 ** 53])(
    "rejects an invalid configStalenessThreshold of %s before creating the SDK client",
    async (configStalenessThreshold) => {
      setup();

      await expect(
        client("dynamodb", FakeClient, { ...options, configStalenessThreshold }),
      ).rejects.toThrow(PhotonError);
      expect(createdClients).toHaveLength(0);
      expect(mocks.sessionConstructor).not.toHaveBeenCalled();
    },
  );

  it.each(["auth", "open", "install"])(
    "cleans up owned resources when %s initialization fails",
    async (failure) => {
      setup();
      const error = new Error(`${failure} failed`);
      if (failure === "auth") mocks.buildCacheAuthProvider.mockRejectedValue(error);
      if (failure === "open") mocks.session.open.mockRejectedValue(error);
      if (failure === "install")
        mocks.handle.install.mockImplementation(() => {
          throw error;
        });

      await expect(client("dynamodb", FakeClient, options)).rejects.toBe(error);

      expect(createdClients[0]?.destroy).toHaveBeenCalledOnce();
      expect(mocks.session.destroy).toHaveBeenCalledTimes(failure === "auth" ? 0 : 1);
      if (failure === "open") {
        expect(mocks.handleConstructor).not.toHaveBeenCalled();
      }
    },
  );
});
