import { TextDecoder } from "node:util";

import { Environment, type ParseResult } from "@marcbachmann/cel-js";

import type { PhotonRequest } from "./models.js";

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

function compilePredicate(entry: unknown): CompiledPredicate | undefined {
  if (!isRecord(entry) || typeof entry.expr !== "string") return undefined;
  const variables = entry.vars === undefined ? {} : entry.vars;
  if (!isRecord(variables)) return undefined;

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

    const expression = environment.parse(entry.expr);
    const checked = expression.check();
    if (!checked.valid) return undefined;
    return { expression, variables: { ...variables } };
  } catch {
    return undefined;
  }
}

/**
 * Compiles and evaluates the CEL request-routing predicates from photon.config.
 *
 * Invalid entries are ignored independently. Runtime errors and bodies that
 * are not JSON objects count as no match.
 */
export class PredicateEvaluator {
  private constructor(private readonly predicates: readonly CompiledPredicate[]) {}

  static compile(rawConfig: Record<string, unknown> | undefined): PredicateEvaluator {
    if (!Array.isArray(rawConfig?.predicates)) return new PredicateEvaluator([]);
    return new PredicateEvaluator(
      rawConfig.predicates
        .map((entry) => compilePredicate(entry))
        .filter((entry): entry is CompiledPredicate => entry !== undefined),
    );
  }

  /** Return true as soon as one compiled predicate matches the request. */
  route(request: PhotonRequest): boolean {
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
