import { describe, expect, it, vi } from "vitest";

import { formatUrl } from "@aws-sdk/core/util";
import { constructStack } from "@smithy/core/client";
import { HttpRequest, HttpResponse } from "@smithy/core/protocols";
import type { DeserializeHandler, FinalizeHandler, SerializeHandler } from "@smithy/types";
import { Readable } from "node:stream";

import {
  PhotonConnectionLost,
  PhotonError,
  PhotonProtocolError,
  PhotonRejectedError,
  PhotonTimeoutError,
} from "../src/errors.js";
import { PhotonRequest, PhotonResponse, RequestMetadata } from "../src/models.js";
import {
  awsToPhotonRequest,
  installPhotonMiddleware,
  PhotonHandle,
  photonToHttpResponse,
  photonMiddleware,
} from "../src/middleware.js";
import type { PhotonSession } from "../src/session.js";

const request = (body?: unknown) =>
  new HttpRequest({
    protocol: "https:",
    hostname: "dynamodb.us-east-1.amazonaws.com",
    port: 8443,
    path: "/",
    query: { page: ["2"], filter: ["a b"] },
    method: "POST",
    headers: { host: "dynamodb.us-east-1.amazonaws.com" },
    body,
  });

function fakeSession(overrides: Partial<PhotonSession> & { isOpen?: boolean } = {}): PhotonSession {
  return {
    isOpen: true,
    isConnectionHealthy: true,
    request: vi.fn(async () => new PhotonResponse({ statusCode: 200 })),
    destroy: vi.fn(),
    ...overrides,
  } as PhotonSession;
}

describe("awsToPhotonRequest", () => {
  it("preserves the final serialized unsigned Smithy request and explicit service metadata", () => {
    const smithyRequest = request(Buffer.from("payload"));

    const photonRequest = awsToPhotonRequest(smithyRequest, "dynamodb", "GetItemCommand");

    expect(photonRequest).toEqual(
      new PhotonRequest({
        method: "POST",
        url: formatUrl(smithyRequest),
        headers: { ...smithyRequest.headers },
        body: Buffer.from("payload"),
        metadata: new RequestMetadata({ service: "dynamodb", operation: "GetItem" }),
      }),
    );
    expect(photonRequest.url).toBe(
      "https://dynamodb.us-east-1.amazonaws.com:8443/?filter=a%20b&page=2",
    );
    expect(photonRequest.headers).not.toBe(smithyRequest.headers);
  });

  it("strips volatile authentication headers but retains content hash", () => {
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
      },
      body: Buffer.from("payload"),
    });

    const photonRequest = awsToPhotonRequest(signed, "dynamodb", "GetItemCommand");

    expect(photonRequest.headers).toEqual({
      host: "dynamodb.us-east-1.amazonaws.com",
      "content-type": "application/x-amz-json-1.0",
      "x-amz-target": "DynamoDB_20120810.GetItem",
      "x-amz-content-sha256": "abc123",
    });
  });

  it.each([
    [undefined, Buffer.alloc(0)],
    [null, Buffer.alloc(0)],
    ["payload", Buffer.from("payload")],
    [Buffer.from("payload"), Buffer.from("payload")],
    [new Uint8Array([112, 97, 121, 108, 111, 97, 100]), Buffer.from("payload")],
  ])("normalizes %s request bodies", (body, expected) => {
    expect(awsToPhotonRequest(request(body), "svc", "RunCommand").body).toEqual(expected);
  });

  it("reuses Buffer request bodies", () => {
    const body = Buffer.from("payload");

    expect(awsToPhotonRequest(request(body), "svc", "RunCommand").body).toBe(body);
  });

  it("rejects streaming and unsupported request bodies", () => {
    expect(() =>
      awsToPhotonRequest(request(Readable.from(["stream"])), "svc", "RunCommand"),
    ).toThrow(new PhotonError("Streaming AWS request bodies are not supported"));
  });

  it("rejects a non-Smithy request", () => {
    expect(() => awsToPhotonRequest({}, "svc", "RunCommand")).toThrow(PhotonError);
  });

  it("rejects missing command metadata", () => {
    expect(() => awsToPhotonRequest(request(), "svc", "")).toThrow(PhotonError);
    expect(() => awsToPhotonRequest(request(), "svc", undefined)).toThrow(PhotonError);
  });

  it.each([
    ["GetItemCommand", "GetItem"],
    ["Custom", "Custom"],
  ])("derives operation %s as %s", (commandName, operation) => {
    expect(awsToPhotonRequest(request(), "svc", commandName).metadata.operation).toBe(operation);
  });
});

describe("photonToHttpResponse", () => {
  it("converts a Photon response to a Smithy response", () => {
    const result = photonToHttpResponse(
      new PhotonResponse({ statusCode: 201, headers: { etag: "abc" }, body: Buffer.from("ok") }),
    );

    expect(result).toBeInstanceOf(HttpResponse);
    expect(result).toEqual(
      new HttpResponse({ statusCode: 201, headers: { etag: "abc" }, body: Buffer.from("ok") }),
    );
  });
});

describe("PhotonHandle", () => {
  it("installs during construction and makes client destroy tear Photon down first", () => {
    const order: string[] = [];
    const session = fakeSession({
      destroy: vi.fn(() => order.push("photon")),
    });
    const sdkClient = {
      middlewareStack: constructStack<object, object>(),
      destroy: vi.fn(() => order.push("sdk")),
    };
    const handle = new PhotonHandle(sdkClient, session, "svc");

    sdkClient.destroy();
    sdkClient.destroy();

    expect(order).toEqual(["photon", "sdk"]);
    expect(handle.installed).toBe(false);
    expect(session.destroy).toHaveBeenCalledOnce();
    expect(sdkClient.middlewareStack.remove("photon")).toBe(false);
  });

  it("tears down once when concurrent async callers destroy the client", async () => {
    const session = fakeSession();
    const originalDestroy = vi.fn();
    const sdkClient = {
      middlewareStack: constructStack<object, object>(),
      destroy: originalDestroy,
    };
    const handle = new PhotonHandle(sdkClient, session, "svc");
    await Promise.all(
      Array.from({ length: 32 }, () => Promise.resolve().then(() => sdkClient.destroy())),
    );

    expect(session.destroy).toHaveBeenCalledOnce();
    expect(originalDestroy).toHaveBeenCalledOnce();
    expect(handle.installed).toBe(false);
  });

  it("shutdown detaches Photon without destroying the SDK client", () => {
    const session = fakeSession();
    const originalDestroy = vi.fn();
    const sdkClient = {
      middlewareStack: constructStack<object, object>(),
      destroy: originalDestroy,
    };
    const handle = new PhotonHandle(sdkClient, session, "svc");

    handle.shutdown();
    handle.shutdown();

    expect(session.destroy).toHaveBeenCalledOnce();
    expect(originalDestroy).not.toHaveBeenCalled();

    sdkClient.destroy();
    expect(originalDestroy).toHaveBeenCalledOnce();
  });
});

describe("photon middleware", () => {
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
    const handler = photonMiddleware("svc", session)(next, { commandName: "RunCommand" });

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
    const handler = photonMiddleware("svc", session)(next, { commandName: "RunCommand" });

    await expect(handler({ input: {}, request: request() })).rejects.toBe(sessionError);
    expect(next).not.toHaveBeenCalled();
  });

  it("does not pass Photon request validation failures through to the origin", async () => {
    const session = fakeSession();
    const next = vi.fn<FinalizeHandler<object, object>>(async () => {
      throw new Error("origin must not be called");
    });
    const handler = photonMiddleware("svc", session)(next, {});

    await expect(handler({ input: {}, request: request() })).rejects.toThrow(
      "Malformed Photon middleware command metadata",
    );
    expect(session.request).not.toHaveBeenCalled();
    expect(next).not.toHaveBeenCalled();
  });

  it("passes a dropped idempotent Photon request through to the origin", async () => {
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
        throw new PhotonTimeoutError("cache unavailable");
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
    const handler = photonMiddleware("dynamodb", session)(next, { commandName: "GetItemCommand" });

    await expect(handler({ input: {}, request: signedRequest })).resolves.toEqual({
      response: expect.any(HttpResponse),
    });
    expect(next).toHaveBeenCalledOnce();
  });

  it("does not pass shutdown Photon failures through to the origin", async () => {
    const connectionClosed = new PhotonConnectionLost("connection closed");
    const session = fakeSession({
      isOpen: false,
      request: vi.fn(async () => {
        throw connectionClosed;
      }),
    });
    const next = vi.fn<FinalizeHandler<object, object>>(async () => {
      throw new Error("origin must not be called");
    });
    const handler = photonMiddleware("dynamodb", session)(next, {
      commandName: "GetItemCommand",
    });

    await expect(handler({ input: {}, request: request() })).rejects.toBe(connectionClosed);
    expect(next).not.toHaveBeenCalled();
  });

  it("does not replay non-idempotent Photon failures at the origin", async () => {
    const photonError = new PhotonTimeoutError("cache unavailable");
    const session = fakeSession({
      request: vi.fn(async () => {
        throw photonError;
      }),
    });
    const next = vi.fn<FinalizeHandler<object, object>>(async () => {
      throw new Error("origin must not be called");
    });
    const handler = photonMiddleware("dynamodb", session)(next, {
      commandName: "UpdateItemCommand",
    });

    await expect(handler({ input: {}, request: request() })).rejects.toBe(photonError);
    expect(next).not.toHaveBeenCalled();
  });

  it("returns non-origin RESP rejections without contacting origin", async () => {
    const photonError = new PhotonRejectedError("UNSUPPORTED", "cache rejected request");
    const session = fakeSession({
      isConnectionHealthy: false,
      request: vi.fn(async () => {
        throw photonError;
      }),
    });
    const next = vi.fn<FinalizeHandler<object, object>>(async () => {
      throw new Error("origin must not be called");
    });
    const handler = photonMiddleware("dynamodb", session)(next, {
      commandName: "GetItemCommand",
    });

    await expect(handler({ input: {}, request: request() })).rejects.toBe(photonError);
    expect(next).not.toHaveBeenCalled();
  });

  it("marks an idempotent ORIGIN_ERROR retryable without contacting origin", async () => {
    const photonError = new PhotonRejectedError("ORIGIN_ERROR", "origin unavailable");
    const session = fakeSession({
      request: vi.fn(async () => {
        throw photonError;
      }),
    });
    const next = vi.fn<FinalizeHandler<object, object>>(async () => {
      throw new Error("origin must not be called");
    });
    const handler = photonMiddleware("dynamodb", session)(next, {
      commandName: "GetItemCommand",
    });

    const failure = await handler({ input: {}, request: request() }).catch(
      (error: unknown) => error,
    );
    expect(failure).toBe(photonError);
    expect((failure as Error & { $retryable?: object }).$retryable).toEqual({});
    expect(next).not.toHaveBeenCalled();
  });

  it("returns a non-idempotent ORIGIN_ERROR without retry or fallback", async () => {
    const photonError = new PhotonRejectedError("ORIGIN_ERROR", "origin unavailable");
    const session = fakeSession({
      request: vi.fn(async () => {
        throw photonError;
      }),
    });
    const next = vi.fn<FinalizeHandler<object, object>>(async () => {
      throw new Error("origin must not be called");
    });
    const handler = photonMiddleware("dynamodb", session)(next, {
      commandName: "UpdateItemCommand",
    });

    await expect(handler({ input: {}, request: request() })).rejects.toBe(photonError);
    expect((photonError as Error & { $retryable?: object }).$retryable).toBeUndefined();
    expect(next).not.toHaveBeenCalled();
  });

  it("does not replay a non-idempotent malformed Photon response", async () => {
    const photonError = new PhotonProtocolError("malformed response");
    const session = fakeSession({
      request: vi.fn(async () => {
        throw photonError;
      }),
    });
    const next = vi.fn<FinalizeHandler<object, object>>(async () => {
      throw new Error("origin must not be called");
    });
    const handler = photonMiddleware("dynamodb", session)(next, {
      commandName: "UpdateItemCommand",
    });

    await expect(handler({ input: {}, request: request() })).rejects.toBe(photonError);
    expect(next).not.toHaveBeenCalled();
  });

  it("does not retry an idempotent malformed Photon response", async () => {
    const photonError = new PhotonProtocolError("malformed response");
    const session = fakeSession({
      isConnectionHealthy: false,
      request: vi.fn(async () => {
        throw photonError;
      }),
    });
    const next = vi.fn<FinalizeHandler<object, object>>(async () => {
      throw new Error("origin must not be called");
    });
    const handler = photonMiddleware("dynamodb", session)(next, {
      commandName: "GetItemCommand",
    });

    await expect(handler({ input: {}, request: request() })).rejects.toBe(photonError);
    expect((photonError as Error & { $retryable?: object }).$retryable).toBeUndefined();
    expect(next).not.toHaveBeenCalled();
  });

  it("runs after final serialization and returns through the deserializer", async () => {
    const session = fakeSession({
      request: vi.fn(async (photonRequest: PhotonRequest) => {
        expect(photonRequest.headers.authorization).toBeUndefined();
        expect(photonRequest.headers["x-amz-date"]).toBeUndefined();
        return new PhotonResponse({ statusCode: 200, body: Buffer.from("cached") });
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
    installPhotonMiddleware({ middlewareStack: clientStack }, "svc", session);
    const stack = clientStack.concat(commandStack);

    const result = await stack.resolve(origin, { commandName: "GetItemCommand" })({ input: {} });

    expect(origin).not.toHaveBeenCalled();
    expect(result.output).toEqual({ value: "cached" });
    expect(finalize).toHaveBeenCalledOnce();
  });
});
