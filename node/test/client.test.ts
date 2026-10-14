import { describe, expect, it, vi } from "vitest";

import { constructStack } from "@smithy/core/client";
import type { AwsCredentialIdentityProvider } from "@smithy/types";

import { ElastiCacheIntegrationsConfigError } from "../src/errors.js";

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
  ElastiCacheIntegrationsConnector: class {
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
    ElastiCacheIntegrationsSession: class {
      constructor(...args: unknown[]) {
        mocks.sessionConstructor(...args);
        return mocks.session;
      }
    },
  };
});

vi.mock("../src/middleware.js", () => ({
  ElastiCacheIntegrationsHandle: class {
    constructor(...args: unknown[]) {
      mocks.handleConstructor(...args);
      mocks.handle.install();
      return mocks.handle;
    }
  },
}));

import { install } from "../src/client.js";

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

class FakeClient {
  readonly middlewareStack = constructStack<object, object>();
  readonly config: {
    credentials: AwsCredentialIdentityProvider;
    region: () => Promise<string>;
    serviceId: string;
  };
  readonly destroy = vi.fn();

  constructor({
    region = async () => "us-east-1",
    serviceId = "DynamoDB",
    clientCredentials = async () => credentials,
  }: {
    region?: () => Promise<string>;
    serviceId?: string;
    clientCredentials?: AwsCredentialIdentityProvider | null;
  } = {}) {
    this.config = {
      credentials: clientCredentials as AwsCredentialIdentityProvider,
      region,
      serviceId,
    };
  }
}

function setup() {
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
  userId: "iam-user",
  overrides: { timeout: 42 },
  connectorOptions: { tls: false, requestTimeout: 1234 },
};

describe("install", () => {
  it("wires up the supplied client", async () => {
    setup();
    const sdkClient = new FakeClient();

    await expect(install({ client: sdkClient, ...options })).resolves.toBeUndefined();

    expect(sdkClient.destroy).not.toHaveBeenCalled();
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
    expect(mocks.sessionConstructor).toHaveBeenCalledWith(
      expect.any(Function),
      { timeout: 42 },
      {
        stalenessDurationMs: 3_600_000,
      },
    );
    expect(mocks.session.open).toHaveBeenCalledOnce();
    expect(mocks.handleConstructor).toHaveBeenCalledWith(sdkClient, mocks.session, "dynamodb");
    expect(mocks.handle.install).toHaveBeenCalledOnce();
  });

  it("signs the cache token with the client's own credentials", async () => {
    setup();
    const sdkClient = new FakeClient();

    await install({ client: sdkClient, ...options });

    expect(mocks.buildCacheAuthProvider.mock.calls[0]![0].credentials).toBe(
      sdkClient.config.credentials,
    );
  });

  it("gives session ownership of managed auth", async () => {
    setup();

    await install({ client: new FakeClient(), ...options });

    const connectorFactory = mocks.sessionConstructor.mock.calls[0]![0] as { close?: () => void };
    connectorFactory.close?.();
    expect(generatedAuthProvider.close).toHaveBeenCalledOnce();
  });

  it("signs the cache token for the client's region", async () => {
    setup();

    await install({
      client: new FakeClient({ region: async () => "eu-west-1" }),
      endpoint: options.endpoint,
      cacheName: options.cacheName,
    });

    expect(mocks.buildCacheAuthProvider).toHaveBeenCalledWith({
      cacheName: "my-cache",
      credentials: expect.any(Function),
      region: "eu-west-1",
    });
  });

  it.each([
    ["DynamoDB", "dynamodb"],
    ["Secrets Manager", "secrets-manager"],
  ])("derives the service %s from the client as %s", async (serviceId, service) => {
    setup();
    const sdkClient = new FakeClient({ serviceId });

    await install({ client: sdkClient, ...options });

    expect(mocks.handleConstructor).toHaveBeenCalledWith(sdkClient, mocks.session, service);
  });

  it.each([
    // A service name no longer creates a client.
    ["a service name", () => "dynamodb"],
    ["a non-SDK object", () => ({})],
    // A client with no resolvable region cannot have its cache token signed.
    [
      "a client whose region cannot be resolved",
      () =>
        new FakeClient({
          region: async () => {
            throw new Error("Region is missing");
          },
        }),
    ],
    ["a client with an empty region", () => new FakeClient({ region: async () => "" })],
    // A client whose credentials cannot be read: the cache would get another principal.
    ["a client without credentials", () => new FakeClient({ clientCredentials: null })],
  ])("rejects clients it cannot use: %s", async (_, makeClient) => {
    setup();

    await expect(install({ client: makeClient() as never, ...options })).rejects.toThrow(
      ElastiCacheIntegrationsConfigError,
    );

    expect(mocks.buildCacheAuthProvider).not.toHaveBeenCalled();
    expect(mocks.sessionConstructor).not.toHaveBeenCalled();
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

    await install({ client: new FakeClient(), ...options, reconnectPolicy });

    expect(mocks.sessionConstructor).toHaveBeenCalledWith(
      expect.any(Function),
      { timeout: 42 },
      {
        ...reconnectPolicy,
        stalenessDurationMs: 3_600_000,
      },
    );
  });

  it("passes a custom IAM refresh interval to the managed auth provider", async () => {
    setup();

    await install({ client: new FakeClient(), ...options, iamRefreshAfterSeconds: 120 });

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

    await install({ client: new FakeClient(), ...options, fallbackDuration: 60 });

    expect(mocks.sessionConstructor).toHaveBeenCalledWith(
      expect.any(Function),
      { timeout: 42 },
      {
        fallbackDurationMs: 60_000,
        stalenessDurationMs: 3_600_000,
      },
    );
  });

  it.each([0.5, Number.MAX_SAFE_INTEGER])(
    "rejects unsupported fallback duration %s",
    async (fallbackDuration) => {
      setup();

      await expect(
        install({ client: new FakeClient(), ...options, fallbackDuration }),
      ).rejects.toThrow("fallbackDuration must be a whole number of seconds");
    },
  );

  it("uses a supplied authProvider instead of generating a token", async () => {
    setup();
    const authProvider = () => ["injected-user", "injected-token"] as [string, string];

    await install({
      client: new FakeClient(),
      endpoint: options.endpoint,
      cacheName: options.cacheName,
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
        stalenessDurationMs: 3_600_000,
      },
    );
  });

  it("plumbs a custom stalenessDuration to the session as milliseconds", async () => {
    setup();

    await install({ client: new FakeClient(), ...options, stalenessDuration: 120 });

    expect(mocks.sessionConstructor).toHaveBeenCalledWith(expect.anything(), expect.anything(), {
      stalenessDurationMs: 120_000,
    });
  });

  it.each([0, -1, 1.5, NaN, Infinity, 2 ** 53])(
    "rejects an invalid stalenessDuration of %s before connecting",
    async (stalenessDuration) => {
      setup();

      await expect(
        install({ client: new FakeClient(), ...options, stalenessDuration }),
      ).rejects.toThrow(ElastiCacheIntegrationsConfigError);
      expect(mocks.sessionConstructor).not.toHaveBeenCalled();
    },
  );

  it.each(["auth", "open", "install"])(
    "keeps the supplied client open when %s bring-up fails",
    async (failure) => {
      setup();
      const sdkClient = new FakeClient();
      const error = new Error(`${failure} failed`);
      if (failure === "auth") mocks.buildCacheAuthProvider.mockRejectedValueOnce(error);
      if (failure === "open") mocks.session.open.mockRejectedValueOnce(error);
      if (failure === "install")
        mocks.handle.install.mockImplementationOnce(() => {
          throw error;
        });

      await expect(install({ client: sdkClient, ...options })).rejects.toBe(error);

      expect(sdkClient.destroy).not.toHaveBeenCalled();
      expect(mocks.session.destroy).toHaveBeenCalledTimes(failure === "auth" ? 0 : 1);
      if (failure === "open") {
        expect(mocks.handleConstructor).not.toHaveBeenCalled();
      }
      await expect(install({ client: sdkClient, ...options })).resolves.toBeUndefined();
    },
  );

  it.each([
    ["an invalid fallbackDuration", { fallbackDuration: 1.5 }],
    ["a session that rejects its reconnect policy", {}],
  ])("closes the managed auth provider when install fails on %s", async (_, extra) => {
    setup();
    const error = new ElastiCacheIntegrationsConfigError("jitter must be in [0, 1)");
    if (!("fallbackDuration" in extra)) {
      mocks.sessionConstructor.mockImplementationOnce(() => {
        throw error;
      });
    }

    await expect(install({ client: new FakeClient(), ...options, ...extra })).rejects.toThrow(
      ElastiCacheIntegrationsConfigError,
    );

    expect(generatedAuthProvider.close).toHaveBeenCalledOnce();
  });
});
