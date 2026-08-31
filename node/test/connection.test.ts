import { readFileSync } from "node:fs";
import { createServer, type Socket } from "node:net";

import { afterEach, describe, expect, it, vi } from "vitest";

import {
  PhotonConnection,
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

describe("PhotonConnection authentication timeout", () => {
  it("uses the dedicated config timeout for AUTH", async () => {
    const cache = await startCache({ failure: "hang", failureCommand: "auth" });
    const connection = new PhotonConnection(cache.host, cache.port!, {
      tls: false,
      requestTimeout: 5_000,
      configTimeout: 25,
      authProvider: () => ["test-user", "test-token"],
    });
    await connection.connect();
    const authentication = connection.authenticate();

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
      await connection.close();
      await authentication.catch(() => undefined);
    }
  });
});

describe("PhotonConnection initialization timeout", () => {
  it("uses the dedicated config timeout for photon.config", async () => {
    const cache = await startCache({ failure: "hang", failureCommand: "photon.config" });
    const connection = new PhotonConnection(cache.host, cache.port!, {
      tls: false,
      requestTimeout: 5_000,
      configTimeout: 25,
    });
    await connection.connect();

    try {
      await expect(
        Promise.race([
          connection.execute("photon.config", Buffer.from("{}")),
          new Promise<never>((_, reject) =>
            setTimeout(() => reject(new Error("config timeout exceeded")), 100),
          ),
        ]),
      ).rejects.toThrow("request timed out after 25ms");
    } finally {
      await connection.close();
    }
  });
});

describe("PhotonConnection", () => {
  it("keeps PhotonConnector as a compatibility alias", () => {
    expect(PhotonConnector).toBe(PhotonConnection);
  });

  it("rejects use before connect", async () => {
    const connection = new PhotonConnection("127.0.0.1", 1, { tls: false });

    await expect(connection.execute("photon.config", Buffer.from("{}"))).rejects.toBeInstanceOf(
      PhotonError,
    );
  });

  it("does not resurrect a connection closed during connect", async () => {
    const cache = await startCache();
    const connection = new PhotonConnection(cache.host, cache.port!, { tls: false });
    const connecting = connection.connect();

    await connection.close();

    await expect(connecting).rejects.toThrow("closed during connect()");
  });

  it("rejects a concurrent connection attempt", async () => {
    const cache = await startCache();
    const connection = new PhotonConnection(cache.host, cache.port!, { tls: false });

    const first = connection.connect();
    await expect(connection.connect()).rejects.toThrow("already connecting");
    await first;
    await connection.close();
  });

  it("connects over plaintext and preserves raw protocol replies", async () => {
    const cache = await startCache({
      responseBody: Buffer.from('{"ok":1}'),
      responseHeaders: "content-type:application/json",
    });
    const connection = new PhotonConnection(cache.host, cache.port!, { tls: false });

    await connection.connect();
    await expect(connection.execute("photon.config", Buffer.from("{}"))).resolves.toEqual(
      Buffer.from(JSON.stringify({ version: "v1", refresh_interval: 60, predicates: [] })),
    );
    await expect(
      connection.execute(
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

    await connection.close();
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
    const connection = new PhotonConnection(cache.host, cache.port!, { tls: false });
    await connection.connect();

    const results = await Promise.all(
      Array.from({ length: 8 }, (_, index) =>
        connection.execute(
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
    await connection.close();
  });

  it("queues each frame before execute returns control to the caller", async () => {
    const cache = await startCache();
    const connection = new PhotonConnection(cache.host, cache.port!, { tls: false });
    await connection.connect();
    const socket = (connection as unknown as { socket: Socket }).socket;
    const write = vi.spyOn(socket, "write");

    const response = connection.execute("photon.config", Buffer.from("{}"));
    const synchronousWriteCount = write.mock.calls.length;

    await expect(response).resolves.toBeInstanceOf(Buffer);
    await connection.close();
    expect(synchronousWriteCount).toBe(1);
  });

  it("times out and tears down when socket backpressure never clears", async () => {
    const cache = await startCache();
    const connection = new PhotonConnection(cache.host, cache.port!, {
      tls: false,
      requestTimeout: 25,
    });
    await connection.connect();
    const socket = (connection as unknown as { socket: Socket }).socket;
    vi.spyOn(socket, "write").mockReturnValue(false);

    await expect(connection.execute("photon.config", Buffer.from("{}"))).rejects.toThrow(
      "send timed out after 25ms",
    );
    expect(socket.destroyed).toBe(true);
    await connection.close();
  });

  it("fails pending requests when a later send backpressure timeout tears down the connection", async () => {
    const cache = await startCache({ failure: "hang", failureCommand: "photon.config" });
    const connection = new PhotonConnection(cache.host, cache.port!, {
      tls: false,
      requestTimeout: 25, // limits send/drain wait here
      configTimeout: 5_000, // limits config request wait
    });
    await connection.connect();

    const pendingRequest = connection.execute("photon.config", Buffer.from("{}")); // on config request wait
    await vi.waitFor(() => {
      expect(cache.photonCommandLog).toContain("photon.config");
    });

    const socket = (connection as unknown as { socket: Socket }).socket;
    vi.spyOn(socket, "write").mockReturnValue(false);
    const timedOutRequest = connection.execute("photon.config", Buffer.from("{}")); // on send/drain wait

    await expect(pendingRequest).rejects.toBeInstanceOf(PhotonConnectionLostError);
    await expect(timedOutRequest).rejects.toThrow("send timed out after 25ms");
    await expect(connection.execute("photon.config", Buffer.from("{}"))).rejects.toBeInstanceOf(
      PhotonConnectionLostError,
    );
    await connection.close();
  });

  it("fully tears down after a synchronous send failure", async () => {
    const cache = await startCache();
    const connection = new PhotonConnection(cache.host, cache.port!, { tls: false });
    await connection.connect();
    const socket = (connection as unknown as { socket: Socket }).socket;
    vi.spyOn(socket, "write").mockImplementation(() => {
      throw new Error("write failed");
    });

    await expect(connection.execute("photon.config", Buffer.from("{}"))).rejects.toBeInstanceOf(
      PhotonConnectionLostError,
    );
    expect((connection as unknown as { socket?: unknown }).socket).toBeUndefined();
    await connection.close();
  });

  it("runs injected AUTH before the caller sends later commands", async () => {
    const cache = await startCache();
    const connection = new PhotonConnection(cache.host, cache.port!, {
      tls: false,
      authProvider: async () => ["test-user", "test-token"],
    });

    await connection.connect();
    await connection.authenticate();

    expect(cache.photonCommandLog).toEqual(["auth"]);
    expect(cache.receivedCommands[0]).toEqual([
      Buffer.from("AUTH"),
      Buffer.from("test-user"),
      Buffer.from("test-token"),
    ]);
    await connection.close();
  });

  it("makes the connection unusable when AUTH is rejected", async () => {
    const cache = await startCache({
      authResponse: Reply.error("invalid-iam-credentials", "nope"),
    });
    const connection = new PhotonConnection(cache.host, cache.port!, {
      tls: false,
      authProvider: () => ["test-user", "test-token"],
    });

    await connection.connect();
    await expect(connection.authenticate()).rejects.toThrow(
      "IAM AUTH rejected: invalid-iam-credentials",
    );
    await expect(connection.execute("photon.config", Buffer.from("{}"))).rejects.toBeInstanceOf(
      PhotonConnectionLostError,
    );
  });

  it("times out a request and makes the connection unusable", async () => {
    const cache = await startCache({ failure: "hang" });
    const connection = new PhotonConnection(cache.host, cache.port!, {
      tls: false,
      requestTimeout: 25,
    });
    await connection.connect();

    await expect(
      connection.execute(
        "photon.request",
        Buffer.from("POST"),
        Buffer.from("https://x/"),
        Buffer.alloc(0),
        Buffer.from("body"),
        Buffer.from("service=s&op=o"),
      ),
    ).rejects.toBeInstanceOf(PhotonTimeoutError);
    await expect(connection.execute("photon.config", Buffer.from("{}"))).rejects.toBeInstanceOf(
      PhotonConnectionLostError,
    );
  });

  it("rejects responses larger than the configured maximum", async () => {
    const cache = await startCache({ responseBody: Buffer.alloc(2_048) });
    const connection = new PhotonConnection(cache.host, cache.port!, {
      tls: false,
      maxResponseSize: 1_024,
    });
    await connection.connect();

    await expect(
      connection.execute(
        "photon.request",
        Buffer.from("POST"),
        Buffer.from("https://x/"),
        Buffer.alloc(0),
        Buffer.from("body"),
        Buffer.from("service=s&op=o"),
      ),
    ).rejects.toThrow("maximum response size");
    await expect(connection.execute("photon.config", Buffer.from("{}"))).rejects.toBeInstanceOf(
      PhotonConnectionLostError,
    );
  });

  it("fails pending requests promptly when closed", async () => {
    const cache = await startCache({ failure: "hang" });
    const connection = new PhotonConnection(cache.host, cache.port!, {
      tls: false,
      requestTimeout: 5_000,
    });
    await connection.connect();

    const request = connection.execute(
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
    await connection.close();

    await expect(settled).resolves.toEqual({
      error: expect.any(PhotonConnectionLostError),
    });
    await connection.close();
  });

  it("forgets the socket reference after close", async () => {
    const cache = await startCache();
    const connection = new PhotonConnection(cache.host, cache.port!, { tls: false });
    await connection.connect();

    await connection.close();

    expect((connection as unknown as { socket?: unknown }).socket).toBeUndefined();
    expect((connection as unknown as { readerTask?: unknown }).readerTask).toBeUndefined();
  });

  it("destroy immediately closes the socket and fails pending requests", async () => {
    const cache = await startCache({ failure: "hang" });
    const connection = new PhotonConnection(cache.host, cache.port!, {
      tls: false,
      requestTimeout: 5_000,
    });
    await connection.connect();

    const request = connection.execute(
      "photon.request",
      Buffer.from("POST"),
      Buffer.from("https://x/"),
      Buffer.alloc(0),
      Buffer.from("body"),
      Buffer.from("service=s&op=o"),
    );
    await new Promise((resolve) => setTimeout(resolve, 10));
    const rejection = expect(request).rejects.toBeInstanceOf(PhotonConnectionLostError);
    connection.destroy();

    await rejection;
    await expect(connection.execute("photon.config", Buffer.from("{}"))).rejects.toBeInstanceOf(
      PhotonConnectionLostError,
    );
    connection.destroy();
    await connection.close();
  });

  it("keeps close awaitable after destroy until the reader settles", async () => {
    const connection = new PhotonConnection("127.0.0.1", 1, { tls: false });
    let settleReader!: () => void;
    const readerTask = new Promise<void>((resolve) => {
      settleReader = resolve;
    });
    (connection as unknown as { readerTask: Promise<void> | undefined }).readerTask = readerTask;

    connection.destroy();
    const closing = connection.close();

    await expect(
      Promise.race([
        closing.then(() => "closed"),
        new Promise<string>((resolve) => setTimeout(() => resolve("pending"), 10)),
      ]),
    ).resolves.toBe("pending");

    settleReader();
    await expect(closing).resolves.toBeUndefined();
    expect(
      (connection as unknown as { readerTask: Promise<void> | undefined }).readerTask,
    ).toBeUndefined();
  });

  it("releases the reader completion after destroy-only teardown settles", async () => {
    const cache = await startCache();
    const connection = new PhotonConnection(cache.host, cache.port!, { tls: false });
    await connection.connect();
    const readerTask = (connection as unknown as { readerTask: Promise<void> | undefined })
      .readerTask;

    connection.destroy();

    await expect(readerTask).resolves.toBeUndefined();
    expect(
      (connection as unknown as { readerTask: Promise<void> | undefined }).readerTask,
    ).toBeUndefined();
  });

  it("fails pending requests when the peer drops the connection", async () => {
    const cache = await startCache({ failure: "drop" });
    const connection = new PhotonConnection(cache.host, cache.port!, {
      tls: false,
      requestTimeout: 5_000,
    });
    await connection.connect();

    const request = connection.execute(
      "photon.request",
      Buffer.from("POST"),
      Buffer.from("https://x/"),
      Buffer.alloc(0),
      Buffer.from("body"),
      Buffer.from("service=s&op=o"),
    );
    await expect(request).rejects.toBeInstanceOf(PhotonConnectionLostError);
    await expect(connection.execute("photon.config", Buffer.from("{}"))).rejects.toBeInstanceOf(
      PhotonConnectionLostError,
    );
  });

  it("verifies TLS with the supplied CA certificate", async () => {
    const cache = await startCache({ tls: true });
    const connection = new PhotonConnection(cache.host, cache.port!, {
      caCerts: readFileSync(certPath),
    });

    await connection.connect();
    await expect(connection.execute("photon.config", Buffer.from("{}"))).resolves.toBeInstanceOf(
      Buffer,
    );
    await connection.close();
  });

  it("rejects an untrusted TLS certificate", async () => {
    const cache = await startCache({ tls: true });
    const connection = new PhotonConnection(cache.host, cache.port!);

    await expect(connection.connect()).rejects.toBeInstanceOf(PhotonConnectionError);
    await connection.close();
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

    const connection = new PhotonConnection("127.0.0.1", address.port, {
      connectTimeout: 25,
    });
    try {
      await expect(connection.connect()).rejects.toBeInstanceOf(PhotonConnectionError);
    } finally {
      await connection.close();
      for (const socket of sockets) socket.destroy();
      await new Promise<void>((resolve) => server.close(() => resolve()));
    }
  });
});
