import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import process from "node:process";
import { publint } from "publint";
import { formatMessage } from "publint/utils";

const [tarballArgument] = process.argv.slice(2);
if (!tarballArgument) {
  throw new Error("Usage: node scripts/package-tarball-smoke.mjs <tarball>");
}

const tarballPath = resolve(tarballArgument);
const listing = execFileSync("tar", ["-tzf", tarballPath], { encoding: "utf8" })
  .split("\n")
  .filter(Boolean);
const toolkitPrefix = "package/node_modules/@aws/developer-toolkit-elasticache/";
const forbidden = /package\/(?:test|src|coverage|\.omx)\/|\.key$|(^|\/)\.omx\//;
const forbiddenEntries = listing.filter((entry) => {
  if (entry.startsWith("package/node_modules/")) {
    return !entry.startsWith(toolkitPrefix) || /\/(?:src|test)\//.test(entry);
  }
  return forbidden.test(entry);
});
if (forbiddenEntries.length > 0) {
  throw new Error(`Packed tarball contains forbidden entries:\n${forbiddenEntries.join("\n")}`);
}
if (
  !listing.includes("package/dist/esm/index.js") ||
  !listing.includes("package/dist/cjs/index.js") ||
  !listing.includes("package/dist/esm/index.d.ts") ||
  !listing.includes("package/dist/cjs/index.d.ts")
) {
  throw new Error("Packed tarball is missing ESM or CommonJS runtime/type entrypoints");
}
if (!listing.includes(`${toolkitPrefix}dist/index.js`)) {
  throw new Error("Packed tarball is missing the bundled ElastiCache toolkit");
}

// publint flags every file: dependency, but a bundled one ships inside the tarball.
const lint = await publint({ pack: { tarball: readFileSync(tarballPath).buffer }, strict: true });
const bundled = new Set(lint.pkg.bundledDependencies ?? []);
const problems = lint.messages.filter(
  (message) =>
    message.type !== "suggestion" &&
    !(message.code === "LOCAL_DEPENDENCY" && bundled.has(message.path[1])),
);
if (problems.length > 0) {
  const details = problems.map((message) => formatMessage(message, lint.pkg)).join("\n");
  throw new Error(`publint found problems in the packed tarball:\n${details}`);
}
execFileSync("npx", ["--no-install", "attw", tarballPath], { stdio: "inherit" });

const sourcePackage = JSON.parse(readFileSync("package.json", "utf8"));
const packedPackage = JSON.parse(
  execFileSync("tar", ["-xOzf", tarballPath, "package/package.json"], { encoding: "utf8" }),
);
if (packedPackage.name !== sourcePackage.name || packedPackage.version !== sourcePackage.version) {
  throw new Error(
    `Packed identity ${packedPackage.name}@${packedPackage.version} does not match source ${sourcePackage.name}@${sourcePackage.version}`,
  );
}

execFileSync(
  process.execPath,
  ["scripts/package-consumer-smoke.mjs", tarballPath, sourcePackage.name, sourcePackage.version],
  { stdio: "inherit" },
);

console.log(`Package smoke passed for ${tarballPath}`);
