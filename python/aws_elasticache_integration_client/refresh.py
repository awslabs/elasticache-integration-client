"""Generic periodic-callback runner used to drive refresh tasks.

:class:`Refresher` runs a no-arg callback on a jittered interval, in a daemon thread, until told to stop.
"""

from __future__ import annotations

import logging
import random
import threading
from typing import Callable, Optional

logger = logging.getLogger(__name__)


class Refresher:
    """Runs ``refresh_once`` every ``interval`` seconds (± jitter) in a daemon thread until stopped,
    tolerating callback failures (a failed tick retries on ``failure_delay``).

    An exception from ``refresh_once`` is logged and the loop continues, so the thread runs until
    ``stop()`` whatever the callback does.

    ``jitter`` and ``wait`` are injectable so tests can disable randomness and substitute a fake
    clock; production code uses the defaults (real jitter, real ``Event.wait``).
    """

    def __init__(
        self,
        interval: float,
        refresh_once: Callable[[], None],
        *,
        name: str = "aws-elasticache-integration-client-refresher",
        jitter: float = 0.2,
        failure_delay: Callable[[int], float] | None = None,
        wait: Callable[[threading.Event, float], bool] | None = None,
    ) -> None:
        self._interval = interval
        self._refresh_once = refresh_once
        self._name = name
        self._jitter = jitter
        # Delay before the next attempt after a failure. When unset, wait the steady interval.
        self._failure_delay = failure_delay
        self._wait = wait or (lambda event, timeout: event.wait(timeout))
        self._stop = threading.Event()
        self._thread: Optional[threading.Thread] = None

    def start(self) -> None:
        thread = threading.Thread(target=self._refresh_loop, name=self._name, daemon=True)
        thread.start()
        self._thread = thread

    def stop(self, timeout: float | None = 5) -> None:
        """Signal the loop to stop and wait up to ``timeout`` seconds for it to exit. Idempotent."""
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=timeout)

    def set_interval(self, interval: float) -> None:
        """Change the interval used for every delay computed after this call.

        A wait already in progress keeps its original length, so a change takes effect one delay
        late at most. Callable from any thread: the loop reads the interval when it computes a
        delay, so a concurrent change is picked up then or on the delay after, never half-applied.
        """
        self._interval = interval

    def _refresh_loop(self) -> None:
        # wait() -> True means stop() fired (quit); False means the interval elapsed (refresh)
        delay = self._next_refresh_delay()
        failures = 0
        while not self._wait(self._stop, delay):
            try:
                self._refresh_once()
            except Exception:
                # A background maintenance tick: one failure must not stop the schedule.
                failures += 1
                level = logging.WARNING if failures == 1 or failures % 100 == 0 else logging.DEBUG
                logger.log(
                    level,
                    "ElastiCacheIntegrations background task %s failed (%d in a row); retrying. The cache keeps serving meanwhile.",
                    self._name,
                    failures,
                    exc_info=True,
                )
                delay = (
                    self._failure_delay(failures)
                    if self._failure_delay
                    else self._next_refresh_delay()
                )
            else:
                failures = 0
                delay = self._next_refresh_delay()

    def _next_refresh_delay(self) -> float:
        return self._interval * random.uniform(1 - self._jitter, 1 + self._jitter)
