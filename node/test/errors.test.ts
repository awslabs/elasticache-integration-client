// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from "vitest";

import {
  ElastiCacheIntegrationsAuthError,
  ElastiCacheIntegrationsCacheUnavailable,
  ElastiCacheIntegrationsConfigError,
  ElastiCacheIntegrationsOverridesRejected,
  ElastiCacheIntegrationsConfigUnevaluable,
  ElastiCacheIntegrationsConnectionLost,
  ElastiCacheIntegrationsConnectionError,
  ElastiCacheIntegrationsError,
  ElastiCacheIntegrationsProtocolError,
  ElastiCacheIntegrationsRejectedError,
  ElastiCacheIntegrationsRequestNotSent,
  ElastiCacheIntegrationsTimeoutError,
} from "../src/errors.js";
import * as elastiCacheIntegrations from "../src/index.js";

const ERROR_TYPES = [
  ElastiCacheIntegrationsError,
  ElastiCacheIntegrationsAuthError,
  ElastiCacheIntegrationsConfigError,
  ElastiCacheIntegrationsOverridesRejected,
  ElastiCacheIntegrationsConfigUnevaluable,
  ElastiCacheIntegrationsConnectionError,
  ElastiCacheIntegrationsProtocolError,
  ElastiCacheIntegrationsConnectionLost,
  ElastiCacheIntegrationsTimeoutError,
  ElastiCacheIntegrationsRequestNotSent,
] as const;

describe("ElastiCacheIntegrations errors", () => {
  it.each(ERROR_TYPES)("preserves a cause and the message for %s", (ErrorType) => {
    const cause = new Error("boom");
    const error = new ErrorType("msg", { cause });

    expect(error.message).toBe("msg");
    expect(error.cause).toBe(cause);
    expect(error).toBeInstanceOf(Error);
    expect(String(error)).toContain("msg");
  });

  it.each(ERROR_TYPES)("leaves cause undefined when none is supplied for %s", (ErrorType) => {
    const error = new ErrorType("msg");

    expect(error.cause).toBeUndefined();
    expect(error.message).toBe("msg");
  });

  it.each(ERROR_TYPES)("keeps %s in the ElastiCacheIntegrationsError hierarchy", (ErrorType) => {
    const error = new ErrorType("msg");

    expect(error).toBeInstanceOf(ElastiCacheIntegrationsError);
    expect(error.name).toBe(ErrorType.name);
  });

  it("exposes the public configuration and availability errors", () => {
    expect(elastiCacheIntegrations.ElastiCacheIntegrationsError).toBe(ElastiCacheIntegrationsError);
    expect(elastiCacheIntegrations.ElastiCacheIntegrationsConfigError).toBe(
      ElastiCacheIntegrationsConfigError,
    );
    expect(elastiCacheIntegrations.ElastiCacheIntegrationsCacheUnavailable).toBe(
      ElastiCacheIntegrationsCacheUnavailable,
    );
  });

  it("makes cache unavailability retryable without putting it in ElastiCacheIntegrationsError", () => {
    const error = new ElastiCacheIntegrationsCacheUnavailable("https://origin.example:8443");

    expect(error).toBeInstanceOf(Error);
    expect(error).not.toBeInstanceOf(ElastiCacheIntegrationsError);
    expect(error.endpointUrl).toBe("https://origin.example:8443");
    expect(error.$retryable).toEqual({});
    expect(error.message).toBe(
      "ElastiCacheIntegrations could not serve the request from the cache, and origin fallback is not allowed.",
    );
    expect(new ElastiCacheIntegrationsCacheUnavailable("x", "no capacity").message).toBe(
      "ElastiCacheIntegrations could not serve the request from the cache, and origin fallback is not allowed: no capacity",
    );
  });

  it("preserves the RESP error code on a rejection", () => {
    const error = new ElastiCacheIntegrationsRejectedError("ORIGIN_ERROR", "origin unavailable");

    expect(error.code).toBe("ORIGIN_ERROR");
    expect(error.message).toBe("origin unavailable");
    expect(error).toBeInstanceOf(ElastiCacheIntegrationsError);
  });
});
