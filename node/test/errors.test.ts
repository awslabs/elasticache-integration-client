import { describe, expect, it } from "vitest";

import {
  PhotonCacheUnavailable,
  PhotonConfigError,
  PhotonConnectionLost,
  PhotonConnectionError,
  PhotonError,
  PhotonProtocolError,
  PhotonTimeoutError,
} from "../src/errors.js";
import * as photon from "../src/index.js";

const ERROR_TYPES = [
  PhotonError,
  PhotonConfigError,
  PhotonConnectionError,
  PhotonProtocolError,
  PhotonConnectionLost,
  PhotonTimeoutError,
] as const;

describe("Photon errors", () => {
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

  it.each(ERROR_TYPES)("keeps %s in the PhotonError hierarchy", (ErrorType) => {
    const error = new ErrorType("msg");

    expect(error).toBeInstanceOf(PhotonError);
    expect(error.name).toBe(ErrorType.name);
  });

  it("exposes the public configuration and availability errors", () => {
    expect(photon.PhotonError).toBe(PhotonError);
    expect(photon.PhotonConfigError).toBe(PhotonConfigError);
    expect(photon.PhotonCacheUnavailable).toBe(PhotonCacheUnavailable);
  });

  it("makes cache unavailability retryable without putting it in PhotonError", () => {
    const error = new PhotonCacheUnavailable("https://origin.example:8443");

    expect(error).toBeInstanceOf(Error);
    expect(error).not.toBeInstanceOf(PhotonError);
    expect(error.endpointUrl).toBe("https://origin.example:8443");
    expect(error.$retryable).toEqual({});
    expect(error.message).toContain("https://origin.example:8443");
  });
});
