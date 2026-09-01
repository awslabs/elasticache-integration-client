import { describe, expect, it, vi } from "vitest";

import { PhotonError } from "../src/errors.js";
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
    expect(session.resolvedConfig).toBeUndefined();

    await session.open();

    expect(connector.connect).toHaveBeenCalledOnce();
    expect(connector.authenticate).toHaveBeenCalledOnce();
    expect(connector.execute).toHaveBeenCalledWith("photon.config", Buffer.from('{"ttl":300}'));
    expect(session.resolvedConfig).toEqual(expect.any(ResolvedConfig));
    expect(session.resolvedConfig.raw).toEqual({
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
