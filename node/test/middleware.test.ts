import { describe, expect, it, vi } from "vitest";

import { formatUrl } from "@aws-sdk/core/util";
import { constructStack } from "@smithy/core/client";
import { HttpRequest, HttpResponse } from "@smithy/core/protocols";
import type { DeserializeHandler, FinalizeHandler, SerializeHandler } from "@smithy/types";
import { Readable } from "node:stream";

import {
  ElastiCacheIntegrationsCacheUnavailable,
  ElastiCacheIntegrationsConnectionError,
  ElastiCacheIntegrationsConnectionLost,
  ElastiCacheIntegrationsError,
  ElastiCacheIntegrationsProtocolError,
  ElastiCacheIntegrationsRejectedError,
  ElastiCacheIntegrationsRequestNotSent,
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
  cacheMiddleware as routeMiddleware,
  cacheRequestHandler,
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

function fakeRequestHandler() {
  return { handle: vi.fn(async () => ({ response: new HttpResponse({ statusCode: 200 }) })) };
}

/**
 * The cache step as the SDK runs it: the middleware, then the wrapped request handler, whose
 * origin is `next`. `options` are the per-call options the SDK passes the request handler.
 */
function cacheMiddleware(service: string, session: ElastiCacheIntegrationsSession, options = {}) {
  return (next: FinalizeHandler<object, object>, context: { commandName?: string }) =>
    async (args: { input: object; request: unknown }) => {
      const routed = new WeakMap<object, ElastiCacheIntegrationsRequest>();
      const origin = {
        handle: (request: HttpRequest) => next({ ...args, request }) as never,
      };
      const handler = cacheRequestHandler(origin, session, routed);
      const middleware = routeMiddleware<object, object>(service, routed);
      return middleware(
        (forwarded) => handler.handle(forwarded.request as HttpRequest, options),
        context as never,
      )(args as never);
    };
}

/** A minimal ElastiCacheIntegrationsConnector double for real-session recovery tests. */
function fakeConnector(
  overrides: Partial<ElastiCacheIntegrationsConnector> = {},
): ElastiCacheIntegrationsConnector {
  return {
    isReady: true,
    connect: vi.fn(async () => undefined),
    markReady: vi.fn(),
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
      config: { requestHandler: fakeRequestHandler() },
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
      config: { requestHandler: fakeRequestHandler() },
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
      config: { requestHandler: fakeRequestHandler() },
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
      config: { requestHandler: fakeRequestHandler() },
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
      config: { requestHandler: fakeRequestHandler() },
      destroy: vi.fn(),
      handlers: new Map([["GetItemCommand", () => undefined]]),
    };
    const handle = new ElastiCacheIntegrationsHandle(sdkClient, fakeSession(), "svc");
    sdkClient.handlers = new Map([["GetItemCommand", () => undefined]]);

    handle.shutdown();

    expect(sdkClient).not.toHaveProperty("handlers");
  });

  it("wraps the client's request handler without changing the shared handler", async () => {
    const sharedHandler = {
      handle: vi.fn(async () => ({ response: new HttpResponse({ statusCode: 200 }) })),
      destroy: vi.fn(),
      httpHandlerConfigs: vi.fn(() => ({ requestTimeout: 1000 })),
    };
    const ownHandle = sharedHandler.handle;
    const sdkClient = {
      middlewareStack: constructStack<object, object>(),
      config: { requestHandler: sharedHandler as object },
      destroy: vi.fn(),
    };
    const handle = new ElastiCacheIntegrationsHandle(sdkClient, fakeSession(), "svc");
    const wrapped = sdkClient.config.requestHandler as typeof sharedHandler;

    expect(wrapped).not.toBe(sharedHandler);
    expect(sharedHandler.handle).toBe(ownHandle);
    expect(wrapped.httpHandlerConfigs()).toEqual({ requestTimeout: 1000 });
    wrapped.destroy();
    expect(sharedHandler.destroy).toHaveBeenCalledOnce();
    // A request the middleware did not prepare goes straight to the real handler.
    const unrouted = request();
    await wrapped.handle(unrouted as never);
    expect(ownHandle).toHaveBeenCalledWith(unrouted, undefined);

    handle.shutdown();
    expect(sdkClient.config.requestHandler).toBe(sharedHandler);
  });

  it("wraps a request handler the application sets after install, and keeps it on shutdown", async () => {
    const sdkClient = {
      middlewareStack: constructStack<object, object>(),
      config: { requestHandler: fakeRequestHandler() as object },
      destroy: vi.fn(),
    };
    const handle = new ElastiCacheIntegrationsHandle(sdkClient, fakeSession(), "svc");
    const replacement = fakeRequestHandler();
    sdkClient.config.requestHandler = replacement;

    // The replacement is wrapped too; a request the middleware did not prepare reaches it as is.
    const wrapped = sdkClient.config.requestHandler as typeof replacement;
    expect(wrapped).not.toBe(replacement);
    const unrouted = request();
    await wrapped.handle(unrouted as never);
    expect(replacement.handle).toHaveBeenCalledWith(unrouted, undefined);

    handle.shutdown();
    expect(sdkClient.config.requestHandler).toBe(replacement);
    expect(Object.getOwnPropertyDescriptor(sdkClient.config, "requestHandler")).toMatchObject({
      writable: true,
      enumerable: true,
    });
  });

  it("is visible to a second copy of the package loaded in the same process", async () => {
    // An app that both imports and requires the package loads the ESM and CJS builds side by side.
    vi.resetModules();
    const otherCopy = await import("../src/middleware.js");
    expect(otherCopy.installedHandle).not.toBe(installedHandle);
    const sdkClient = {
      middlewareStack: constructStack<object, object>(),
      config: { requestHandler: fakeRequestHandler() },
      destroy: vi.fn(),
    };

    const handle = new ElastiCacheIntegrationsHandle(sdkClient, fakeSession(), "svc");
    expect(otherCopy.installedHandle(sdkClient)).toBe(handle);

    handle.shutdown();
    expect(otherCopy.installedHandle(sdkClient)).toBeUndefined();
  });
});

describe("elastiCacheIntegrations middleware", () => {
  it("passes the call's abort signal to the session and does not fall back after an abort", async () => {
    const controller = new AbortController();
    const aborted = Object.assign(new Error("Request aborted"), { name: "AbortError" });
    const session = fakeSession({
      request: vi.fn(async () => {
        throw aborted;
      }),
    });
    const next = vi.fn<FinalizeHandler<object, object>>(async () => {
      throw new Error("origin must not be called");
    });
    const handler = cacheMiddleware("dynamodb", session, { abortSignal: controller.signal })(next, {
      commandName: "GetItemCommand",
    });

    await expect(handler({ input: {}, request: request() })).rejects.toBe(aborted);
    expect(session.request).toHaveBeenCalledWith(expect.any(ElastiCacheIntegrationsRequest), {
      abortSignal: controller.signal,
    });
    expect(next).not.toHaveBeenCalled();
  });

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

  it("retries a dropped-connection timeout without calling origin", async () => {
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
    const next = vi.fn<FinalizeHandler<object, object>>(async () => {
      throw new Error("origin must not be called");
    });
    const handler = cacheMiddleware("dynamodb", session)(next, { commandName: "GetItemCommand" });

    const failure = await handler({ input: {}, request: signedRequest }).catch(
      (error: unknown) => error,
    );
    expect(failure).toBeInstanceOf(ElastiCacheIntegrationsTimeoutError);
    expect((failure as Error & { $retryable?: object }).$retryable).toEqual({});
    expect(next).not.toHaveBeenCalled();
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
        connectionTimeoutMs: 2_000,
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
        fallbackDurationMs: 0,
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

  it("does not send a zero-fallback in-flight read to origin during recovery", async () => {
    const { session, connectionLost, goStale } = await staleRaceSession();
    const next = vi.fn<FinalizeHandler<object, object>>(async () => ({
      response: new HttpResponse({ statusCode: 200 }),
    }));
    const handler = cacheMiddleware("dynamodb", session)(next, {
      commandName: "GetItemCommand",
    });

    const handling = handler({ input: {}, request: request() });
    await goStale();

    await expect(handling).rejects.toBe(connectionLost);
    expect((connectionLost as Error & { $retryable?: object }).$retryable).toEqual({});
    expect(next).not.toHaveBeenCalled();
    await session.close();
  });

  it("marks a PutItem retryable when staleness recovery closes its connection", async () => {
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
    expect((connectionLost as Error & { $retryable?: object }).$retryable).toEqual({});
    expect(next).not.toHaveBeenCalled();
    await session.close();
  });

  it("marks a lost connection retryable for any operation", async () => {
    const cacheError = new ElastiCacheIntegrationsConnectionLost("connection closed by peer");
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
      commandName: "UpdateItemCommand",
    });

    await expect(handler({ input: {}, request: request() })).rejects.toBe(cacheError);
    expect((cacheError as Error & { $retryable?: object }).$retryable).toEqual({});
    expect(next).not.toHaveBeenCalled();
  });

  it("does not mark a lost connection retryable while the connection is healthy", async () => {
    const cacheError = new ElastiCacheIntegrationsConnectionLost("connection closed");
    const session = fakeSession({
      request: vi.fn(async () => {
        throw cacheError;
      }),
    });
    const handler = cacheMiddleware("dynamodb", session)(vi.fn(), {
      commandName: "UpdateItemCommand",
    });

    await expect(handler({ input: {}, request: request() })).rejects.toBe(cacheError);
    expect((cacheError as Error & { $retryable?: object }).$retryable).toBeUndefined();
  });

  it("does not mark a lost connection retryable after the session is closed", async () => {
    const cacheError = new ElastiCacheIntegrationsConnectionLost("connection closed");
    const session = fakeSession({
      isOpen: false,
      isConnectionHealthy: false,
      request: vi.fn(async () => {
        throw cacheError;
      }),
    });
    const handler = cacheMiddleware("dynamodb", session)(vi.fn(), {
      commandName: "UpdateItemCommand",
    });

    await expect(handler({ input: {}, request: request() })).rejects.toBe(cacheError);
    expect((cacheError as Error & { $retryable?: object }).$retryable).toBeUndefined();
  });

  it.each([
    ["a timeout", () => new ElastiCacheIntegrationsTimeoutError("cache unavailable"), true],
    ["a dropped-connection timeout", () => new ElastiCacheIntegrationsTimeoutError("timed out"), false],
    ["a connection error", () => new ElastiCacheIntegrationsConnectionError("connect failed"), false],
  ])("marks %s on a write retryable without calling origin", async (_, makeError, healthy) => {
    const cacheError = makeError();
    const session = fakeSession({
      isConnectionHealthy: healthy,
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
    expect((cacheError as Error & { $retryable?: object }).$retryable).toEqual({});
    expect(next).not.toHaveBeenCalled();
  });

  it("does not mark a connection error retryable while the connection is healthy", async () => {
    const cacheError = new ElastiCacheIntegrationsConnectionError("connect failed");
    const session = fakeSession({
      request: vi.fn(async () => {
        throw cacheError;
      }),
    });
    const handler = cacheMiddleware("dynamodb", session)(vi.fn(), {
      commandName: "UpdateItemCommand",
    });

    await expect(handler({ input: {}, request: request() })).rejects.toBe(cacheError);
    expect((cacheError as Error & { $retryable?: object }).$retryable).toBeUndefined();
  });

  it("passes a write that was not sent to the cache through to origin", async () => {
    const session = fakeSession({
      request: vi.fn(async () => {
        throw new ElastiCacheIntegrationsRequestNotSent("admission timed out");
      }),
    });
    const next = vi.fn<FinalizeHandler<object, object>>(async () => ({
      response: new HttpResponse({ statusCode: 200 }),
    }));
    const handler = cacheMiddleware("dynamodb", session)(next, {
      commandName: "UpdateItemCommand",
    });

    await expect(handler({ input: {}, request: request() })).resolves.toEqual({
      response: expect.any(HttpResponse),
    });
    expect(next).toHaveBeenCalledOnce();
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

  it.each([
    ["ORIGIN_ERROR", "GetItemCommand", true],
    ["ORIGIN_ERROR", "UpdateItemCommand", true],
    ["ORIGIN_ERROR", "UpdateItemCommand", false],
    ["INTERNAL_ERROR_RETRY", "GetItemCommand", true],
    ["INTERNAL_ERROR_RETRY", "UpdateItemCommand", false],
  ])(
    "marks %s on %s retryable without contacting origin (healthy: %s)",
    async (code, commandName, healthy) => {
      const cacheError = new ElastiCacheIntegrationsRejectedError(code, "not applied");
      const session = fakeSession({
        isConnectionHealthy: healthy,
        request: vi.fn(async () => {
          throw cacheError;
        }),
      });
      const next = vi.fn<FinalizeHandler<object, object>>(async () => {
        throw new Error("origin must not be called");
      });
      const handler = cacheMiddleware("dynamodb", session)(next, { commandName });

      await expect(handler({ input: {}, request: request() })).rejects.toBe(cacheError);
      expect((cacheError as Error & { $retryable?: object }).$retryable).toEqual({});
      expect(next).not.toHaveBeenCalled();
    },
  );

  it.each([
    ["INTERNAL_ERROR_FAIL", "UpdateItemCommand"],
    ["INTERNAL_ERROR_FAIL", "GetItemCommand"],
    ["INTERNAL_ERROR", "GetItemCommand"],
    ["PARSE_ERROR", "GetItemCommand"],
    ["SOME_NEW_CODE", "UpdateItemCommand"],
  ])("returns %s on %s without retry or fallback", async (code, commandName) => {
    const cacheError = new ElastiCacheIntegrationsRejectedError(code, "rejected");
    const session = fakeSession({
      request: vi.fn(async () => {
        throw cacheError;
      }),
    });
    const next = vi.fn<FinalizeHandler<object, object>>(async () => {
      throw new Error("origin must not be called");
    });
    const handler = cacheMiddleware("dynamodb", session)(next, { commandName });

    await expect(handler({ input: {}, request: request() })).rejects.toBe(cacheError);
    expect((cacheError as Error & { $retryable?: object }).$retryable).toBeUndefined();
    expect(cacheError.cause).toBeUndefined();
    expect(next).not.toHaveBeenCalled();
  });

  it("does not replay a malformed ElastiCacheIntegrations response for a write", async () => {
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

  it("does not retry a malformed ElastiCacheIntegrations response for a read", async () => {
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
    const origin = vi.fn(async (): Promise<{ response: HttpResponse }> => {
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
    const routed = new WeakMap<object, ElastiCacheIntegrationsRequest>();
    installCacheMiddleware({ middlewareStack: clientStack }, "svc", routed);
    const requestHandler = cacheRequestHandler({ handle: origin }, session, routed);
    const stack = clientStack.concat(commandStack);

    // The SDK's terminal step: hand the request and the call's options to the request handler.
    const result = await stack.resolve(
      async (args) =>
        (await requestHandler.handle(args.request as HttpRequest, {})) as unknown as {
          output: { value: string };
          response: unknown;
        },
      { commandName: "GetItemCommand" },
    )({ input: {} });

    expect(origin).not.toHaveBeenCalled();
    expect(result.output).toEqual({ value: "cached" });
    expect(finalize).toHaveBeenCalledOnce();
  });
});
