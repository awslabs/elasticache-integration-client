import { readFileSync } from "node:fs";
import { createServer, type Socket } from "node:net";

import { afterEach, describe, expect, it, vi } from "vitest";

import {
  PhotonConnector,
  PendingRequests,
  SocketReader,
  type SocketChunkSource,
} from "../src/connection.js";
import {
  PhotonConnectionLostError,
  PhotonConnectionError,
  PhotonError,
  PhotonProtocolError,
  PhotonTimeoutError,
} from "../src/errors.js";
import { RespError } from "../src/resp.js";
import { MockCache, Reply } from "./mock-cache.js";

const here = new URL(".", import.meta.url);
const certPath = new URL("./fixtures/tls/localhost.crt", here);
const activeCaches: MockCache[] = [];

afterEach(async () => {
  await Promise.all(activeCaches.splice(0).map((cache) => cache.stop()));
});

function startCache(options: ConstructorParameters<typeof MockCache>[0] = {}): Promise<MockCache> {
  const cache = new MockCache(options);
  activeCaches.push(cache);
  return cache.start().then(() => cache);
}

function sourceFrom(chunks: Buffer[]): SocketChunkSource {
  return {
    async *[Symbol.asyncIterator]() {
      for (const chunk of chunks) yield chunk;
    },
  };
}

describe("SocketReader", () => {
  it("reassembles partial frames and preserves trailing frames", async () => {
    const first = Buffer.from("+first\r\n");
    const second = Buffer.from("+second\r\n");
    const reader = new SocketReader(
      sourceFrom([first.subarray(0, 3), Buffer.concat([first.subarray(3), second])]),
    );

    await expect(reader.readFrame()).resolves.toEqual(Buffer.from("first"));
    await expect(reader.readFrame()).resolves.toEqual(Buffer.from("second"));
  });

  it("allows several small frames in one source chunk", async () => {
    const reader = new SocketReader(sourceFrom([Buffer.from("+one\r\n+two\r\n+three\r\n")]), 64, 8);

    await expect(reader.readFrame()).resolves.toEqual(Buffer.from("one"));
    await expect(reader.readFrame()).resolves.toEqual(Buffer.from("two"));
    await expect(reader.readFrame()).resolves.toEqual(Buffer.from("three"));
  });

  it("turns clean EOF into PhotonConnectionLostError", async () => {
    const reader = new SocketReader(sourceFrom([]));

    await expect(reader.readFrame()).rejects.toBeInstanceOf(PhotonConnectionLostError);
  });

  it("rejects malformed and truncated frames", async () => {
    await expect(
      new SocketReader(sourceFrom([Buffer.from("?unsupported\r\n")])).readFrame(),
    ).rejects.toBeInstanceOf(PhotonProtocolError);
    await expect(
      new SocketReader(sourceFrom([Buffer.from("$5\r\nab")])).readFrame(),
    ).rejects.toBeInstanceOf(PhotonConnectionLostError);
  });
});

describe("PendingRequests", () => {
  it("resolves responses in registration order, including RespError values", async () => {
    const pending = new PendingRequests();
    const first = pending.register();
    const second = pending.register();
    const third = pending.register();
    const error = new RespError("ERR", "reply");

    pending.resolveNext(Buffer.from("first"));
    pending.resolveNext(error);
    pending.resolveNext(Buffer.from("third"));

    await expect(first).resolves.toEqual(Buffer.from("first"));
    await expect(second).resolves.toBe(error);
    await expect(third).resolves.toEqual(Buffer.from("third"));
  });

  it("fails every pending request", async () => {
    const pending = new PendingRequests();
    const first = pending.register();
    const second = pending.register();

    pending.failAll(new PhotonConnectionLostError("connection closed"));

    await expect(first).rejects.toBeInstanceOf(PhotonConnectionLostError);
    await expect(second).rejects.toBeInstanceOf(PhotonConnectionLostError);
  });

  it("rejects registrations above the configured capacity", async () => {
    const pending = new PendingRequests(1);
    const first = pending.register();

    expect(() => pending.register()).toThrow("maximum pending requests");

    pending.resolveNext(Buffer.from("first"));
    await expect(first).resolves.toEqual(Buffer.from("first"));
  });

  it("rejects invalid capacities", () => {
    expect(() => new PendingRequests(0)).toThrow("positive integer");
    expect(() => new PendingRequests(Number.POSITIVE_INFINITY)).toThrow("positive integer");
  });
});

describe("SocketReader limits", () => {
  it("rejects an oversized frame even when the source yields it as one chunk", async () => {
    const source = sourceFrom([
      Buffer.concat([Buffer.from("$1024\r\n"), Buffer.alloc(1024), Buffer.from("\r\n")]),
    ]);
    const reader = new SocketReader(source, 64, 8);

    await expect(reader.readFrame()).rejects.toThrow("maximum response size");
  });
});

describe("PhotonConnector authentication timeout", () => {
  it("uses the shared request timeout for AUTH", async () => {
    const cache = await startCache({ failure: "hang", failureCommand: "auth" });
    const connector = new PhotonConnector(cache.host, cache.port!, {
      tls: false,
      requestTimeout: 25,
      authProvider: () => ["test-user", "test-token"],
    });
    await connector.connect();
    const authentication = connector.authenticate();

    try {
      await expect(
        Promise.race([
          authentication,
          new Promise<never>((_, reject) =>
            setTimeout(() => reject(new Error("request timeout exceeded")), 100),
          ),
        ]),
      ).rejects.toThrow("IAM AUTH timed out");
    } finally {
      await connector.close();
      await authentication.catch(() => undefined);
    }
  });
});

describe("PhotonConnector", () => {
  it("rejects use before connect", async () => {
    const connector = new PhotonConnector("127.0.0.1", 1, { tls: false });

    await expect(connector.execute("photon.config", Buffer.from("{}"))).rejects.toBeInstanceOf(
      PhotonError,
    );
  });

  it("does not resurrect a connector closed during connect", async () => {
    const cache = await startCache();
    const connector = new PhotonConnector(cache.host, cache.port!, { tls: false });
    const connecting = connector.connect();

    await connector.close();

    await expect(connecting).rejects.toThrow("closed during connect()");
  });

  it("rejects a concurrent connection attempt", async () => {
    const cache = await startCache();
    const connector = new PhotonConnector(cache.host, cache.port!, { tls: false });

    const first = connector.connect();
    await expect(connector.connect()).rejects.toThrow("already connecting");
    await first;
    await connector.close();
  });

  it("connects over plaintext and preserves raw protocol replies", async () => {
    const cache = await startCache({
      responseBody: Buffer.from('{"ok":1}'),
      responseHeaders: "content-type:application/json",
    });
    const connector = new PhotonConnector(cache.host, cache.port!, { tls: false });

    await connector.connect();
    await expect(connector.execute("photon.config", Buffer.from("{}"))).resolves.toEqual(
      Buffer.from(JSON.stringify({ version: "v1", refresh_interval: 60, predicates: [] })),
    );
    await expect(
      connector.execute(
        "photon.request",
        Buffer.from("POST"),
        Buffer.from("https://x/"),
        Buffer.alloc(0),
        Buffer.from("body"),
        Buffer.from("service=s&op=o"),
      ),
    ).resolves.toEqual([
      200,
      Buffer.from("content-type:application/json"),
      Buffer.from('{"ok":1}'),
    ]);

    await connector.close();
  });

  it("correlates concurrent requests by FIFO wire order", async () => {
    const cache = await startCache({
      responseFn: (request) => {
        const body = request.body;
        return {
          toBytes: () =>
            Buffer.concat([
              Buffer.from("*3\r\n:200\r\n"),
              Buffer.from("$0\r\n\r\n"),
              Buffer.from(`$${body.length}\r\n`),
              body,
              Buffer.from("\r\n"),
            ]),
        };
      },
    });
    const connector = new PhotonConnector(cache.host, cache.port!, { tls: false });
    await connector.connect();

    const results = await Promise.all(
      Array.from({ length: 8 }, (_, index) =>
        connector.execute(
          "photon.request",
          Buffer.from("POST"),
          Buffer.from("https://x/"),
          Buffer.alloc(0),
          Buffer.from(`token-${index}`),
          Buffer.from("service=s&op=o"),
        ),
      ),
    );

    expect(results.map((result) => (result as unknown[])[2])).toEqual(
      Array.from({ length: 8 }, (_, index) => Buffer.from(`token-${index}`)),
    );
    await connector.close();
  });

  it("queues each frame before execute returns control to the caller", async () => {
    const cache = await startCache();
    const connector = new PhotonConnector(cache.host, cache.port!, { tls: false });
    await connector.connect();
    const socket = (connector as unknown as { socket: Socket }).socket;
    const write = vi.spyOn(socket, "write");

    const response = connector.execute("photon.config", Buffer.from("{}"));
    const synchronousWriteCount = write.mock.calls.length;

    await expect(response).resolves.toBeInstanceOf(Buffer);
    await connector.close();
    expect(synchronousWriteCount).toBe(1);
  });

  it("runs injected AUTH before the caller sends later commands", async () => {
    const cache = await startCache();
    const connector = new PhotonConnector(cache.host, cache.port!, {
      tls: false,
      authProvider: async () => ["test-user", "test-token"],
    });

    await connector.connect();
    await connector.authenticate();

    expect(cache.photonCommandLog).toEqual(["auth"]);
    expect(cache.receivedCommands[0]).toEqual([
      Buffer.from("AUTH"),
      Buffer.from("test-user"),
      Buffer.from("test-token"),
    ]);
    await connector.close();
  });

  it("makes the connection unusable when AUTH is rejected", async () => {
    const cache = await startCache({
      authResponse: Reply.error("invalid-iam-credentials", "nope"),
    });
    const connector = new PhotonConnector(cache.host, cache.port!, {
      tls: false,
      authProvider: () => ["test-user", "test-token"],
    });

    await connector.connect();
    await expect(connector.authenticate()).rejects.toThrow(
      "IAM AUTH rejected: invalid-iam-credentials",
    );
    await expect(connector.execute("photon.config", Buffer.from("{}"))).rejects.toBeInstanceOf(
      PhotonConnectionLostError,
    );
  });

  it("times out a request and makes the connection unusable", async () => {
    const cache = await startCache({ failure: "hang" });
    const connector = new PhotonConnector(cache.host, cache.port!, {
      tls: false,
      requestTimeout: 25,
    });
    await connector.connect();

    await expect(
      connector.execute(
        "photon.request",
        Buffer.from("POST"),
        Buffer.from("https://x/"),
        Buffer.alloc(0),
        Buffer.from("body"),
        Buffer.from("service=s&op=o"),
      ),
    ).rejects.toBeInstanceOf(PhotonTimeoutError);
    await expect(connector.execute("photon.config", Buffer.from("{}"))).rejects.toBeInstanceOf(
      PhotonConnectionLostError,
    );
  });

  it("rejects responses larger than the configured maximum", async () => {
    const cache = await startCache({ responseBody: Buffer.alloc(2_048) });
    const connector = new PhotonConnector(cache.host, cache.port!, {
      tls: false,
      maxResponseSize: 1_024,
    });
    await connector.connect();

    await expect(
      connector.execute(
        "photon.request",
        Buffer.from("POST"),
        Buffer.from("https://x/"),
        Buffer.alloc(0),
        Buffer.from("body"),
        Buffer.from("service=s&op=o"),
      ),
    ).rejects.toThrow("maximum response size");
    await expect(connector.execute("photon.config", Buffer.from("{}"))).rejects.toBeInstanceOf(
      PhotonConnectionLostError,
    );
  });

  it("fails pending requests promptly when closed", async () => {
    const cache = await startCache({ failure: "hang" });
    const connector = new PhotonConnector(cache.host, cache.port!, {
      tls: false,
      requestTimeout: 5_000,
    });
    await connector.connect();

    const request = connector.execute(
      "photon.request",
      Buffer.from("POST"),
      Buffer.from("https://x/"),
      Buffer.alloc(0),
      Buffer.from("body"),
      Buffer.from("service=s&op=o"),
    );
    const settled = request.then(
      (value) => ({ value }),
      (error: unknown) => ({ error }),
    );
    await new Promise((resolve) => setTimeout(resolve, 10));
    await connector.close();

    await expect(settled).resolves.toEqual({
      error: expect.any(PhotonConnectionLostError),
    });
    await connector.close();
  });

  it("forgets the socket reference after close", async () => {
    const cache = await startCache();
    const connector = new PhotonConnector(cache.host, cache.port!, { tls: false });
    await connector.connect();

    await connector.close();

    expect((connector as unknown as { socket?: unknown }).socket).toBeUndefined();
    expect((connector as unknown as { readerTask?: unknown }).readerTask).toBeUndefined();
  });

  it("destroy immediately closes the socket and fails pending requests", async () => {
    const cache = await startCache({ failure: "hang" });
    const connector = new PhotonConnector(cache.host, cache.port!, {
      tls: false,
      requestTimeout: 5_000,
    });
    await connector.connect();

    const request = connector.execute(
      "photon.request",
      Buffer.from("POST"),
      Buffer.from("https://x/"),
      Buffer.alloc(0),
      Buffer.from("body"),
      Buffer.from("service=s&op=o"),
    );
    await new Promise((resolve) => setTimeout(resolve, 10));
    const rejection = expect(request).rejects.toBeInstanceOf(PhotonConnectionLostError);
    connector.destroy();

    await rejection;
    await expect(connector.execute("photon.config", Buffer.from("{}"))).rejects.toBeInstanceOf(
      PhotonConnectionLostError,
    );
    connector.destroy();
    await connector.close();
  });

  it("keeps close awaitable after destroy until the reader settles", async () => {
    const connector = new PhotonConnector("127.0.0.1", 1, { tls: false });
    let settleReader!: () => void;
    const readerTask = new Promise<void>((resolve) => {
      settleReader = resolve;
    });
    (connector as unknown as { readerTask: Promise<void> | undefined }).readerTask = readerTask;

    connector.destroy();
    const closing = connector.close();

    await expect(
      Promise.race([
        closing.then(() => "closed"),
        new Promise<string>((resolve) => setTimeout(() => resolve("pending"), 10)),
      ]),
    ).resolves.toBe("pending");

    settleReader();
    await expect(closing).resolves.toBeUndefined();
    expect(
      (connector as unknown as { readerTask: Promise<void> | undefined }).readerTask,
    ).toBeUndefined();
  });

  it("releases the reader completion after destroy-only teardown settles", async () => {
    const cache = await startCache();
    const connector = new PhotonConnector(cache.host, cache.port!, { tls: false });
    await connector.connect();
    const readerTask = (connector as unknown as { readerTask: Promise<void> | undefined })
      .readerTask;

    connector.destroy();

    await expect(readerTask).resolves.toBeUndefined();
    expect(
      (connector as unknown as { readerTask: Promise<void> | undefined }).readerTask,
    ).toBeUndefined();
  });

  it("fails pending requests when the peer drops the connection", async () => {
    const cache = await startCache({ failure: "drop" });
    const connector = new PhotonConnector(cache.host, cache.port!, {
      tls: false,
      requestTimeout: 5_000,
    });
    await connector.connect();

    const request = connector.execute(
      "photon.request",
      Buffer.from("POST"),
      Buffer.from("https://x/"),
      Buffer.alloc(0),
      Buffer.from("body"),
      Buffer.from("service=s&op=o"),
    );
    await expect(request).rejects.toBeInstanceOf(PhotonConnectionLostError);
    await expect(connector.execute("photon.config", Buffer.from("{}"))).rejects.toBeInstanceOf(
      PhotonConnectionLostError,
    );
  });

  it("verifies TLS with the supplied CA certificate", async () => {
    const cache = await startCache({ tls: true });
    const connector = new PhotonConnector(cache.host, cache.port!, {
      caCerts: readFileSync(certPath),
    });

    await connector.connect();
    await expect(connector.execute("photon.config", Buffer.from("{}"))).resolves.toBeInstanceOf(
      Buffer,
    );
    await connector.close();
  });

  it("rejects an untrusted TLS certificate", async () => {
    const cache = await startCache({ tls: true });
    const connector = new PhotonConnector(cache.host, cache.port!);

    await expect(connector.connect()).rejects.toBeInstanceOf(PhotonConnectionError);
    await connector.close();
  });

  it("cleans up a TLS handshake timeout without an unhandled socket error", async () => {
    const sockets = new Set<Socket>();
    const server = createServer((socket) => {
      sockets.add(socket);
      socket.once("close", () => sockets.delete(socket));
    });
    await new Promise<void>((resolve, reject) => {
      server.once("error", reject);
      server.listen(0, "127.0.0.1", () => {
        server.off("error", reject);
        resolve();
      });
    });

    const address = server.address();
    if (!address || typeof address === "string") {
      server.close();
      throw new Error("test server did not bind");
    }

    const connector = new PhotonConnector("127.0.0.1", address.port, {
      connectTimeout: 25,
    });
    try {
      await expect(connector.connect()).rejects.toBeInstanceOf(PhotonConnectionError);
    } finally {
      await connector.close();
      for (const socket of sockets) socket.destroy();
      await new Promise<void>((resolve) => server.close(() => resolve()));
    }
  });
});
