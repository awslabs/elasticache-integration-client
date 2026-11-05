import { createServer } from "node:net";

import { describe, expect, it, vi } from "vitest";

import {
  ElastiCacheIntegrationsAuthError,
  ElastiCacheIntegrationsCacheUnavailable,
  ElastiCacheIntegrationsConfigError,
  ElastiCacheIntegrationsOverridesRejected,
  ElastiCacheIntegrationsConfigUnevaluable,
  ElastiCacheIntegrationsConnectionError,
  ElastiCacheIntegrationsConnectionLost,
  ElastiCacheIntegrationsError,
  ElastiCacheIntegrationsTimeoutError,
} from "../src/errors.js";
import { ElastiCacheIntegrationsConnector } from "../src/connection.js";
import {
  ElastiCacheIntegrationsRequest,
  ElastiCacheIntegrationsResponse,
  RequestMetadata,
  ResolvedConfig,
} from "../src/models.js";
import { PROTOCOL_VERSION } from "../src/protocol.js";
import { RespError } from "../src/resp.js";
import { ElastiCacheIntegrationsSession, parseEndpoint } from "../src/session.js";
import type { Refresher } from "../src/refresh.js";
import { MockCache, MockTcpProxy } from "./mock-cache.js";

function fakeConnector(
  overrides: Partial<ElastiCacheIntegrationsConnector> = {},
): ElastiCacheIntegrationsConnector {
  return {
    isReady: true,
    connect: vi.fn(async () => undefined),
    markReady: vi.fn(),
    handshake: vi.fn(async () => undefined),
    reauthenticate: vi.fn(async () => undefined),
    execute: vi.fn(async (command: string, ...args: Buffer[]) => {
      if (command !== "aws.config") {
        return [200, Buffer.from("content-type:application/json"), Buffer.from("ok")];
      }
      // A no-argument aws.config means "nothing changed", as the cache does.
      if (args.length === 0) return null;
      return Buffer.from(
        '{"version":"1.0","refresh_interval":60,"predicates":[{"expr":"true","vars":{}}]}',
      );
    }),
    close: vi.fn(async () => undefined),
    destroy: vi.fn(() => undefined),
    ...overrides,
  } as unknown as ElastiCacheIntegrationsConnector;
}

function controlledConnector(
  options: {
    predicate?: string;
    connect?: () => Promise<void>;
    handshake?: () => Promise<void>;
    reauthenticate?: () => Promise<void>;
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
    markReady: vi.fn(),
    handshake: vi.fn(async () => {
      await options.handshake?.();
    }),
    reauthenticate: vi.fn(async () => {
      await options.reauthenticate?.();
    }),
    execute: vi.fn(async (command: string) => {
      if (command === "aws.config") {
        if (options.config !== undefined) return options.config();
        return Buffer.from(
          JSON.stringify({
            refresh_interval: 60,
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
  } as unknown as ElastiCacheIntegrationsConnector;
  return {
    connector,
    fail: () => {
      dead = true;
      notify();
    },
  };
}

function testRequest(): ElastiCacheIntegrationsRequest {
  return new ElastiCacheIntegrationsRequest({
    method: "GET",
    url: "https://x/",
    body: "{}",
    metadata: new RequestMetadata({ service: "svc", operation: "GetItem" }),
  });
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/** An address with nothing listening on it, so connecting is refused. */
async function unusedPort(): Promise<number> {
  const server = createServer();
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address() as { port: number };
  await new Promise((resolve) => server.close(resolve));
  return port;
}

/** A factory whose first connector is `initial` and whose replacements all fail to connect. */
function outageFactory(initial: ElastiCacheIntegrationsConnector) {
  const factory = vi.fn<() => ElastiCacheIntegrationsConnector>(() =>
    factory.mock.calls.length === 1
      ? initial
      : controlledConnector({
          connect: async () => {
            throw new ElastiCacheIntegrationsConnectionError("unavailable");
          },
        }).connector,
  );
  return factory;
}

const slowBackoff = { baseDelayMs: 10_000, maxDelayMs: 10_000, jitter: 0 };

async function waitForState(session: ElastiCacheIntegrationsSession, state: string): Promise<void> {
  await vi.waitFor(() => {
    expect(session.connectionState).toBe(state);
  });
}

/** Reach the session's private refreshers; lifecycle assertions need them, callers do not. */
function configRefresherOf(session: ElastiCacheIntegrationsSession): Refresher | undefined {
  return (session as unknown as { configRefresher: Refresher | undefined }).configRefresher;
}

function reauthRefresherOf(session: ElastiCacheIntegrationsSession): Refresher | undefined {
  return (session as unknown as { reauthRefresher: Refresher | undefined }).reauthRefresher;
}

describe("ElastiCacheIntegrationsSession", () => {
  it("reports whether the session is open", async () => {
    const session = new ElastiCacheIntegrationsSession(fakeConnector());

    expect(session.isOpen).toBe(false);
    await session.open();
    expect(session.isOpen).toBe(true);
    await session.close();
    expect(session.isOpen).toBe(false);
  });

  it("opens in lifecycle order and stores the resolved config", async () => {
    const connector = fakeConnector();
    const session = new ElastiCacheIntegrationsSession(connector, { item_ttl: 300 });
    expect(session.config).toBeUndefined();

    await session.open();

    expect(connector.connect).toHaveBeenCalledOnce();
    expect(connector.handshake).toHaveBeenCalledOnce();
    expect(connector.execute).toHaveBeenCalledWith(
      "aws.config",
      Buffer.from(`{"version":"${PROTOCOL_VERSION}","item_ttl":300}`),
    );
    expect(session.config).toEqual(expect.any(ResolvedConfig));
    expect(session.config.raw).toEqual({
      version: "1.0",
      refresh_interval: 60,
      predicates: [{ expr: "true", vars: {} }],
    });
    const connectOrder = (connector.connect as ReturnType<typeof vi.fn>).mock
      .invocationCallOrder[0];
    const handshakeOrder = (connector.handshake as ReturnType<typeof vi.fn>).mock
      .invocationCallOrder[0];
    const executeOrder = (connector.execute as ReturnType<typeof vi.fn>).mock
      .invocationCallOrder[0];
    if (connectOrder === undefined || handshakeOrder === undefined || executeOrder === undefined) {
      throw new Error("expected lifecycle methods to be called");
    }
    expect(connectOrder).toBeLessThan(handshakeOrder);
    expect(handshakeOrder).toBeLessThan(executeOrder);
    await session.close();
  });

  it("serializes requests, delegates execution, and parses ElastiCacheIntegrationsResponse", async () => {
    const connector = fakeConnector();
    const session = new ElastiCacheIntegrationsSession(connector);
    await session.open();
    const request = new ElastiCacheIntegrationsRequest({
      method: "POST",
      url: "https://x/",
      body: '{"value":"body"}',
      metadata: new RequestMetadata({ service: "svc", operation: "Test" }),
    });

    await expect(session.request(request)).resolves.toEqual(
      new ElastiCacheIntegrationsResponse({
        statusCode: 200,
        headers: { "content-type": "application/json" },
        body: Buffer.from("ok"),
      }),
    );
    expect(connector.execute).toHaveBeenLastCalledWith(
      "aws.request",
      Buffer.from("POST"),
      Buffer.from("https://x/"),
      Buffer.alloc(0),
      Buffer.from('{"value":"body"}'),
      Buffer.from("service=svc&op=Test"),
    );
    await session.close();
  });

  it("closes a stale-auth connection and passes the rejected request through", async () => {
    let ready = true;
    const close = vi.fn(async () => {
      ready = false;
    });
    const connector = fakeConnector({
      execute: vi.fn(async (command: string) =>
        command === "aws.config"
          ? Buffer.from(
              '{"version":"1.0","refresh_interval":60,"predicates":[{"expr":"true","vars":{}}]}',
            )
          : new RespError("AUTH_ERROR", "missing FAS"),
      ),
      close,
    });
    Object.defineProperty(connector, "isReady", { get: () => ready });
    const session = new ElastiCacheIntegrationsSession(
      connector,
      {},
      { connectionTimeoutMs: 2_000 },
    );
    await session.open();

    const response = await session.request(
      new ElastiCacheIntegrationsRequest({
        method: "POST",
        url: "https://x/",
        body: "{}",
        metadata: new RequestMetadata({ service: "svc", operation: "UpdateItem" }),
      }),
    );

    expect(response).toBeUndefined();
    expect(close).toHaveBeenCalledOnce();
    expect(session.isConnectionHealthy).toBe(false);

    vi.mocked(connector.execute).mockClear();
    await expect(
      session.request(
        new ElastiCacheIntegrationsRequest({
          method: "POST",
          url: "https://x/",
          metadata: new RequestMetadata({ service: "svc", operation: "UpdateItem" }),
        }),
      ),
    ).resolves.toBeUndefined();
    expect(connector.execute).not.toHaveBeenCalled();
    await session.close();
  });

  it("declines unmatched requests without a cache round trip", async () => {
    const connector = fakeConnector({
      execute: vi.fn(async (command: string) =>
        command === "aws.config"
          ? Buffer.from(
              '{"refresh_interval":60,"predicates":[{"expr":"operation == \'GetItem\'","vars":{}}]}',
            )
          : [200, Buffer.alloc(0), Buffer.alloc(0)],
      ),
    });
    const session = new ElastiCacheIntegrationsSession(connector);
    await session.open();
    vi.mocked(connector.execute).mockClear();

    await expect(
      session.request(
        new ElastiCacheIntegrationsRequest({
          method: "POST",
          url: "https://x/",
          body: "{}",
          metadata: new RequestMetadata({ service: "svc", operation: "PutItem" }),
        }),
      ),
    ).resolves.toBeUndefined();
    expect(connector.execute).not.toHaveBeenCalled();
    await session.close();
  });

  it.each(["connect", "handshake", "config"])(
    "closes and rethrows when %s fails",
    async (failure) => {
      const original = new Error(failure);
      const connector = fakeConnector({
        connect: vi.fn(async () => {
          if (failure === "connect") throw original;
        }),
        markReady: vi.fn(),
        handshake: vi.fn(async () => {
          if (failure === "handshake") throw original;
        }),
        execute: vi.fn(async () => {
          if (failure === "config") throw original;
          return Buffer.from('{"refresh_interval":60}');
        }),
      });

      const session = new ElastiCacheIntegrationsSession(connector);
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
      markReady: vi.fn(),
      handshake: vi.fn(),
      execute: vi.fn(() => config),
    });
    const session = new ElastiCacheIntegrationsSession(connector);
    const opening = session.open();

    await vi.waitFor(() => {
      expect(connector.execute).toHaveBeenCalledWith(
        "aws.config",
        Buffer.from(`{"version":"${PROTOCOL_VERSION}"}`),
      );
    });
    resolveConfig(Buffer.from('{"version":"1.0","refresh_interval":60}'));
    session.destroy();

    await expect(opening).rejects.toThrow("Session closed during open()");
    expect(session.isOpen).toBe(false);
    expect(connector.destroy).toHaveBeenCalledOnce();
  });

  it("makes close idempotent and destroy synchronous/idempotent", async () => {
    const connector = fakeConnector();
    const session = new ElastiCacheIntegrationsSession(connector);
    await session.open();

    await session.close();
    await session.close();
    expect(connector.close).toHaveBeenCalledOnce();

    const destroyConnector = fakeConnector();
    const destroySession = new ElastiCacheIntegrationsSession(destroyConnector);
    await destroySession.open();
    destroySession.destroy();
    destroySession.destroy();

    expect(destroyConnector.destroy).toHaveBeenCalledOnce();
  });

  it.each(["close", "destroy"] as const)(
    "releases connector-factory resources exactly once on %s",
    async (teardown) => {
      const connector = fakeConnector();
      const closeFactory = vi.fn();
      const factory = Object.assign(() => connector, { close: closeFactory });
      const session = new ElastiCacheIntegrationsSession(factory);
      await session.open();

      if (teardown === "close") {
        await session.close();
        await session.close();
      } else {
        session.destroy();
        session.destroy();
      }

      expect(closeFactory).toHaveBeenCalledOnce();
    },
  );

  it("delegates requests after close to the connector", async () => {
    const connector = fakeConnector();
    const session = new ElastiCacheIntegrationsSession(connector);
    await session.open();
    await session.close();
    vi.mocked(connector.execute).mockClear();
    vi.mocked(connector.execute).mockRejectedValue(
      new ElastiCacheIntegrationsError("connection is closed"),
    );

    await expect(
      session.request(
        new ElastiCacheIntegrationsRequest({
          method: "POST",
          url: "https://x/",
          metadata: new RequestMetadata({ service: "svc", operation: "Test" }),
        }),
      ),
    ).rejects.toThrow("connection is closed");
    expect(connector.execute).toHaveBeenCalledOnce();
  });

  it("starts config and authentication refreshers after opening", async () => {
    const delays: number[] = [];
    const realSetTimeout = globalThis.setTimeout;
    const setTimeoutSpy = vi.spyOn(globalThis, "setTimeout").mockImplementation(((
      callback: () => void,
      delay?: number,
      ...args: unknown[]
    ) => {
      delays.push(delay ?? 0);
      return realSetTimeout(callback, delay, ...args);
    }) as typeof setTimeout);
    const session = new ElastiCacheIntegrationsSession(fakeConnector());

    try {
      await session.open();

      // The refresher is scheduled from the config's 60s interval, +/- 20% jitter.
      const refreshDelays = delays.filter((delay) => delay >= 48_000 && delay <= 72_000);
      const reauthDelays = delays.filter((delay) => delay >= 720_000 && delay <= 1_080_000);
      expect(refreshDelays).toHaveLength(1);
      expect(reauthDelays).toHaveLength(1);
      expect(configRefresherOf(session)).toBeDefined();
      expect(reauthRefresherOf(session)).toBeDefined();
    } finally {
      setTimeoutSpy.mockRestore();
      await session.close();
    }
  });

  it("applies reconnect policy jitter to both background refresh schedules", async () => {
    const delays: number[] = [];
    const realSetTimeout = globalThis.setTimeout;
    const setTimeoutSpy = vi.spyOn(globalThis, "setTimeout").mockImplementation(((
      callback: () => void,
      delay?: number,
      ...args: unknown[]
    ) => {
      delays.push(delay ?? 0);
      return realSetTimeout(callback, delay, ...args);
    }) as typeof setTimeout);
    const session = new ElastiCacheIntegrationsSession(fakeConnector(), {}, { jitter: 0 });

    try {
      await session.open();

      expect(delays).toContain(60_000);
      expect(delays).toContain(900_000);
    } finally {
      setTimeoutSpy.mockRestore();
      await session.close();
    }
  });

  it("does not start a refresher when opening fails", async () => {
    const connector = fakeConnector({
      execute: vi.fn(async () => {
        throw new ElastiCacheIntegrationsError("config failed");
      }),
    });
    const session = new ElastiCacheIntegrationsSession(connector);

    await expect(session.open()).rejects.toThrow("config failed");
    expect(configRefresherOf(session)).toBeUndefined();
    expect(reauthRefresherOf(session)).toBeUndefined();
    expect(connector.close).toHaveBeenCalledOnce();
  });

  it("re-authenticates the live connector", async () => {
    const connector = fakeConnector();
    const session = new ElastiCacheIntegrationsSession(connector);
    await session.open();
    vi.mocked(connector.reauthenticate).mockClear();

    await reauthenticateOnce(session);

    expect(connector.reauthenticate).toHaveBeenCalledOnce();
    await session.close();
  });

  it("propagates a re-AUTH rejection so the refresher applies backoff", async () => {
    const rejection = new ElastiCacheIntegrationsAuthError("IAM AUTH rejected: WRONGPASS");
    const connector = fakeConnector({
      reauthenticate: vi.fn(async () => {
        throw rejection;
      }),
    });
    const session = new ElastiCacheIntegrationsSession(connector);
    await session.open();

    await expect(reauthenticateOnce(session)).rejects.toBe(rejection);
    await session.close();
  });

  it("skips re-AUTH while reconnecting", async () => {
    const connector = fakeConnector();
    const session = new ElastiCacheIntegrationsSession(connector);
    await session.open();
    vi.mocked(connector.reauthenticate).mockClear();
    const internals = session as unknown as {
      connection: "alive" | "reconnecting";
      connector: ElastiCacheIntegrationsConnector | undefined;
    };
    internals.connection = "reconnecting";
    internals.connector = undefined;

    await reauthenticateOnce(session);

    expect(connector.reauthenticate).not.toHaveBeenCalled();
    internals.connection = "alive";
    internals.connector = connector;
    await session.close();
  });

  it("reconnects when a refresh timeout makes the connector unusable", async () => {
    const initial = controlledConnector();
    const replacement = controlledConnector();
    const factory = vi
      .fn<() => ElastiCacheIntegrationsConnector>()
      .mockReturnValueOnce(initial.connector)
      .mockReturnValue(replacement.connector);
    const session = new ElastiCacheIntegrationsSession(
      factory,
      {},
      { baseDelayMs: 1, maxDelayMs: 1, jitter: 0 },
    );
    await session.open();
    const refresher = configRefresherOf(session);
    if (refresher === undefined) throw new Error("expected a refresher");
    const stop = vi.spyOn(refresher, "stop");
    vi.mocked(initial.connector.execute).mockRejectedValue(
      new ElastiCacheIntegrationsTimeoutError("request timed out during refresh"),
    );

    await refreshOnce(session);
    await waitForState(session, "alive");

    expect(session.isOpen).toBe(true);
    expect(stop).toHaveBeenCalledOnce();
    expect(initial.connector.close).toHaveBeenCalledOnce();
    expect(replacement.connector.handshake).toHaveBeenCalledOnce();
    expect(factory).toHaveBeenCalledTimes(2);
    await session.close();
  });

  it("reconnects when a refresh receives a malformed RESP frame", async () => {
    const cache = new MockCache();
    await cache.start();
    const factory = vi.fn(
      () =>
        new ElastiCacheIntegrationsConnector(cache.host, cache.port!, {
          tls: false,
          authProvider: () => ["test-user", "test-token"],
        }),
    );
    const session = new ElastiCacheIntegrationsSession(
      factory,
      {},
      { baseDelayMs: 1, maxDelayMs: 1, jitter: 0 },
    );
    try {
      await session.open();
      cache.noArgConfigFrame = Buffer.from("_unexpected\r\n");

      await refreshOnce(session);
      await waitForState(session, "alive");

      expect(session.isOpen).toBe(true);
      expect(factory).toHaveBeenCalledTimes(2);
    } finally {
      await session.close();
      await cache.stop();
    }
  });

  it("does not log an in-flight refresh failure during shutdown", async () => {
    let rejectRefresh!: (error: unknown) => void;
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const connector = fakeConnector();
    const session = new ElastiCacheIntegrationsSession(connector);
    await session.open();
    vi.mocked(connector.execute).mockImplementation(
      () =>
        new Promise<never>((_, reject) => {
          rejectRefresh = reject;
        }),
    );

    const refreshing = refreshOnce(session);
    await session.close();
    rejectRefresh(new ElastiCacheIntegrationsConnectionLost("connection closed"));
    await refreshing;

    expect(warn).not.toHaveBeenCalled();
    warn.mockRestore();
  });

  it.each(["close", "destroy"] as const)(
    "stops both refreshers before tearing the connector down on %s",
    async (teardown) => {
      const connector = fakeConnector();
      const session = new ElastiCacheIntegrationsSession(connector);
      await session.open();
      const refresher = configRefresherOf(session);
      const reauthRefresher = reauthRefresherOf(session);
      if (refresher === undefined || reauthRefresher === undefined) {
        throw new Error("expected both refreshers");
      }
      const stop = vi.spyOn(refresher, "stop");
      const stopReauth = vi.spyOn(reauthRefresher, "stop");

      if (teardown === "close") await session.close();
      else session.destroy();

      // A refresh must never fire into a half-closed connection.
      const stopOrder = stop.mock.invocationCallOrder[0];
      const stopReauthOrder = stopReauth.mock.invocationCallOrder[0];
      const teardownMock = vi.mocked(teardown === "close" ? connector.close : connector.destroy);
      const teardownOrder = teardownMock.mock.invocationCallOrder[0];
      if (stopOrder === undefined || stopReauthOrder === undefined || teardownOrder === undefined) {
        throw new Error("expected teardown to stop both refreshers");
      }
      expect(stopOrder).toBeLessThan(teardownOrder);
      expect(stopReauthOrder).toBeLessThan(teardownOrder);
    },
  );

  it("awaits an in-flight refresh and does not reschedule after close", async () => {
    vi.useFakeTimers();
    const random = vi.spyOn(Math, "random").mockReturnValue(0.5);
    let finishRefresh!: () => void;
    const session = new ElastiCacheIntegrationsSession(fakeConnector());
    const refreshOnce = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finishRefresh = resolve;
        }),
    );
    (
      session as unknown as {
        refreshOnce: () => Promise<void>;
      }
    ).refreshOnce = refreshOnce;

    try {
      await session.open();
      await vi.advanceTimersByTimeAsync(60_000);

      expect(refreshOnce).toHaveBeenCalledOnce();
      // The config callback is still in flight; only the independent re-AUTH timer remains.
      expect(vi.getTimerCount()).toBe(1);

      await session.close();
      finishRefresh();
      await Promise.resolve();
      expect(vi.getTimerCount()).toBe(0);
    } finally {
      random.mockRestore();
      vi.useRealTimers();
    }
  });

  it("enforces the CREATED -> OPEN -> CLOSED lifecycle", async () => {
    const connector = fakeConnector();
    const session = new ElastiCacheIntegrationsSession(connector);
    const request = new ElastiCacheIntegrationsRequest({
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
        throw new ElastiCacheIntegrationsConnectionError("first");
      },
    });
    const second = controlledConnector({
      connect: async () => {
        throw new ElastiCacheIntegrationsConnectionError("second");
      },
    });
    const third = controlledConnector();
    const factory = vi
      .fn<() => ElastiCacheIntegrationsConnector>()
      .mockReturnValueOnce(first.connector)
      .mockReturnValueOnce(second.connector)
      .mockReturnValue(third.connector);
    const session = new ElastiCacheIntegrationsSession(
      factory,
      {},
      {
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

  it("connection timeout bounds connect and configure", async () => {
    const cache = new MockCache({ failure: "hang", failureCommand: "hello" });
    await cache.start();
    const connectors: ElastiCacheIntegrationsConnector[] = [];
    const session = new ElastiCacheIntegrationsSession(
      () => {
        const connector = new ElastiCacheIntegrationsConnector(cache.host, cache.port!, {
          tls: false,
          connectionTimeout: 5_000,
          authProvider: () => ["test-user", "test-token"],
        });
        connectors.push(connector);
        return connector;
      },
      {},
      {
        connectionTimeoutMs: 25,
      },
    );
    const started = performance.now();
    try {
      await expect(session.open()).rejects.toThrow("opening a connection timed out after 0.025s");
      expect(performance.now() - started).toBeLessThan(500);
      expect(connectors.length).toBeGreaterThanOrEqual(1);
      expect(connectors.every((connector) => connector.isDead)).toBe(true);
    } finally {
      await session.close();
      await cache.stop();
    }
  });

  it("does not replay a failed request and reconnects in the background", async () => {
    const initial = controlledConnector();
    vi.mocked(initial.connector.execute).mockImplementation(async (command: string) => {
      if (command === "aws.config") {
        return Buffer.from('{"refresh_interval":60,"predicates":[{"expr":"true","vars":{}}]}');
      }
      initial.fail();
      throw new ElastiCacheIntegrationsConnectionLost("connection dropped");
    });
    let allowReconnect!: () => void;
    const reconnectGate = new Promise<void>((resolve) => {
      allowReconnect = resolve;
    });
    const replacement = controlledConnector({ connect: () => reconnectGate });
    const factory = vi
      .fn<() => ElastiCacheIntegrationsConnector>()
      .mockReturnValueOnce(initial.connector)
      .mockReturnValue(replacement.connector);
    const session = new ElastiCacheIntegrationsSession(
      factory,
      {},
      {
        connectionTimeoutMs: 2_000,
        baseDelayMs: 1,
        maxDelayMs: 1,
        jitter: 0,
      },
    );
    await session.open();

    const failed = session.request(testRequest());
    await expect(failed).rejects.toBeInstanceOf(ElastiCacheIntegrationsConnectionLost);
    await vi.waitFor(() => {
      expect(replacement.connector.connect).toHaveBeenCalledOnce();
    });

    await expect(session.request(testRequest())).resolves.toBeUndefined();
    allowReconnect();
    await waitForState(session, "alive");
    await expect(session.request(testRequest())).resolves.toEqual(
      new ElastiCacheIntegrationsResponse({
        statusCode: 200,
        headers: { "content-type": "application/json" },
        body: Buffer.from("ok"),
      }),
    );
    expect(initial.connector.execute).toHaveBeenCalledTimes(2);
    expect(replacement.connector.handshake).toHaveBeenCalledOnce();
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
      .fn<() => ElastiCacheIntegrationsConnector>()
      .mockReturnValueOnce(initial.connector)
      .mockReturnValue(replacement.connector);
    const session = new ElastiCacheIntegrationsSession(
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
        new ElastiCacheIntegrationsConnector(proxy.host, proxy.port!, {
          tls: false,
          connectionTimeout: 500,
          requestTimeout: 500,
          authProvider,
        }),
    );
    const session = new ElastiCacheIntegrationsSession(
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
      expect(cache.cacheCommandLog.filter((command) => command === "aws.config")).toHaveLength(2);
    } finally {
      await session.close();
      await proxy.stop();
      await cache.stop();
    }
  });

  it("keeps reconnecting until a later attempt succeeds", async () => {
    const initial = controlledConnector();
    let calls = 0;
    const factory = vi.fn<() => ElastiCacheIntegrationsConnector>(() => {
      calls++;
      if (calls === 1) return initial.connector;
      return controlledConnector({
        connect: async () => {
          if (calls < 6) throw new ElastiCacheIntegrationsConnectionError("unavailable");
        },
      }).connector;
    });
    const session = new ElastiCacheIntegrationsSession(
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

  it("ends the reconnect wait with AbortError when the caller aborts, without falling back", async () => {
    const initial = controlledConnector();
    const factory = vi.fn<() => ElastiCacheIntegrationsConnector>(() =>
      factory.mock.calls.length === 1
        ? initial.connector
        : controlledConnector({
            connect: async () => {
              throw new ElastiCacheIntegrationsConnectionError("unavailable");
            },
          }).connector,
    );
    const session = new ElastiCacheIntegrationsSession(
      factory,
      {},
      { connectionTimeoutMs: 60_000, baseDelayMs: 10_000, maxDelayMs: 10_000, jitter: 0 },
    );
    await session.open();
    initial.fail();
    await waitForState(session, "reconnecting");

    const controller = new AbortController();
    const started = performance.now();
    const pending = session.request(testRequest(), { abortSignal: controller.signal });
    setTimeout(() => controller.abort(), 20);

    await expect(pending).rejects.toMatchObject({ name: "AbortError" });
    expect(performance.now() - started).toBeLessThan(5_000);
    await session.close();
  });

  it("rejects an already-aborted request before it is sent", async () => {
    const connector = fakeConnector();
    const session = new ElastiCacheIntegrationsSession(() => connector);
    await session.open();
    const controller = new AbortController();
    controller.abort(new Error("caller gave up"));

    const failure = session.request(testRequest(), { abortSignal: controller.signal });

    await expect(failure).rejects.toMatchObject({
      name: "AbortError",
      message: "Request aborted",
      cause: new Error("caller gave up"),
    });
    expect(vi.mocked(connector.execute).mock.calls.map(([command]) => command)).not.toContain(
      "aws.request",
    );
    await session.close();
  });

  it("stops waiting for the reply when the caller aborts and keeps the connection", async () => {
    let reply: (value: unknown) => void = () => {};
    let requests = 0;
    const controlled = controlledConnector({
      request: () =>
        ++requests === 1 ? new Promise((resolve) => (reply = resolve)) : Promise.resolve(undefined),
    });
    const session = new ElastiCacheIntegrationsSession(() => controlled.connector);
    await session.open();
    const controller = new AbortController();

    const pending = session.request(testRequest(), { abortSignal: controller.signal });
    await vi.waitFor(() => expect(controlled.connector.execute).toHaveBeenCalledTimes(2));
    controller.abort();

    await expect(pending).rejects.toMatchObject({ name: "AbortError" });
    reply(undefined); // the late reply is read and dropped
    expect(session.isConnectionHealthy).toBe(true);
    await expect(session.request(testRequest())).resolves.toMatchObject({ statusCode: 200 });
    await session.close();
  });

  it("allows origin fallback indefinitely by default while reconnecting", async () => {
    const initial = controlledConnector();
    const factory = vi.fn<() => ElastiCacheIntegrationsConnector>(() =>
      factory.mock.calls.length === 1
        ? initial.connector
        : controlledConnector({
            connect: async () => {
              throw new ElastiCacheIntegrationsConnectionError("unavailable");
            },
          }).connector,
    );
    const session = new ElastiCacheIntegrationsSession(
      factory,
      {},
      {
        connectionTimeoutMs: 1,
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

  it("allows finite fallback and then raises ElastiCacheIntegrationsCacheUnavailable", async () => {
    const initial = controlledConnector();
    const factory = vi.fn<() => ElastiCacheIntegrationsConnector>(() =>
      factory.mock.calls.length === 1
        ? initial.connector
        : controlledConnector({
            connect: async () => {
              throw new ElastiCacheIntegrationsConnectionError("unavailable");
            },
          }).connector,
    );
    const session = new ElastiCacheIntegrationsSession(
      factory,
      {},
      {
        fallbackDurationMs: 5,
        connectionTimeoutMs: 1,
        baseDelayMs: 10_000,
        maxDelayMs: 10_000,
        jitter: 0,
      },
    );
    await session.open();
    initial.fail();

    await expect(session.request(testRequest())).resolves.toBeUndefined();
    await new Promise((resolve) => setTimeout(resolve, 10));
    const request = new ElastiCacheIntegrationsRequest({
      method: "GET",
      url: "https://user:secret@x:8443/path?token=sensitive",
      body: "{}",
      metadata: new RequestMetadata({ service: "svc", operation: "GetItem" }),
    });
    const error = await session.request(request).catch((cause: unknown) => cause);
    expect(error).toBeInstanceOf(ElastiCacheIntegrationsCacheUnavailable);
    expect(error).toMatchObject({ endpointUrl: "https://x:8443" });
    expect(String(error)).not.toContain("secret");
    expect(String(error)).not.toContain("token");
    await session.close();
  });

  it("raises when the reconnect wait ends and fallback is disabled", async () => {
    const initial = controlledConnector();
    const replacement = controlledConnector({
      connect: async () => {
        throw new ElastiCacheIntegrationsConnectionError("unavailable");
      },
    });
    const factory = vi
      .fn<() => ElastiCacheIntegrationsConnector>()
      .mockReturnValueOnce(initial.connector)
      .mockReturnValue(replacement.connector);
    const session = new ElastiCacheIntegrationsSession(
      factory,
      {},
      {
        fallbackDurationMs: 0,
        connectionTimeoutMs: 1,
        baseDelayMs: 10_000,
        maxDelayMs: 10_000,
        jitter: 0,
      },
    );
    await session.open();
    initial.fail();

    await expect(session.request(testRequest())).rejects.toBeInstanceOf(
      ElastiCacheIntegrationsCacheUnavailable,
    );
    await session.close();
  });

  it("does not escalate an unmatched request during an outage", async () => {
    const initial = controlledConnector({ predicate: "false" });
    const factory = vi
      .fn<() => ElastiCacheIntegrationsConnector>()
      .mockReturnValueOnce(initial.connector)
      .mockImplementation(
        () =>
          controlledConnector({
            connect: async () => {
              throw new ElastiCacheIntegrationsConnectionError("unavailable");
            },
          }).connector,
      );
    const session = new ElastiCacheIntegrationsSession(
      factory,
      {},
      {
        fallbackDurationMs: 0,
        connectionTimeoutMs: 1,
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
      .fn<() => ElastiCacheIntegrationsConnector>()
      .mockReturnValueOnce(initial.connector)
      .mockReturnValue(replacement.connector);
    const session = new ElastiCacheIntegrationsSession(
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
    vi.useFakeTimers();
    const initial = controlledConnector();
    const failedReplacement = controlledConnector({
      connect: async () => {
        throw new ElastiCacheIntegrationsConnectionError("unavailable");
      },
    });
    const factory = vi
      .fn<() => ElastiCacheIntegrationsConnector>()
      .mockReturnValueOnce(initial.connector)
      .mockReturnValue(failedReplacement.connector);
    const session = new ElastiCacheIntegrationsSession(
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
      await vi.advanceTimersByTimeAsync(10_001);
      expect(factory).toHaveBeenCalledTimes(callsAfterClose);
    } finally {
      await session.close();
      vi.useRealTimers();
    }
  });

  it.each(["handshake", "config"] as const)(
    "times out reconnect when %s hangs and allows shutdown",
    async (failure) => {
      vi.useFakeTimers();
      try {
        const initial = controlledConnector();
        const never = () => new Promise<never>(() => undefined);
        const replacement = controlledConnector({
          ...(failure === "handshake" ? { handshake: never } : { config: never }),
        });
        const factory = vi
          .fn<() => ElastiCacheIntegrationsConnector>()
          .mockReturnValueOnce(initial.connector)
          .mockReturnValue(replacement.connector);
        const session = new ElastiCacheIntegrationsSession(
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

        await vi.waitFor(() => {
          if (failure === "handshake") {
            expect(replacement.connector.handshake).toHaveBeenCalledOnce();
          } else {
            expect(replacement.connector.execute).toHaveBeenCalledWith(
              "aws.config",
              Buffer.from(`{"version":"${PROTOCOL_VERSION}"}`),
            );
          }
        });

        // Each background attempt is capped at 5 s, independent of connectionTimeoutMs.
        await vi.advanceTimersByTimeAsync(5_001);
        expect(replacement.connector.destroy).toHaveBeenCalled();

        const started = performance.now();
        await session.close();
        expect(performance.now() - started).toBeLessThan(500);
        expect(factory).toHaveBeenCalledTimes(2);
      } finally {
        vi.useRealTimers();
      }
    },
  );

  it("creating a session gives up after connection timeout", async () => {
    const port = await unusedPort();
    const connectors: ElastiCacheIntegrationsConnector[] = [];
    const session = new ElastiCacheIntegrationsSession(
      () => {
        const connector = new ElastiCacheIntegrationsConnector("127.0.0.1", port, {
          tls: false,
          authProvider: () => ["test-user", "test-token"],
        });
        connectors.push(connector);
        return connector;
      },
      {},
      { connectionTimeoutMs: 200, baseDelayMs: 10, maxDelayMs: 20, jitter: 0 },
    );
    const started = performance.now();

    await expect(session.open()).rejects.toBeInstanceOf(ElastiCacheIntegrationsConnectionError);

    expect(connectors.length).toBeGreaterThanOrEqual(2);
    expect(performance.now() - started).toBeLessThan(1_000);
    expect(connectors.some((connector) => connector.isReady)).toBe(false);
  });

  it("creating a session uses full connection timeout", async () => {
    const built: ElastiCacheIntegrationsConnector[] = [];
    const session = new ElastiCacheIntegrationsSession(
      () => {
        const { connector } = controlledConnector({
          handshake: async () => {
            throw new ElastiCacheIntegrationsConnectionError("cache unreachable");
          },
        });
        built.push(connector);
        return connector;
      },
      {},
      { connectionTimeoutMs: 300, baseDelayMs: 20, maxDelayMs: 20, jitter: 0 },
    );
    const started = performance.now();

    const error = await session.open().catch((cause: unknown) => cause);

    const elapsed = performance.now() - started;
    expect(error).toBeInstanceOf(ElastiCacheIntegrationsConnectionError);
    expect((error as Error).message).toBe("opening a connection timed out after 0.3s");
    expect((error as Error).cause).toMatchObject({ message: "cache unreachable" });
    // No backoff sleep runs past the deadline.
    expect(elapsed).toBeGreaterThanOrEqual(290);
    expect(elapsed).toBeLessThan(700);
    expect(built.length).toBeGreaterThanOrEqual(3);
    for (const connector of built) expect(connector.close).toHaveBeenCalledOnce();
  });

  it("creating a session preserves non-connection setup error", async () => {
    const built: ElastiCacheIntegrationsConnector[] = [];
    const session = new ElastiCacheIntegrationsSession(
      () => {
        const { connector } = controlledConnector({
          handshake: async () => {
            throw new ElastiCacheIntegrationsOverridesRejected("aws.config rejected");
          },
        });
        built.push(connector);
        return connector;
      },
      {},
      { connectionTimeoutMs: 200, baseDelayMs: 20, maxDelayMs: 20, jitter: 0 },
    );
    const started = performance.now();

    await expect(session.open()).rejects.toBeInstanceOf(ElastiCacheIntegrationsOverridesRejected);

    expect(performance.now() - started).toBeGreaterThanOrEqual(190);
    expect(built.length).toBeGreaterThanOrEqual(2);
    for (const connector of built) expect(connector.close).toHaveBeenCalledOnce();
  });

  it("request waits then passes through when window open", async () => {
    const initial = controlledConnector();
    const session = new ElastiCacheIntegrationsSession(
      outageFactory(initial.connector),
      {},
      { connectionTimeoutMs: 50, ...slowBackoff },
    );
    await session.open();
    initial.fail();
    const started = performance.now();

    await expect(session.request(testRequest())).resolves.toBeUndefined();

    expect(performance.now() - started).toBeGreaterThanOrEqual(45);
    await session.close();
  });

  it("request waits then escalates when window spent", async () => {
    const initial = controlledConnector();
    const session = new ElastiCacheIntegrationsSession(
      outageFactory(initial.connector),
      {},
      { connectionTimeoutMs: 50, fallbackDurationMs: 0, ...slowBackoff },
    );
    await session.open();
    initial.fail();
    const started = performance.now();

    await expect(session.request(testRequest())).rejects.toBeInstanceOf(
      ElastiCacheIntegrationsCacheUnavailable,
    );

    expect(performance.now() - started).toBeGreaterThanOrEqual(45);
    await session.close();
  });

  it("request served when reconnect lands within the wait", async () => {
    const initial = controlledConnector();
    let allowReconnect!: () => void;
    const gate = new Promise<void>((resolve) => {
      allowReconnect = resolve;
    });
    const replacement = controlledConnector({ connect: () => gate });
    const factory = vi
      .fn<() => ElastiCacheIntegrationsConnector>()
      .mockReturnValueOnce(initial.connector)
      .mockReturnValue(replacement.connector);
    const session = new ElastiCacheIntegrationsSession(
      factory,
      {},
      { connectionTimeoutMs: 2_000, fallbackDurationMs: 0, baseDelayMs: 1, maxDelayMs: 1 },
    );
    await session.open();
    initial.fail();

    const pending = session.request(testRequest());
    await sleep(30);
    allowReconnect();

    await expect(pending).resolves.toBeInstanceOf(ElastiCacheIntegrationsResponse);
    expect(replacement.connector.execute).toHaveBeenCalledTimes(2);
    await session.close();
  });

  it("request on a dead connector waits for the reconnect", async () => {
    const initial = controlledConnector();
    // The connector is dead, but it has not reported the loss to the session yet.
    Object.defineProperty(initial.connector, "isDead", { get: () => true });
    const replacement = controlledConnector();
    const factory = vi
      .fn<() => ElastiCacheIntegrationsConnector>()
      .mockReturnValueOnce(initial.connector)
      .mockReturnValue(replacement.connector);
    const session = new ElastiCacheIntegrationsSession(
      factory,
      {},
      { connectionTimeoutMs: 2_000, fallbackDurationMs: 0, baseDelayMs: 1, maxDelayMs: 1 },
    );
    await session.open();

    await expect(session.request(testRequest())).resolves.toBeInstanceOf(
      ElastiCacheIntegrationsResponse,
    );

    // Only the config handshake reached the dead connector.
    expect(initial.connector.execute).toHaveBeenCalledTimes(1);
    expect(replacement.connector.execute).toHaveBeenCalledTimes(2);
    await session.close();
  });

  it("retry after an in-flight loss waits for the reconnect", async () => {
    // The loss goes up for the SDK to retry. The retry finds the dead connector, removes it, and
    // is served on the new connector instead of failing again.
    let lost = false;
    const dying = controlledConnector({
      request: async () => {
        lost = true;
        throw new ElastiCacheIntegrationsConnectionLost("socket died");
      },
    });
    Object.defineProperty(dying.connector, "isDead", { get: () => lost });
    const replacement = controlledConnector();
    const factory = vi
      .fn<() => ElastiCacheIntegrationsConnector>()
      .mockReturnValueOnce(dying.connector)
      .mockReturnValue(replacement.connector);
    const session = new ElastiCacheIntegrationsSession(
      factory,
      {},
      { connectionTimeoutMs: 2_000, fallbackDurationMs: 0, baseDelayMs: 1, maxDelayMs: 1 },
    );
    await session.open();

    await expect(session.request(testRequest())).rejects.toBeInstanceOf(
      ElastiCacheIntegrationsConnectionLost,
    );
    await expect(session.request(testRequest())).resolves.toBeInstanceOf(
      ElastiCacheIntegrationsResponse,
    );

    expect(replacement.connector.execute).toHaveBeenCalledTimes(2);
    await session.close();
  });

  it("request does not wait after the reconnect wait", async () => {
    const initial = controlledConnector();
    const session = new ElastiCacheIntegrationsSession(
      outageFactory(initial.connector),
      {},
      { connectionTimeoutMs: 100, ...slowBackoff },
    );
    await session.open();
    initial.fail();
    await sleep(150);
    const started = performance.now();

    await expect(session.request(testRequest())).resolves.toBeUndefined();

    // A request that waited the full connectionTimeout would take 100 ms.
    expect(performance.now() - started).toBeLessThan(80);
    await session.close();
  });

  it("strict mode does not allow fallback before ready deadline", async () => {
    const initial = controlledConnector();
    const session = new ElastiCacheIntegrationsSession(
      outageFactory(initial.connector),
      {},
      { connectionTimeoutMs: 1_000, fallbackDurationMs: 0, ...slowBackoff },
    );
    await session.open();
    initial.fail();
    let settled = false;
    const pending = session.request(testRequest()).finally(() => {
      settled = true;
    });
    pending.catch(() => undefined);

    await sleep(50);
    expect(settled).toBe(false);

    await expect(pending).rejects.toBeInstanceOf(ElastiCacheIntegrationsCacheUnavailable);
    await session.close();
  });

  it("fallback window starts when the wait ends", async () => {
    const initial = controlledConnector();
    const session = new ElastiCacheIntegrationsSession(
      outageFactory(initial.connector),
      {},
      { connectionTimeoutMs: 5_000, fallbackDurationMs: 1_000, ...slowBackoff },
    );
    await session.open();
    initial.fail();

    const deadlines = session as unknown as { readyDeadline: number; fallbackDeadline: number };
    expect(deadlines.fallbackDeadline - deadlines.readyDeadline).toBe(1_000);
    await session.close();
  });

  it("closing the session wakes a waiting request", async () => {
    const initial = controlledConnector();
    const session = new ElastiCacheIntegrationsSession(
      outageFactory(initial.connector),
      {},
      { connectionTimeoutMs: 5_000, ...slowBackoff },
    );
    await session.open();
    initial.fail();
    const outcome = session.request(testRequest());
    await sleep(20);

    const started = performance.now();
    await session.close();

    // The woken request falls back to the origin.
    await expect(outcome).resolves.toBeUndefined();
    expect(performance.now() - started).toBeLessThan(500);
  });

  it("validates reconnect policy bounds", () => {
    const connector = fakeConnector();
    const aboveTimerLimit = 2_147_483_648;

    expect(
      () => new ElastiCacheIntegrationsSession(() => connector, {}, { connectionTimeoutMs: 0 }),
    ).toThrow(ElastiCacheIntegrationsConfigError);
    expect(
      () => new ElastiCacheIntegrationsSession(() => connector, {}, { baseDelayMs: 0 }),
    ).toThrow("greater than zero");
    expect(
      () =>
        new ElastiCacheIntegrationsSession(
          () => connector,
          {},
          { connectionTimeoutMs: aboveTimerLimit },
        ),
    ).toThrow("at most 2147483647");
    expect(
      () =>
        new ElastiCacheIntegrationsSession(
          () => connector,
          {},
          { authRefreshIntervalMs: aboveTimerLimit },
        ),
    ).toThrow("at most 2147483647");
    expect(
      () => new ElastiCacheIntegrationsSession(() => connector, {}, { authRefreshIntervalMs: 0 }),
    ).toThrow("greater than zero");
    expect(
      () =>
        new ElastiCacheIntegrationsSession(
          () => connector,
          {},
          { authRefreshIntervalMs: 2_000_000_000 },
        ),
    ).toThrow(
      expect.objectContaining({
        name: "ElastiCacheIntegrationsConfigError",
        message: expect.stringContaining("after jitter is applied"),
      }),
    );
    expect(
      () =>
        new ElastiCacheIntegrationsSession(
          () => connector,
          {},
          { authRefreshIntervalMs: 2_000_000_000, jitter: 0 },
        ),
    ).not.toThrow();
    expect(
      () =>
        new ElastiCacheIntegrationsSession(() => connector, {}, { baseDelayMs: aboveTimerLimit }),
    ).toThrow("at most 2147483647");
    expect(
      () =>
        new ElastiCacheIntegrationsSession(() => connector, {}, { maxDelayMs: aboveTimerLimit }),
    ).toThrow("at most 2147483647");
    expect(
      () =>
        new ElastiCacheIntegrationsSession(() => connector, {}, { baseDelayMs: 10, maxDelayMs: 5 }),
    ).toThrow("at least baseDelayMs");
    expect(() => new ElastiCacheIntegrationsSession(() => connector, {}, { jitter: 1 })).toThrow(
      "jitter must be in [0, 1)",
    );
    expect(
      () => new ElastiCacheIntegrationsSession(() => connector, {}, { fallbackDurationMs: 0.5 }),
    ).toThrow("whole number");
    expect(
      () => new ElastiCacheIntegrationsSession(() => connector, {}, { fallbackDurationMs: -0.5 }),
    ).toThrow("whole number");

    const random = vi.spyOn(Math, "random").mockReturnValue(0.999);
    try {
      const session = new ElastiCacheIntegrationsSession(
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

/** Invoke the session's private refresh cycle directly, as the refresher's timer would. */
function refreshOnce(session: ElastiCacheIntegrationsSession): Promise<void> {
  return (session as unknown as { refreshOnce: () => Promise<void> }).refreshOnce();
}

/** Invoke one background authentication refresh directly. */
function reauthenticateOnce(session: ElastiCacheIntegrationsSession): Promise<void> {
  return (
    session as unknown as {
      reauthenticateOnce: () => Promise<void>;
    }
  ).reauthenticateOnce();
}

const REFRESHED_CONFIG = Buffer.from(
  '{"version":"1.0","refresh_interval":30,"predicates":[{"expr":"operation == \'GetItem\'","vars":{}}]}',
);

// Short enough to read, long enough that only a deliberate advance reaches it.
const STALENESS_WINDOW_MS = 10_000;

function putItem(): ElastiCacheIntegrationsRequest {
  return new ElastiCacheIntegrationsRequest({
    method: "POST",
    url: "https://x/",
    body: "{}",
    metadata: new RequestMetadata({ service: "svc", operation: "PutItem" }),
  });
}

describe("ElastiCacheIntegrationsSession config refresh", () => {
  it("re-issues aws.config with no argument after the handshake", async () => {
    const connector = fakeConnector();
    const session = new ElastiCacheIntegrationsSession(connector, { item_ttl: 300 });
    await session.open();
    vi.mocked(connector.execute).mockClear();

    await refreshOnce(session);

    expect(connector.execute).toHaveBeenCalledOnce();
    expect(connector.execute).toHaveBeenCalledWith("aws.config");
    await session.close();
  });

  it("resends the overrides until a parseable config acknowledges them", async () => {
    const connector = fakeConnector();
    const session = new ElastiCacheIntegrationsSession(connector, { item_ttl: 300 });
    await session.open();
    // Simulate a session whose overrides the cache has not confirmed yet.
    (session as unknown as { overridesSent: boolean }).overridesSent = false;
    vi.mocked(connector.execute).mockClear();

    await refreshOnce(session);

    // The reply parsed, so the next refresh drops the overrides.
    await refreshOnce(session);

    expect(connector.execute).toHaveBeenCalledTimes(2);
    expect(connector.execute).toHaveBeenNthCalledWith(
      1,
      "aws.config",
      Buffer.from(`{"version":"${PROTOCOL_VERSION}","item_ttl":300}`),
    );
    expect(connector.execute).toHaveBeenNthCalledWith(2, "aws.config");
    await session.close();
  });

  it.each([
    ["a failed send", () => Promise.reject(new ElastiCacheIntegrationsError("send failed"))],
    ["a malformed reply", () => Promise.resolve(Buffer.from('{"version":"1.0"}'))],
  ])("sends unacknowledged overrides again after %s", async (_name, reply) => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const connector = fakeConnector();
    const session = new ElastiCacheIntegrationsSession(connector, { item_ttl: 300 });
    await session.open();
    // Simulate a session whose overrides the cache has not confirmed yet.
    (session as unknown as { overridesSent: boolean }).overridesSent = false;
    vi.mocked(connector.execute).mockImplementation(reply);

    // A failed send and a malformed reply both leave the overrides unapplied, so resending is
    // idempotent while never resending would leave the connection wrongly configured for good.
    await refreshOnce(session);
    expect((session as unknown as { overridesSent: boolean }).overridesSent).toBe(false);

    vi.mocked(connector.execute).mockClear();
    vi.mocked(connector.execute).mockResolvedValue(REFRESHED_CONFIG);
    await refreshOnce(session);

    expect(connector.execute).toHaveBeenCalledWith(
      "aws.config",
      Buffer.from(`{"version":"${PROTOCOL_VERSION}","item_ttl":300}`),
    );
    expect((session as unknown as { overridesSent: boolean }).overridesSent).toBe(true);
    warn.mockRestore();
    await session.close();
  });

  it("treats a null reply as unchanged", async () => {
    const connector = fakeConnector();
    const session = new ElastiCacheIntegrationsSession(connector);
    await session.open();
    const config = session.config;
    vi.mocked(connector.execute).mockResolvedValue(null);

    await refreshOnce(session);

    expect(session.config).toBe(config);
    await session.close();
  });

  it("installs a server-side config change and its predicates", async () => {
    const connector = fakeConnector();
    const session = new ElastiCacheIntegrationsSession(connector);
    await session.open();
    // The opening config matches everything; the refreshed one only matches GetItem.
    await expect(session.request(putItem())).resolves.toBeDefined();
    vi.mocked(connector.execute).mockResolvedValue(REFRESHED_CONFIG);

    await refreshOnce(session);

    // A refresh carries no overrides, so it cannot renegotiate: the version is the one
    // settled at the handshake. The interval and the predicates are what moved.
    expect(session.config?.version).toBe(PROTOCOL_VERSION);
    expect(session.config?.refreshInterval).toBe(30);
    await expect(session.request(putItem())).resolves.toBeUndefined();
    await session.close();
  });

  it("reschedules the refresher on the refreshed interval", async () => {
    const connector = fakeConnector();
    const session = new ElastiCacheIntegrationsSession(connector);
    await session.open();
    const refresher = configRefresherOf(session);
    if (refresher === undefined) throw new Error("expected a refresher");
    const setInterval = vi.spyOn(refresher, "setInterval");
    vi.mocked(connector.execute).mockResolvedValue(REFRESHED_CONFIG);

    await refreshOnce(session);

    expect(setInterval).toHaveBeenCalledWith(30);
    await session.close();
  });

  it.each([
    [
      "a failed command",
      () => Promise.reject(new ElastiCacheIntegrationsError("aws.config failed")),
    ],
    ["a malformed reply", () => Promise.resolve(Buffer.from("not json"))],
    ["a config missing refresh_interval", () => Promise.resolve(Buffer.from('{"version":"1.0"}'))],
  ])("keeps the last known-good config after %s", async (_name, reply) => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const connector = fakeConnector();
    const session = new ElastiCacheIntegrationsSession(connector);
    await session.open();
    const config = session.config;
    const refresher = configRefresherOf(session);
    if (refresher === undefined) throw new Error("expected a refresher");
    const setInterval = vi.spyOn(refresher, "setInterval");
    vi.mocked(connector.execute).mockImplementation(reply);

    await expect(refreshOnce(session)).resolves.toBeUndefined();

    expect(session.config).toBe(config);
    expect(session.isOpen).toBe(true);
    // The predicates from the opening config still route requests to ElastiCacheIntegrations.
    vi.mocked(connector.execute).mockResolvedValue([200, Buffer.alloc(0), Buffer.alloc(0)]);
    await expect(session.request(putItem())).resolves.toBeDefined();
    expect(setInterval).not.toHaveBeenCalled();
    expect(warn).toHaveBeenCalledOnce();
    warn.mockRestore();
    await session.close();
  });

  it("keeps the whole previous config when the new interval cannot be scheduled", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const connector = fakeConnector();
    const session = new ElastiCacheIntegrationsSession(connector);
    await session.open();
    const config = session.config;
    // An interval whose jittered delay exceeds Node's maximum schedulable timeout.
    vi.mocked(connector.execute).mockResolvedValue(
      Buffer.from('{"version":"1.0","refresh_interval":2147484,"predicates":[]}'),
    );

    await expect(refreshOnce(session)).resolves.toBeUndefined();

    expect(session.config).toBe(config);
    // The opening config's predicates still route requests to ElastiCacheIntegrations.
    vi.mocked(connector.execute).mockResolvedValue([200, Buffer.alloc(0), Buffer.alloc(0)]);
    await expect(session.request(putItem())).resolves.toBeDefined();
    expect(warn).toHaveBeenCalledOnce();
    warn.mockRestore();
    await session.close();
  });

  it("propagates a non-ElastiCacheIntegrations error so the refresher can swallow it", async () => {
    const connector = fakeConnector();
    const session = new ElastiCacheIntegrationsSession(connector);
    await session.open();
    const failure = new Error("not a ElastiCacheIntegrationsError");
    vi.mocked(connector.execute).mockRejectedValue(failure);

    await expect(refreshOnce(session)).rejects.toBe(failure);
    await session.close();
  });

  // --- refresh: the staleness window ---

  /** A session whose config stops being trusted `STALENESS_WINDOW_MS` after it is confirmed. */
  function staleSession(
    connector: ElastiCacheIntegrationsConnector,
    clock: { now: number },
  ): ElastiCacheIntegrationsSession {
    return new ElastiCacheIntegrationsSession(
      connector,
      {},
      {
        connectionTimeoutMs: 2_000,
        stalenessDurationMs: STALENESS_WINDOW_MS,
        clock: () => clock.now,
      },
    );
  }

  it("starts the window trusted once a connection is configured", async () => {
    const clock = { now: 0 };
    const session = staleSession(fakeConnector(), clock);
    await session.open();

    expect(session.configStale).toBe(false);
    clock.now = STALENESS_WINDOW_MS;
    expect(session.configStale).toBe(true);
    await session.close();
  });

  it("keeps a healthy connection when a refresh succeeds past the window", async () => {
    // The window bounds how long refreshes may fail, not the time between ticks.
    const clock = { now: 0 };
    const connector = fakeConnector();
    const session = staleSession(connector, clock);
    await session.open();
    vi.mocked(connector.execute).mockClear();
    vi.mocked(connector.execute).mockResolvedValueOnce(null);

    clock.now = STALENESS_WINDOW_MS;
    await refreshOnce(session);

    expect(connector.execute).toHaveBeenCalledOnce();
    expect(connector.close).not.toHaveBeenCalled();
    expect(session.connectionState).toBe("alive");
    expect(session.configStale).toBe(false);
    await session.close();
  });

  it("drops the connection when a refresh fails past the window", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const clock = { now: 0 };
    const connector = fakeConnector();
    const session = staleSession(connector, clock);
    await session.open();
    vi.mocked(connector.execute).mockClear();
    vi.mocked(connector.execute).mockResolvedValueOnce(Buffer.from("not json"));

    clock.now = STALENESS_WINDOW_MS;
    await refreshOnce(session);

    expect(connector.execute).toHaveBeenCalledOnce();
    expect(connector.close).toHaveBeenCalledOnce();
    expect(connector.destroy).not.toHaveBeenCalled();
    expect(session.isOpen).toBe(true);
    expect(session.connectionState).toBe("reconnecting");
    expect(warn).toHaveBeenCalledWith(expect.stringContaining("dropping the connection"));

    // The request has not been sent while there is no connector, so it can use outage fallback.
    await expect(session.request(putItem())).resolves.toBeUndefined();
    expect(connector.execute).toHaveBeenCalledOnce();

    // Explicit close() remains terminal and does not tear the discarded connector down twice.
    await session.close();
    expect(connector.close).toHaveBeenCalledOnce();
    warn.mockRestore();
  });

  it("keeps the connection while failing refreshes are still inside the window", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const clock = { now: 0 };
    const connector = fakeConnector();
    const session = staleSession(connector, clock);
    await session.open();
    vi.mocked(connector.execute).mockResolvedValue(Buffer.from("not json"));

    clock.now = STALENESS_WINDOW_MS - 1;
    await refreshOnce(session);
    expect(session.connectionState).toBe("alive");
    expect(connector.close).not.toHaveBeenCalled();

    // A failed refresh does not confirm the config, so the next tick finds the window passed.
    clock.now = STALENESS_WINDOW_MS;
    await refreshOnce(session);
    expect(session.connectionState).toBe("reconnecting");
    expect(connector.close).toHaveBeenCalledOnce();
    await session.close();
    warn.mockRestore();
  });

  it.each([
    ["a null (unchanged) reply", null],
    ["an installed config", REFRESHED_CONFIG],
  ])("restarts the window on %s", async (_, reply) => {
    const clock = { now: 0 };
    const connector = fakeConnector();
    const session = staleSession(connector, clock);
    await session.open();

    clock.now = STALENESS_WINDOW_MS - 1;
    vi.mocked(connector.execute).mockResolvedValueOnce(reply);
    await refreshOnce(session);

    // Without the restart above, the window would have passed a whole window ago.
    clock.now = 2 * STALENESS_WINDOW_MS - 2;
    expect(session.configStale).toBe(false);
    await refreshOnce(session);
    expect(session.connectionState).toBe("alive");
    expect(connector.close).not.toHaveBeenCalled();
    await session.close();
  });

  it("alarms at once on CONFIG_INVALID but keeps the connection and config", async () => {
    const error = vi.spyOn(console, "error").mockImplementation(() => undefined);
    const connector = fakeConnector();
    const session = new ElastiCacheIntegrationsSession(connector);
    await session.open();
    const config = session.config;
    vi.mocked(connector.execute).mockResolvedValue(
      new RespError("CONFIG_INVALID", "table does not exist"),
    );

    await refreshOnce(session);

    expect(session.config).toBe(config);
    expect(session.connectionState).toBe("alive");
    expect(connector.close).not.toHaveBeenCalled();
    expect(error).toHaveBeenCalledWith(expect.stringContaining("CONFIG_INVALID"));
    await session.close();
    error.mockRestore();
  });

  it("alarms at once on an unevaluable config and installs none of it", async () => {
    const error = vi.spyOn(console, "error").mockImplementation(() => undefined);
    const connector = fakeConnector();
    const session = new ElastiCacheIntegrationsSession(connector);
    await session.open();
    const config = session.config;
    const refresher = configRefresherOf(session);
    if (refresher === undefined) throw new Error("expected a refresher");
    const setInterval = vi.spyOn(refresher, "setInterval");
    vi.mocked(connector.execute).mockResolvedValue(
      Buffer.from(
        '{"version":"1.0","refresh_interval":30,"predicates":[' +
          '{"expr":"operation == \'GetItem\'","vars":{}},{"expr":"this is not (valid CEL"}]}',
      ),
    );

    await refreshOnce(session);

    // Neither the config, its predicates, nor its interval were installed.
    expect(session.config).toBe(config);
    expect(setInterval).not.toHaveBeenCalled();
    vi.mocked(connector.execute).mockResolvedValue([200, Buffer.alloc(0), Buffer.alloc(0)]);
    await expect(session.request(putItem())).resolves.toBeDefined();
    expect(session.connectionState).toBe("alive");
    expect(connector.close).not.toHaveBeenCalled();
    expect(error).toHaveBeenCalledWith(expect.stringContaining("cannot evaluate"));
    await session.close();
    error.mockRestore();
  });

  it("lets repeated CONFIG_INVALID refreshes reach the window and drop the connection", async () => {
    const error = vi.spyOn(console, "error").mockImplementation(() => undefined);
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const clock = { now: 0 };
    const connector = fakeConnector();
    const session = staleSession(connector, clock);
    await session.open();
    vi.mocked(connector.execute).mockResolvedValue(new RespError("CONFIG_INVALID", "rejected"));

    clock.now = STALENESS_WINDOW_MS - 1;
    await refreshOnce(session);
    expect(session.configStale).toBe(false);
    expect(connector.close).not.toHaveBeenCalled();

    clock.now = STALENESS_WINDOW_MS;
    await refreshOnce(session);
    expect(connector.close).toHaveBeenCalledOnce();
    expect(warn).toHaveBeenCalledWith(expect.stringContaining("dropping the connection"));
    await session.close();
    error.mockRestore();
    warn.mockRestore();
  });

  it.each([
    [
      "a CONFIG_INVALID reply",
      new RespError("CONFIG_INVALID", "table does not exist"),
      ElastiCacheIntegrationsOverridesRejected,
    ],
    [
      "an unevaluable config",
      Buffer.from('{"version":"1.0","refresh_interval":60,"predicates":[{"expr":"(("}]}'),
      ElastiCacheIntegrationsConfigUnevaluable,
    ],
  ])("fails to open on %s and alarms at error level", async (_, reply, ErrorType) => {
    const error = vi.spyOn(console, "error").mockImplementation(() => undefined);
    const connector = fakeConnector({ execute: vi.fn(async () => reply) });
    const session = new ElastiCacheIntegrationsSession(connector);

    await expect(session.open()).rejects.toBeInstanceOf(ErrorType);
    expect(configRefresherOf(session)).toBeUndefined();
    expect(error).toHaveBeenCalledWith(expect.stringContaining("Configuring a connection failed"));
    error.mockRestore();
  });

  it("propagates an in-flight request error when it races a stale drop", async () => {
    // request() itself cannot tell whether this request already reached ElastiCacheIntegrations before the
    // connector closed, so it must not silently convert the failure into an origin fallback: only
    // the middleware knows whether the request is idempotent enough to safely retry.
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const clock = { now: 0 };
    const connector = fakeConnector();
    const session = staleSession(connector, clock);
    await session.open();

    let rejectRequest!: (error: unknown) => void;
    const connectionLost = new ElastiCacheIntegrationsConnectionLost("connection closed");
    vi.mocked(connector.execute)
      .mockImplementationOnce(
        () =>
          new Promise((_resolve, reject) => {
            rejectRequest = reject;
          }),
      )
      // The refresh fails past the window, which is what drops the connection.
      .mockResolvedValueOnce(Buffer.from("not json"));
    vi.mocked(connector.close).mockImplementation(async () => {
      // Dropping the stale connection closes the connector, rejecting requests already in flight.
      rejectRequest(connectionLost);
    });

    // Started while the session is still open, so it reaches the connector.
    const requesting = session.request(putItem());

    clock.now = STALENESS_WINDOW_MS;
    await refreshOnce(session);
    expect(session.connectionState).toBe("reconnecting");

    await expect(requesting).rejects.toBe(connectionLost);
    await session.close();
    warn.mockRestore();
  });

  it("does not install a config that arrives after teardown", async () => {
    let resolveConfig!: (value: Buffer) => void;
    const connector = fakeConnector();
    const session = new ElastiCacheIntegrationsSession(connector);
    await session.open();
    const config = session.config;
    vi.mocked(connector.execute).mockImplementation(
      () =>
        new Promise<Buffer>((resolve) => {
          resolveConfig = resolve;
        }),
    );

    const refreshing = refreshOnce(session);
    await session.close();
    resolveConfig(REFRESHED_CONFIG);
    await refreshing;

    expect(session.config).toBe(config);
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
    expect(() => parseEndpoint(endpoint)).toThrow(ElastiCacheIntegrationsError);
  });
});
