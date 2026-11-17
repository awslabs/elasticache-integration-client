// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

import { execFileSync } from "node:child_process";
import { mkdirSync } from "node:fs";
import { createRequire } from "node:module";
import { resolve } from "node:path";
import process from "node:process";

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
execFileSync(process.execPath, ["scripts/package-tarball-smoke.mjs", tarballPath], {
  stdio: "inherit",
});
