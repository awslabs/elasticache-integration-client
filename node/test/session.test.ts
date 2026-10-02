import { describe, expect, it, vi } from "vitest";

import {
  PhotonAuthError,
  PhotonCacheUnavailable,
  PhotonConfigError,
  PhotonOverridesRejected,
  PhotonConfigUnevaluable,
  PhotonConnectionError,
  PhotonConnectionLost,
  PhotonError,
  PhotonTimeoutError,
} from "../src/errors.js";
import { PhotonConnector } from "../src/connection.js";
import { PhotonRequest, PhotonResponse, RequestMetadata, ResolvedConfig } from "../src/models.js";
import { RespError } from "../src/resp.js";
import { PhotonSession, parseEndpoint } from "../src/session.js";
import type { Refresher } from "../src/refresh.js";
import { MockCache, MockTcpProxy } from "./mock-cache.js";

function fakeConnector(overrides: Partial<PhotonConnector> = {}): PhotonConnector {
  return {
    isReady: true,
    connect: vi.fn(async () => undefined),
    handshake: vi.fn(async () => undefined),
    reauthenticate: vi.fn(async () => undefined),
    execute: vi.fn(async (command: string, ...args: Buffer[]) => {
      if (command !== "photon.config") {
        return [200, Buffer.from("content-type:application/json"), Buffer.from("ok")];
      }
      // A no-argument photon.config means "nothing changed", as the cache does.
      if (args.length === 0) return null;
      return Buffer.from(
        '{"version":"v1","refresh_interval":60,"predicates":[{"expr":"true","vars":{}}]}',
      );
    }),
    close: vi.fn(async () => undefined),
    destroy: vi.fn(() => undefined),
    ...overrides,
  } as unknown as PhotonConnector;
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
    handshake: vi.fn(async () => {
      await options.handshake?.();
    }),
    reauthenticate: vi.fn(async () => {
      await options.reauthenticate?.();
    }),
    execute: vi.fn(async (command: string) => {
      if (command === "photon.config") {
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

/** Reach the session's private refreshers; lifecycle assertions need them, callers do not. */
function configRefresherOf(session: PhotonSession): Refresher | undefined {
  return (session as unknown as { configRefresher: Refresher | undefined }).configRefresher;
}

function reauthRefresherOf(session: PhotonSession): Refresher | undefined {
  return (session as unknown as { reauthRefresher: Refresher | undefined }).reauthRefresher;
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
    expect(connector.handshake).toHaveBeenCalledOnce();
    expect(connector.execute).toHaveBeenCalledWith("photon.config", Buffer.from('{"ttl":300}'));
    expect(session.config).toEqual(expect.any(ResolvedConfig));
    expect(session.config.raw).toEqual({
      version: "v1",
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
    await session.close();
  });

  it("closes a stale-auth connection and passes the rejected request through", async () => {
    let ready = true;
    const close = vi.fn(async () => {
      ready = false;
    });
    const connector = fakeConnector({
      execute: vi.fn(async (command: string) =>
        command === "photon.config"
          ? Buffer.from(
              '{"version":"v1","refresh_interval":60,"predicates":[{"expr":"true","vars":{}}]}',
            )
          : new RespError("AUTH_ERROR", "missing FAS"),
      ),
      close,
    });
    Object.defineProperty(connector, "isReady", { get: () => ready });
    const session = new PhotonSession(connector);
    await session.open();

    const response = await session.request(
      new PhotonRequest({
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
        new PhotonRequest({
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
        command === "photon.config"
          ? Buffer.from(
              '{"refresh_interval":60,"predicates":[{"expr":"operation == \'GetItem\'","vars":{}}]}',
            )
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
        handshake: vi.fn(async () => {
          if (failure === "handshake") throw original;
        }),
        execute: vi.fn(async () => {
          if (failure === "config") throw original;
          return Buffer.from('{"refresh_interval":60}');
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
      handshake: vi.fn(),
      execute: vi.fn(() => config),
    });
    const session = new PhotonSession(connector);
    const opening = session.open();

    await vi.waitFor(() => {
      expect(connector.execute).toHaveBeenCalledWith("photon.config", Buffer.from("{}"));
    });
    resolveConfig(Buffer.from('{"version":"v1","refresh_interval":60}'));
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

  it.each(["close", "destroy"] as const)(
    "releases connector-factory resources exactly once on %s",
    async (teardown) => {
      const connector = fakeConnector();
      const closeFactory = vi.fn();
      const factory = Object.assign(() => connector, { close: closeFactory });
      const session = new PhotonSession(factory);
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
    const session = new PhotonSession(fakeConnector());

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
    const session = new PhotonSession(fakeConnector(), {}, { jitter: 0 });

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
        throw new PhotonError("config failed");
      }),
    });
    const session = new PhotonSession(connector);

    await expect(session.open()).rejects.toThrow("config failed");
    expect(configRefresherOf(session)).toBeUndefined();
    expect(reauthRefresherOf(session)).toBeUndefined();
    expect(connector.close).toHaveBeenCalledOnce();
  });

  it("re-authenticates the live connector", async () => {
    const connector = fakeConnector();
    const session = new PhotonSession(connector);
    await session.open();
    vi.mocked(connector.reauthenticate).mockClear();

    await reauthenticateOnce(session);

    expect(connector.reauthenticate).toHaveBeenCalledOnce();
    await session.close();
  });

  it("propagates a re-AUTH rejection so the refresher applies backoff", async () => {
    const rejection = new PhotonAuthError("IAM AUTH rejected: WRONGPASS");
    const connector = fakeConnector({
      reauthenticate: vi.fn(async () => {
        throw rejection;
      }),
    });
    const session = new PhotonSession(connector);
    await session.open();

    await expect(reauthenticateOnce(session)).rejects.toBe(rejection);
    await session.close();
  });

  it("skips re-AUTH while reconnecting", async () => {
    const connector = fakeConnector();
    const session = new PhotonSession(connector);
    await session.open();
    vi.mocked(connector.reauthenticate).mockClear();
    const internals = session as unknown as {
      connection: "alive" | "reconnecting";
      connector: PhotonConnector | undefined;
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
      .fn<() => PhotonConnector>()
      .mockReturnValueOnce(initial.connector)
      .mockReturnValue(replacement.connector);
    const session = new PhotonSession(factory, {}, { baseDelayMs: 1, maxDelayMs: 1, jitter: 0 });
    await session.open();
    const refresher = configRefresherOf(session);
    if (refresher === undefined) throw new Error("expected a refresher");
    const stop = vi.spyOn(refresher, "stop");
    vi.mocked(initial.connector.execute).mockRejectedValue(
      new PhotonTimeoutError("request timed out during refresh"),
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
        new PhotonConnector(cache.host, cache.port!, {
          tls: false,
          authProvider: () => ["test-user", "test-token"],
        }),
    );
    const session = new PhotonSession(factory, {}, { baseDelayMs: 1, maxDelayMs: 1, jitter: 0 });
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
    const session = new PhotonSession(connector);
    await session.open();
    vi.mocked(connector.execute).mockImplementation(
      () =>
        new Promise<never>((_, reject) => {
          rejectRefresh = reject;
        }),
    );

    const refreshing = refreshOnce(session);
    await session.close();
    rejectRefresh(new PhotonConnectionLost("connection closed"));
    await refreshing;

    expect(warn).not.toHaveBeenCalled();
    warn.mockRestore();
  });

  it.each(["close", "destroy"] as const)(
    "stops both refreshers before tearing the connector down on %s",
    async (teardown) => {
      const connector = fakeConnector();
      const session = new PhotonSession(connector);
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
    const session = new PhotonSession(fakeConnector());
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

  it("bounds the complete initial connect, HELLO, and config attempt", async () => {
    const cache = new MockCache({ failure: "hang", failureCommand: "hello" });
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
        return Buffer.from('{"refresh_interval":60,"predicates":[{"expr":"true","vars":{}}]}');
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
    vi.useFakeTimers();
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
      const initial = controlledConnector();
      const never = () => new Promise<never>(() => undefined);
      const replacement = controlledConnector({
        ...(failure === "handshake" ? { handshake: never } : { config: never }),
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
        if (failure === "handshake") {
          expect(replacement.connector.handshake).toHaveBeenCalledOnce();
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
    expect(
      () => new PhotonSession(() => connector, {}, { authRefreshIntervalMs: aboveTimerLimit }),
    ).toThrow("at most 2147483647");
    expect(() => new PhotonSession(() => connector, {}, { authRefreshIntervalMs: 0 })).toThrow(
      "greater than zero",
    );
    expect(
      () => new PhotonSession(() => connector, {}, { authRefreshIntervalMs: 2_000_000_000 }),
    ).toThrow(
      expect.objectContaining({
        name: "PhotonConfigError",
        message: expect.stringContaining("after jitter is applied"),
      }),
    );
    expect(
      () =>
        new PhotonSession(() => connector, {}, { authRefreshIntervalMs: 2_000_000_000, jitter: 0 }),
    ).not.toThrow();
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

/** Invoke the session's private refresh cycle directly, as the refresher's timer would. */
function refreshOnce(session: PhotonSession): Promise<void> {
  return (session as unknown as { refreshOnce: () => Promise<void> }).refreshOnce();
}

/** Invoke one background authentication refresh directly. */
function reauthenticateOnce(session: PhotonSession): Promise<void> {
  return (
    session as unknown as {
      reauthenticateOnce: () => Promise<void>;
    }
  ).reauthenticateOnce();
}

const REFRESHED_CONFIG = Buffer.from(
  '{"version":"v2","refresh_interval":30,"predicates":[{"expr":"operation == \'GetItem\'","vars":{}}]}',
);

// Short enough to read, long enough that only a deliberate advance reaches it.
const STALENESS_WINDOW_MS = 10_000;

function putItem(): PhotonRequest {
  return new PhotonRequest({
    method: "POST",
    url: "https://x/",
    body: "{}",
    metadata: new RequestMetadata({ service: "svc", operation: "PutItem" }),
  });
}

describe("PhotonSession config refresh", () => {
  it("re-issues photon.config with no argument after the handshake", async () => {
    const connector = fakeConnector();
    const session = new PhotonSession(connector, { ttl: 300 });
    await session.open();
    vi.mocked(connector.execute).mockClear();

    await refreshOnce(session);

    expect(connector.execute).toHaveBeenCalledOnce();
    expect(connector.execute).toHaveBeenCalledWith("photon.config");
    await session.close();
  });

  it("resends the overrides until a parseable config acknowledges them", async () => {
    const connector = fakeConnector();
    const session = new PhotonSession(connector, { ttl: 300 });
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
      "photon.config",
      Buffer.from('{"ttl":300}'),
    );
    expect(connector.execute).toHaveBeenNthCalledWith(2, "photon.config");
    await session.close();
  });

  it.each([
    ["a failed send", () => Promise.reject(new PhotonError("send failed"))],
    ["a malformed reply", () => Promise.resolve(Buffer.from('{"version":"v2"}'))],
  ])("sends unacknowledged overrides again after %s", async (_name, reply) => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const connector = fakeConnector();
    const session = new PhotonSession(connector, { ttl: 300 });
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

    expect(connector.execute).toHaveBeenCalledWith("photon.config", Buffer.from('{"ttl":300}'));
    expect((session as unknown as { overridesSent: boolean }).overridesSent).toBe(true);
    warn.mockRestore();
    await session.close();
  });

  it("treats a null reply as unchanged", async () => {
    const connector = fakeConnector();
    const session = new PhotonSession(connector);
    await session.open();
    const config = session.config;
    vi.mocked(connector.execute).mockResolvedValue(null);

    await refreshOnce(session);

    expect(session.config).toBe(config);
    await session.close();
  });

  it("installs a server-side config change and its predicates", async () => {
    const connector = fakeConnector();
    const session = new PhotonSession(connector);
    await session.open();
    // The opening config matches everything; the refreshed one only matches GetItem.
    await expect(session.request(putItem())).resolves.toBeDefined();
    vi.mocked(connector.execute).mockResolvedValue(REFRESHED_CONFIG);

    await refreshOnce(session);

    expect(session.config?.version).toBe("v2");
    expect(session.config?.refreshInterval).toBe(30);
    await expect(session.request(putItem())).resolves.toBeUndefined();
    await session.close();
  });

  it("reschedules the refresher on the refreshed interval", async () => {
    const connector = fakeConnector();
    const session = new PhotonSession(connector);
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
    ["a failed command", () => Promise.reject(new PhotonError("photon.config failed"))],
    ["a malformed reply", () => Promise.resolve(Buffer.from("not json"))],
    ["a config missing refresh_interval", () => Promise.resolve(Buffer.from('{"version":"v2"}'))],
  ])("keeps the last known-good config after %s", async (_name, reply) => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const connector = fakeConnector();
    const session = new PhotonSession(connector);
    await session.open();
    const config = session.config;
    const refresher = configRefresherOf(session);
    if (refresher === undefined) throw new Error("expected a refresher");
    const setInterval = vi.spyOn(refresher, "setInterval");
    vi.mocked(connector.execute).mockImplementation(reply);

    await expect(refreshOnce(session)).resolves.toBeUndefined();

    expect(session.config).toBe(config);
    expect(session.isOpen).toBe(true);
    // The predicates from the opening config still route requests to Photon.
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
    const session = new PhotonSession(connector);
    await session.open();
    const config = session.config;
    // An interval whose jittered delay exceeds Node's maximum schedulable timeout.
    vi.mocked(connector.execute).mockResolvedValue(
      Buffer.from('{"version":"v2","refresh_interval":2147484,"predicates":[]}'),
    );

    await expect(refreshOnce(session)).resolves.toBeUndefined();

    expect(session.config).toBe(config);
    // The opening config's predicates still route requests to Photon.
    vi.mocked(connector.execute).mockResolvedValue([200, Buffer.alloc(0), Buffer.alloc(0)]);
    await expect(session.request(putItem())).resolves.toBeDefined();
    expect(warn).toHaveBeenCalledOnce();
    warn.mockRestore();
    await session.close();
  });

  it("propagates a non-Photon error so the refresher can swallow it", async () => {
    const connector = fakeConnector();
    const session = new PhotonSession(connector);
    await session.open();
    const failure = new Error("not a PhotonError");
    vi.mocked(connector.execute).mockRejectedValue(failure);

    await expect(refreshOnce(session)).rejects.toBe(failure);
    await session.close();
  });

  // --- refresh: the staleness window ---

  /** A session whose config stops being trusted `STALENESS_WINDOW_MS` after it is confirmed. */
  function staleSession(connector: PhotonConnector, clock: { now: number }): PhotonSession {
    return new PhotonSession(
      connector,
      {},
      { stalenessDurationMs: STALENESS_WINDOW_MS, clock: () => clock.now },
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
    const session = new PhotonSession(connector);
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
    const session = new PhotonSession(connector);
    await session.open();
    const config = session.config;
    const refresher = configRefresherOf(session);
    if (refresher === undefined) throw new Error("expected a refresher");
    const setInterval = vi.spyOn(refresher, "setInterval");
    vi.mocked(connector.execute).mockResolvedValue(
      Buffer.from(
        '{"version":"v2","refresh_interval":30,"predicates":[' +
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
      PhotonOverridesRejected,
    ],
    [
      "an unevaluable config",
      Buffer.from('{"version":"v1","refresh_interval":60,"predicates":[{"expr":"(("}]}'),
      PhotonConfigUnevaluable,
    ],
  ])("fails to open on %s and alarms at error level", async (_, reply, ErrorType) => {
    const error = vi.spyOn(console, "error").mockImplementation(() => undefined);
    const connector = fakeConnector({ execute: vi.fn(async () => reply) });
    const session = new PhotonSession(connector);

    await expect(session.open()).rejects.toBeInstanceOf(ErrorType);
    expect(configRefresherOf(session)).toBeUndefined();
    expect(error).toHaveBeenCalledWith(expect.stringContaining("Configuring a connection failed"));
    error.mockRestore();
  });

  it("propagates an in-flight request error when it races a stale drop", async () => {
    // request() itself cannot tell whether this request already reached Photon before the
    // connector closed, so it must not silently convert the failure into an origin fallback: only
    // the middleware knows whether the request is idempotent enough to safely retry.
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const clock = { now: 0 };
    const connector = fakeConnector();
    const session = staleSession(connector, clock);
    await session.open();

    let rejectRequest!: (error: unknown) => void;
    const connectionLost = new PhotonConnectionLost("connection closed");
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
    const session = new PhotonSession(connector);
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
    expect(() => parseEndpoint(endpoint)).toThrow(PhotonError);
  });
});
