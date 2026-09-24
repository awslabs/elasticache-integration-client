"""Generic periodic-callback runner used to drive config refresh.

:class:`ConfigRefresher` knows nothing about Photon, sessions, or config — it runs a
no-arg callback on a jittered interval, in a daemon thread, until told to stop.
"""

from __future__ import annotations

import logging
import random
import threading
from typing import Callable, Optional

logger = logging.getLogger(__name__)


class ConfigRefresher:
    """Runs ``refresh_once`` every ``interval`` seconds (± jitter) in a daemon thread until stopped.

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
        jitter: float = 0.2,
        wait: Callable[[threading.Event, float], bool] | None = None,
    ) -> None:
        self._interval = interval
        self._refresh_once = refresh_once
        self._jitter = jitter
        self._wait = wait or (lambda event, timeout: event.wait(timeout))
        self._stop = threading.Event()
        self._thread: Optional[threading.Thread] = None

    def start(self) -> None:
        thread = threading.Thread(target=self._refresh_loop, name="photon-refresher", daemon=True)
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
        while not self._wait(self._stop, self._next_refresh_delay()):
            try:
                self._refresh_once()
            except Exception:
                # The callback handles its own expected failures, so anything reaching here is a
                # bug. Letting it end the thread would stop every later refresh with no log of it.
                logger.warning(
                    "Unexpected error while refreshing; the refresher keeps running", exc_info=True
                )

    def _next_refresh_delay(self) -> float:
        return self._interval * random.uniform(1 - self._jitter, 1 + self._jitter)
