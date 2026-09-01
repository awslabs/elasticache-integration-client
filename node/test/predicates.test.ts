import { describe, expect, it } from "vitest";

import { PhotonRequest, RequestMetadata } from "../src/models.js";
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
  options: { service?: string; headers?: Record<string, string> } = {},
): PhotonRequest {
  return new PhotonRequest({
    method: "POST",
    url: "https://dynamodb.us-east-1.amazonaws.com/",
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
        "&& body.TransactItems.all(i, i.all(k, i[k].TableName in allowed))",
      { ops: ["TransactGetItems", "TransactWriteItems"], allowed },
    ),
  );
}

describe("PredicateEvaluator", () => {
  it("routes the default proxy operations", () => {
    const predicates = defaultEvaluator();

    for (const operation of allOperations) {
      expect(predicates.route(request(operation)), operation).toBe(true);
    }
    for (const operation of ["CreateTable", "DescribeTable", "ExecuteStatement"]) {
      expect(predicates.route(request(operation)), operation).toBe(false);
    }
    expect(predicates.route(request("GetItem", "{}", { service: "s3" }))).toBe(false);
  });

  it("routes single-table, batch, and transaction allowlists", () => {
    const predicates = tableAllowlistEvaluator("Users", "Sessions");

    expect(predicates.route(request("GetItem", '{"TableName":"Users"}'))).toBe(true);
    expect(predicates.route(request("PutItem", '{"TableName":"Orders"}'))).toBe(false);
    expect(
      predicates.route(request("BatchGetItem", '{"RequestItems":{"Users":{},"Sessions":{}}}')),
    ).toBe(true);
    expect(
      predicates.route(request("BatchWriteItem", '{"RequestItems":{"Users":[],"Orders":[]}}')),
    ).toBe(false);
    expect(predicates.route(request("BatchGetItem", '{"RequestItems":{}}'))).toBe(false);
    expect(
      predicates.route(
        request(
          "TransactGetItems",
          '{"TransactItems":[{"Get":{"TableName":"Users"}},{"Get":{"TableName":"Sessions"}}]}',
        ),
      ),
    ).toBe(true);
    expect(
      predicates.route(
        request("TransactWriteItems", '{"TransactItems":[{"Put":{"TableName":"Orders"}}]}'),
      ),
    ).toBe(false);
    expect(predicates.route(request("TransactGetItems", '{"TransactItems":[]}'))).toBe(false);
  });

  it("ignores malformed predicate entries without dropping valid siblings", () => {
    const predicates = evaluator(
      "not-an-object",
      { vars: {} },
      predicate("this is not (valid CEL"),
      predicate("operation == 'GetItem'"),
    );

    expect(predicates.route(request("GetItem"))).toBe(true);
    expect(predicates.route(request("PutItem"))).toBe(false);
    expect(PredicateEvaluator.compile(undefined).route(request("GetItem"))).toBe(false);
    expect(PredicateEvaluator.compile({ predicates: "invalid" }).route(request("GetItem"))).toBe(
      false,
    );
  });

  it("treats malformed and non-object bodies as no match", () => {
    const predicates = defaultEvaluator();

    expect(predicates.route(request("GetItem", ""))).toBe(true);
    for (const body of ["not json", "[]", "null", '{"TableName":"Users"} trailing']) {
      expect(predicates.route(request("GetItem", body)), body).toBe(false);
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

    expect(predicates.route(request("GetItem", "{}", { headers: { "x-test": "yes" } }))).toBe(true);
    expect(predicates.route(request("PutItem", "{}", { headers: { "x-test": "yes" } }))).toBe(
      false,
    );
  });

  it("evaluates large valid request bodies", () => {
    const prefix = '{"TableName":"Users","Pad":"';
    const suffix = '"}';
    const body = prefix + "x".repeat(600 * 1024 - prefix.length - suffix.length) + suffix;

    expect(Buffer.byteLength(body)).toBe(600 * 1024);
    expect(tableAllowlistEvaluator("Users").route(request("GetItem", body))).toBe(true);
  });
});
