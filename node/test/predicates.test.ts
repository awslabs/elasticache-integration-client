import { describe, expect, it } from "vitest";

import { ElastiCacheIntegrationsConfigUnevaluable } from "../src/errors.js";
import { ElastiCacheIntegrationsRequest, RequestMetadata } from "../src/models.js";
import { PredicateEvaluator } from "../src/predicates.js";

const allOperations = [
  "GetItem",
  "BatchGetItem",
  "Query",
  "Scan",
  "PutItem",
  "UpdateItem",
  "DeleteItem",
  "BatchWriteItem",
  "TransactGetItems",
  "TransactWriteItems",
];
const singleTableOperations = ["GetItem", "Query", "Scan", "PutItem", "UpdateItem", "DeleteItem"];

function predicate(expr: string, vars: Record<string, unknown> = {}) {
  return { expr, vars };
}

function evaluator(...predicates: unknown[]): PredicateEvaluator {
  return PredicateEvaluator.compile({ predicates });
}

function request(
  operation: string,
  body: Buffer | string | null = "{}",
  options: { service?: string; url?: string; headers?: Record<string, string> } = {},
): ElastiCacheIntegrationsRequest {
  return new ElastiCacheIntegrationsRequest({
    method: "POST",
    url: options.url ?? "https://dynamodb.us-east-1.amazonaws.com/",
    headers: options.headers,
    body,
    metadata: new RequestMetadata({
      service: options.service ?? "dynamodb",
      operation,
    }),
  });
}

function defaultEvaluator(): PredicateEvaluator {
  return evaluator(predicate("service == 'dynamodb' && operation in ops", { ops: allOperations }));
}

function tableAllowlistEvaluator(...allowed: string[]): PredicateEvaluator {
  return evaluator(
    predicate(
      "service == 'dynamodb' && operation in ops " +
        "&& has(body.TableName) && body.TableName in allowed",
      { ops: singleTableOperations, allowed },
    ),
    predicate(
      "service == 'dynamodb' && operation in ops " +
        "&& has(body.RequestItems) && size(body.RequestItems) > 0 " +
        "&& body.RequestItems.all(t, t in allowed)",
      { ops: ["BatchGetItem", "BatchWriteItem"], allowed },
    ),
    predicate(
      "service == 'dynamodb' && operation in ops " +
        "&& has(body.TransactItems) && size(body.TransactItems) > 0 " +
        '&& body.TransactItems.all(i, i.all(k, "TableName" in i[k] && i[k].TableName in allowed))',
      { ops: ["TransactGetItems", "TransactWriteItems"], allowed },
    ),
  );
}

describe("PredicateEvaluator", () => {
  it("routes the default cache operations", () => {
    const predicates = defaultEvaluator();

    for (const operation of allOperations) {
      expect(predicates.shouldRouteToCache(request(operation)), operation).toBe(true);
    }
    for (const operation of ["CreateTable", "DescribeTable", "ExecuteStatement"]) {
      expect(predicates.shouldRouteToCache(request(operation)), operation).toBe(false);
    }
    expect(predicates.shouldRouteToCache(request("GetItem", "{}", { service: "s3" }))).toBe(false);
  });

  it("routes single-table, batch, and transaction allowlists", () => {
    const predicates = tableAllowlistEvaluator("Users", "Sessions");

    expect(predicates.shouldRouteToCache(request("GetItem", '{"TableName":"Users"}'))).toBe(true);
    expect(predicates.shouldRouteToCache(request("PutItem", '{"TableName":"Orders"}'))).toBe(false);
    expect(
      predicates.shouldRouteToCache(
        request("BatchGetItem", '{"RequestItems":{"Users":{},"Sessions":{}}}'),
      ),
    ).toBe(true);
    expect(
      predicates.shouldRouteToCache(
        request("BatchWriteItem", '{"RequestItems":{"Users":[],"Orders":[]}}'),
      ),
    ).toBe(false);
    expect(predicates.shouldRouteToCache(request("BatchGetItem", '{"RequestItems":{}}'))).toBe(
      false,
    );
    expect(
      predicates.shouldRouteToCache(
        request(
          "TransactGetItems",
          '{"TransactItems":[{"Get":{"TableName":"Users"}},{"Get":{"TableName":"Sessions"}}]}',
        ),
      ),
    ).toBe(true);
    expect(
      predicates.shouldRouteToCache(
        request("TransactWriteItems", '{"TransactItems":[{"Put":{"TableName":"Orders"}}]}'),
      ),
    ).toBe(false);
    expect(predicates.shouldRouteToCache(request("TransactGetItems", '{"TransactItems":[]}'))).toBe(
      false,
    );
    // The "TableName" guard: an action carrying no table is a clean non-match, not a throw.
    expect(
      predicates.shouldRouteToCache(
        request("TransactGetItems", '{"TransactItems":[{"Get":{"Key":{}}}]}'),
      ),
    ).toBe(false);
  });

  it("compiles a missing predicates field to no predicates", () => {
    expect(PredicateEvaluator.compile(undefined).shouldRouteToCache(request("GetItem"))).toBe(
      false,
    );
    expect(PredicateEvaluator.compile({}).shouldRouteToCache(request("GetItem"))).toBe(false);
  });

  it.each([
    ["a string", "nope"],
    ["an object", { expr: "true" }],
    ["null", null],
  ])("rejects the config when predicates is %s", (_, value) => {
    expect(() => PredicateEvaluator.compile({ predicates: value })).toThrow(
      ElastiCacheIntegrationsConfigUnevaluable,
    );
  });

  // One bad entry taints the whole config: caching by the entries that did compile would route a
  // different set of requests than the cache resolved.
  it.each([
    ["a non-object entry", "not-an-object"],
    ["an entry without a string expr", { vars: {} }],
    ["an uncompilable expression", predicate("this is not (valid CEL")],
    ["an expression that fails type checking", predicate("operation == 1")],
  ])("rejects the whole config for %s", (_, bad) => {
    expect(() => evaluator(predicate("operation == 'GetItem'"), bad)).toThrow(
      ElastiCacheIntegrationsConfigUnevaluable,
    );
  });

  it("treats non-object vars as no vars", () => {
    const predicates = evaluator({ expr: "operation == 'GetItem'", vars: "not-an-object" });

    expect(predicates.shouldRouteToCache(request("GetItem"))).toBe(true);
  });

  it("routes only when the runtime result is exactly true", () => {
    expect(
      evaluator(predicate("enabled", { enabled: true })).shouldRouteToCache(request("GetItem")),
    ).toBe(true);
    expect(
      evaluator(predicate("enabled", { enabled: false })).shouldRouteToCache(request("GetItem")),
    ).toBe(false);
    expect(evaluator(predicate("value", { value: 1 })).shouldRouteToCache(request("GetItem"))).toBe(
      false,
    );
    expect(
      evaluator(predicate("value", { value: "non-empty" })).shouldRouteToCache(request("GetItem")),
    ).toBe(false);
    expect(evaluator(predicate("1")).shouldRouteToCache(request("GetItem"))).toBe(false);
  });

  it("treats malformed and non-object bodies as no match", () => {
    const predicates = defaultEvaluator();

    expect(predicates.shouldRouteToCache(request("GetItem", ""))).toBe(true);
    for (const body of [
      "not json",
      "[]",
      "null",
      '{"TableName":"Users"} trailing',
      Buffer.from([...Buffer.from('{"TableName":"Users","invalid":"'), 0xff, ...Buffer.from('"}')]),
    ]) {
      expect(predicates.shouldRouteToCache(request("GetItem", body)), body).toBe(false);
    }
  });

  it("continues after evaluation errors and exposes request fields and variables", () => {
    const predicates = evaluator(
      predicate("body.Missing == 'value'"),
      predicate(
        "operation == expected && method == 'POST' && url.startsWith(prefix) " +
          "&& headers['x-test'] == 'yes'",
        { expected: "GetItem", prefix: "https://dynamodb." },
      ),
    );

    expect(
      predicates.shouldRouteToCache(request("GetItem", "{}", { headers: { "x-test": "yes" } })),
    ).toBe(true);
    expect(
      predicates.shouldRouteToCache(
        request("GetItem", "{}", {
          headers: { "x-test": "yes" },
          url: "https://s3.us-east-1.amazonaws.com/",
        }),
      ),
    ).toBe(false);
    expect(
      predicates.shouldRouteToCache(request("PutItem", "{}", { headers: { "x-test": "yes" } })),
    ).toBe(false);
  });

  it("evaluates large valid request bodies", () => {
    const prefix = '{"TableName":"Users","Pad":"';
    const suffix = '"}';
    const body = prefix + "x".repeat(600 * 1024 - prefix.length - suffix.length) + suffix;

    expect(Buffer.byteLength(body)).toBe(600 * 1024);
    expect(tableAllowlistEvaluator("Users").shouldRouteToCache(request("GetItem", body))).toBe(
      true,
    );
  });
});
