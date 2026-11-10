#!/usr/bin/env node

import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { resolve } from "node:path";
import process from "node:process";

const RELEASE_VERSION = /^[0-9]+\.[0-9]+\.[0-9]+(?:-rc\.[0-9]+)?$/;

export function setPackageVersion(version, root = process.cwd()) {
  if (!RELEASE_VERSION.test(version)) {
    throw new Error(`Unsupported release version: ${version}`);
  }

  const packagePath = resolve(root, "package.json");
  const lockPath = resolve(root, "package-lock.json");
  const indexPath = resolve(root, "src/index.ts");
  const packageMetadata = JSON.parse(readFileSync(packagePath, "utf8"));
  const lockMetadata = JSON.parse(readFileSync(lockPath, "utf8"));
  const indexSource = readFileSync(indexPath, "utf8");

  if (typeof lockMetadata.packages?.[""] !== "object") {
    throw new Error("package-lock.json is missing root package metadata");
  }

  const versionDeclaration = /export const version = "[^"]+";/g;
  const matches = indexSource.match(versionDeclaration);
  if (matches?.length !== 1) {
    throw new Error("src/index.ts must contain exactly one exported version declaration");
  }

  packageMetadata.version = version;
  lockMetadata.version = version;
  lockMetadata.packages[""].version = version;

  writeFileSync(packagePath, `${JSON.stringify(packageMetadata, null, 2)}\n`);
  writeFileSync(lockPath, `${JSON.stringify(lockMetadata, null, 2)}\n`);
  writeFileSync(
    indexPath,
    indexSource.replace(versionDeclaration, `export const version = "${version}";`),
  );
}

const invokedPath = process.argv[1] ? resolve(process.argv[1]) : "";
if (invokedPath === fileURLToPath(import.meta.url)) {
  const version = process.argv[2];
  if (!version) {
    throw new Error("Usage: node scripts/set-package-version.mjs <version>");
  }
  setPackageVersion(version);
}
