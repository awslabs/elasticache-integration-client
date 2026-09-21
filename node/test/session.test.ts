import { describe, expect, it, vi } from "vitest";

import {
  PhotonCacheUnavailable,
  PhotonConfigError,
  PhotonConnectionError,
  PhotonConnectionLost,
  PhotonError,
} from "../src/errors.js";
import { PhotonConnector } from "../src/connection.js";
import { PhotonRequest, PhotonResponse, RequestMetadata, ResolvedConfig } from "../src/models.js";
import { PhotonSession, parseEndpoint } from "../src/session.js";
import { MockCache, MockTcpProxy } from "./mock-cache.js";

function fakeConnector(overrides: Partial<PhotonConnector> = {}): PhotonConnector {
  return {
    connect: vi.fn(async () => undefined),
    authenticate: vi.fn(async () => undefined),
    execute: vi.fn(async (command: string) =>
      command === "photon.config"
        ? Buffer.from(
            '{"version":"v1","refresh_interval":60,"predicates":[{"expr":"true","vars":{}}]}',
          )
        : [200, Buffer.from("content-type:application/json"), Buffer.from("ok")],
    ),
    close: vi.fn(async () => undefined),
    destroy: vi.fn(() => undefined),
    ...overrides,
  } as unknown as PhotonConnector;
}

function controlledConnector(
  options: {
    predicate?: string;
    connect?: () => Promise<void>;
    authenticate?: () => Promise<void>;
    config?: () => Promise<unknown>;
    request?: () => Promise<unknown>;
  } = {},
) {
  let dead = false;
  let disconnectNotified = false;
  const notify = () => {
    if (disconnectNotified) return;
    disconnectNotified = true;
    connector.onDisconnect?.();
  };
  const connector = {
    onDisconnect: undefined as (() => void) | undefined,
    connect: vi.fn(async () => {
      await options.connect?.();
      dead = false;
    }),
    authenticate: vi.fn(async () => {
      await options.authenticate?.();
    }),
    execute: vi.fn(async (command: string) => {
      if (command === "photon.config") {
        if (options.config !== undefined) return options.config();
        return Buffer.from(
          JSON.stringify({
            predicates: [{ expr: options.predicate ?? "true", vars: {} }],
          }),
        );
      }
      return (
        (await options.request?.()) ?? [
          200,
          Buffer.from("content-type:application/json"),
          Buffer.from("ok"),
        ]
      );
    }),
    close: vi.fn(async () => {
      dead = true;
      notify();
    }),
    destroy: vi.fn(() => {
      dead = true;
      notify();
    }),
    get isDead() {
      return dead;
    },
    get isReady() {
      return !dead;
    },
  } as unknown as PhotonConnector;
  return {
    connector,
    fail: () => {
      dead = true;
      notify();
    },
  };
}

function testRequest(): PhotonRequest {
  return new PhotonRequest({
    method: "GET",
    url: "https://x/",
    body: "{}",
    metadata: new RequestMetadata({ service: "svc", operation: "GetItem" }),
  });
}

async function waitForState(session: PhotonSession, state: string): Promise<void> {
  await vi.waitFor(() => {
    expect(session.connectionState).toBe(state);
  });
}

describe("PhotonSession", () => {
  it("reports whether the session is open", async () => {
    const session = new PhotonSession(fakeConnector());

    expect(session.isOpen).toBe(false);
    await session.open();
    expect(session.isOpen).toBe(true);
    await session.close();
    expect(session.isOpen).toBe(false);
  });

  it("opens in lifecycle order and stores the resolved config", async () => {
    const connector = fakeConnector();
    const session = new PhotonSession(connector, { ttl: 300 });
    expect(session.config).toBeUndefined();

    await session.open();

    expect(connector.connect).toHaveBeenCalledOnce();
    expect(connector.authenticate).toHaveBeenCalledOnce();
    expect(connector.execute).toHaveBeenCalledWith("photon.config", Buffer.from('{"ttl":300}'));
    expect(session.config).toEqual(expect.any(ResolvedConfig));
    expect(session.config.raw).toEqual({
      version: "v1",
      refresh_interval: 60,
      predicates: [{ expr: "true", vars: {} }],
    });
    const connectOrder = (connector.connect as ReturnType<typeof vi.fn>).mock
      .invocationCallOrder[0];
    const authenticateOrder = (connector.authenticate as ReturnType<typeof vi.fn>).mock
      .invocationCallOrder[0];
    const executeOrder = (connector.execute as ReturnType<typeof vi.fn>).mock
      .invocationCallOrder[0];
    if (
      connectOrder === undefined ||
      authenticateOrder === undefined ||
      executeOrder === undefined
    ) {
      throw new Error("expected lifecycle methods to be called");
    }
    expect(connectOrder).toBeLessThan(authenticateOrder);
    expect(authenticateOrder).toBeLessThan(executeOrder);
  });

  it("serializes requests, delegates execution, and parses PhotonResponse", async () => {
    const connector = fakeConnector();
    const session = new PhotonSession(connector);
    await session.open();
    const request = new PhotonRequest({
      method: "POST",
      url: "https://x/",
      body: '{"value":"body"}',
      metadata: new RequestMetadata({ service: "svc", operation: "Test" }),
    });

    await expect(session.request(request)).resolves.toEqual(
      new PhotonResponse({
        statusCode: 200,
        headers: { "content-type": "application/json" },
        body: Buffer.from("ok"),
      }),
    );
    expect(connector.execute).toHaveBeenLastCalledWith(
      "photon.request",
      Buffer.from("POST"),
      Buffer.from("https://x/"),
      Buffer.alloc(0),
      Buffer.from('{"value":"body"}'),
      Buffer.from("service=svc&op=Test"),
    );
  });

  it("declines unmatched requests without a cache round trip", async () => {
    const connector = fakeConnector({
      execute: vi.fn(async (command: string) =>
        command === "photon.config"
          ? Buffer.from('{"predicates":[{"expr":"operation == \'GetItem\'","vars":{}}]}')
          : [200, Buffer.alloc(0), Buffer.alloc(0)],
      ),
    });
    const session = new PhotonSession(connector);
    await session.open();
    vi.mocked(connector.execute).mockClear();

    await expect(
      session.request(
        new PhotonRequest({
          method: "POST",
          url: "https://x/",
          body: "{}",
          metadata: new RequestMetadata({ service: "svc", operation: "PutItem" }),
        }),
      ),
    ).resolves.toBeUndefined();
    expect(connector.execute).not.toHaveBeenCalled();
  });

  it.each(["connect", "authenticate", "config"])(
    "closes and rethrows when %s fails",
    async (failure) => {
      const original = new Error(failure);
      const connector = fakeConnector({
        connect: vi.fn(async () => {
          if (failure === "connect") throw original;
        }),
        authenticate: vi.fn(async () => {
          if (failure === "authenticate") throw original;
        }),
        execute: vi.fn(async () => {
          if (failure === "config") throw original;
          return Buffer.from("{}");
        }),
      });

      const session = new PhotonSession(connector);
      await expect(session.open()).rejects.toBe(original);
      expect(connector.close).toHaveBeenCalledOnce();
    },
  );

  it("does not reopen when closed while the config handshake is resolving", async () => {
    let resolveConfig!: (value: Buffer) => void;
    const config = new Promise<Buffer>((resolve) => {
      resolveConfig = resolve;
    });
    const connector = fakeConnector({
      connect: vi.fn(),
      authenticate: vi.fn(),
      execute: vi.fn(() => config),
    });
    const session = new PhotonSession(connector);
    const opening = session.open();

    await vi.waitFor(() => {
      expect(connector.execute).toHaveBeenCalledWith("photon.config", Buffer.from("{}"));
    });
    resolveConfig(Buffer.from('{"version":"v1"}'));
    session.destroy();

    await expect(opening).rejects.toThrow("Session closed during open()");
    expect(session.isOpen).toBe(false);
    expect(connector.destroy).toHaveBeenCalledOnce();
  });

  it("makes close idempotent and destroy synchronous/idempotent", async () => {
    const connector = fakeConnector();
    const session = new PhotonSession(connector);
    await session.open();

    await session.close();
    await session.close();
    expect(connector.close).toHaveBeenCalledOnce();

    const destroyConnector = fakeConnector();
    const destroySession = new PhotonSession(destroyConnector);
    await destroySession.open();
    destroySession.destroy();
    destroySession.destroy();

    expect(destroyConnector.destroy).toHaveBeenCalledOnce();
  });

  it("delegates requests after close to the connector", async () => {
    const connector = fakeConnector();
    const session = new PhotonSession(connector);
    await session.open();
    await session.close();
    vi.mocked(connector.execute).mockClear();
    vi.mocked(connector.execute).mockRejectedValue(new PhotonError("connection is closed"));

    await expect(
      session.request(
        new PhotonRequest({
          method: "POST",
          url: "https://x/",
          metadata: new RequestMetadata({ service: "svc", operation: "Test" }),
        }),
      ),
    ).rejects.toThrow("connection is closed");
    expect(connector.execute).toHaveBeenCalledOnce();
  });

  it("enforces the CREATED -> OPEN -> CLOSED lifecycle", async () => {
    const connector = fakeConnector();
    const session = new PhotonSession(connector);
    const request = new PhotonRequest({
      method: "POST",
      url: "https://x/",
      metadata: new RequestMetadata({ service: "svc", operation: "Test" }),
    });

    await expect(session.request(request)).rejects.toThrow("request() called on a created session");
    await session.open();
    await expect(session.open()).rejects.toThrow("open() called on a open session");
    await session.close();
    await expect(session.open()).rejects.toThrow("open() called on a closed session");
  });

  it("retries initial connection attempts before succeeding", async () => {
    const first = controlledConnector({
      connect: async () => {
        throw new PhotonConnectionError("first");
      },
    });
    const second = controlledConnector({
      connect: async () => {
        throw new PhotonConnectionError("second");
      },
    });
    const third = controlledConnector();
    const factory = vi
      .fn<() => PhotonConnector>()
      .mockReturnValueOnce(first.connector)
      .mockReturnValueOnce(second.connector)
      .mockReturnValue(third.connector);
    const session = new PhotonSession(
      factory,
      {},
      {
        connectAttempts: 3,
        baseDelayMs: 1,
        maxDelayMs: 1,
        jitter: 0,
      },
    );

    await session.open();

    expect(factory).toHaveBeenCalledTimes(3);
    expect(first.connector.close).toHaveBeenCalled();
    expect(second.connector.close).toHaveBeenCalled();
    expect(session.connectionState).toBe("alive");
    await session.close();
  });

  it("fails startup after the configured attempt limit", async () => {
    const factory = vi.fn<() => PhotonConnector>(() => {
      return controlledConnector({
        connect: async () => {
          throw new PhotonConnectionError("unavailable");
        },
      }).connector;
    });
    const session = new PhotonSession(
      factory,
      {},
      {
        connectAttempts: 2,
        baseDelayMs: 1,
        maxDelayMs: 1,
        jitter: 0,
      },
    );

    await expect(session.open()).rejects.toThrow("unavailable");
    expect(factory).toHaveBeenCalledTimes(2);
    expect(session.connectionState).toBe("closed");
  });

  it("bounds the complete initial connect, AUTH, and config attempt", async () => {
    const cache = new MockCache({ failure: "hang", failureCommand: "auth" });
    await cache.start();
    const connectors: PhotonConnector[] = [];
    const session = new PhotonSession(
      () => {
        const connector = new PhotonConnector(cache.host, cache.port!, {
          tls: false,
          configTimeout: 5_000,
          authProvider: () => ["test-user", "test-token"],
        });
        connectors.push(connector);
        return connector;
      },
      {},
      {
        connectAttempts: 1,
        connectionTimeoutMs: 25,
      },
    );
    const started = performance.now();
    try {
      await expect(session.open()).rejects.toThrow("opening a connection timed out after 25ms");
      expect(performance.now() - started).toBeLessThan(500);
      expect(connectors).toHaveLength(1);
      expect(connectors[0]?.isDead).toBe(true);
    } finally {
      await session.close();
      await cache.stop();
    }
  });

  it("does not replay a failed request and reconnects in the background", async () => {
    const initial = controlledConnector();
    vi.mocked(initial.connector.execute).mockImplementation(async (command: string) => {
      if (command === "photon.config") {
        return Buffer.from('{"predicates":[{"expr":"true","vars":{}}]}');
      }
      initial.fail();
      throw new PhotonConnectionLost("connection dropped");
    });
    let allowReconnect!: () => void;
    const reconnectGate = new Promise<void>((resolve) => {
      allowReconnect = resolve;
    });
    const replacement = controlledConnector({ connect: () => reconnectGate });
    const factory = vi
      .fn<() => PhotonConnector>()
      .mockReturnValueOnce(initial.connector)
      .mockReturnValue(replacement.connector);
    const session = new PhotonSession(
      factory,
      {},
      {
        baseDelayMs: 1,
        maxDelayMs: 1,
        jitter: 0,
      },
    );
    await session.open();

    const failed = session.request(testRequest());
    await expect(failed).rejects.toBeInstanceOf(PhotonConnectionLost);
    await vi.waitFor(() => {
      expect(replacement.connector.connect).toHaveBeenCalledOnce();
    });

    await expect(session.request(testRequest())).resolves.toBeUndefined();
    allowReconnect();
    await waitForState(session, "alive");
    await expect(session.request(testRequest())).resolves.toEqual(
      new PhotonResponse({
        statusCode: 200,
        headers: { "content-type": "application/json" },
        body: Buffer.from("ok"),
      }),
    );
    expect(initial.connector.execute).toHaveBeenCalledTimes(2);
    expect(replacement.connector.authenticate).toHaveBeenCalledOnce();
    expect(factory).toHaveBeenCalledTimes(2);
    await session.close();
  });

  it("starts reconnecting after an idle disconnect without a request", async () => {
    const initial = controlledConnector();
    let allowReconnect!: () => void;
    const reconnectGate = new Promise<void>((resolve) => {
      allowReconnect = resolve;
    });
    const replacement = controlledConnector({ connect: () => reconnectGate });
    const factory = vi
      .fn<() => PhotonConnector>()
      .mockReturnValueOnce(initial.connector)
      .mockReturnValue(replacement.connector);
    const session = new PhotonSession(
      factory,
      {},
      {
        baseDelayMs: 1,
        maxDelayMs: 1,
        jitter: 0,
      },
    );
    await session.open();
    initial.fail();

    await vi.waitFor(() => {
      expect(replacement.connector.connect).toHaveBeenCalledOnce();
    });
    expect(session.connectionState).toBe("reconnecting");
    expect(initial.connector.execute).toHaveBeenCalledOnce();
    allowReconnect();
    await waitForState(session, "alive");
    expect(factory).toHaveBeenCalledTimes(2);
    await session.close();
  });

  it("reconnects an idle real socket with fresh auth and config", async () => {
    const cache = new MockCache({
      config: { predicates: [{ expr: "true", vars: {} }] },
      responseBody: Buffer.from("reconnected"),
    });
    await cache.start();
    const proxy = new MockTcpProxy(cache.host, cache.port!);
    await proxy.start();
    const authProvider = vi.fn(async () => ["test-user", "test-token"] as [string, string]);
    const factory = vi.fn(
      () =>
        new PhotonConnector(proxy.host, proxy.port!, {
          tls: false,
          connectTimeout: 500,
          configTimeout: 500,
          requestTimeout: 500,
          authProvider,
        }),
    );
    const session = new PhotonSession(
      factory,
      {},
      {
        baseDelayMs: 1,
        maxDelayMs: 2,
        jitter: 0,
      },
    );
    try {
      await session.open();
      proxy.disconnectClients();

      await vi.waitFor(
        () => {
          expect(factory).toHaveBeenCalledTimes(2);
          expect(session.connectionState).toBe("alive");
        },
        { timeout: 5_000 },
      );

      await expect(session.request(testRequest())).resolves.toMatchObject({
        body: Buffer.from("reconnected"),
      });
      expect(authProvider).toHaveBeenCalledTimes(2);
      expect(cache.photonCommandLog.filter((command) => command === "photon.config")).toHaveLength(
        2,
      );
    } finally {
      await session.close();
      await proxy.stop();
      await cache.stop();
    }
  });

  it("keeps reconnecting until a later attempt succeeds", async () => {
    const initial = controlledConnector();
    let calls = 0;
    const factory = vi.fn<() => PhotonConnector>(() => {
      calls++;
      if (calls === 1) return initial.connector;
      return controlledConnector({
        connect: async () => {
          if (calls < 6) throw new PhotonConnectionError("unavailable");
        },
      }).connector;
    });
    const session = new PhotonSession(
      factory,
      {},
      {
        baseDelayMs: 1,
        maxDelayMs: 1,
        jitter: 0,
      },
    );
    await session.open();
    initial.fail();

    await waitForState(session, "alive");
    expect(factory).toHaveBeenCalledTimes(6);
    await session.close();
  });

  it("allows origin fallback indefinitely by default while reconnecting", async () => {
    const initial = controlledConnector();
    const factory = vi.fn<() => PhotonConnector>(() =>
      factory.mock.calls.length === 1
        ? initial.connector
        : controlledConnector({
            connect: async () => {
              throw new PhotonConnectionError("unavailable");
            },
          }).connector,
    );
    const session = new PhotonSession(
      factory,
      {},
      {
        baseDelayMs: 10_000,
        maxDelayMs: 10_000,
        jitter: 0,
      },
    );
    await session.open();
    initial.fail();
    await waitForState(session, "reconnecting");

    await expect(session.request(testRequest())).resolves.toBeUndefined();
    await expect(session.request(testRequest())).resolves.toBeUndefined();
    await session.close();
  });

  it("allows finite fallback and then raises PhotonCacheUnavailable", async () => {
    const initial = controlledConnector();
    const factory = vi.fn<() => PhotonConnector>(() =>
      factory.mock.calls.length === 1
        ? initial.connector
        : controlledConnector({
            connect: async () => {
              throw new PhotonConnectionError("unavailable");
            },
          }).connector,
    );
    const session = new PhotonSession(
      factory,
      {},
      {
        fallbackDurationMs: 5,
        baseDelayMs: 10_000,
        maxDelayMs: 10_000,
        jitter: 0,
      },
    );
    await session.open();
    initial.fail();

    await expect(session.request(testRequest())).resolves.toBeUndefined();
    await new Promise((resolve) => setTimeout(resolve, 10));
    const request = new PhotonRequest({
      method: "GET",
      url: "https://user:secret@x:8443/path?token=sensitive",
      body: "{}",
      metadata: new RequestMetadata({ service: "svc", operation: "GetItem" }),
    });
    const error = await session.request(request).catch((cause: unknown) => cause);
    expect(error).toBeInstanceOf(PhotonCacheUnavailable);
    expect(error).toMatchObject({ endpointUrl: "https://x:8443" });
    expect(String(error)).not.toContain("secret");
    expect(String(error)).not.toContain("token");
    await session.close();
  });

  it("raises immediately when fallback is disabled", async () => {
    const initial = controlledConnector();
    const replacement = controlledConnector({
      connect: async () => {
        throw new PhotonConnectionError("unavailable");
      },
    });
    const factory = vi
      .fn<() => PhotonConnector>()
      .mockReturnValueOnce(initial.connector)
      .mockReturnValue(replacement.connector);
    const session = new PhotonSession(
      factory,
      {},
      {
        fallbackDurationMs: 0,
        baseDelayMs: 10_000,
        maxDelayMs: 10_000,
        jitter: 0,
      },
    );
    await session.open();
    initial.fail();

    await expect(session.request(testRequest())).rejects.toBeInstanceOf(PhotonCacheUnavailable);
    await session.close();
  });

  it("does not escalate an unmatched request during an outage", async () => {
    const initial = controlledConnector({ predicate: "false" });
    const factory = vi
      .fn<() => PhotonConnector>()
      .mockReturnValueOnce(initial.connector)
      .mockImplementation(
        () =>
          controlledConnector({
            connect: async () => {
              throw new PhotonConnectionError("unavailable");
            },
          }).connector,
      );
    const session = new PhotonSession(
      factory,
      {},
      {
        fallbackDurationMs: 0,
        baseDelayMs: 10_000,
        maxDelayMs: 10_000,
        jitter: 0,
      },
    );
    await session.open();
    initial.fail();

    await expect(session.request(testRequest())).resolves.toBeUndefined();
    await session.close();
  });

  it("recompiles predicates from the recovered config", async () => {
    const initial = controlledConnector({ predicate: "true" });
    const replacement = controlledConnector({ predicate: "false" });
    const factory = vi
      .fn<() => PhotonConnector>()
      .mockReturnValueOnce(initial.connector)
      .mockReturnValue(replacement.connector);
    const session = new PhotonSession(
      factory,
      {},
      {
        baseDelayMs: 1,
        maxDelayMs: 1,
        jitter: 0,
      },
    );
    await session.open();
    initial.fail();

    await waitForState(session, "alive");
    await expect(session.request(testRequest())).resolves.toBeUndefined();
    expect(replacement.connector.execute).toHaveBeenCalledOnce();
    await session.close();
  });

  it("interrupts reconnect backoff when closed", async () => {
    const initial = controlledConnector();
    const failedReplacement = controlledConnector({
      connect: async () => {
        throw new PhotonConnectionError("unavailable");
      },
    });
    const factory = vi
      .fn<() => PhotonConnector>()
      .mockReturnValueOnce(initial.connector)
      .mockReturnValue(failedReplacement.connector);
    const random = vi.spyOn(Math, "random").mockReturnValue(0.999);
    const session = new PhotonSession(
      factory,
      {},
      {
        baseDelayMs: 10_000,
        maxDelayMs: 10_000,
        jitter: 0,
      },
    );
    try {
      await session.open();
      initial.fail();
      const request = session.request(testRequest());
      await vi.waitFor(() => {
        expect(failedReplacement.connector.connect).toHaveBeenCalledOnce();
      });

      const started = performance.now();
      await session.close();
      expect(performance.now() - started).toBeLessThan(500);
      await expect(request).resolves.toBeUndefined();
      const callsAfterClose = factory.mock.calls.length;
      expect(factory).toHaveBeenCalledTimes(callsAfterClose);
    } finally {
      random.mockRestore();
      await session.close();
    }
  });

  it.each(["authenticate", "config"] as const)(
    "times out reconnect when %s hangs and allows shutdown",
    async (failure) => {
      const initial = controlledConnector();
      const never = () => new Promise<never>(() => undefined);
      const replacement = controlledConnector({
        ...(failure === "authenticate" ? { authenticate: never } : { config: never }),
      });
      const factory = vi
        .fn<() => PhotonConnector>()
        .mockReturnValueOnce(initial.connector)
        .mockReturnValue(replacement.connector);
      const session = new PhotonSession(
        factory,
        {},
        {
          connectionTimeoutMs: 25,
          baseDelayMs: 10_000,
          maxDelayMs: 10_000,
          jitter: 0,
        },
      );
      await session.open();
      initial.fail();

      await vi.waitFor(() => {
        if (failure === "authenticate") {
          expect(replacement.connector.authenticate).toHaveBeenCalledOnce();
        } else {
          expect(replacement.connector.execute).toHaveBeenCalledWith(
            "photon.config",
            Buffer.from("{}"),
          );
        }
      });

      const started = performance.now();
      await session.close();
      expect(performance.now() - started).toBeLessThan(500);
      expect(replacement.connector.destroy).toHaveBeenCalled();
      expect(factory).toHaveBeenCalledTimes(2);
    },
  );

  it("validates reconnect policy bounds", () => {
    const connector = fakeConnector();
    const aboveTimerLimit = 2_147_483_648;

    expect(() => new PhotonSession(() => connector, {}, { connectAttempts: 0 })).toThrow(
      PhotonConfigError,
    );
    expect(() => new PhotonSession(() => connector, {}, { baseDelayMs: 0 })).toThrow(
      "greater than zero",
    );
    expect(
      () => new PhotonSession(() => connector, {}, { connectionTimeoutMs: aboveTimerLimit }),
    ).toThrow("at most 2147483647");
    expect(() => new PhotonSession(() => connector, {}, { baseDelayMs: aboveTimerLimit })).toThrow(
      "at most 2147483647",
    );
    expect(() => new PhotonSession(() => connector, {}, { maxDelayMs: aboveTimerLimit })).toThrow(
      "at most 2147483647",
    );
    expect(
      () => new PhotonSession(() => connector, {}, { baseDelayMs: 10, maxDelayMs: 5 }),
    ).toThrow("at least baseDelayMs");
    expect(() => new PhotonSession(() => connector, {}, { jitter: 1 })).toThrow(
      "jitter must be in [0, 1)",
    );
    expect(() => new PhotonSession(() => connector, {}, { fallbackDurationMs: 0.5 })).toThrow(
      "whole number",
    );
    expect(() => new PhotonSession(() => connector, {}, { fallbackDurationMs: -0.5 })).toThrow(
      "whole number",
    );

    const random = vi.spyOn(Math, "random").mockReturnValue(0.999);
    try {
      const session = new PhotonSession(
        () => connector,
        {},
        { baseDelayMs: 2_147_483_647, maxDelayMs: 2_147_483_647, jitter: 0.999 },
      );
      const delay = (
        session as unknown as {
          delayFor(attempt: number): number;
        }
      ).delayFor(1);
      expect(delay).toBe(2_147_483_647);
    } finally {
      random.mockRestore();
    }
  });
});

describe("parseEndpoint", () => {
  it.each([
    ["cache.example.com:6379", ["cache.example.com", 6379]],
    ["127.0.0.1:11211", ["127.0.0.1", 11211]],
    ["::1:6379", ["::1", 6379]],
    ["[::1]:6379", ["::1", 6379]],
  ])("parses %s", (endpoint, expected) => {
    expect(parseEndpoint(endpoint)).toEqual(expected);
  });

  it.each([
    "",
    "cache.example.com",
    ":6379",
    "cache.example.com:",
    "host:nope",
    "host:0",
    "host:65536",
  ])("rejects invalid endpoint %s", (endpoint) => {
    expect(() => parseEndpoint(endpoint)).toThrow(PhotonError);
  });
});
