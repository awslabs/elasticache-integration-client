import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

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

vi.mock("../src/connection.js", async () => ({
  ...(await vi.importActual("../src/connection.js")),
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
  installedHandle: () => undefined,
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

const caCertsPath = fileURLToPath(new URL("./fixtures/tls/localhost.crt", import.meta.url));

const options = {
  endpoint: "cache.example.com:6379",
  cacheName: "my-cache",
  userId: "iam-user",
  clientConfiguration: { cachePolicyOverrides: { timeout: 42 }, cacheRequestTimeout: 1.234 },
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
      resolvedCredentials: credentials,
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
      requestTimeout: 1234,
      connectionTimeout: 5_000,
      verify: true,
      authProvider: generatedAuthProvider,
    });
    expect(mocks.sessionConstructor).toHaveBeenCalledWith(
      expect.any(Function),
      { timeout: 42 },
      {
        connectionTimeoutMs: 5_000,
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

  it("resolves the client's credentials once during install", async () => {
    setup();
    const clientCredentials = vi.fn(async () => credentials);

    await install({ client: new FakeClient({ clientCredentials }), ...options });

    expect(clientCredentials).toHaveBeenCalledOnce();
    expect(mocks.buildCacheAuthProvider.mock.calls[0]![0].resolvedCredentials).toBe(credentials);
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
      resolvedCredentials: credentials,
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

  it("rejects a client whose credentials cannot be resolved, with the SDK's error as the cause", async () => {
    setup();
    const sdkError = new Error("Could not load credentials from any providers");
    const client = new FakeClient({
      clientCredentials: async () => {
        throw sdkError;
      },
    });

    const error = await install({ client: client as never, ...options }).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ElastiCacheIntegrationsConfigError);
    expect((error as Error).message).toBe(
      "This AWS SDK client carries no credentials, so the cache cannot be authenticated.",
    );
    expect((error as Error).cause).toBe(sdkError);
    expect(mocks.buildCacheAuthProvider).not.toHaveBeenCalled();
    expect(mocks.sessionConstructor).not.toHaveBeenCalled();
  });

  it("passes iamTokenRefreshDelay to the managed auth provider", async () => {
    setup();

    await install({
      client: new FakeClient(),
      ...options,
      clientConfiguration: { ...options.clientConfiguration, iamTokenRefreshDelay: 120 },
    });

    expect(mocks.buildCacheAuthProvider).toHaveBeenCalledWith({
      cacheName: "my-cache",
      credentials: expect.any(Function),
      resolvedCredentials: credentials,
      refreshAfterSeconds: 120,
      region: "us-east-1",
      userId: "iam-user",
    });
  });

  it("passes the fallbackDuration to the session", async () => {
    setup();

    await install({
      client: new FakeClient(),
      ...options,
      clientConfiguration: { ...options.clientConfiguration, fallbackDuration: 60 },
    });

    expect(mocks.sessionConstructor).toHaveBeenCalledWith(
      expect.any(Function),
      { timeout: 42 },
      {
        fallbackDurationMs: 60_000,
        connectionTimeoutMs: 5_000,
        stalenessDurationMs: 3_600_000,
      },
    );
  });

  it.each([
    [undefined, 5_000],
    [1.5, 1_500],
  ])("passes connectionTimeout %s to the session", async (requested, expected) => {
    setup();

    await install({
      client: new FakeClient(),
      ...options,
      clientConfiguration: {
        ...options.clientConfiguration,
        ...(requested === undefined ? {} : { connectionTimeout: requested }),
      },
    });

    expect(mocks.sessionConstructor).toHaveBeenCalledWith(
      expect.any(Function),
      { timeout: 42 },
      expect.objectContaining({ connectionTimeoutMs: expected }),
    );
  });

  it.each([
    ["cacheRequestTimeout", 0, "cacheRequestTimeout must be a positive number of seconds"],
    ["cacheRequestTimeout", true, "cacheRequestTimeout must be a positive number of seconds"],
    ["cacheRequestTimeout", NaN, "cacheRequestTimeout must be a positive number of seconds"],
    ["cacheRequestTimeout", Infinity, "cacheRequestTimeout must be a positive number of seconds"],
    [
      "cacheRequestTimeout",
      2_147_484,
      "cacheRequestTimeout must be a positive number of seconds, at most 2147483",
    ],
    ["connectionTimeout", 0, "connectionTimeout must be a positive number of seconds"],
    ["connectionTimeout", -1, "connectionTimeout must be a positive number of seconds"],
    ["connectionTimeout", NaN, "connectionTimeout must be a positive number of seconds"],
    ["connectionTimeout", Infinity, "connectionTimeout must be a positive number of seconds"],
    ["connectionTimeout", true, "connectionTimeout must be a positive number of seconds"],
    ["connectionTimeout", "2", "connectionTimeout must be a positive number of seconds"],
    [
      "connectionTimeout",
      2_147_484,
      "connectionTimeout must be a positive number of seconds, at most 2147483",
    ],
    ["fallbackDuration", 0.5, "fallbackDuration must be a whole number of seconds"],
    ["fallbackDuration", true, "fallbackDuration must be a whole number of seconds"],
    ["fallbackDuration", "60", "fallbackDuration must be a whole number of seconds"],
    [
      "fallbackDuration",
      Number.MAX_SAFE_INTEGER,
      "fallbackDuration must be a whole number of seconds",
    ],
    ["cachePolicyOverrides", [], "cachePolicyOverrides must be an object or undefined"],
    ["cachePolicyOverrides", { item_ttl: 60n }, "cachePolicyOverrides must be JSON-serializable"],
    [
      "cachePolicyOverrides",
      { item_ttl: Infinity },
      "cachePolicyOverrides must be JSON-serializable",
    ],
    ["cachePolicyTimeout", 0, "cachePolicyTimeout must be a positive whole number of seconds"],
    ["cachePolicyTimeout", -1, "cachePolicyTimeout must be a positive whole number of seconds"],
    ["cachePolicyTimeout", 1.5, "cachePolicyTimeout must be a positive whole number of seconds"],
    ["cachePolicyTimeout", NaN, "cachePolicyTimeout must be a positive whole number of seconds"],
    [
      "cachePolicyTimeout",
      Infinity,
      "cachePolicyTimeout must be a positive whole number of seconds",
    ],
    [
      "cachePolicyTimeout",
      2 ** 53,
      "cachePolicyTimeout must be a positive whole number of seconds",
    ],
    ["cachePolicyTimeout", "600", "cachePolicyTimeout must be a positive whole number of seconds"],
    [
      "iamTokenRefreshDelay",
      0,
      "iamTokenRefreshDelay must be a number of seconds greater than 0 and less than 880",
    ],
    [
      "iamTokenRefreshDelay",
      true,
      "iamTokenRefreshDelay must be a number of seconds greater than 0 and less than 880",
    ],
    [
      "iamTokenRefreshDelay",
      NaN,
      "iamTokenRefreshDelay must be a number of seconds greater than 0 and less than 880",
    ],
    [
      "iamTokenRefreshDelay",
      Infinity,
      "iamTokenRefreshDelay must be a number of seconds greater than 0 and less than 880",
    ],
    [
      "iamTokenRefreshDelay",
      880,
      "iamTokenRefreshDelay must be a number of seconds greater than 0 and less than 880",
    ],
    [
      "iamTokenRefreshDelay",
      "300",
      "iamTokenRefreshDelay must be a number of seconds greater than 0 and less than 880",
    ],
  ])("clientConfiguration rejects unusable values: %s = %s", async (field, bad, message) => {
    setup();

    await expect(
      install({
        client: new FakeClient(),
        ...options,
        clientConfiguration: { [field]: bad } as never,
      }),
    ).rejects.toThrow(message);
    expect(mocks.buildCacheAuthProvider).not.toHaveBeenCalled();
    expect(mocks.sessionConstructor).not.toHaveBeenCalled();
  });

  it.each([
    [undefined, 5_000],
    [1.25, 1_250],
  ])("passes cacheRequestTimeout %s to the connector factory", async (requested, expected) => {
    setup();

    await install({
      client: new FakeClient(),
      endpoint: options.endpoint,
      cacheName: options.cacheName,
      ...(requested === undefined
        ? {}
        : { clientConfiguration: { cacheRequestTimeout: requested } }),
    });

    const connectorFactory = mocks.sessionConstructor.mock.calls[0]![0] as () => unknown;
    connectorFactory();
    expect(mocks.connection.mock.calls[0]![2]).toMatchObject({ requestTimeout: expected });
  });

  it("passes cachePolicyOverrides to the session", async () => {
    setup();

    await install({
      client: new FakeClient(),
      ...options,
      clientConfiguration: { cachePolicyOverrides: { tables: ["t"] } },
    });

    expect(mocks.sessionConstructor.mock.calls[0]![1]).toEqual({ tables: ["t"] });
  });

  it("copies cachePolicyOverrides before passing them to the session", async () => {
    setup();
    const overrides = { tables: ["t"] };

    await install({
      client: new FakeClient(),
      ...options,
      clientConfiguration: { cachePolicyOverrides: overrides },
    });
    overrides.tables.push("other");

    expect(mocks.sessionConstructor.mock.calls[0]![1]).toEqual({ tables: ["t"] });
  });

  it("reads the caCerts file and passes verify and its contents to the connector", async () => {
    setup();

    await install({
      client: new FakeClient(),
      endpoint: options.endpoint,
      cacheName: options.cacheName,
      verify: false,
      caCerts: caCertsPath,
    });

    const connectorFactory = mocks.sessionConstructor.mock.calls[0]![0] as () => unknown;
    connectorFactory();
    expect(mocks.connection.mock.calls[0]![2]).toMatchObject({
      verify: false,
      caCerts: readFileSync(caCertsPath),
    });
  });

  it("rejects a caCerts path that cannot be read", async () => {
    setup();

    await expect(
      install({
        client: new FakeClient(),
        ...options,
        caCerts: "/no/such/ca-bundle.pem",
      }),
    ).rejects.toThrow(
      new ElastiCacheIntegrationsConfigError(
        "caCerts must be the path of a readable CA bundle file, got /no/such/ca-bundle.pem",
      ),
    );
    expect(mocks.buildCacheAuthProvider).not.toHaveBeenCalled();
    expect(mocks.sessionConstructor).not.toHaveBeenCalled();
  });

  it.each([
    "fallbackDuration",
    "overrides",
    "stalenessDuration",
    "iamRefreshAfterSeconds",
    "connectorOptions",
    "reconnectPolicy",
  ])("rejects the unknown install option %s", async (name) => {
    setup();

    await expect(
      install({ client: new FakeClient(), ...options, [name]: 0 } as never),
    ).rejects.toThrow(
      new ElastiCacheIntegrationsConfigError(`install() got an unexpected option '${name}'`),
    );
    expect(mocks.buildCacheAuthProvider).not.toHaveBeenCalled();
    expect(mocks.sessionConstructor).not.toHaveBeenCalled();
  });

  it("rejects an unknown clientConfiguration field", async () => {
    setup();

    await expect(
      install({
        client: new FakeClient(),
        ...options,
        clientConfiguration: { fallbackDurationSeconds: 0 } as never,
      }),
    ).rejects.toThrow(
      new ElastiCacheIntegrationsConfigError(
        "clientConfiguration got an unexpected field 'fallbackDurationSeconds'",
      ),
    );
    expect(mocks.buildCacheAuthProvider).not.toHaveBeenCalled();
    expect(mocks.sessionConstructor).not.toHaveBeenCalled();
  });

  it.each([0, "", "false", null])("rejects a non-boolean verify %j", async (verify) => {
    setup();

    await expect(
      install({ client: new FakeClient(), ...options, verify: verify as never }),
    ).rejects.toThrow(
      new ElastiCacheIntegrationsConfigError(
        `verify must be a boolean, got ${typeof verify === "object" ? "null" : typeof verify}`,
      ),
    );
    expect(mocks.buildCacheAuthProvider).not.toHaveBeenCalled();
    expect(mocks.sessionConstructor).not.toHaveBeenCalled();
  });

  it("treats a null clientConfiguration as all defaults", async () => {
    setup();

    await install({
      client: new FakeClient(),
      endpoint: options.endpoint,
      cacheName: options.cacheName,
      clientConfiguration: null,
    });

    const connectorFactory = mocks.sessionConstructor.mock.calls[0]![0] as () => unknown;
    connectorFactory();
    expect(mocks.connection.mock.calls[0]![2]).toMatchObject({ requestTimeout: 5_000 });
    expect(mocks.sessionConstructor.mock.calls[0]![2]).toEqual({
      connectionTimeoutMs: 5_000,
      stalenessDurationMs: 3_600_000,
    });
  });

  it("rejects a clientConfiguration that is not an object", async () => {
    setup();

    await expect(
      install({ client: new FakeClient(), ...options, clientConfiguration: "fast" as never }),
    ).rejects.toThrow(
      new ElastiCacheIntegrationsConfigError(
        "clientConfiguration must be an object, null, or undefined, got string",
      ),
    );
    expect(mocks.sessionConstructor).not.toHaveBeenCalled();
  });

  it("verifies the cache's TLS certificate by default", async () => {
    setup();

    await install({
      client: new FakeClient(),
      endpoint: options.endpoint,
      cacheName: options.cacheName,
    });

    const connectorFactory = mocks.sessionConstructor.mock.calls[0]![0] as () => unknown;
    connectorFactory();
    expect(mocks.connection.mock.calls[0]![2]).toMatchObject({ verify: true });
  });

  it.each([
    [undefined, 3_600_000],
    [600, 600_000],
  ])("passes the cachePolicyTimeout %s to the session", async (requested, expected) => {
    setup();

    await install({
      client: new FakeClient(),
      endpoint: options.endpoint,
      cacheName: options.cacheName,
      ...(requested === undefined
        ? {}
        : { clientConfiguration: { cachePolicyTimeout: requested } }),
    });

    expect(mocks.sessionConstructor).toHaveBeenCalledWith(expect.anything(), expect.anything(), {
      connectionTimeoutMs: 5_000,
      stalenessDurationMs: expected,
    });
  });

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

  it("closes the auth provider when the session rejects its reconnect policy", async () => {
    setup();
    mocks.sessionConstructor.mockImplementationOnce(() => {
      throw new ElastiCacheIntegrationsConfigError("jitter must be in [0, 1)");
    });

    await expect(install({ client: new FakeClient(), ...options })).rejects.toThrow(
      ElastiCacheIntegrationsConfigError,
    );

    expect(generatedAuthProvider.close).toHaveBeenCalledOnce();
  });
});
