// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

/**
 * The per-call abort signal an AWS SDK v3 caller passes as `send(command, { abortSignal })`.
 *
 * The SDK accepts both the platform AbortSignal and its own deprecated `onabort`-only shape.
 */
import type { HttpHandlerOptions } from "@smithy/types";

export type SdkAbortSignal = NonNullable<HttpHandlerOptions["abortSignal"]>;

/** The error the SDK's own request handler raises for an aborted call. */
export function abortError(signal: SdkAbortSignal): Error {
  const reason = "reason" in signal ? (signal as { reason?: unknown }).reason : undefined;
  const error =
    reason === undefined || reason instanceof Error
      ? new Error("Request aborted", reason === undefined ? undefined : { cause: reason })
      : new Error(String(reason));
  error.name = "AbortError";
  return error;
}

/** Call `onAbort` once when `signal` aborts. Returns a function that removes the listener. */
export function onAbort(signal: SdkAbortSignal, onAbort: () => void): () => void {
  if (typeof (signal as AbortSignal).addEventListener === "function") {
    const platform = signal as AbortSignal;
    platform.addEventListener("abort", onAbort, { once: true });
    return () => platform.removeEventListener("abort", onAbort);
  }
  const previous = signal.onabort;
  const handler = function (this: unknown, ...args: unknown[]) {
    onAbort();
    if (typeof previous === "function") Reflect.apply(previous, this, args);
  };
  signal.onabort = handler;
  return () => {
    if (signal.onabort === handler) signal.onabort = previous;
  };
}

/** Settle with `work`, or reject with the abort error as soon as `signal` aborts. */
export async function raceAbort<T>(
  work: Promise<T>,
  signal: SdkAbortSignal | undefined,
): Promise<T> {
  if (signal === undefined) return work;
  if (signal.aborted) {
    void work.catch(() => undefined);
    throw abortError(signal);
  }
  let remove = () => {};
  const aborted = new Promise<never>((_, reject) => {
    remove = onAbort(signal, () => reject(abortError(signal)));
  });
  // The work keeps running after an abort; its own timeout settles it, and nobody reads the result.
  void work.catch(() => undefined);
  try {
    return await Promise.race([work, aborted]);
  } finally {
    remove();
  }
}
