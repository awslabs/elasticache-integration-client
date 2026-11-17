// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

import { mkdir, rm, writeFile } from "node:fs/promises";

await mkdir("dist/cjs", { recursive: true });
await writeFile("dist/cjs/package.json", '{\n  "type": "commonjs"\n}\n');
await writeFile(
  "dist/cjs/toolkit-loader.js",
  [
    '"use strict";',
    "let toolkitPromise;",
    "function loadToolkit() {",
    '  toolkitPromise ??= import("@aws/developer-toolkit-elasticache");',
    "  return toolkitPromise;",
    "}",
    "exports.loadToolkit = loadToolkit;",
    "",
  ].join("\n"),
);
await rm("dist/cjs/toolkit-loader.js.map", { force: true });
