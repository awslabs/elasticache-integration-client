import { describe, expect, it, vi } from "vitest";

import { constructStack } from "@smithy/core/client";
import type { AwsCredentialIdentity, AwsCredentialIdentityProvider } from "@smithy/types";

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
  ]) {
    mock.mockReset();
  }
  mocks.buildCacheAuthProvider.mockResolvedValue("auth-provider");
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
    expect(mocks.connection).toHaveBeenCalledWith("cache.example.com", 6379, {
      tls: false,
      requestTimeout: 1234,
      authProvider: "auth-provider",
    });
    expect(mocks.sessionConstructor).toHaveBeenCalledWith(expect.anything(), { timeout: 42 });
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
    expect(mocks.sessionConstructor).toHaveBeenCalledWith(expect.anything(), {});
  });

  it("passes reconnect policy to the session", async () => {
    setup();
    const reconnectPolicy = {
      maxReconnectAttempts: 2,
      baseDelayMs: 5,
      maxDelayMs: 20,
      circuitFailureThreshold: 4,
      circuitOpenDurationMs: 100,
    };

    await client("dynamodb", FakeClient, { ...options, reconnectPolicy });

    expect(mocks.sessionConstructor).toHaveBeenCalledWith(
      expect.any(Function),
      { timeout: 42 },
      reconnectPolicy,
    );
  });

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
