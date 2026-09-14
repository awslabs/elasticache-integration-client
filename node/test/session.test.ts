import { describe, expect, it, vi } from "vitest";

import {
  PhotonCircuitOpenError,
  PhotonConnectionError,
  PhotonConnectionLost,
  PhotonError,
} from "../src/errors.js";
import { PhotonRequest, PhotonResponse, RequestMetadata, ResolvedConfig } from "../src/models.js";
import { PhotonSession, parseEndpoint } from "../src/session.js";
import type { PhotonConnector } from "../src/connection.js";

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
    request?: () => Promise<unknown>;
  } = {},
) {
  let dead = false;
  const connector = {
    connect: vi.fn(async () => {
      await options.connect?.();
      dead = false;
    }),
    authenticate: vi.fn(async () => undefined),
    execute: vi.fn(async (command: string) => {
      if (command === "photon.config") {
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
    }),
    destroy: vi.fn(() => {
      dead = true;
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
      expect(connector.close).toHaveBeenCalledTimes(failure === "connect" ? 0 : 1);
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

    await expect(opening).rejects.toThrow("session closed during open()");
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

  it("does not replay a failed request and recovers in the background", async () => {
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
        maxReconnectAttempts: 1,
        baseDelayMs: 1,
        maxDelayMs: 1,
      },
    );
    await session.open();

    const failed = session.request(testRequest());
    await expect(failed).rejects.toBeInstanceOf(PhotonConnectionLost);
    await vi.waitFor(() => {
      expect(replacement.connector.connect).toHaveBeenCalledOnce();
    });

    const next = session.request(testRequest());
    allowReconnect();
    await expect(next).resolves.toEqual(
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

  it("shares one recovery flight across concurrent requests", async () => {
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
        maxReconnectAttempts: 1,
        baseDelayMs: 1,
        maxDelayMs: 1,
      },
    );
    await session.open();
    initial.fail();

    const requests = [session.request(testRequest()), session.request(testRequest())];
    await vi.waitFor(() => {
      expect(replacement.connector.connect).toHaveBeenCalledOnce();
    });
    allowReconnect();

    await expect(Promise.all(requests)).resolves.toHaveLength(2);
    expect(factory).toHaveBeenCalledTimes(2);
    expect(replacement.connector.execute).toHaveBeenCalledTimes(3);
    await session.close();
  });

  it("opens the circuit and uses one half-open probe to restore service", async () => {
    const initial = controlledConnector();
    const failedReplacement = controlledConnector({
      connect: async () => {
        throw new PhotonConnectionError("unavailable");
      },
    });
    const healthyReplacement = controlledConnector();
    const factory = vi
      .fn<() => PhotonConnector>()
      .mockReturnValueOnce(initial.connector)
      .mockReturnValueOnce(failedReplacement.connector)
      .mockReturnValue(healthyReplacement.connector);
    const session = new PhotonSession(
      factory,
      {},
      {
        maxReconnectAttempts: 1,
        baseDelayMs: 1,
        maxDelayMs: 1,
        circuitFailureThreshold: 1,
        circuitOpenDurationMs: 5,
      },
    );
    await session.open();
    initial.fail();

    await expect(session.request(testRequest())).rejects.toThrow("unavailable");
    expect(session.circuitState).toBe("open");
    await expect(session.request(testRequest())).rejects.toBeInstanceOf(PhotonCircuitOpenError);
    expect(factory).toHaveBeenCalledTimes(2);

    await new Promise((resolve) => setTimeout(resolve, 10));
    await expect(session.request(testRequest())).resolves.toBeInstanceOf(PhotonResponse);
    expect(session.circuitState).toBe("closed");
    expect(factory).toHaveBeenCalledTimes(3);
    await session.close();
  });

  it("re-evaluates a waiting request against predicates from the recovered config", async () => {
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
        maxReconnectAttempts: 1,
        baseDelayMs: 1,
        maxDelayMs: 1,
      },
    );
    await session.open();
    initial.fail();

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
        maxReconnectAttempts: 10,
        baseDelayMs: 10_000,
        maxDelayMs: 10_000,
      },
    );
    try {
      await session.open();
      initial.fail();
      const request = session.request(testRequest());
      await vi.waitFor(() => {
        expect(failedReplacement.connector.connect).toHaveBeenCalledOnce();
      });

      await session.close();
      await expect(request).rejects.toThrow("Session closed during recovery");
      const callsAfterClose = factory.mock.calls.length;
      await new Promise((resolve) => setTimeout(resolve, 10));
      expect(factory).toHaveBeenCalledTimes(callsAfterClose);
    } finally {
      random.mockRestore();
      await session.close();
    }
  });

  it("validates reconnect policy bounds", () => {
    const connector = fakeConnector();

    expect(() => new PhotonSession(() => connector, {}, { maxReconnectAttempts: -1 })).toThrow(
      "non-negative integer",
    );
    expect(() => new PhotonSession(() => connector, {}, { baseDelayMs: 0 })).toThrow(
      "positive integer",
    );
    expect(
      () => new PhotonSession(() => connector, {}, { baseDelayMs: 10, maxDelayMs: 5 }),
    ).toThrow("at least baseDelayMs");
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
