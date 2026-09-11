/**
 * Tests for ConfigRefresher.
 *
 * The refresher has no dependency on the session, connector, or protocol
 * modules, so it is exercised in isolation with a counting callback. Timing is
 * driven by fake timers and an injected random source, so nothing here sleeps.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { PhotonError } from "../src/errors.js";
import { ConfigRefresher } from "../src/refresh.js";

/** Captured delays passed to setTimeout, in milliseconds. */
function trackDelays(): number[] {
  const delays: number[] = [];
  vi.spyOn(globalThis, "setTimeout").mockImplementation(((
    callback: () => void,
    delay?: number,
    ...args: unknown[]
  ) => {
    delays.push(delay ?? 0);
    return timerSetTimeout(callback, delay, ...args);
  }) as typeof setTimeout);
  return delays;
}

// Captured before the spy replaces the global so the spy can still schedule.
let timerSetTimeout: typeof setTimeout;

describe("ConfigRefresher", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    timerSetTimeout = globalThis.setTimeout;
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it("invokes the callback once per interval, indefinitely", () => {
    const refreshOnce = vi.fn();
    const refresher = new ConfigRefresher(60, refreshOnce, { jitter: 0 });

    refresher.start();
    expect(refreshOnce).not.toHaveBeenCalled();

    vi.advanceTimersByTime(60_000);
    expect(refreshOnce).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(180_000);
    expect(refreshOnce).toHaveBeenCalledTimes(4);

    refresher.stop();
  });

  it("recomputes jitter for every cycle", () => {
    const delays = trackDelays();
    const randomValues = [0, 0.5, 1];
    let index = 0;
    const refresher = new ConfigRefresher(10, () => undefined, {
      random: () => randomValues[index++ % randomValues.length]!,
    });

    refresher.start();
    vi.advanceTimersByTime(60_000);
    refresher.stop();

    // 10s +/- 20%: random 0 -> 8s, 0.5 -> 10s, 1 -> 12s.
    expect(delays.slice(0, 3)).toEqual([8_000, 10_000, 12_000]);
  });

  it("keeps every jittered delay within the interval bounds", () => {
    const delays = trackDelays();
    const refresher = new ConfigRefresher(10, () => undefined);

    refresher.start();
    vi.advanceTimersByTime(500_000);
    refresher.stop();

    expect(delays.length).toBeGreaterThan(10);
    for (const delay of delays) {
      expect(delay).toBeGreaterThanOrEqual(8_000);
      expect(delay).toBeLessThanOrEqual(12_000);
    }
  });

  it("uses the exact interval when jitter is disabled", () => {
    const delays = trackDelays();
    const refresher = new ConfigRefresher(10, () => undefined, { jitter: 0 });

    refresher.start();
    vi.advanceTimersByTime(100_000);
    refresher.stop();

    expect(new Set(delays)).toEqual(new Set([10_000]));
  });

  it("swallows a throwing callback and keeps refreshing", () => {
    const refreshOnce = vi.fn(() => {
      throw new Error("refresh failed");
    });
    const refresher = new ConfigRefresher(10, refreshOnce, { jitter: 0 });

    refresher.start();
    // An escaping throw would become an uncaughtException and kill the process.
    expect(() => vi.advanceTimersByTime(30_000)).not.toThrow();
    expect(refreshOnce).toHaveBeenCalledTimes(3);

    refresher.stop();
  });

  it("stops promptly and idempotently before the first cycle", () => {
    const refreshOnce = vi.fn();
    const refresher = new ConfigRefresher(3600, refreshOnce);

    refresher.start();
    refresher.stop();
    refresher.stop();

    vi.advanceTimersByTime(3_600_000 * 2);
    expect(refreshOnce).not.toHaveBeenCalled();
  });

  it("does not reschedule when the callback stops the refresher", () => {
    const refresher: ConfigRefresher = new ConfigRefresher(
      10,
      () => {
        refresher.stop();
      },
      { jitter: 0 },
    );

    refresher.start();
    vi.advanceTimersByTime(100_000);

    expect(vi.getTimerCount()).toBe(0);
  });

  it("ignores start() after stop() and start() called twice", () => {
    const delays = trackDelays();
    const refreshOnce = vi.fn();
    const refresher = new ConfigRefresher(10, refreshOnce, { jitter: 0 });

    refresher.start();
    refresher.start();
    expect(delays).toHaveLength(1);

    refresher.stop();
    refresher.start();
    vi.advanceTimersByTime(100_000);
    expect(refreshOnce).not.toHaveBeenCalled();
  });

  it.each([0, -1, Number.NaN, Number.POSITIVE_INFINITY])(
    "rejects the invalid interval %s",
    (interval) => {
      expect(() => new ConfigRefresher(interval, () => undefined)).toThrow(PhotonError);
    },
  );

  it("rejects an interval whose jittered delay cannot be scheduled", () => {
    // Node.js wraps a delay above 2^31-1 ms to ~1ms, which would hot-loop the refresher.
    const maxIntervalSeconds = Math.floor((2 ** 31 - 1) / 1000 / 1.2);

    expect(() => new ConfigRefresher(maxIntervalSeconds, () => undefined)).not.toThrow();
    expect(() => new ConfigRefresher(maxIntervalSeconds + 1, () => undefined)).toThrow(PhotonError);
    // Jitter widens the largest delay, so a smaller interval is allowed without it.
    expect(
      () => new ConfigRefresher(maxIntervalSeconds + 1, () => undefined, { jitter: 0 }),
    ).not.toThrow();
  });

  it("never schedules a delay Node.js would wrap", () => {
    const delays = trackDelays();
    const refresher = new ConfigRefresher(Math.floor((2 ** 31 - 1) / 1000 / 1.2), () => undefined, {
      random: () => 1,
    });

    refresher.start();
    refresher.stop();

    expect(delays[0]).toBeLessThanOrEqual(2 ** 31 - 1);
  });

  it.each([-0.1, 1, 1.5, Number.NaN])("rejects the invalid jitter %s", (jitter) => {
    expect(() => new ConfigRefresher(10, () => undefined, { jitter })).toThrow(PhotonError);
  });
});

describe("ConfigRefresher with real timers", () => {
  it("unrefs its timer so it cannot keep the process alive", () => {
    const refresher = new ConfigRefresher(3600, () => undefined);
    refresher.start();

    const timer = (refresher as unknown as { timer: NodeJS.Timeout }).timer;
    expect(timer.hasRef()).toBe(false);

    refresher.stop();
  });
});
