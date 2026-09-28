"""Reconnect and fallback settings for a :class:`~photon.session.PhotonSession`."""

from __future__ import annotations

import math
import random
from dataclasses import dataclass

from photon.exceptions import PhotonConfigError


@dataclass(frozen=True, kw_only=True, slots=True)
class ReconnectPolicy:
    """Configure reconnect backoff and fallback.

    ``fallback_duration`` controls how long requests may fall back to the
    origin while the cache is disconnected:

    * ``None`` or a negative value: fall back indefinitely.
    * ``0``: never fall back; raise immediately.
    * A positive value: fall back for that many seconds, then raise.

    Reconnects continue until the connection is restored or the session closes.
    """

    fallback_duration: int | None = None
    connect_attempts: int = 3
    # How long opening one connection may take: transport connect, AUTH and the config exchange
    # bounded from outside while the steps keep their own timeouts underneath. It caps every
    # attempt, so creating a session takes at most connect_attempts of these plus the backoff
    # between them, and each later reconnect attempt is capped by one.
    connection_timeout: float = 5.0
    # How often to re-run AUTH in the background to refresh the connection's FAS.
    auth_refresh_interval: float = 900.0
    base_delay: float = 0.1
    max_delay: float = 3.2
    jitter: float = 0.2

    def __post_init__(self) -> None:
        if self.fallback_duration is not None and (
            isinstance(self.fallback_duration, bool)
            or not isinstance(self.fallback_duration, int)
        ):
            raise PhotonConfigError("fallback_duration must be a whole number of seconds or None")
        if self.connect_attempts < 1:
            raise PhotonConfigError("connect_attempts must be >= 1")
        if self.connection_timeout <= 0:
            raise PhotonConfigError("connection_timeout must be > 0")
        if self.auth_refresh_interval <= 0:
            raise PhotonConfigError("auth_refresh_interval must be > 0")
        if self.base_delay <= 0:
            raise PhotonConfigError("base_delay must be > 0")
        if self.max_delay < self.base_delay:
            raise PhotonConfigError("max_delay must be >= base_delay")
        if not (0 <= self.jitter < 1):
            raise PhotonConfigError("jitter must be in [0, 1)")

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
        delay = min(self.max_delay, self.base_delay * 2 ** exponent)
        if not self.jitter:
            return delay
        return delay * random.uniform(1 - self.jitter, 1 + self.jitter)
