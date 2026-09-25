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
  PhotonConnectionLost,
  PhotonConnectionError,
  PhotonError,
  PhotonProtocolError,
  PhotonTimeoutError,
} from "../src/errors.js";
import { parseRequestResponse } from "../src/protocol.js";
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

function requestArgs(body: string): [Buffer, Buffer, Buffer, Buffer, Buffer] {
  return [
    Buffer.from("POST"),
    Buffer.from("https://x/"),
    Buffer.alloc(0),
    Buffer.from(body),
    Buffer.from("service=s&op=o"),
  ];
}

describe("SocketReader", () => {
  it("reassembles partial frames and preserves trailing frames", async () => {
    const first = Buffer.from("+first\r\n");
    const second = Buffer.from("+second\r\n");
    const reader = new SocketReader(
      sourceFrom([first.subarray(0, 3), Buffer.concat([first.subarray(3), second])]),
    );

    await expect(reader.readFrame()).resolves.toBe("first");
    await expect(reader.readFrame()).resolves.toBe("second");
  });

  it("allows several small frames in one source chunk", async () => {
    const reader = new SocketReader(sourceFrom([Buffer.from("+one\r\n+two\r\n+three\r\n")]), 64, 8);

    await expect(reader.readFrame()).resolves.toBe("one");
    await expect(reader.readFrame()).resolves.toBe("two");
    await expect(reader.readFrame()).resolves.toBe("three");
  });

  it("decodes a RESP3 null frame", async () => {
    const reader = new SocketReader(sourceFrom([Buffer.from("_\r\n+next\r\n")]));

    await expect(reader.readFrame()).resolves.toBeNull();
    await expect(reader.readFrame()).resolves.toBe("next");
  });

  it("turns clean EOF into PhotonConnectionLost", async () => {
    const reader = new SocketReader(sourceFrom([]));

    await expect(reader.readFrame()).rejects.toBeInstanceOf(PhotonConnectionLost);
  });

  it("rejects malformed and truncated frames", async () => {
    await expect(
      new SocketReader(sourceFrom([Buffer.from("?unsupported\r\n")])).readFrame(),
    ).rejects.toBeInstanceOf(PhotonProtocolError);
    await expect(
      new SocketReader(sourceFrom([Buffer.from("$5\r\nab")])).readFrame(),
    ).rejects.toBeInstanceOf(PhotonConnectionLost);
  });
});

describe("PendingRequests", () => {
  it("resolves responses in registration order, including RespError values", async () => {
    const pending = new PendingRequests();
    const first = pending.registerNewRequest();
    const second = pending.registerNewRequest();
    const third = pending.registerNewRequest();
    const error = new RespError("ERR", "reply");

    pending.resolveOldestRequest(Buffer.from("first"));
    pending.resolveOldestRequest(error);
    pending.resolveOldestRequest(Buffer.from("third"));

    await expect(first.promise).resolves.toEqual(Buffer.from("first"));
    await expect(second.promise).resolves.toBe(error);
    await expect(third.promise).resolves.toEqual(Buffer.from("third"));
  });

  it("fails every pending request", async () => {
    const pending = new PendingRequests();
    const first = pending.registerNewRequest();
    const second = pending.registerNewRequest();

    pending.failAll(new PhotonConnectionLost("connection closed"));

    await expect(first.promise).rejects.toBeInstanceOf(PhotonConnectionLost);
    await expect(second.promise).rejects.toBeInstanceOf(PhotonConnectionLost);
  });

  it("rejects registrations above the configured capacity", async () => {
    const pending = new PendingRequests(1);
    const first = pending.registerNewRequest();

    expect(() => pending.registerNewRequest()).toThrow("maximum pending requests");

    pending.resolveOldestRequest(Buffer.from("first"));
    await expect(first.promise).resolves.toEqual(Buffer.from("first"));
  });

  it("rejects invalid capacities", () => {
    expect(() => new PendingRequests(0)).toThrow("positive integer");
    expect(() => new PendingRequests(Number.POSITIVE_INFINITY)).toThrow("positive integer");
  });

  it("moves the oldest FIFO request into the async map", async () => {
    const pending = new PendingRequests();
    const first = pending.registerNewRequest();
    const second = pending.registerNewRequest();

    expect(pending.moveNextToAsyncResponses("42")).toBe(true);
    pending.resolveOldestRequest(Buffer.from("second"));

    await expect(second.promise).resolves.toEqual(Buffer.from("second"));
    expect(pending.resolveAsync("42", Buffer.from("first"))).toBe(true);
    await expect(first.promise).resolves.toEqual(Buffer.from("first"));
  });

  it("keeps FIFO-only requests out of the async map", async () => {
    const pending = new PendingRequests();
    const request = pending.registerNewRequest(false);

    expect(pending.moveNextToAsyncResponses("42")).toBe(false);
    pending.resolveOldestRequest("42");

    await expect(request.promise).resolves.toBe("42");
    expect(pending.resolveAsync("42", Buffer.from("late"))).toBe(false);
  });

  it("rejects duplicate async ids and empty FIFO promotion", async () => {
    const pending = new PendingRequests();
    const fifoFuture = pending.registerNewRequest();

    pending.moveNextToAsyncResponses("42");
    expect(() => pending.moveNextToAsyncResponses("42")).toThrow("without a pending FIFO request");

    const deferredFuture = pending.registerNewRequest();
    expect(() => pending.moveNextToAsyncResponses("42")).toThrow("duplicate async response id");
    expect(pending.resolveAsync("missing", Buffer.from("ignored"))).toBe(false);
    expect(deferredFuture.settled).toBe(false);

    expect(pending.resolveAsync("42", Buffer.from("deferred"))).toBe(true);
    pending.resolveOldestRequest(Buffer.from("positional"));
    await expect(fifoFuture.promise).resolves.toEqual(Buffer.from("deferred"));
    await expect(deferredFuture.promise).resolves.toEqual(Buffer.from("positional"));
  });

  it("removes only an async request by handle identity", () => {
    const pending = new PendingRequests();
    const first = pending.registerNewRequest();

    expect(pending.removeFromAsync(first)).toBe(false);
    pending.moveNextToAsyncResponses("42");
    expect(pending.removeFromAsync(first)).toBe(true);
    expect(pending.removeFromAsync(first)).toBe(false);
  });

  it("fails pending FIFO and async requests", async () => {
    const pending = new PendingRequests();
    const first = pending.registerNewRequest();
    const second = pending.registerNewRequest();

    pending.moveNextToAsyncResponses("42");
    pending.failAll(new PhotonConnectionLost("connection closed"));

    await expect(first.promise).rejects.toBeInstanceOf(PhotonConnectionLost);
    await expect(second.promise).rejects.toBeInstanceOf(PhotonConnectionLost);
    // The async map must be emptied too, so a late push cannot match a failed request.
    expect(pending.resolveAsync("42", Buffer.from("late"))).toBe(false);
    expect(pending.removeFromAsync(first)).toBe(false);
  });

  it("counts a deferred request against maxPending until it resolves", async () => {
    const pending = new PendingRequests(1);
    const first = pending.registerNewRequest();

    pending.moveNextToAsyncResponses("42");
    expect(() => pending.registerNewRequest()).toThrow("maximum pending requests");

    pending.resolveAsync("42", Buffer.from("first"));
    const second = pending.registerNewRequest();
    pending.resolveOldestRequest(Buffer.from("second"));
    await expect(first.promise).resolves.toEqual(Buffer.from("first"));
    await expect(second.promise).resolves.toEqual(Buffer.from("second"));
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
  it("uses the dedicated config timeout for AUTH", async () => {
    const cache = await startCache({ failure: "hang", failureCommand: "auth" });
    const connector = new PhotonConnector(cache.host, cache.port!, {
      tls: false,
      requestTimeout: 5_000,
      configTimeout: 25,
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

describe("PhotonConnector initialization timeout", () => {
  it("uses the dedicated config timeout for photon.config", async () => {
    const cache = await startCache({ failure: "hang", failureCommand: "photon.config" });
    const connector = new PhotonConnector(cache.host, cache.port!, {
      tls: false,
      requestTimeout: 5_000,
      configTimeout: 25,
    });
    await connector.connect();

    try {
      await expect(
        Promise.race([
          connector.execute("photon.config", Buffer.from("{}")),
          new Promise<never>((_, reject) =>
            setTimeout(() => reject(new Error("config timeout exceeded")), 100),
          ),
        ]),
      ).rejects.toThrow("request timed out after 25ms");
    } finally {
      await connector.close();
    }
  });
});

describe("PhotonConnector", () => {
  it("defaults the request timeout to five seconds", () => {
    const connector = new PhotonConnector("127.0.0.1", 1, { tls: false });

    expect((connector as unknown as { requestTimeout: number }).requestTimeout).toBe(5_000);
  });

  it("rejects use before connect", async () => {
    const connector = new PhotonConnector("127.0.0.1", 1, { tls: false });

    await expect(connector.execute("photon.config", Buffer.from("{}"))).rejects.toBeInstanceOf(
      PhotonError,
    );
  });

  it("does not resurrect a connection closed during connect", async () => {
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

  it("round-trips a RESP3 null reply over the socket", async () => {
    const cache = await startCache();
    const connector = new PhotonConnector(cache.host, cache.port!, { tls: false });

    await connector.connect();
    // A bare photon.config makes the mock send the literal `_\r\n` frame.
    await expect(connector.execute("photon.config")).resolves.toBeNull();

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

  it("resolves a later FIFO response before the deferred request push", async () => {
    let requestCount = 0;
    const cache = await startCache({
      responseFn: (request) => {
        requestCount += 1;
        if (requestCount === 1) return Reply.requestId(42);
        return Reply.ok(200, "content-type:application/octet-stream", request.body);
      },
    });
    const connector = new PhotonConnector(cache.host, cache.port!, {
      tls: false,
      requestTimeout: 250,
    });
    await connector.connect();

    let firstSettled = false;
    const first = connector.execute("photon.request", ...requestArgs("first"));
    void first.then(
      () => {
        firstSettled = true;
      },
      () => {
        firstSettled = true;
      },
    );
    await vi.waitFor(() => expect(cache.requests).toHaveLength(1));

    const second = connector.execute("photon.request", ...requestArgs("second"));
    const secondResult = await second;
    expect(secondResult).toEqual([
      200,
      Buffer.from("content-type:application/octet-stream"),
      Buffer.from("second"),
    ]);
    expect(firstSettled).toBe(false);

    cache.sendPush(
      42,
      Reply.ok(200, "content-type:application/octet-stream", Buffer.from("first")),
    );
    await expect(first).resolves.toEqual([
      200,
      Buffer.from("content-type:application/octet-stream"),
      Buffer.from("first"),
    ]);
    await connector.close();
  });

  it.each([42, 9_007_199_254_740_993n])(
    "resolves an immediate request-id reply followed by a response push for id %s",
    async (requestId) => {
      const cache = await startCache({
        responseFn: () => Reply.asyncResponse(requestId, Reply.ok()),
      });
      const connector = new PhotonConnector(cache.host, cache.port!, { tls: false });
      await connector.connect();

      await expect(connector.execute("photon.request", ...requestArgs("body"))).resolves.toEqual([
        200,
        Buffer.from("content-type:application/x-amz-json-1.0"),
        Buffer.from('{"Item": {}}'),
      ]);
      await connector.close();
    },
  );

  it("resolves a deferred request via a push kind encoded as a simple string", async () => {
    let requestCount = 0;
    const cache = await startCache({
      responseFn: (request) => {
        requestCount += 1;
        if (requestCount === 1) return Reply.requestId(42);
        return Reply.ok(200, "content-type:application/octet-stream", request.body);
      },
    });
    const connector = new PhotonConnector(cache.host, cache.port!, { tls: false });
    await connector.connect();

    const deferred = connector.execute("photon.request", ...requestArgs("deferred"));
    await vi.waitFor(() => expect(cache.requests).toHaveLength(1));

    cache.sendPush(
      42,
      Reply.ok(200, "content-type:application/octet-stream", Buffer.from("deferred")),
      Buffer.from("photon.response", "ascii"),
      "simple",
    );
    await expect(deferred).resolves.toEqual([
      200,
      Buffer.from("content-type:application/octet-stream"),
      Buffer.from("deferred"),
    ]);
    await connector.close();
  });

  it("keeps a request-id-shaped reply positional for a non-request command", async () => {
    const cache = await startCache({ authResponse: Reply.requestId(42) });
    const connector = new PhotonConnector(cache.host, cache.port!, {
      tls: false,
      configTimeout: 100,
    });
    await connector.connect();

    await expect(
      connector.execute("AUTH", Buffer.from("test-user"), Buffer.from("test-token")),
    ).resolves.toBe("42");
    await connector.close();
  });

  it("passes a RespError in a response push through the normal parser path", async () => {
    const cache = await startCache({
      responseFn: () => Reply.asyncResponse(42, Reply.error("ERR", "boom")),
    });
    const connector = new PhotonConnector(cache.host, cache.port!, { tls: false });
    await connector.connect();

    const pushedError = await connector.execute("photon.request", ...requestArgs("body"));
    const expectedError = new RespError("ERR", "boom");

    expect(pushedError).toEqual(expectedError);
    expect(() => parseRequestResponse(expectedError)).toThrow("photon.request failed: ERR boom");
    expect(() => parseRequestResponse(pushedError)).toThrow("photon.request failed: ERR boom");
    await connector.close();
  });

  it("drops response pushes for unknown ids while keeping the connection usable", async () => {
    let requestCount = 0;
    const cache = await startCache({
      responseFn: (request) => {
        requestCount += 1;
        if (requestCount === 1) return Reply.requestId(42);
        return Reply.ok(200, "content-type:application/octet-stream", request.body);
      },
    });
    const connector = new PhotonConnector(cache.host, cache.port!, { tls: false });
    await connector.connect();

    const first = connector.execute("photon.request", ...requestArgs("first"));
    await vi.waitFor(() => expect(cache.requests).toHaveLength(1));
    cache.sendPush(999, Reply.ok(), Buffer.from("photon.response"));

    await expect(connector.execute("photon.request", ...requestArgs("second"))).resolves.toEqual([
      200,
      Buffer.from("content-type:application/octet-stream"),
      Buffer.from("second"),
    ]);

    cache.sendPush(
      42,
      Reply.ok(200, "content-type:application/octet-stream", Buffer.from("first")),
    );
    await expect(first).resolves.toEqual([
      200,
      Buffer.from("content-type:application/octet-stream"),
      Buffer.from("first"),
    ]);
    await connector.close();
  });

  it.each([
    [">0\r\n", "empty payload"],
    [">3\r\n:123\r\n:42\r\n$1\r\nx\r\n", "non-bytes kind"],
    [">2\r\n$15\r\nphoton.response\r\n:42\r\n", "wrong arity"],
    [">3\r\n$15\r\nphoton.response\r\n:42\r\n$1\r\nx\r\n", "integer id is no longer accepted"],
    [">3\r\n$10\r\nother.kind\r\n$2\r\n42\r\n$1\r\nx\r\n", "unknown push kind"],
    [">3\r\n$15\r\nphoton.response\r\n$1\r\n\xff\r\n$1\r\nx\r\n", "invalid UTF-8 id"],
  ])("kills the connection on a malformed push: %s (%s)", async (frame) => {
    // latin1 so a raw invalid-UTF-8 byte (\xff) in a table entry round-trips as
    // that exact byte rather than being re-encoded as multi-byte UTF-8.
    const cache = await startCache({ response: new Reply(Buffer.from(frame, "latin1")) });
    const connector = new PhotonConnector(cache.host, cache.port!, { tls: false });
    await connector.connect();

    try {
      const failure = await connector
        .execute("photon.request", ...requestArgs("malformed"))
        .catch((error: unknown) => error);
      expect(failure).toBeInstanceOf(PhotonConnectionLost);
      expect((failure as Error).cause).toBeInstanceOf(PhotonProtocolError);
      await expect(
        connector.execute("photon.request", ...requestArgs("after-failure")),
      ).rejects.toBeInstanceOf(PhotonConnectionLost);
    } finally {
      await connector.close();
    }
  });

  it("preserves the connection when a deferred request times out", async () => {
    let requestCount = 0;
    const cache = await startCache({
      responseFn: (request) => {
        requestCount += 1;
        if (requestCount === 1) return Reply.requestId(42);
        return Reply.ok(200, "content-type:application/octet-stream", request.body);
      },
    });
    const connector = new PhotonConnector(cache.host, cache.port!, {
      tls: false,
      requestTimeout: 100,
    });
    await connector.connect();

    const deferred = connector.execute("photon.request", ...requestArgs("deferred"));
    const deferredExpectation = expect(deferred).rejects.toThrow("async request timed out");
    await vi.waitFor(() => expect(cache.requests).toHaveLength(1));
    await deferredExpectation;

    await expect(
      connector.execute("photon.request", ...requestArgs("after-timeout")),
    ).resolves.toEqual([
      200,
      Buffer.from("content-type:application/octet-stream"),
      Buffer.from("after-timeout"),
    ]);

    cache.sendPush(42, Reply.ok(), Buffer.from("photon.response"));
    await expect(
      connector.execute("photon.request", ...requestArgs("still-usable")),
    ).resolves.toEqual([
      200,
      Buffer.from("content-type:application/octet-stream"),
      Buffer.from("still-usable"),
    ]);
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

  it("times out and tears down when socket backpressure never clears", async () => {
    const cache = await startCache();
    const connector = new PhotonConnector(cache.host, cache.port!, {
      tls: false,
      requestTimeout: 25,
    });
    await connector.connect();
    const socket = (connector as unknown as { socket: Socket }).socket;
    vi.spyOn(socket, "write").mockReturnValue(false);

    await expect(connector.execute("photon.config", Buffer.from("{}"))).rejects.toThrow(
      "send timed out after 25ms",
    );
    expect(socket.destroyed).toBe(true);
    await connector.close();
  });

  it("fails pending requests when a later send backpressure timeout tears down the connection", async () => {
    const cache = await startCache({ failure: "hang", failureCommand: "photon.config" });
    const connector = new PhotonConnector(cache.host, cache.port!, {
      tls: false,
      requestTimeout: 25, // limits send/drain wait here
      configTimeout: 5_000, // limits config request wait
    });
    await connector.connect();

    const pendingRequest = connector.execute("photon.config", Buffer.from("{}")); // on config request wait
    await vi.waitFor(() => {
      expect(cache.photonCommandLog).toContain("photon.config");
    });

    const socket = (connector as unknown as { socket: Socket }).socket;
    vi.spyOn(socket, "write").mockReturnValue(false);
    const timedOutRequest = connector.execute("photon.config", Buffer.from("{}")); // on send/drain wait

    await expect(pendingRequest).rejects.toBeInstanceOf(PhotonConnectionLost);
    await expect(timedOutRequest).rejects.toThrow("send timed out after 25ms");
    await expect(connector.execute("photon.config", Buffer.from("{}"))).rejects.toBeInstanceOf(
      PhotonConnectionLost,
    );
    await connector.close();
  });

  it("fully tears down after a synchronous send failure", async () => {
    const cache = await startCache();
    const connector = new PhotonConnector(cache.host, cache.port!, { tls: false });
    await connector.connect();
    const socket = (connector as unknown as { socket: Socket }).socket;
    vi.spyOn(socket, "write").mockImplementation(() => {
      throw new Error("write failed");
    });

    await expect(connector.execute("photon.config", Buffer.from("{}"))).rejects.toBeInstanceOf(
      PhotonConnectionLost,
    );
    expect((connector as unknown as { socket?: unknown }).socket).toBeUndefined();
    await connector.close();
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

  it("does not accept a bulk string containing OK as AUTH success", async () => {
    const cache = await startCache({
      authResponse: new Reply(Buffer.from("$2\r\nOK\r\n")),
    });
    const authProvider = Object.assign(() => ["test-user", "test-token"] as [string, string], {
      refreshAuth: vi.fn(),
    });
    const connector = new PhotonConnector(cache.host, cache.port!, {
      tls: false,
      authProvider,
    });

    await connector.connect();
    await expect(connector.authenticate()).rejects.toThrow("IAM AUTH failed");
    expect(authProvider.refreshAuth).not.toHaveBeenCalled();
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
      PhotonConnectionLost,
    );
  });

  it("invalidates managed credentials after AUTH rejection", async () => {
    const cache = await startCache({
      authResponse: Reply.error("invalid-iam-credentials", "nope"),
    });
    const authProvider = Object.assign(() => ["test-user", "cached-token"] as [string, string], {
      refreshAuth: vi.fn(async () => ["test-user", "replacement-token"] as [string, string]),
    });
    const connector = new PhotonConnector(cache.host, cache.port!, {
      tls: false,
      authProvider,
    });

    await connector.connect();
    await expect(connector.authenticate()).rejects.toThrow(
      "IAM AUTH rejected: invalid-iam-credentials",
    );
    expect(authProvider.refreshAuth).toHaveBeenCalledOnce();
    await connector.close();
  });

  it("preserves an object auth provider receiver while invalidating credentials", async () => {
    const cache = await startCache({
      authResponse: Reply.error("invalid-iam-credentials", "nope"),
    });
    class StatefulAuthProvider {
      refreshed = false;

      getAuth(): [string, string] {
        return ["test-user", "cached-token"];
      }

      refreshAuth(): [string, string] {
        this.refreshed = true;
        return ["test-user", "replacement-token"];
      }
    }
    const authProvider = new StatefulAuthProvider();
    const connector = new PhotonConnector(cache.host, cache.port!, {
      tls: false,
      authProvider,
    });

    await connector.connect();
    await expect(connector.authenticate()).rejects.toThrow(
      "IAM AUTH rejected: invalid-iam-credentials",
    );
    expect(authProvider.refreshed).toBe(true);
    await connector.close();
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
      PhotonConnectionLost,
    );
  });

  it("rejects responses larger than the configured maximum", async () => {
    const cache = await startCache({ responseBody: Buffer.alloc(2_048) });
    const connector = new PhotonConnector(cache.host, cache.port!, {
      tls: false,
      maxResponseSize: 1_024,
    });
    await connector.connect();

    const failure = await connector
      .execute(
        "photon.request",
        Buffer.from("POST"),
        Buffer.from("https://x/"),
        Buffer.alloc(0),
        Buffer.from("body"),
        Buffer.from("service=s&op=o"),
      )
      .catch((error: unknown) => error);
    expect(failure).toBeInstanceOf(PhotonConnectionLost);
    expect((failure as Error).cause).toBeInstanceOf(PhotonProtocolError);
    expect(((failure as Error).cause as Error).message).toContain("maximum response size");
    await expect(connector.execute("photon.config", Buffer.from("{}"))).rejects.toBeInstanceOf(
      PhotonConnectionLost,
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
      error: expect.any(PhotonConnectionLost),
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
    const rejection = expect(request).rejects.toBeInstanceOf(PhotonConnectionLost);
    connector.destroy();

    await rejection;
    await expect(connector.execute("photon.config", Buffer.from("{}"))).rejects.toBeInstanceOf(
      PhotonConnectionLost,
    );
    connector.destroy();
    await connector.close();
  });

  it("notifies its owner exactly once when closed", async () => {
    const cache = await startCache();
    const connector = new PhotonConnector(cache.host, cache.port!, { tls: false });
    const onDisconnect = vi.fn();
    connector.onDisconnect = onDisconnect;
    await connector.connect();

    connector.destroy();
    await connector.close();
    connector.destroy();

    expect(onDisconnect).toHaveBeenCalledOnce();
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
    await expect(request).rejects.toBeInstanceOf(PhotonConnectionLost);
    await expect(connector.execute("photon.config", Buffer.from("{}"))).rejects.toBeInstanceOf(
      PhotonConnectionLost,
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

  // These two run in this order deliberately, and together they pin the bug: the warning
  // latches once per process, so a connector that never performs an unverified handshake must
  // leave the latch alone. If the first test consumed it, the second would be silent.
  it("does not warn when TLS is off and verification is moot", async () => {
    const cache = await startCache();
    const emitWarning = vi.spyOn(process, "emitWarning").mockImplementation(() => {});
    const connector = new PhotonConnector(cache.host, cache.port!, {
      tls: false,
      insecureSkipVerify: true,
    });

    try {
      await connector.connect();
      expect(emitWarning).not.toHaveBeenCalled();
    } finally {
      emitWarning.mockRestore();
      await connector.close();
    }
  });

  it("warns on the first handshake that waives verification", async () => {
    const cache = await startCache({ tls: true });
    const emitWarning = vi.spyOn(process, "emitWarning").mockImplementation(() => {});
    const connector = new PhotonConnector(cache.host, cache.port!, { insecureSkipVerify: true });

    try {
      await connector.connect();
      expect(emitWarning).toHaveBeenCalledWith(
        expect.stringContaining("insecureSkipVerify"),
        "PhotonInsecureTlsWarning",
      );
    } finally {
      emitWarning.mockRestore();
      await connector.close();
    }
  });

  it("accepts an untrusted TLS certificate when verification is skipped", async () => {
    const cache = await startCache({ tls: true });
    // Same server and same absent CA as the test above, so the flag is the only difference.
    const connector = new PhotonConnector(cache.host, cache.port!, { insecureSkipVerify: true });

    await connector.connect();
    await expect(connector.execute("photon.config", Buffer.from("{}"))).resolves.toBeInstanceOf(
      Buffer,
    );
    await connector.close();
  });

  it("still rejects an untrusted TLS certificate when verification is not skipped", async () => {
    const cache = await startCache({ tls: true });
    const connector = new PhotonConnector(cache.host, cache.port!, { insecureSkipVerify: false });

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
