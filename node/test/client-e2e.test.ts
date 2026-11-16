import { fileURLToPath } from "node:url";

import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";

import { DynamoDBClient, GetItemCommand, PutItemCommand } from "@aws-sdk/client-dynamodb";
import { AssumeRoleCommand, STSClient } from "@aws-sdk/nested-clients/sts";
import { HttpRequest } from "@smithy/core/protocols";
import { createServer, type Server } from "node:http";

import { install } from "../src/index.js";
import {
  ElastiCacheIntegrationsConfigError,
  ElastiCacheIntegrationsConnectionLost,
} from "../src/errors.js";
import { MockCache, Reply } from "./mock-cache.js";

const CA_CERTS = fileURLToPath(new URL("./fixtures/tls/localhost.crt", import.meta.url));

const retryableResponse = Buffer.from(`<?xml version="1.0"?>
<ErrorResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/">
  <Error><Type>Receiver</Type><Code>InternalFailure</Code><Message>retry once</Message></Error>
  <RequestId>retry-example</RequestId>
</ErrorResponse>`);
const successResponse = Buffer.from(`<?xml version="1.0"?>
<AssumeRoleResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/">
  <AssumeRoleResult>
    <Credentials>
      <AccessKeyId>ASIAEXAMPLE</AccessKeyId>
      <SecretAccessKey>secret-example</SecretAccessKey>
      <SessionToken>session-example</SessionToken>
      <Expiration>2030-01-01T00:00:00Z</Expiration>
    </Credentials>
    <AssumedRoleUser>
      <AssumedRoleId>AROAEXAMPLE:elastiCacheIntegrations-test</AssumedRoleId>
      <Arn>arn:aws:iam::123456789012:role/ElastiCacheIntegrationsTest</Arn>
    </AssumedRoleUser>
  </AssumeRoleResult>
</AssumeRoleResponse>`);

describe("public client", () => {
  let responses = 0;
  let origin: Server;
  let originEndpoint: string;
  const originRequests: Array<{ body: string; headers: Record<string, string | string[]> }> = [];
  const cache = new MockCache({
    tls: true,
    config: {
      predicates: [{ expr: "service == 'dynamodb' && operation == 'GetItem'", vars: {} }],
    },
    responseFn: () =>
      ++responses === 1
        ? Reply.ok(500, "content-type:text/xml", retryableResponse)
        : Reply.ok(200, "content-type:text/xml", successResponse),
  });
  const originalCredentials = {
    accessKeyId: process.env.AWS_ACCESS_KEY_ID,
    secretAccessKey: process.env.AWS_SECRET_ACCESS_KEY,
    sessionToken: process.env.AWS_SESSION_TOKEN,
  };

  beforeAll(async () => {
    process.env.AWS_ACCESS_KEY_ID = "AKIAEXAMPLE";
    process.env.AWS_SECRET_ACCESS_KEY = "secret-example";
    process.env.AWS_SESSION_TOKEN = "token-example";
    origin = createServer((request, response) => {
      const chunks: Buffer[] = [];
      request.on("data", (chunk: Buffer) => chunks.push(chunk));
      request.on("end", () => {
        originRequests.push({
          body: Buffer.concat(chunks).toString(),
          headers: request.headers as Record<string, string | string[]>,
        });
        response.statusCode = responses++ === 0 ? 500 : 200;
        response.setHeader("content-type", "text/xml");
        response.end(responses === 1 ? retryableResponse : successResponse);
      });
    });
    await new Promise<void>((resolve) => origin.listen(0, "127.0.0.1", resolve));
    const address = origin.address();
    if (address === null || typeof address === "string") throw new Error("origin did not start");
    originEndpoint = `http://127.0.0.1:${address.port}`;
    await cache.start();
  });

  afterAll(async () => {
    await cache.stop();
    await new Promise<void>((resolve, reject) =>
      origin.close((error) => (error ? reject(error) : resolve())),
    );
    for (const [name, value] of Object.entries(originalCredentials)) {
      if (value === undefined) delete process.env[name];
      else process.env[name] = value;
    }
  });

  it("passes a non-JSON request through to the origin without a cache round trip", async () => {
    const sts = new STSClient({
      region: "us-east-1",
      endpoint: originEndpoint,
      credentials: {
        accessKeyId: "AKIAEXAMPLE",
        secretAccessKey: "secret-example",
        sessionToken: "token-example",
      },
      maxAttempts: 2,
    });
    await install({
      client: sts,
      endpoint: cache.endpoint,
      cacheName: "test-cache",
      caCerts: CA_CERTS,
    });

    const preCacheRequests: HttpRequest[] = [];
    sts.middlewareStack.addRelativeTo(
      (next) => async (args) => {
        if (!HttpRequest.isInstance(args.request)) {
          throw new Error("expected Smithy HttpRequest before ElastiCacheIntegrations");
        }
        preCacheRequests.push(args.request);
        return next(args);
      },
      {
        name: "testSignedRequestProbe",
        relation: "after",
        toMiddleware: "httpSigningMiddleware",
      },
    );

    try {
      const result = await sts.send(
        new AssumeRoleCommand({
          RoleArn: "arn:aws:iam::123456789012:role/ElastiCacheIntegrationsTest",
          RoleSessionName: "elastiCacheIntegrations-test",
        }),
      );

      expect(result.Credentials?.AccessKeyId).toBe("ASIAEXAMPLE");
      expect(result.Credentials?.SecretAccessKey).toBe("secret-example");
      expect(preCacheRequests).toHaveLength(2);
      // The SDK's real signer still runs; ElastiCacheIntegrations strips the auth headers later.
      for (const request of preCacheRequests) {
        expect(request.headers.authorization).toBeDefined();
        expect(request.headers["x-amz-date"]).toBeDefined();
        expect(request.headers["x-amz-security-token"]).toBeDefined();
      }
      expect(cache.cacheCommandLog).toEqual(["hello", "aws.config"]);
      expect(cache.requests).toHaveLength(0);
      expect(originRequests).toHaveLength(2);
      for (const request of originRequests) {
        expect(request.body).toContain("Action=AssumeRole");
        expect(request.headers.authorization).toBeDefined();
        expect(request.headers["x-amz-date"]).toBeDefined();
        expect(request.headers["x-amz-security-token"]).toBeDefined();
      }
    } finally {
      sts.destroy();
      sts.destroy();
    }
  });
});

describe("public DynamoDB client", () => {
  let responses = 0;
  const cache = new MockCache({
    tls: true,
    config: {
      predicates: [
        {
          expr:
            "service == 'dynamodb' && operation == 'GetItem' " +
            "&& body.TableName == 'ElastiCacheIntegrationsTest'",
          vars: {},
        },
      ],
    },
    responseFn: () =>
      ++responses === 1
        ? Reply.ok(
            500,
            "content-type:application/x-amz-json-1.0",
            Buffer.from(
              '{"__type":"com.amazonaws.dynamodb.v20120810#InternalServerError","message":"retry once"}',
            ),
          )
        : Reply.ok(
            200,
            "content-type:application/x-amz-json-1.0",
            Buffer.from('{"Item":{"id":{"S":"value"}}}'),
          ),
  });

  beforeAll(async () => {
    await cache.start();
  });

  afterAll(async () => {
    await cache.stop();
  });

  it("routes serialized unsigned DynamoDB requests through ElastiCacheIntegrations and deserializes retries", async () => {
    const dynamodb = new DynamoDBClient({
      region: "us-east-1",
      endpoint: "http://127.0.0.1:1",
      credentials: {
        accessKeyId: "AKIAEXAMPLE",
        secretAccessKey: "secret-example",
        sessionToken: "token-example",
      },
      maxAttempts: 2,
    });
    await install({
      client: dynamodb,
      endpoint: cache.endpoint,
      cacheName: "test-cache",
      caCerts: CA_CERTS,
    });

    const preCacheRequests: HttpRequest[] = [];
    dynamodb.middlewareStack.addRelativeTo(
      (next) => async (args) => {
        if (!HttpRequest.isInstance(args.request)) {
          throw new Error("expected Smithy HttpRequest before ElastiCacheIntegrations");
        }
        preCacheRequests.push(args.request);
        return next(args);
      },
      {
        name: "testSignedDynamoDBRequestProbe",
        relation: "after",
        toMiddleware: "httpSigningMiddleware",
      },
    );

    try {
      const result = await dynamodb.send(
        new GetItemCommand({
          TableName: "ElastiCacheIntegrationsTest",
          Key: { id: { S: "key" } },
        }),
      );

      expect(result.Item).toEqual({ id: { S: "value" } });
      expect(preCacheRequests).toHaveLength(2);
      // The SDK's real signer still runs; ElastiCacheIntegrations strips the auth headers later.
      for (const request of preCacheRequests) {
        expect(request.headers.authorization).toBeDefined();
        expect(request.headers["x-amz-date"]).toBeDefined();
        expect(request.headers["x-amz-security-token"]).toBeDefined();
      }
      expect(cache.cacheCommandLog).toEqual(["hello", "aws.config", "aws.request", "aws.request"]);
      expect(cache.requests).toHaveLength(2);
      for (const request of cache.requests) {
        expect(request.body.toString()).toBe(
          '{"TableName":"ElastiCacheIntegrationsTest","Key":{"id":{"S":"key"}}}',
        );
        expect(request.headers.toString()).toContain("content-type:application/x-amz-json-1.0");
        expect(request.headers.toString()).toContain("x-amz-target:DynamoDB_20120810.GetItem");
        expect(request.headers.toString()).not.toMatch(
          /authorization|x-amz-date|x-amz-security-token/i,
        );
        expect(request.metadata.toString()).toBe("service=dynamodb&op=GetItem");
      }
    } finally {
      dynamodb.destroy();
      dynamodb.destroy();
    }
  });

  it("routes a command the client sent before install when cacheMiddleware is on", async () => {
    const cachingCache = new MockCache({
      tls: true,
      config: { predicates: [{ expr: "operation == 'GetItem'", vars: {} }] },
      responseFn: () =>
        Reply.ok(
          200,
          "content-type:application/x-amz-json-1.0",
          Buffer.from('{"Item":{"id":{"S":"value"}}}'),
        ),
    });
    await cachingCache.start();
    const dynamodb = new DynamoDBClient({
      region: "us-east-1",
      endpoint: "http://127.0.0.1:1",
      credentials: {
        accessKeyId: "AKIAEXAMPLE",
        secretAccessKey: "secret-example",
        sessionToken: "token-example",
      },
      maxAttempts: 1,
      cacheMiddleware: true,
    });
    const getItem = () =>
      dynamodb.send(
        new GetItemCommand({ TableName: "ElastiCacheIntegrationsTest", Key: { id: { S: "key" } } }),
      );

    try {
      // Nothing listens on the origin, but the SDK caches the resolved handler anyway.
      await expect(getItem()).rejects.toThrow();
      await install({
        client: dynamodb,
        endpoint: cachingCache.endpoint,
        cacheName: "test-cache",
        caCerts: CA_CERTS,
      });

      const result = await getItem();

      expect(result.Item).toEqual({ id: { S: "value" } });
      expect(cachingCache.requests).toHaveLength(1);
    } finally {
      dynamodb.destroy();
      await cachingCache.stop();
    }
  });

  it("refuses a second install and keeps serving through the first", async () => {
    const onceCache = new MockCache({
      tls: true,
      config: { predicates: [{ expr: "operation == 'GetItem'", vars: {} }] },
      responseFn: () =>
        Reply.ok(
          200,
          "content-type:application/x-amz-json-1.0",
          Buffer.from('{"Item":{"id":{"S":"value"}}}'),
        ),
    });
    await onceCache.start();
    const dynamodb = new DynamoDBClient({
      region: "us-east-1",
      endpoint: "http://127.0.0.1:1",
      credentials: { accessKeyId: "AKIAEXAMPLE", secretAccessKey: "secret-example" },
      maxAttempts: 1,
    });
    const installOptions = {
      client: dynamodb,
      endpoint: onceCache.endpoint,
      cacheName: "test-cache",
      caCerts: CA_CERTS,
    };
    const getItem = () =>
      dynamodb.send(
        new GetItemCommand({ TableName: "ElastiCacheIntegrationsTest", Key: { id: { S: "key" } } }),
      );

    try {
      await install(installOptions);
      const install2 = install(installOptions);
      await expect(install2).rejects.toBeInstanceOf(ElastiCacheIntegrationsConfigError);
      await expect(install2).rejects.toThrow("already has ElastiCache Integrations installed");
      // The refused install opened no second connection.
      expect(onceCache.cacheCommandLog).toEqual(["hello", "aws.config"]);

      await expect(getItem()).resolves.toMatchObject({ Item: { id: { S: "value" } } });
      expect(onceCache.requests).toHaveLength(1);

      // destroy() detaches, so the client can be installed into again.
      dynamodb.destroy();
      await install(installOptions);
      await expect(getItem()).resolves.toMatchObject({ Item: { id: { S: "value" } } });
      expect(onceCache.requests).toHaveLength(2);
    } finally {
      dynamodb.destroy();
      await onceCache.stop();
    }
  });

  it("keeps caching after the application replaces the request handler", async () => {
    const cachingCache = new MockCache({
      tls: true,
      config: { predicates: [{ expr: "operation == 'GetItem'", vars: {} }] },
      responseFn: () =>
        Reply.ok(
          200,
          "content-type:application/x-amz-json-1.0",
          Buffer.from('{"Item":{"id":{"S":"value"}}}'),
        ),
    });
    await cachingCache.start();
    const dynamodb = new DynamoDBClient({
      region: "us-east-1",
      endpoint: "http://127.0.0.1:1",
      credentials: {
        accessKeyId: "AKIAEXAMPLE",
        secretAccessKey: "secret-example",
        sessionToken: "token-example",
      },
      maxAttempts: 1,
    });
    await install({
      client: dynamodb,
      endpoint: cachingCache.endpoint,
      cacheName: "test-cache",
      caCerts: CA_CERTS,
    });
    const origin = vi.fn(async () => {
      throw new Error("origin must not be called");
    });
    dynamodb.config.requestHandler = { handle: origin } as never;

    try {
      const result = await dynamodb.send(
        new GetItemCommand({ TableName: "ElastiCacheIntegrationsTest", Key: { id: { S: "key" } } }),
      );

      expect(result.Item).toEqual({ id: { S: "value" } });
      expect(origin).not.toHaveBeenCalled();
    } finally {
      dynamodb.destroy();
      await cachingCache.stop();
    }
  });

  it("stops waiting for the cache when the caller's abortSignal fires", async () => {
    const hangingCache = new MockCache({
      tls: true,
      config: { predicates: [{ expr: "operation == 'GetItem'", vars: {} }] },
      failure: "hang",
    });
    await hangingCache.start();
    const dynamodb = new DynamoDBClient({
      region: "us-east-1",
      endpoint: "http://127.0.0.1:1",
      credentials: {
        accessKeyId: "AKIAEXAMPLE",
        secretAccessKey: "secret-example",
        sessionToken: "token-example",
      },
      maxAttempts: 3,
    });
    await install({
      client: dynamodb,
      endpoint: hangingCache.endpoint,
      cacheName: "test-cache",
      caCerts: CA_CERTS,
      clientConfiguration: { cacheRequestTimeout: 5_000 },
    });

    try {
      const started = performance.now();
      const request = dynamodb.send(
        new GetItemCommand({ TableName: "ElastiCacheIntegrationsTest", Key: { id: { S: "key" } } }),
        { abortSignal: AbortSignal.timeout(100) },
      );

      await expect(request).rejects.toMatchObject({ name: "AbortError" });
      expect(performance.now() - started).toBeLessThan(2_000);
      expect(hangingCache.cacheCommandLog.filter((c) => c === "aws.request")).toHaveLength(1);
    } finally {
      dynamodb.destroy();
      await hangingCache.stop();
    }
  });

  it("rejects an in-flight request when async callers destroy the client", async () => {
    const hangingCache = new MockCache({
      tls: true,
      config: {
        predicates: [{ expr: "operation == 'GetItem'", vars: {} }],
      },
      failure: "hang",
    });
    await hangingCache.start();
    const dynamodb = new DynamoDBClient({
      region: "us-east-1",
      endpoint: "http://127.0.0.1:1",
      credentials: {
        accessKeyId: "AKIAEXAMPLE",
        secretAccessKey: "secret-example",
        sessionToken: "token-example",
      },
      maxAttempts: 1,
    });
    await install({
      client: dynamodb,
      endpoint: hangingCache.endpoint,
      cacheName: "test-cache",
      caCerts: CA_CERTS,
      clientConfiguration: { cacheRequestTimeout: 5_000 },
    });

    try {
      const request = dynamodb.send(
        new GetItemCommand({
          TableName: "ElastiCacheIntegrationsTest",
          Key: { id: { S: "key" } },
        }),
      );
      const rejection = expect(request).rejects.toBeInstanceOf(
        ElastiCacheIntegrationsConnectionLost,
      );
      await vi.waitFor(() => {
        expect(hangingCache.cacheCommandLog).toContain("aws.request");
      });

      await Promise.all(
        Array.from({ length: 32 }, () => Promise.resolve().then(() => dynamodb.destroy())),
      );

      await rejection;
    } finally {
      dynamodb.destroy();
      await hangingCache.stop();
    }
  });
});

/** The ORIGIN_ERROR rejection in the cause chain, as the README tells applications to find it. */
function originErrorCause(error: unknown): Error | undefined {
  for (let cause = error; cause instanceof Error; cause = cause.cause) {
    if (
      cause.name === "ElastiCacheIntegrationsRejectedError" &&
      (cause as Error & { code?: unknown }).code === "ORIGIN_ERROR"
    ) {
      return cause;
    }
  }
  return undefined;
}

describe("ORIGIN_ERROR through the public DynamoDB client", () => {
  const key = { id: { S: "key" } };

  it.each([
    {
      name: "a retried read",
      send: (client: DynamoDBClient) =>
        client.send(new GetItemCommand({ TableName: "t", Key: key })),
      attempts: 2,
    },
    {
      name: "a write",
      send: (client: DynamoDBClient) =>
        client.send(new PutItemCommand({ TableName: "t", Item: key })),
      attempts: 1,
    },
  ])("puts the rejection in the cause chain of $name", async ({ send, attempts }) => {
    const cache = new MockCache({
      tls: true,
      config: { predicates: [{ expr: "service == 'dynamodb'", vars: {} }] },
      response: Reply.error("ORIGIN_ERROR", "origin unavailable"),
    });
    await cache.start();
    const dynamodb = new DynamoDBClient({
      region: "us-east-1",
      endpoint: "http://127.0.0.1:1",
      credentials: { accessKeyId: "AKIAEXAMPLE", secretAccessKey: "secret-example" },
      maxAttempts: 2,
    });
    try {
      await install({
        client: dynamodb,
        endpoint: cache.endpoint,
        cacheName: "test-cache",
        caCerts: CA_CERTS,
      });

      const failure = await send(dynamodb).catch((error: unknown) => error);

      const rejection = originErrorCause(failure);
      expect(rejection, String(failure)).toBeDefined();
      expect(rejection?.message).toBe("aws.request failed: ORIGIN_ERROR origin unavailable");
      expect(cache.requests).toHaveLength(attempts);
    } finally {
      dynamodb.destroy();
      await cache.stop();
    }
  });
});
