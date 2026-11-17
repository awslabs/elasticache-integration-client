// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

import type * as Toolkit from "@aws/developer-toolkit-elasticache";

export type ToolkitModule = typeof Toolkit;

let toolkitPromise: Promise<ToolkitModule> | undefined;

/** Load the ESM-only ElastiCache toolkit once. */
export function loadToolkit(): Promise<ToolkitModule> {
  toolkitPromise ??= import("@aws/developer-toolkit-elasticache");
  return toolkitPromise;
}
