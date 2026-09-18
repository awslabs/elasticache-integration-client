import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import { format, resolveConfig } from "prettier";

import { validateIdempotencyCatalog } from "./idempotency-catalog.mjs";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const catalogPath = resolve(scriptDir, "../../shared/idempotent-operations.json");
const outputPath = resolve(scriptDir, "../src/idempotency.ts");
const catalog = validateIdempotencyCatalog(JSON.parse(readFileSync(catalogPath, "utf8")));
const prettierConfig = (await resolveConfig(outputPath)) ?? {};

const serviceEntries = Object.entries(catalog)
  .map(
    ([service, operations]) =>
      `  ${JSON.stringify(service)}: new Set(${JSON.stringify(operations, null, 2)
        .split("\n")
        .map((line, index) => (index === 0 ? line : `  ${line}`))
        .join("\n")}),`,
  )
  .join("\n");

const generated = await format(
  `/**
 * Generated from ../../shared/idempotent-operations.json.
 * Run \`npm run generate:idempotency\` after changing the shared catalog.
 */
export const IDEMPOTENT_OPERATIONS: Readonly<Record<string, ReadonlySet<string>>> = {
${serviceEntries}
};

export function isIdempotentOperation(service: string, operation: string): boolean {
  return IDEMPOTENT_OPERATIONS[service.toLowerCase()]?.has(operation) ?? false;
}
`,
  { ...prettierConfig, parser: "typescript" },
);

if (process.argv.includes("--check")) {
  if (readFileSync(outputPath, "utf8") !== generated) {
    throw new Error("src/idempotency.ts is stale; run npm run generate:idempotency");
  }
} else {
  writeFileSync(outputPath, generated);
}
