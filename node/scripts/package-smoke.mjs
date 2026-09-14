import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

const require = createRequire(import.meta.url);
const cjsAuth = require(resolve("dist/cjs/auth.js"));
const cjsToken = await cjsAuth.generateIamAuthToken({
  cacheName: "my-cache",
  userId: "iam-user",
  region: "us-east-1",
  credentials: async () => ({
    accessKeyId: "AKIAIOSFODNN7EXAMPLE",
    secretAccessKey: "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
  }),
});
if (!cjsToken.startsWith("my-cache/") || !cjsToken.includes("User=iam-user")) {
  throw new Error("CommonJS IAM toolkit integration smoke failed");
}

mkdirSync(".artifacts", { recursive: true });

const packed = JSON.parse(
  execFileSync("npm", ["pack", "--ignore-scripts", "--pack-destination", ".artifacts", "--json"], {
    encoding: "utf8",
  }),
);
const tarball = packed[0]?.filename;
if (typeof tarball !== "string") {
  throw new Error("npm pack did not return a tarball filename");
}

const tarballPath = resolve(".artifacts", tarball);
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
  !listing.includes("package/dist/cjs/index.js")
) {
  throw new Error("Packed tarball is missing both ESM and CommonJS entrypoints");
}
if (!listing.includes(`${toolkitPrefix}dist/index.js`)) {
  throw new Error("Packed tarball is missing the bundled ElastiCache toolkit");
}

const smokeRoot = mkdtempSync(join(tmpdir(), "photon-nodejs-smoke-"));
try {
  for (const [name, type, source] of [
    [
      "esm-consumer",
      "module",
      'import * as photon from "photon-nodejs";\nif (typeof photon.client !== "function" || photon.version !== "0.0.1" || "PhotonError" in photon || "PhotonHandle" in photon) process.exit(1);\n',
    ],
    [
      "cjs-consumer",
      "commonjs",
      'const photon = require("photon-nodejs");\nif (typeof photon.client !== "function" || photon.version !== "0.0.1" || "PhotonError" in photon || "PhotonHandle" in photon) process.exit(1);\n',
    ],
  ]) {
    const consumer = join(smokeRoot, name);
    mkdirSync(consumer);
    writeFileSync(join(consumer, "package.json"), JSON.stringify({ type }));
    execFileSync("npm", ["install", "--no-audit", "--no-fund", "--ignore-scripts", tarballPath], {
      cwd: consumer,
      stdio: "inherit",
    });
    const entry = join(consumer, type === "module" ? "index.mjs" : "index.cjs");
    writeFileSync(entry, source);
    execFileSync("node", [entry], { cwd: consumer, stdio: "inherit" });
    if (!existsSync(join(consumer, "node_modules", "photon-nodejs"))) {
      throw new Error(`Consumer ${name} did not install photon-nodejs`);
    }
  }

  const packageJson = JSON.parse(readFileSync("package.json", "utf8"));
  if (packageJson.name !== "photon-nodejs") {
    throw new Error("Unexpected package name in the smoke-test source package");
  }
} finally {
  rmSync(smokeRoot, { recursive: true, force: true });
}

console.log(`Package smoke passed for ${tarball}`);
