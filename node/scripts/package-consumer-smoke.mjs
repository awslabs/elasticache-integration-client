import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import process from "node:process";

const [packageSpec, expectedName, expectedVersion] = process.argv.slice(2);
if (!packageSpec || !expectedName || !expectedVersion) {
  throw new Error(
    "Usage: node scripts/package-consumer-smoke.mjs <package-spec> <package-name> <version>",
  );
}

const checks = [
  'typeof elastiCacheIntegrations.install === "function"',
  `elastiCacheIntegrations.version === ${JSON.stringify(expectedVersion)}`,
  'typeof elastiCacheIntegrations.ElastiCacheIntegrationsError === "function"',
  'typeof elastiCacheIntegrations.ElastiCacheIntegrationsConfigError === "function"',
  'typeof elastiCacheIntegrations.ElastiCacheIntegrationsCacheUnavailable === "function"',
  '!("ElastiCacheIntegrationsHandle" in elastiCacheIntegrations)',
].join(" && ");
const smokeRoot = mkdtempSync(join(tmpdir(), "aws-elasticache-integration-client-smoke-"));

try {
  for (const [name, type, source] of [
    [
      "esm-consumer",
      "module",
      `import * as elastiCacheIntegrations from ${JSON.stringify(expectedName)};\nif (!(${checks})) process.exit(1);\n`,
    ],
    [
      "cjs-consumer",
      "commonjs",
      `const elastiCacheIntegrations = require(${JSON.stringify(expectedName)});\nif (!(${checks})) process.exit(1);\n`,
    ],
  ]) {
    const consumer = join(smokeRoot, name);
    mkdirSync(consumer);
    writeFileSync(join(consumer, "package.json"), JSON.stringify({ type }));
    execFileSync("npm", ["install", "--no-audit", "--no-fund", "--ignore-scripts", packageSpec], {
      cwd: consumer,
      stdio: "inherit",
    });
    const entry = join(consumer, type === "module" ? "index.mjs" : "index.cjs");
    writeFileSync(entry, source);
    execFileSync(process.execPath, [entry], { cwd: consumer, stdio: "inherit" });
    if (!existsSync(join(consumer, "node_modules", ...expectedName.split("/")))) {
      throw new Error(`Consumer ${name} did not install ${expectedName}`);
    }
  }
} finally {
  rmSync(smokeRoot, { recursive: true, force: true });
}

console.log(`Consumer smoke passed for ${expectedName}@${expectedVersion}`);
