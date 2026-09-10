import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";

import { DynamoDBClient, GetItemCommand } from "@aws-sdk/client-dynamodb";
import { AssumeRoleCommand, STSClient } from "@aws-sdk/nested-clients/sts";
import { HttpRequest } from "@smithy/core/protocols";
import { createServer, type Server } from "node:http";

import { client } from "../src/index.js";
import { PhotonConnectionLost } from "../src/errors.js";
import { MockCache, Reply } from "./mock-cache.js";

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
      <AssumedRoleId>AROAEXAMPLE:photon-test</AssumedRoleId>
      <Arn>arn:aws:iam::123456789012:role/PhotonTest</Arn>
    </AssumedRoleUser>
  </AssumeRoleResult>
</AssumeRoleResponse>`);

describe("public client", () => {
  let responses = 0;
  let origin: Server;
  let originEndpoint: string;
  const originRequests: Array<{ body: string; headers: Record<string, string | string[]> }> = [];
  const cache = new MockCache({
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
    const sts = await client("sts", STSClient, {
      endpoint: cache.endpoint,
      cacheName: "test-cache",
      region: "us-east-1",
      connectorOptions: { tls: false },
      clientConfig: {
        endpoint: originEndpoint,
        credentials: {
          accessKeyId: "AKIAEXAMPLE",
          secretAccessKey: "secret-example",
          sessionToken: "token-example",
        },
        maxAttempts: 2,
      },
    });

    const prePhotonRequests: HttpRequest[] = [];
    sts.middlewareStack.addRelativeTo(
      (next) => async (args) => {
        if (!HttpRequest.isInstance(args.request)) {
          throw new Error("expected Smithy HttpRequest before Photon");
        }
        prePhotonRequests.push(args.request);
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
          RoleArn: "arn:aws:iam::123456789012:role/PhotonTest",
          RoleSessionName: "photon-test",
        }),
      );

      expect(result.Credentials?.AccessKeyId).toBe("ASIAEXAMPLE");
      expect(result.Credentials?.SecretAccessKey).toBe("secret-example");
      expect(prePhotonRequests).toHaveLength(2);
      // The SDK's real signer still runs; Photon strips the auth headers later.
      for (const request of prePhotonRequests) {
        expect(request.headers.authorization).toBeDefined();
        expect(request.headers["x-amz-date"]).toBeDefined();
        expect(request.headers["x-amz-security-token"]).toBeDefined();
      }
      expect(cache.photonCommandLog).toEqual(["auth", "photon.config"]);
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
    config: {
      predicates: [
        {
          expr:
            "service == 'dynamodb' && operation == 'GetItem' " +
            "&& body.TableName == 'PhotonTest'",
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

  it("routes serialized unsigned DynamoDB requests through Photon and deserializes retries", async () => {
    const dynamodb = await client("dynamodb", DynamoDBClient, {
      endpoint: cache.endpoint,
      cacheName: "test-cache",
      region: "us-east-1",
      connectorOptions: { tls: false },
      clientConfig: {
        endpoint: "http://127.0.0.1:1",
        credentials: {
          accessKeyId: "AKIAEXAMPLE",
          secretAccessKey: "secret-example",
          sessionToken: "token-example",
        },
        maxAttempts: 2,
      },
    });

    const prePhotonRequests: HttpRequest[] = [];
    dynamodb.middlewareStack.addRelativeTo(
      (next) => async (args) => {
        if (!HttpRequest.isInstance(args.request)) {
          throw new Error("expected Smithy HttpRequest before Photon");
        }
        prePhotonRequests.push(args.request);
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
          TableName: "PhotonTest",
          Key: { id: { S: "key" } },
        }),
      );

      expect(result.Item).toEqual({ id: { S: "value" } });
      expect(prePhotonRequests).toHaveLength(2);
      // The SDK's real signer still runs; Photon strips the auth headers later.
      for (const request of prePhotonRequests) {
        expect(request.headers.authorization).toBeDefined();
        expect(request.headers["x-amz-date"]).toBeDefined();
        expect(request.headers["x-amz-security-token"]).toBeDefined();
      }
      expect(cache.photonCommandLog).toEqual([
        "auth",
        "photon.config",
        "photon.request",
        "photon.request",
      ]);
      expect(cache.requests).toHaveLength(2);
      for (const request of cache.requests) {
        expect(request.body.toString()).toBe('{"TableName":"PhotonTest","Key":{"id":{"S":"key"}}}');
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

  it("rejects an in-flight request when async callers destroy the client", async () => {
    const hangingCache = new MockCache({
      config: {
        predicates: [{ expr: "operation == 'GetItem'", vars: {} }],
      },
      failure: "hang",
    });
    await hangingCache.start();
    const dynamodb = await client("dynamodb", DynamoDBClient, {
      endpoint: hangingCache.endpoint,
      cacheName: "test-cache",
      region: "us-east-1",
      connectorOptions: { tls: false, requestTimeout: 5_000 },
      clientConfig: {
        endpoint: "http://127.0.0.1:1",
        credentials: {
          accessKeyId: "AKIAEXAMPLE",
          secretAccessKey: "secret-example",
          sessionToken: "token-example",
        },
        maxAttempts: 1,
      },
    });

    try {
      const request = dynamodb.send(
        new GetItemCommand({
          TableName: "PhotonTest",
          Key: { id: { S: "key" } },
        }),
      );
      const rejection = expect(request).rejects.toBeInstanceOf(PhotonConnectionLost);
      await vi.waitFor(() => {
        expect(hangingCache.photonCommandLog).toContain("photon.request");
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
