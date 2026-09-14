import { describe, expect, it } from "vitest";

import {
  PhotonCircuitOpenError,
  PhotonConnectionLost,
  PhotonConnectionError,
  PhotonError,
  PhotonProtocolError,
  PhotonTimeoutError,
} from "../src/errors.js";
import * as photon from "../src/index.js";

const ERROR_TYPES = [
  PhotonError,
  PhotonCircuitOpenError,
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

  it("does not expose internal error types from the package entry point", () => {
    expect("PhotonError" in photon).toBe(false);
  });
});
