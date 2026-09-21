"""Generic periodic-callback runner used to drive config refresh.

:class:`ConfigRefresher` knows nothing about Photon, sessions, or config — it runs a
no-arg callback on a jittered interval, in a daemon thread, until told to stop.
"""

from __future__ import annotations

import random
import threading
from typing import Callable, Optional


class ConfigRefresher:
    """Runs ``refresh_once`` every ``interval`` seconds (± jitter) in a daemon thread until stopped.

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
        """Change the interval used for all subsequent delays.

        Applies starting with the next computed delay. Only called from the refresher's own
        thread, so no lock is needed.
        """
        self._interval = interval

    def _refresh_loop(self) -> None:
        # wait() -> True means stop() fired (quit); False means the interval elapsed (refresh)
        while not self._wait(self._stop, self._next_refresh_delay()):
            self._refresh_once()

    def _next_refresh_delay(self) -> float:
        return self._interval * random.uniform(1 - self._jitter, 1 + self._jitter)
