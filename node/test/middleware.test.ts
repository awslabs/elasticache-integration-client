import { describe, expect, it, vi } from "vitest";

import { formatUrl } from "@aws-sdk/core/util";
import { constructStack } from "@smithy/core/client";
import { HttpRequest, HttpResponse } from "@smithy/core/protocols";
import type { DeserializeHandler, FinalizeHandler, SerializeHandler } from "@smithy/types";
import { Readable } from "node:stream";

import {
  ElastiCacheIntegrationsCacheUnavailable,
  ElastiCacheIntegrationsConnectionLost,
  ElastiCacheIntegrationsError,
  ElastiCacheIntegrationsProtocolError,
  ElastiCacheIntegrationsRejectedError,
  ElastiCacheIntegrationsTimeoutError,
} from "../src/errors.js";
import {
  ElastiCacheIntegrationsRequest,
  ElastiCacheIntegrationsResponse,
  RequestMetadata,
} from "../src/models.js";
import {
  awsToCacheRequest,
  installCacheMiddleware,
  installedHandle,
  ElastiCacheIntegrationsHandle,
  cacheToHttpResponse,
  cacheMiddleware,
} from "../src/middleware.js";
import { ElastiCacheIntegrationsSession } from "../src/session.js";
import type { ElastiCacheIntegrationsConnector } from "../src/connection.js";

const request = (body?: unknown) =>
  new HttpRequest({
    protocol: "https:",
    hostname: "dynamodb.us-east-1.amazonaws.com",
    port: 8443,
    path: "/",
    query: { page: ["2"], filter: ["a b"] },
    method: "POST",
    headers: { "content-type": "application/x-amz-json-1.0" },
    body,
  });

function fakeSession(
  overrides: Partial<ElastiCacheIntegrationsSession> & { isOpen?: boolean } = {},
): ElastiCacheIntegrationsSession {
  return {
    isOpen: true,
    isConnectionHealthy: true,
    request: vi.fn(async () => new ElastiCacheIntegrationsResponse({ statusCode: 200 })),
    destroy: vi.fn(),
    ...overrides,
  } as ElastiCacheIntegrationsSession;
}

/** A minimal ElastiCacheIntegrationsConnector double for real-session recovery tests. */
function fakeConnector(
  overrides: Partial<ElastiCacheIntegrationsConnector> = {},
): ElastiCacheIntegrationsConnector {
  return {
    isReady: true,
    connect: vi.fn(async () => undefined),
    handshake: vi.fn(async () => undefined),
    execute: vi.fn(async (command: string) =>
      command === "aws.config"
        ? Buffer.from('{"refresh_interval":60,"predicates":[{"expr":"true","vars":{}}]}')
        : undefined,
    ),
    close: vi.fn(async () => undefined),
    destroy: vi.fn(() => undefined),
    ...overrides,
  } as unknown as ElastiCacheIntegrationsConnector;
}

describe("awsToCacheRequest", () => {
  it("preserves the final serialized unsigned Smithy request and explicit service metadata", () => {
    const smithyRequest = request(Buffer.from("payload"));

    const cacheRequest = awsToCacheRequest(smithyRequest, "dynamodb", "GetItemCommand")!;

    expect(cacheRequest).toEqual(
      new ElastiCacheIntegrationsRequest({
        method: "POST",
        url: formatUrl(smithyRequest),
        headers: { ...smithyRequest.headers },
        body: Buffer.from("payload"),
        metadata: new RequestMetadata({ service: "dynamodb", operation: "GetItem" }),
      }),
    );
    expect(cacheRequest.url).toBe(
      "https://dynamodb.us-east-1.amazonaws.com:8443/?filter=a%20b&page=2",
    );
    expect(cacheRequest.headers).not.toBe(smithyRequest.headers);
  });

  it("strips SigV4 and JS SDK-only headers", () => {
    const signed = new HttpRequest({
      protocol: "https:",
      hostname: "dynamodb.us-east-1.amazonaws.com",
      path: "/",
      method: "POST",
      headers: {
        host: "dynamodb.us-east-1.amazonaws.com",
        "content-type": "application/x-amz-json-1.0",
        "x-amz-target": "DynamoDB_20120810.GetItem",
        authorization: "AWS4-HMAC-SHA256 Credential=AKIAEXAMPLE/...",
        "X-Amz-Date": "20260806T000000Z",
        "x-amz-security-token": "token-example",
        "x-amz-content-sha256": "abc123",
        "x-amz-user-agent": "aws-sdk-js/3.0.0",
      },
      body: Buffer.from("payload"),
    });

    const cacheRequest = awsToCacheRequest(signed, "dynamodb", "GetItemCommand")!;

    expect(cacheRequest.headers).toEqual({
      "content-type": "application/x-amz-json-1.0",
      "x-amz-target": "DynamoDB_20120810.GetItem",
    });
  });

  it.each([
    [undefined, Buffer.alloc(0)],
    [null, Buffer.alloc(0)],
    ["", Buffer.alloc(0)],
    ["payload", Buffer.from("payload")],
    [Buffer.from("payload"), Buffer.from("payload")],
    [new Uint8Array([112, 97, 121, 108, 111, 97, 100]), Buffer.from("payload")],
    [new Uint8Array(), Buffer.alloc(0)],
  ])("normalizes %s request bodies", (body, expected) => {
    expect(awsToCacheRequest(request(body), "svc", "RunCommand")!.body).toEqual(expected);
  });

  it("copies a Uint8Array subclass body without converting it to a string", () => {
    // The SDK's JSON codec builds bodies as a Uint8Array subclass whose valueOf() and
    // toString() decode to a string and log a warning, and may throw in a future version.
    class StringConvertingBody extends Uint8Array {
      override valueOf(): never {
        throw new Error("valueOf called");
      }
      override toString(): never {
        throw new Error("toString called");
      }
    }
    const body = new StringConvertingBody(Buffer.from('{"TableName":"t"}'));

    expect(awsToCacheRequest(request(body), "svc", "RunCommand")!.body).toEqual(
      Buffer.from('{"TableName":"t"}'),
    );
  });

  it("reuses Buffer request bodies", () => {
    const body = Buffer.from("payload");

    expect(awsToCacheRequest(request(body), "svc", "RunCommand")!.body).toBe(body);
  });

  it.each([
    ["a Readable", Readable.from(["stream"])],
    ["a web ReadableStream", new ReadableStream()],
    ["a Blob", new Blob(["payload"])],
    ["a non-Uint8Array typed array", new Int8Array()],
    ["0", 0],
    ["false", false],
  ])("declines an uncarriable %s request body", (_name, body) => {
    expect(awsToCacheRequest(request(body), "svc", "RunCommand")).toBeUndefined();
  });

  it("rejects a non-Smithy request", () => {
    expect(() => awsToCacheRequest({}, "svc", "RunCommand")).toThrow(ElastiCacheIntegrationsError);
  });

  it("rejects missing command metadata", () => {
    expect(() => awsToCacheRequest(request(), "svc", "")).toThrow(ElastiCacheIntegrationsError);
    expect(() => awsToCacheRequest(request(), "svc", undefined)).toThrow(
      ElastiCacheIntegrationsError,
    );
  });

  it.each([
    ["GetItemCommand", "GetItem"],
    ["Custom", "Custom"],
  ])("derives operation %s as %s", (commandName, operation) => {
    expect(awsToCacheRequest(request(), "svc", commandName)!.metadata.operation).toBe(operation);
  });
});

describe("cacheToHttpResponse", () => {
  it("converts a ElastiCacheIntegrations response to a Smithy response", () => {
    const result = cacheToHttpResponse(
      new ElastiCacheIntegrationsResponse({
        statusCode: 201,
        headers: { etag: "abc" },
        body: Buffer.from("ok"),
      }),
    );

    expect(result).toBeInstanceOf(HttpResponse);
    expect(result).toEqual(
      new HttpResponse({ statusCode: 201, headers: { etag: "abc" }, body: Buffer.from("ok") }),
    );
  });
});

describe("ElastiCacheIntegrationsHandle", () => {
  it("installs during construction and makes client destroy tear ElastiCacheIntegrations down first", () => {
    const order: string[] = [];
    const session = fakeSession({
      destroy: vi.fn(() => order.push("elastiCacheIntegrations")),
    });
    const sdkClient = {
      middlewareStack: constructStack<object, object>(),
      destroy: vi.fn(() => order.push("sdk")),
    };
    const handle = new ElastiCacheIntegrationsHandle(sdkClient, session, "svc");

    sdkClient.destroy();
    sdkClient.destroy();

    // The second destroy() is the SDK's own again, as on a client that never had a handle.
    expect(order).toEqual(["elastiCacheIntegrations", "sdk", "sdk"]);
    expect(handle.installed).toBe(false);
    expect(session.destroy).toHaveBeenCalledOnce();
    expect(sdkClient.middlewareStack.remove("elastiCacheIntegrations")).toBe(false);
  });

  it("tears down once when concurrent async callers destroy the client", async () => {
    const session = fakeSession();
    const originalDestroy = vi.fn();
    const sdkClient = {
      middlewareStack: constructStack<object, object>(),
      destroy: originalDestroy,
    };
    const handle = new ElastiCacheIntegrationsHandle(sdkClient, session, "svc");
    await Promise.all(
      Array.from({ length: 32 }, () => Promise.resolve().then(() => sdkClient.destroy())),
    );

    expect(session.destroy).toHaveBeenCalledOnce();
    expect(originalDestroy).toHaveBeenCalledTimes(32);
    expect(handle.installed).toBe(false);
  });

  it("can be installed again after destroy", () => {
    const originalDestroy = vi.fn();
    const sdkClient = {
      middlewareStack: constructStack<object, object>(),
      destroy: originalDestroy,
    };
    const firstSession = fakeSession();
    const secondSession = fakeSession();

    new ElastiCacheIntegrationsHandle(sdkClient, firstSession, "svc");
    sdkClient.destroy();
    expect(sdkClient.destroy).toBe(originalDestroy);

    new ElastiCacheIntegrationsHandle(sdkClient, secondSession, "svc");
    sdkClient.destroy();

    expect(secondSession.destroy).toHaveBeenCalledOnce();
    expect(originalDestroy).toHaveBeenCalledTimes(2);
    expect(sdkClient.destroy).toBe(originalDestroy);
  });

  it("shutdown detaches ElastiCacheIntegrations without destroying the SDK client", () => {
    const session = fakeSession();
    const originalDestroy = vi.fn();
    const sdkClient = {
      middlewareStack: constructStack<object, object>(),
      destroy: originalDestroy,
    };
    const handle = new ElastiCacheIntegrationsHandle(sdkClient, session, "svc");

    handle.shutdown();
    handle.shutdown();

    expect(session.destroy).toHaveBeenCalledOnce();
    expect(originalDestroy).not.toHaveBeenCalled();

    sdkClient.destroy();
    expect(originalDestroy).toHaveBeenCalledOnce();
  });

  it("shutdown drops handler chains cached while the middleware was installed", () => {
    const sdkClient = {
      middlewareStack: constructStack<object, object>(),
      destroy: vi.fn(),
      handlers: new Map([["GetItemCommand", () => undefined]]),
    };
    const handle = new ElastiCacheIntegrationsHandle(sdkClient, fakeSession(), "svc");
    sdkClient.handlers = new Map([["GetItemCommand", () => undefined]]);

    handle.shutdown();

    expect(sdkClient).not.toHaveProperty("handlers");
  });

  it("is visible to a second copy of the package loaded in the same process", async () => {
    // An app that both imports and requires the package loads the ESM and CJS builds side by side.
    vi.resetModules();
    const otherCopy = await import("../src/middleware.js");
    expect(otherCopy.installedHandle).not.toBe(installedHandle);
    const sdkClient = {
      middlewareStack: constructStack<object, object>(),
      destroy: vi.fn(),
    };

    const handle = new ElastiCacheIntegrationsHandle(sdkClient, fakeSession(), "svc");
    expect(otherCopy.installedHandle(sdkClient)).toBe(handle);

    handle.shutdown();
    expect(otherCopy.installedHandle(sdkClient)).toBeUndefined();
  });
});

describe("elastiCacheIntegrations middleware", () => {
  it("passes predicate declines through to the untouched origin request", async () => {
    const signedRequest = request(Buffer.from("{}"));
    signedRequest.headers.authorization = "AWS4-HMAC-SHA256 Credential=example";
    const session = fakeSession({ request: vi.fn(async () => undefined) });
    const next = vi.fn<FinalizeHandler<object, object>>(async (args) => {
      expect(args.request).toBe(signedRequest);
      expect((args.request as HttpRequest).headers.authorization).toBe(
        "AWS4-HMAC-SHA256 Credential=example",
      );
      return { response: new HttpResponse({ statusCode: 204 }) };
    });
    const handler = cacheMiddleware("svc", session)(next, { commandName: "RunCommand" });

    await expect(handler({ input: {}, request: signedRequest })).resolves.toEqual({
      response: expect.any(HttpResponse),
    });
    expect(next).toHaveBeenCalledOnce();
  });

  it("replaces the terminal origin handler while preserving session errors", async () => {
    const sessionError = new Error("session failed");
    const session = fakeSession({
      request: vi.fn(async () => {
        throw sessionError;
      }),
    });
    const next = vi.fn<FinalizeHandler<object, object>>(async () => {
      throw new Error("origin must not be called");
    });
    const handler = cacheMiddleware("svc", session)(next, { commandName: "RunCommand" });

    await expect(handler({ input: {}, request: request() })).rejects.toBe(sessionError);
    expect(next).not.toHaveBeenCalled();
  });

  it("passes a streaming request body through to the origin without calling ElastiCacheIntegrations", async () => {
    const body = Readable.from(["payload"]);
    const streamingRequest = request(body);
    const session = fakeSession();
    const next = vi.fn<FinalizeHandler<object, object>>(async (args) => {
      expect(args.request).toBe(streamingRequest);
      return { response: new HttpResponse({ statusCode: 200 }) };
    });
    const handler = cacheMiddleware("s3", session)(next, { commandName: "PutObjectCommand" });

    await expect(handler({ input: {}, request: streamingRequest })).resolves.toEqual({
      response: expect.any(HttpResponse),
    });
    expect(session.request).not.toHaveBeenCalled();
    expect(next).toHaveBeenCalledOnce();
    expect(body.readableDidRead).toBe(false);
  });

  it("does not pass ElastiCacheIntegrations request validation failures through to the origin", async () => {
    const session = fakeSession();
    const next = vi.fn<FinalizeHandler<object, object>>(async () => {
      throw new Error("origin must not be called");
    });
    const handler = cacheMiddleware("svc", session)(next, {});

    await expect(handler({ input: {}, request: request() })).rejects.toThrow(
      "Malformed ElastiCacheIntegrations middleware command metadata",
    );
    expect(session.request).not.toHaveBeenCalled();
    expect(next).not.toHaveBeenCalled();
  });

  it("passes a dropped idempotent ElastiCacheIntegrations request through to the origin", async () => {
    const signedRequest = new HttpRequest({
      protocol: "https:",
      hostname: "dynamodb.us-east-1.amazonaws.com",
      path: "/",
      method: "POST",
      headers: {
        host: "dynamodb.us-east-1.amazonaws.com",
        authorization: "AWS4-HMAC-SHA256 Credential=example",
        "x-amz-date": "20260812T000000Z",
      },
      body: Buffer.from("signed-body"),
    });
    const session = fakeSession({
      isConnectionHealthy: false,
      request: vi.fn(async () => {
        throw new ElastiCacheIntegrationsTimeoutError("cache unavailable");
      }),
    });
    const next = vi.fn<FinalizeHandler<object, object>>(async (args) => {
      expect(args.request).toBe(signedRequest);
      expect((args.request as HttpRequest).headers.authorization).toBe(
        "AWS4-HMAC-SHA256 Credential=example",
      );
      expect((args.request as HttpRequest).headers["x-amz-date"]).toBe("20260812T000000Z");
      return { response: new HttpResponse({ statusCode: 503 }) };
    });
    const handler = cacheMiddleware("dynamodb", session)(next, { commandName: "GetItemCommand" });

    await expect(handler({ input: {}, request: signedRequest })).resolves.toEqual({
      response: expect.any(HttpResponse),
    });
    expect(next).toHaveBeenCalledOnce();
  });

  it("does not pass shutdown ElastiCacheIntegrations failures through to the origin", async () => {
    const connectionClosed = new ElastiCacheIntegrationsConnectionLost("connection closed");
    const session = fakeSession({
      isOpen: false,
      request: vi.fn(async () => {
        throw connectionClosed;
      }),
    });
    const next = vi.fn<FinalizeHandler<object, object>>(async () => {
      throw new Error("origin must not be called");
    });
    const handler = cacheMiddleware("dynamodb", session)(next, {
      commandName: "GetItemCommand",
    });

    await expect(handler({ input: {}, request: request() })).rejects.toBe(connectionClosed);
    expect(next).not.toHaveBeenCalled();
  });

  it("propagates fallback-window exhaustion without calling origin", async () => {
    const unavailable = new ElastiCacheIntegrationsCacheUnavailable("https://x/");
    const session = fakeSession({
      request: vi.fn(async () => {
        throw unavailable;
      }),
    });
    const next = vi.fn<FinalizeHandler<object, object>>(async () => {
      throw new Error("origin must not be called");
    });
    const handler = cacheMiddleware("svc", session)(next, {
      commandName: "RunCommand",
    });

    await expect(handler({ input: {}, request: request() })).rejects.toBe(unavailable);
    expect(next).not.toHaveBeenCalled();
  });

  it("falls back to the origin while stale config triggers recovery", async () => {
    let clock = 0;
    const connector = fakeConnector();
    const session = new ElastiCacheIntegrationsSession(
      connector,
      {},
      {
        stalenessDurationMs: 1000,
        clock: () => clock,
      },
    );
    await session.open();
    vi.mocked(connector.execute).mockRejectedValue(
      new ElastiCacheIntegrationsError("aws.config failed"),
    );
    clock = 1000;
    // Drive staleness recovery exactly as Refresher would, without waiting on real timers.
    await (session as unknown as { refreshOnce: () => Promise<void> }).refreshOnce();
    expect(session.isOpen).toBe(true);
    expect(session.connectionState).toBe("reconnecting");

    const next = vi.fn<FinalizeHandler<object, object>>(async () => ({
      response: new HttpResponse({ statusCode: 200 }),
    }));
    // Idempotency does not matter here: the request has not reached ElastiCacheIntegrations while reconnecting.
    const handler = cacheMiddleware("svc", session)(next, { commandName: "RunCommand" });

    await expect(handler({ input: {}, request: request() })).resolves.toEqual({
      response: expect.any(HttpResponse),
    });
    expect(next).toHaveBeenCalledOnce();

    await session.close();
  });

  /**
   * Bring up a real session, then leave a request in flight against the connector at the exact
   * moment stale config starts recovery, so closing the connector rejects that request.
   */
  async function staleRaceSession(): Promise<{
    session: ElastiCacheIntegrationsSession;
    connectionLost: ElastiCacheIntegrationsConnectionLost;
    goStale: () => Promise<void>;
  }> {
    let clock = 0;
    let rejectRequest!: (error: unknown) => void;
    const connectionLost = new ElastiCacheIntegrationsConnectionLost("connection closed");
    const connector = fakeConnector();
    const session = new ElastiCacheIntegrationsSession(
      connector,
      {},
      {
        stalenessDurationMs: 1000,
        clock: () => clock,
      },
    );
    await session.open();
    vi.mocked(connector.execute).mockImplementation((command: string) => {
      if (command === "aws.config")
        return Promise.reject(new ElastiCacheIntegrationsError("aws.config failed"));
      return new Promise((_resolve, reject) => {
        rejectRequest = reject;
      });
    });
    vi.mocked(connector.close).mockImplementation(async () => {
      // Staleness recovery closing the connector rejects the request already in flight.
      rejectRequest(connectionLost);
    });
    return {
      session,
      connectionLost,
      goStale: async () => {
        expect(session.isConnectionHealthy).toBe(true);
        clock = 1000;
        await (session as unknown as { refreshOnce: () => Promise<void> }).refreshOnce();
        expect(session.connectionState).toBe("reconnecting");
      },
    };
  }

  it("falls back to the origin for an idempotent request racing staleness recovery", async () => {
    const { session, goStale } = await staleRaceSession();
    const next = vi.fn<FinalizeHandler<object, object>>(async () => ({
      response: new HttpResponse({ statusCode: 200 }),
    }));
    const handler = cacheMiddleware("dynamodb", session)(next, {
      commandName: "GetItemCommand",
    });

    const handling = handler({ input: {}, request: request() });
    await goStale();

    await expect(handling).resolves.toEqual({ response: expect.any(HttpResponse) });
    expect(next).toHaveBeenCalledOnce();
    await session.close();
  });

  it("does not replay a non-idempotent request racing staleness recovery", async () => {
    const { session, connectionLost, goStale } = await staleRaceSession();
    const next = vi.fn<FinalizeHandler<object, object>>(async () => {
      throw new Error("origin must not be called");
    });
    const handler = cacheMiddleware("dynamodb", session)(next, {
      commandName: "PutItemCommand",
    });

    const handling = handler({ input: {}, request: request() });
    await goStale();

    await expect(handling).rejects.toBe(connectionLost);
    expect(next).not.toHaveBeenCalled();
    await session.close();
  });

  it("does not replay non-idempotent ElastiCacheIntegrations failures at the origin", async () => {
    const cacheError = new ElastiCacheIntegrationsTimeoutError("cache unavailable");
    const session = fakeSession({
      request: vi.fn(async () => {
        throw cacheError;
      }),
    });
    const next = vi.fn<FinalizeHandler<object, object>>(async () => {
      throw new Error("origin must not be called");
    });
    const handler = cacheMiddleware("dynamodb", session)(next, {
      commandName: "UpdateItemCommand",
    });

    await expect(handler({ input: {}, request: request() })).rejects.toBe(cacheError);
    expect(next).not.toHaveBeenCalled();
  });

  it("does not mark a nominally healthy connection-loss error retryable", async () => {
    const cacheError = new ElastiCacheIntegrationsConnectionLost("socket died");
    const session = fakeSession({
      request: vi.fn(async () => {
        throw cacheError;
      }),
    });
    const next = vi.fn<FinalizeHandler<object, object>>(async () => {
      throw new Error("origin must not be called");
    });
    const handler = cacheMiddleware("dynamodb", session)(next, {
      commandName: "GetItemCommand",
    });

    await expect(handler({ input: {}, request: request() })).rejects.toBe(cacheError);
    expect((cacheError as Error & { $retryable?: object }).$retryable).toBeUndefined();
    expect(next).not.toHaveBeenCalled();
  });

  it("returns non-origin RESP rejections without contacting origin", async () => {
    const cacheError = new ElastiCacheIntegrationsRejectedError(
      "UNSUPPORTED",
      "cache rejected request",
    );
    const session = fakeSession({
      isConnectionHealthy: false,
      request: vi.fn(async () => {
        throw cacheError;
      }),
    });
    const next = vi.fn<FinalizeHandler<object, object>>(async () => {
      throw new Error("origin must not be called");
    });
    const handler = cacheMiddleware("dynamodb", session)(next, {
      commandName: "GetItemCommand",
    });

    await expect(handler({ input: {}, request: request() })).rejects.toBe(cacheError);
    expect(next).not.toHaveBeenCalled();
  });

  it("marks an idempotent ORIGIN_ERROR retryable without contacting origin", async () => {
    const cacheError = new ElastiCacheIntegrationsRejectedError(
      "ORIGIN_ERROR",
      "origin unavailable",
    );
    const session = fakeSession({
      request: vi.fn(async () => {
        throw cacheError;
      }),
    });
    const next = vi.fn<FinalizeHandler<object, object>>(async () => {
      throw new Error("origin must not be called");
    });
    const handler = cacheMiddleware("dynamodb", session)(next, {
      commandName: "GetItemCommand",
    });

    const failure = await handler({ input: {}, request: request() }).catch(
      (error: unknown) => error,
    );
    expect(failure).toBe(cacheError);
    expect((failure as Error & { $retryable?: object }).$retryable).toEqual({});
    expect(next).not.toHaveBeenCalled();
  });

  it("returns a non-idempotent ORIGIN_ERROR without retry or fallback", async () => {
    const cacheError = new ElastiCacheIntegrationsRejectedError(
      "ORIGIN_ERROR",
      "origin unavailable",
    );
    const session = fakeSession({
      request: vi.fn(async () => {
        throw cacheError;
      }),
    });
    const next = vi.fn<FinalizeHandler<object, object>>(async () => {
      throw new Error("origin must not be called");
    });
    const handler = cacheMiddleware("dynamodb", session)(next, {
      commandName: "UpdateItemCommand",
    });

    await expect(handler({ input: {}, request: request() })).rejects.toBe(cacheError);
    expect((cacheError as Error & { $retryable?: object }).$retryable).toBeUndefined();
    expect(next).not.toHaveBeenCalled();
  });

  it("does not replay a non-idempotent malformed ElastiCacheIntegrations response", async () => {
    const cacheError = new ElastiCacheIntegrationsProtocolError("malformed response");
    const session = fakeSession({
      request: vi.fn(async () => {
        throw cacheError;
      }),
    });
    const next = vi.fn<FinalizeHandler<object, object>>(async () => {
      throw new Error("origin must not be called");
    });
    const handler = cacheMiddleware("dynamodb", session)(next, {
      commandName: "UpdateItemCommand",
    });

    await expect(handler({ input: {}, request: request() })).rejects.toBe(cacheError);
    expect(next).not.toHaveBeenCalled();
  });

  it("does not retry an idempotent malformed ElastiCacheIntegrations response", async () => {
    const cacheError = new ElastiCacheIntegrationsProtocolError("malformed response");
    const session = fakeSession({
      isConnectionHealthy: false,
      request: vi.fn(async () => {
        throw cacheError;
      }),
    });
    const next = vi.fn<FinalizeHandler<object, object>>(async () => {
      throw new Error("origin must not be called");
    });
    const handler = cacheMiddleware("dynamodb", session)(next, {
      commandName: "GetItemCommand",
    });

    await expect(handler({ input: {}, request: request() })).rejects.toBe(cacheError);
    expect((cacheError as Error & { $retryable?: object }).$retryable).toBeUndefined();
    expect(next).not.toHaveBeenCalled();
  });

  it("runs after final serialization and returns through the deserializer", async () => {
    const session = fakeSession({
      request: vi.fn(async (cacheRequest: ElastiCacheIntegrationsRequest) => {
        expect(cacheRequest.headers.authorization).toBeUndefined();
        expect(cacheRequest.headers["x-amz-date"]).toBeUndefined();
        return new ElastiCacheIntegrationsResponse({
          statusCode: 200,
          body: Buffer.from("cached"),
        });
      }),
    });
    const clientStack = constructStack<object, { value: string }>();
    const commandStack = constructStack<object, { value: string }>();
    const origin = vi.fn<DeserializeHandler<object, { value: string }>>(async () => {
      throw new Error("origin must not be called");
    });
    const finalize = vi.fn(
      (next: FinalizeHandler<object, { value: string }>) =>
        async (args: Parameters<typeof next>[0]) =>
          next(args),
    );
    const serializer =
      (next: SerializeHandler<object, { value: string }>) =>
      async (args: Parameters<typeof next>[0]) =>
        next({ ...args, request: request() });
    const deserializerMiddleware =
      (next: DeserializeHandler<object, { value: string }>) =>
      async (args: Parameters<typeof next>[0]) => {
        const result = await next(args);
        return {
          response: result.response,
          output: { value: String(result.response && (result.response as HttpResponse).body) },
        };
      };

    clientStack.add(finalize, {
      name: "finalizeRequestMiddleware",
      step: "finalizeRequest",
    });
    commandStack.add(serializer, { name: "serializerMiddleware", step: "serialize" });
    commandStack.add(deserializerMiddleware, {
      name: "deserializerMiddleware",
      step: "deserialize",
    });
    installCacheMiddleware({ middlewareStack: clientStack }, "svc", session);
    const stack = clientStack.concat(commandStack);

    const result = await stack.resolve(origin, { commandName: "GetItemCommand" })({ input: {} });

    expect(origin).not.toHaveBeenCalled();
    expect(result.output).toEqual({ value: "cached" });
    expect(finalize).toHaveBeenCalledOnce();
  });
});
