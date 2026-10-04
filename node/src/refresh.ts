/**
 * Refresher: a generic periodic-callback runner used to drive background tasks.
 *
 * The refresher knows nothing about Photon, sessions, or config. It invokes a
 * no-argument callback on a jittered interval until stopped. Callback failures
 * may use a separate retry delay. The timer is
 * unref'd so a running refresher never keeps the Node.js process alive, and a
 * failing callback is swallowed so it can neither crash the host process nor
 * stop future cycles.
 */
import { PhotonError } from "./errors.js";

/** Default jitter fraction: each delay lands within +/-20% of the interval. */
const DEFAULT_JITTER = 0.2;

/**
 * Largest delay Node.js can schedule. A larger value silently wraps to a ~1ms
 * delay, which would turn the refresher into a hot loop, so it is rejected.
 */
const MAX_TIMEOUT_MS = 2 ** 31 - 1;

export interface RefresherOptions {
  /**
   * Fraction of the interval to jitter by, in [0, 1). A value of 0 disables
   * jitter and every delay is exactly the interval.
   */
  jitter?: number;
  /** Random source in [0, 1), injectable so tests can pin the jitter factor. */
  random?: () => number;
  /** Task name included in failure logs. */
  name?: string;
  /** Delay in milliseconds after a callback failure. Defaults to the steady interval. */
  failureDelayMs?: (consecutiveFailures: number) => number;
}

/** Runs `refreshOnce` every `intervalSeconds` (+/- jitter) until stopped. */
export class Refresher {
  private readonly jitter: number;
  private readonly random: () => number;
  private readonly name: string;
  private readonly failureDelayMs: RefresherOptions["failureDelayMs"];
  private intervalSeconds: number;
  private timer: NodeJS.Timeout | undefined;
  private stopped = false;
  private consecutiveFailures = 0;

  constructor(
    intervalSeconds: number,
    private readonly refreshOnce: () => void | Promise<void>,
    options: RefresherOptions = {},
  ) {
    this.jitter = options.jitter ?? DEFAULT_JITTER;
    if (!Number.isFinite(this.jitter) || this.jitter < 0 || this.jitter >= 1) {
      throw new PhotonError(`Invalid jitter: must be in [0, 1), got ${this.jitter}`);
    }
    this.validateInterval(intervalSeconds);
    this.intervalSeconds = intervalSeconds;
    this.random = options.random ?? Math.random;
    this.name = options.name ?? "photon-refresher";
    this.failureDelayMs = options.failureDelayMs;
  }

  /**
   * Replace the interval used by future cycles.
   *
   * A wait already in progress is left alone, so a refreshed config takes effect from the next
   * scheduled cycle onwards.
   */
  setInterval(intervalSeconds: number): void {
    this.validateInterval(intervalSeconds);
    this.intervalSeconds = intervalSeconds;
  }

  private validateInterval(intervalSeconds: number): void {
    if (!Number.isFinite(intervalSeconds) || intervalSeconds <= 0) {
      throw new PhotonError(`Invalid refresh interval: must be positive, got ${intervalSeconds}`);
    }
    // Check the longest delay jitter can produce, not the interval itself.
    const maxDelayMs = intervalSeconds * 1000 * (1 + this.jitter);
    if (maxDelayMs > MAX_TIMEOUT_MS) {
      throw new PhotonError(
        `Invalid refresh interval: ${intervalSeconds}s exceeds the maximum schedulable delay ` +
          `of ${MAX_TIMEOUT_MS}ms`,
      );
    }
  }

  /** Schedule the first cycle. Idempotent; a no-op once stopped. */
  start(): void {
    if (this.stopped || this.timer !== undefined) return;
    this.schedule();
  }

  /** Cancel any pending cycle. Synchronous and idempotent. */
  stop(): void {
    this.stopped = true;
    if (this.timer !== undefined) {
      clearTimeout(this.timer);
      this.timer = undefined;
    }
  }

  /**
   * Schedule one cycle, then reschedule once the cycle finishes so each delay
   * gets freshly computed jitter and refreshes never overlap.
   */
  private schedule(delayMs = this.nextDelayMs()): void {
    const timer = setTimeout(() => {
      this.timer = undefined;
      if (this.stopped) return;
      void this.runCycle();
    }, delayMs);
    // Node.js only; a timer object from a fake-timer implementation may not have it.
    timer.unref?.();
    this.timer = timer;
  }

  /** Await one refresh, then schedule the next. Never rejects. */
  private async runCycle(): Promise<void> {
    let nextDelayMs: number | undefined;
    try {
      await this.refreshOnce();
      this.consecutiveFailures = 0;
    } catch (error) {
      const message =
        `Photon background task ${this.name} failed (${++this.consecutiveFailures} in a row); ` +
        "retrying. The cache keeps serving meanwhile.";
      if (this.consecutiveFailures === 1 || this.consecutiveFailures % 100 === 0) {
        console.warn(message, error);
      }
      nextDelayMs = this.failureDelayMs?.(this.consecutiveFailures);
    }
    if (!this.stopped) this.schedule(nextDelayMs);
  }

  private nextDelayMs(): number {
    const intervalMs = this.intervalSeconds * 1000;
    if (this.jitter === 0) return intervalMs;
    return intervalMs * (1 + this.jitter * (2 * this.random() - 1));
  }
}
