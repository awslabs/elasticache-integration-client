"""Reconnect and fallback settings for a :class:`~aws_elasticache_integration_client.session.ElastiCacheIntegrationsSession`."""

from __future__ import annotations

import math
import random
from dataclasses import dataclass

from aws_elasticache_integration_client.exceptions import ElastiCacheIntegrationsConfigError

#: Bounds bringing a connection up — TCP connect, TLS, ``HELLO 3 AUTH`` and the first ``aws.config``
#: — for each attempt and across all of them.
DEFAULT_CONNECTION_TIMEOUT_MS = 5000
DEFAULT_CONNECTION_TIMEOUT = DEFAULT_CONNECTION_TIMEOUT_MS / 1000


@dataclass(frozen=True, kw_only=True, slots=True)
class ReconnectPolicy:
    """Configure reconnect backoff and fallback.

    ``fallback_duration`` controls how long requests may fall back to the
    origin after waiting up to ``connection_timeout`` for a connection:

    * ``None`` or a negative value: fall back indefinitely.
    * ``0``: never fall back; raise if the reconnect wait ends without a connection.
    * A positive value: fall back for that many seconds, then raise.

    Reconnects continue until the connection is restored or the session closes.
    """

    fallback_duration: int | None = None
    # How long to keep trying to get a connection: at startup before raising, and after a drop
    # before a waiting request falls back. Attempts back off inside it.
    connection_timeout: float = DEFAULT_CONNECTION_TIMEOUT
    # How often to re-run AUTH in the background to refresh the connection's FAS.
    auth_refresh_interval: float = 900.0
    base_delay: float = 0.1
    max_delay: float = 3.2
    jitter: float = 0.2

    def __post_init__(self) -> None:
        if self.fallback_duration is not None and (
            isinstance(self.fallback_duration, bool) or not isinstance(self.fallback_duration, int)
        ):
            raise ElastiCacheIntegrationsConfigError(
                "fallback_duration must be a whole number of seconds or None"
            )
        if self.connection_timeout <= 0:
            raise ElastiCacheIntegrationsConfigError("connection_timeout must be > 0")
        if self.auth_refresh_interval <= 0:
            raise ElastiCacheIntegrationsConfigError("auth_refresh_interval must be > 0")
        if self.base_delay <= 0:
            raise ElastiCacheIntegrationsConfigError("base_delay must be > 0")
        if self.max_delay < self.base_delay:
            raise ElastiCacheIntegrationsConfigError("max_delay must be >= base_delay")
        if not (0 <= self.jitter < 1):
            raise ElastiCacheIntegrationsConfigError("jitter must be in [0, 1)")

    @property
    def fallback_window(self) -> float:
        """Return the fallback duration in seconds, with unbounded as infinity."""
        if self.fallback_duration is None or self.fallback_duration < 0:
            return math.inf
        return float(self.fallback_duration)

    def delay_for(self, attempt: int) -> float:
        """Return jittered exponential backoff for a 1-based attempt.

        The delay is capped at ``max_delay`` before jitter is applied. Jitter is
        applied to every attempt, including attempts after reaching the cap.
        """
        # Bound the exponent so a long run of failures cannot overflow the float
        exponent = min(attempt - 1, 1023)
        delay = min(self.max_delay, self.base_delay * 2**exponent)
        if not self.jitter:
            return delay
        return delay * random.uniform(1 - self.jitter, 1 + self.jitter)
