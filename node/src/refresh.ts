/**
 * ConfigRefresher: a generic periodic-callback runner used to drive config refresh.
 *
 * The refresher knows nothing about Photon, sessions, or config. It invokes a
 * no-argument callback on a jittered interval until stopped. The timer is
 * unref'd so a running refresher never keeps the Node.js process alive, and a
 * throwing callback is swallowed so it can neither crash the host process nor
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

export interface ConfigRefresherOptions {
  /**
   * Fraction of the interval to jitter by, in [0, 1). A value of 0 disables
   * jitter and every delay is exactly the interval.
   */
  jitter?: number;
  /** Random source in [0, 1), injectable so tests can pin the jitter factor. */
  random?: () => number;
}

/** Runs `refreshOnce` every `intervalSeconds` (+/- jitter) until stopped. */
export class ConfigRefresher {
  private readonly jitter: number;
  private readonly random: () => number;
  private timer: NodeJS.Timeout | undefined;
  private stopped = false;

  constructor(
    private readonly intervalSeconds: number,
    private readonly refreshOnce: () => void,
    options: ConfigRefresherOptions = {},
  ) {
    if (!Number.isFinite(intervalSeconds) || intervalSeconds <= 0) {
      throw new PhotonError(`Invalid refresh interval: must be positive, got ${intervalSeconds}`);
    }
    this.jitter = options.jitter ?? DEFAULT_JITTER;
    if (!Number.isFinite(this.jitter) || this.jitter < 0 || this.jitter >= 1) {
      throw new PhotonError(`Invalid jitter: must be in [0, 1), got ${this.jitter}`);
    }
    // Check the longest delay jitter can produce, not the interval itself.
    const maxDelayMs = intervalSeconds * 1000 * (1 + this.jitter);
    if (maxDelayMs > MAX_TIMEOUT_MS) {
      throw new PhotonError(
        `Invalid refresh interval: ${intervalSeconds}s exceeds the maximum schedulable delay ` +
          `of ${MAX_TIMEOUT_MS}ms`,
      );
    }
    this.random = options.random ?? Math.random;
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
   * Schedule one cycle, then reschedule from inside the callback so each delay
   * gets freshly computed jitter.
   */
  private schedule(): void {
    const timer = setTimeout(() => {
      this.timer = undefined;
      if (this.stopped) return;
      try {
        this.refreshOnce();
      } catch {
        // A failed refresh keeps the previous config; the next cycle retries.
        // An unhandled throw here would surface as an uncaughtException and
        // take down the host process. Swallowed for now as refreshOnce is
        // currently a no-op.
      }
      if (!this.stopped) this.schedule();
    }, this.nextDelayMs());
    // Node.js only; a timer object from a fake-timer implementation may not have it.
    timer.unref?.();
    this.timer = timer;
  }

  private nextDelayMs(): number {
    const intervalMs = this.intervalSeconds * 1000;
    if (this.jitter === 0) return intervalMs;
    return intervalMs * (1 + this.jitter * (2 * this.random() - 1));
  }
}
