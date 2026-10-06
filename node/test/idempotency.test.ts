import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

import { IDEMPOTENT_OPERATIONS, isIdempotentOperation } from "../src/idempotency.js";

describe("idempotent operations", () => {
  it("matches the shared language-neutral catalog", () => {
    const shared = JSON.parse(
      readFileSync(new URL("../../shared/idempotent-operations.json", import.meta.url), "utf8"),
    ) as Record<string, string[]>;
    const packaged = Object.fromEntries(
      Object.entries(IDEMPOTENT_OPERATIONS).map(([service, operations]) => [
        service,
        [...operations],
      ]),
    );

    expect(packaged).toEqual(shared);
  });

  it("is conservative for writes and unknown operations", () => {
    expect(isIdempotentOperation("DynamoDB", "GetItem")).toBe(true);
    expect(isIdempotentOperation("dynamodb", "UpdateItem")).toBe(false);
    expect(isIdempotentOperation("dynamodb", "ExecuteStatement")).toBe(false);
    expect(isIdempotentOperation("dynamodb", "ExecuteTransaction")).toBe(false);
    expect(isIdempotentOperation("unknown", "GetItem")).toBe(false);
  });
});
