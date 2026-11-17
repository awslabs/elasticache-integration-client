// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

import { TextDecoder } from "node:util";

import { Environment, type ParseResult } from "@marcbachmann/cel-js";

import { ElastiCacheIntegrationsConfigUnevaluable } from "./errors.js";
import type { ElastiCacheIntegrationsRequest } from "./models.js";

interface CompiledPredicate {
  expression: ParseResult;
  variables: Record<string, unknown>;
}

const REQUEST_VARIABLES = {
  service: "string",
  operation: "string",
  method: "string",
  url: "string",
  headers: "map<string,string>",
  body: "dyn",
} as const;

const STRICT_UTF8 = new TextDecoder("utf-8", { fatal: true });

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function parseBody(body: Buffer | string | null): Record<string, unknown> | undefined {
  if (body === null || body.length === 0) return {};
  try {
    const parsed: unknown = JSON.parse(Buffer.isBuffer(body) ? STRICT_UTF8.decode(body) : body);
    return isRecord(parsed) ? parsed : undefined;
  } catch {
    return undefined;
  }
}

function compilePredicate(entry: unknown): CompiledPredicate {
  if (!isRecord(entry)) {
    throw new ElastiCacheIntegrationsConfigUnevaluable("Predicate entry is not an object");
  }
  if (typeof entry.expr !== "string") {
    throw new ElastiCacheIntegrationsConfigUnevaluable("Predicate entry has no string 'expr'");
  }
  const variables = isRecord(entry.vars) ? entry.vars : {};

  let expression: ParseResult;
  let valid: boolean;
  try {
    const environment = new Environment({ homogeneousAggregateLiterals: false });
    for (const [name, type] of Object.entries(REQUEST_VARIABLES)) {
      environment.registerVariable(name, Object.hasOwn(variables, name) ? "dyn" : type);
    }
    for (const name of Object.keys(variables)) {
      if (!Object.hasOwn(REQUEST_VARIABLES, name)) {
        environment.registerVariable(name, "dyn");
      }
    }

    expression = environment.parse(entry.expr);
    valid = expression.check().valid;
  } catch (cause) {
    throw new ElastiCacheIntegrationsConfigUnevaluable(
      `Predicate expression cannot be compiled: ${JSON.stringify(entry.expr)}`,
      { cause },
    );
  }
  if (!valid) {
    throw new ElastiCacheIntegrationsConfigUnevaluable(
      `Predicate expression cannot be compiled: ${JSON.stringify(entry.expr)}`,
    );
  }
  return { expression, variables: { ...variables } };
}

/**
 * Compiles and evaluates the CEL request-routing predicates from aws.config.
 *
 * Runtime errors and bodies that are not JSON objects count as no match. An
 * entry that cannot be compiled is different: it taints the whole config,
 * which is rejected rather than caching by the entries that did compile.
 */
export class PredicateEvaluator {
  private constructor(private readonly predicates: readonly CompiledPredicate[]) {}

  /**
   * Compile the `predicates` of a resolved config, in wire order. A missing
   * `predicates` key compiles to no predicates, so every request passes
   * through.
   *
   * @throws ElastiCacheIntegrationsConfigUnevaluable `predicates` is present but not an array,
   *   an entry is not an object, has no string `expr`, or has an expression
   *   this client cannot compile. Caching by the entries that happened to
   *   compile would route a different set of requests than the cache resolved.
   */
  static compile(rawConfig: Record<string, unknown> | undefined): PredicateEvaluator {
    if (rawConfig === undefined || rawConfig === null || !("predicates" in rawConfig)) {
      return new PredicateEvaluator([]);
    }
    const entries = rawConfig.predicates;
    if (!Array.isArray(entries)) {
      throw new ElastiCacheIntegrationsConfigUnevaluable(
        `Config 'predicates' must be a list, got ${entries === null ? "null" : typeof entries}`,
      );
    }
    return new PredicateEvaluator(entries.map((entry) => compilePredicate(entry)));
  }

  /** Return true as soon as one compiled predicate matches the request. */
  shouldRouteToCache(request: ElastiCacheIntegrationsRequest): boolean {
    if (this.predicates.length === 0) return false;
    const body = parseBody(request.body);
    if (body === undefined) return false;

    const context = {
      service: request.metadata.service,
      operation: request.metadata.operation,
      method: request.method,
      url: request.url,
      headers: request.headers,
      body,
    };
    return this.predicates.some(({ expression, variables }) => {
      try {
        return expression({ ...context, ...variables }) === true;
      } catch {
        return false;
      }
    });
  }
}
