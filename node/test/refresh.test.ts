/**
 * Tests for Refresher.
 *
 * The refresher has no dependency on the session, connector, or protocol
 * modules, so it is exercised in isolation with a counting callback. Timing is
 * driven by fake timers and an injected random source, so nothing here sleeps.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ElastiCacheIntegrationsError } from "../src/errors.js";
import { Refresher } from "../src/refresh.js";

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

describe("Refresher", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    timerSetTimeout = globalThis.setTimeout;
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it("invokes the callback once per interval, indefinitely", async () => {
    const refreshOnce = vi.fn();
    const refresher = new Refresher(60, refreshOnce, { jitter: 0 });

    refresher.start();
    expect(refreshOnce).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(60_000);
    expect(refreshOnce).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(180_000);
    expect(refreshOnce).toHaveBeenCalledTimes(4);

    refresher.stop();
  });

  it("recomputes jitter for every cycle", async () => {
    const delays = trackDelays();
    const randomValues = [0, 0.5, 1];
    let index = 0;
    const refresher = new Refresher(10, () => undefined, {
      random: () => randomValues[index++ % randomValues.length]!,
    });

    refresher.start();
    await vi.advanceTimersByTimeAsync(60_000);
    refresher.stop();

    // 10s +/- 20%: random 0 -> 8s, 0.5 -> 10s, 1 -> 12s.
    expect(delays.slice(0, 3)).toEqual([8_000, 10_000, 12_000]);
  });

  it("keeps every jittered delay within the interval bounds", async () => {
    const delays = trackDelays();
    const refresher = new Refresher(10, () => undefined);

    refresher.start();
    await vi.advanceTimersByTimeAsync(500_000);
    refresher.stop();

    expect(delays.length).toBeGreaterThan(10);
    for (const delay of delays) {
      expect(delay).toBeGreaterThanOrEqual(8_000);
      expect(delay).toBeLessThanOrEqual(12_000);
    }
  });

  it("uses the exact interval when jitter is disabled", async () => {
    const delays = trackDelays();
    const refresher = new Refresher(10, () => undefined, { jitter: 0 });

    refresher.start();
    await vi.advanceTimersByTimeAsync(100_000);
    refresher.stop();

    expect(new Set(delays)).toEqual(new Set([10_000]));
  });

  it("swallows a throwing callback, logs it, and keeps refreshing", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const refreshOnce = vi.fn(() => {
      throw new Error("refresh failed");
    });
    const refresher = new Refresher(10, refreshOnce, { jitter: 0 });

    refresher.start();
    // An escaping throw would become an uncaughtException and kill the process.
    await expect(vi.advanceTimersByTimeAsync(30_000)).resolves.not.toThrow();
    expect(refreshOnce).toHaveBeenCalledTimes(3);
    expect(warn).toHaveBeenCalledOnce();

    refresher.stop();
  });

  it("stops promptly and idempotently before the first cycle", () => {
    const refreshOnce = vi.fn();
    const refresher = new Refresher(3600, refreshOnce);

    refresher.start();
    refresher.stop();
    refresher.stop();

    vi.advanceTimersByTime(3_600_000 * 2);
    expect(refreshOnce).not.toHaveBeenCalled();
  });

  it("does not reschedule when the callback stops the refresher", async () => {
    const refresher: Refresher = new Refresher(
      10,
      () => {
        refresher.stop();
      },
      { jitter: 0 },
    );

    refresher.start();
    await vi.advanceTimersByTimeAsync(100_000);

    expect(vi.getTimerCount()).toBe(0);
  });

  it("ignores start() after stop() and start() called twice", () => {
    const delays = trackDelays();
    const refreshOnce = vi.fn();
    const refresher = new Refresher(10, refreshOnce, { jitter: 0 });

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
      expect(() => new Refresher(interval, () => undefined)).toThrow(ElastiCacheIntegrationsError);
    },
  );

  it("rejects an interval whose jittered delay cannot be scheduled", () => {
    // Node.js wraps a delay above 2^31-1 ms to ~1ms, which would hot-loop the refresher.
    const maxIntervalSeconds = Math.floor((2 ** 31 - 1) / 1000 / 1.2);

    expect(() => new Refresher(maxIntervalSeconds, () => undefined)).not.toThrow();
    expect(() => new Refresher(maxIntervalSeconds + 1, () => undefined)).toThrow(
      ElastiCacheIntegrationsError,
    );
    // Jitter widens the largest delay, so a smaller interval is allowed without it.
    expect(
      () => new Refresher(maxIntervalSeconds + 1, () => undefined, { jitter: 0 }),
    ).not.toThrow();
  });

  it("never schedules a delay Node.js would wrap", () => {
    const delays = trackDelays();
    const refresher = new Refresher(Math.floor((2 ** 31 - 1) / 1000 / 1.2), () => undefined, {
      random: () => 1,
    });

    refresher.start();
    refresher.stop();

    expect(delays[0]).toBeLessThanOrEqual(2 ** 31 - 1);
  });

  it("awaits an async callback before scheduling the next cycle", async () => {
    const delays = trackDelays();
    let running = 0;
    let overlapped = false;
    const refreshOnce = vi.fn(async () => {
      running += 1;
      if (running > 1) overlapped = true;
      // Resolves only after 5s of timer time, well past the 10s-interval boundary.
      await new Promise((resolve) => setTimeout(resolve, 5_000));
      running -= 1;
    });
    const refresher = new Refresher(10, refreshOnce, { jitter: 0 });

    refresher.start();
    await vi.advanceTimersByTimeAsync(60_000);
    refresher.stop();

    expect(overlapped).toBe(false);
    // Each cycle costs 10s of waiting plus 5s of work, so 60s allows 4 cycles.
    expect(refreshOnce).toHaveBeenCalledTimes(4);
    expect(new Set(delays.filter((delay) => delay === 10_000)).size).toBe(1);
  });

  it("swallows a rejected callback, logs it, and keeps refreshing", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const refreshOnce = vi.fn(() => Promise.reject(new Error("refresh failed")));
    const refresher = new Refresher(10, refreshOnce, { jitter: 0 });

    refresher.start();
    // An escaping rejection would become an unhandledRejection and kill the process.
    await expect(vi.advanceTimersByTimeAsync(30_000)).resolves.not.toThrow();
    expect(refreshOnce).toHaveBeenCalledTimes(3);
    expect(warn).toHaveBeenCalledOnce();

    refresher.stop();
  });

  it("uses failure backoff and restores the steady interval after success", async () => {
    const delays = trackDelays();
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const outcomes = [new Error("one"), new Error("two"), undefined];
    const refreshOnce = vi.fn(() => {
      const outcome = outcomes.shift();
      if (outcome !== undefined) throw outcome;
    });
    const refresher = new Refresher(100, refreshOnce, {
      jitter: 0,
      name: "aws-elasticache-integration-client-reauth",
      failureDelayMs: (failures) => failures * 1_000,
    });

    refresher.start();
    await vi.advanceTimersByTimeAsync(103_000);
    refresher.stop();

    expect(delays.slice(0, 4)).toEqual([100_000, 1_000, 2_000, 100_000]);
    expect(warn.mock.calls[0]?.[0]).toContain("aws-elasticache-integration-client-reauth");
  });

  it("does not reschedule after stop during an in-flight callback", async () => {
    let finishRefresh!: () => void;
    const refreshOnce = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finishRefresh = resolve;
        }),
    );
    const refresher = new Refresher(10, refreshOnce, { jitter: 0 });

    refresher.start();
    await vi.advanceTimersByTimeAsync(10_000);
    refresher.stop();
    finishRefresh();
    await Promise.resolve();

    expect(refreshOnce).toHaveBeenCalledOnce();
    expect(vi.getTimerCount()).toBe(0);
  });

  it("applies a new interval from the next cycle onwards", async () => {
    const delays = trackDelays();
    const refresher = new Refresher(10, () => undefined, { jitter: 0 });

    refresher.start();
    await vi.advanceTimersByTimeAsync(10_000);
    refresher.setInterval(30);
    await vi.advanceTimersByTimeAsync(60_000);
    refresher.stop();

    // The first cycle already scheduled its 10s successor before the interval changed.
    expect(delays).toEqual([10_000, 10_000, 30_000, 30_000]);
  });

  it("applies an interval set from inside the callback starting the next cycle", async () => {
    // Mirrors the production shape: the refresh callback itself calls setInterval() once it
    // learns the server's new interval, rather than a test calling it from outside a cycle.
    const delays = trackDelays();
    const refresher: Refresher = new Refresher(
      10,
      () => {
        refresher.setInterval(30);
      },
      { jitter: 0 },
    );

    refresher.start();
    await vi.advanceTimersByTimeAsync(40_000);
    refresher.stop();

    expect(delays).toEqual([10_000, 30_000, 30_000]);
  });

  it("leaves a wait already in progress alone when the interval changes", async () => {
    const delays = trackDelays();
    const refreshOnce = vi.fn();
    const refresher = new Refresher(100, refreshOnce, { jitter: 0 });

    refresher.start();
    await vi.advanceTimersByTimeAsync(50_000);
    refresher.setInterval(10);

    // The pending 100s wait is not shortened to 10s.
    await vi.advanceTimersByTimeAsync(20_000);
    expect(refreshOnce).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(30_000);
    expect(refreshOnce).toHaveBeenCalledTimes(1);

    refresher.stop();
    expect(delays).toEqual([100_000, 10_000]);
  });

  it.each([0, -1, Number.NaN, Number.POSITIVE_INFINITY])(
    "rejects the invalid interval %s for setInterval",
    (interval) => {
      const refresher = new Refresher(10, () => undefined);

      expect(() => refresher.setInterval(interval)).toThrow(ElastiCacheIntegrationsError);
      refresher.stop();
    },
  );

  it("keeps the previous interval when setInterval rejects the new one", async () => {
    const delays = trackDelays();
    const refresher = new Refresher(10, () => undefined, { jitter: 0 });

    refresher.start();
    expect(() => refresher.setInterval(0)).toThrow(ElastiCacheIntegrationsError);
    await vi.advanceTimersByTimeAsync(20_000);
    refresher.stop();

    expect(delays).toEqual([10_000, 10_000, 10_000]);
  });

  it.each([-0.1, 1, 1.5, Number.NaN])("rejects the invalid jitter %s", (jitter) => {
    expect(() => new Refresher(10, () => undefined, { jitter })).toThrow(
      ElastiCacheIntegrationsError,
    );
  });
});

describe("Refresher with real timers", () => {
  it("unrefs its timer so it cannot keep the process alive", () => {
    const refresher = new Refresher(3600, () => undefined);
    refresher.start();

    const timer = (refresher as unknown as { timer: NodeJS.Timeout }).timer;
    expect(timer.hasRef()).toBe(false);

    refresher.stop();
  });
});
