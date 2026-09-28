import assert from "node:assert/strict";
import test from "node:test";

import { validateIdempotencyCatalog } from "./idempotency-catalog.mjs";

test("accepts a valid catalog", () => {
  const catalog = { dynamodb: ["GetItem", "Query"] };

  assert.equal(validateIdempotencyCatalog(catalog), catalog);
});

for (const invalidTopLevel of [null, [], "dynamodb"]) {
  test(`rejects non-object top-level value ${JSON.stringify(invalidTopLevel)}`, () => {
    assert.throws(() => validateIdempotencyCatalog(invalidTopLevel), /must be a top-level object/);
  });
}

test("rejects empty or non-lowercase service keys", () => {
  assert.throws(() => validateIdempotencyCatalog({ "": ["GetItem"] }), /must be lowercase/);
  assert.throws(() => validateIdempotencyCatalog({ DynamoDB: ["GetItem"] }), /must be lowercase/);
});

test("rejects a non-array service entry", () => {
  assert.throws(() => validateIdempotencyCatalog({ dynamodb: "GetItem" }), /must be an array/);
});

test("rejects non-string or empty operations", () => {
  assert.throws(() => validateIdempotencyCatalog({ dynamodb: [""] }), /must be non-empty strings/);
  assert.throws(() => validateIdempotencyCatalog({ dynamodb: [42] }), /must be non-empty strings/);
});

test("rejects duplicate operations", () => {
  assert.throws(
    () => validateIdempotencyCatalog({ dynamodb: ["GetItem", "GetItem"] }),
    /is duplicated/,
  );
});
