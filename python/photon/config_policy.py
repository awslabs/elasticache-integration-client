"""Config-staleness settings for a :class:`~photon.session.PhotonSession`."""

from __future__ import annotations

from dataclasses import dataclass

from photon.exceptions import PhotonConfigError

DEFAULT_STALENESS_DURATION = 3600


@dataclass(frozen=True, kw_only=True, slots=True)
class ConfigPolicy:
    """How long the resolved config stays trusted while refreshes are failing.

    Once ``staleness_duration`` seconds pass with no config confirmed by the server, the next
    refresh that fails drops the connection so a new one re-resolves it. A refresh that succeeds
    restarts the window, so a healthy connection is never dropped.
    """

    staleness_duration: int = DEFAULT_STALENESS_DURATION

    def __post_init__(self) -> None:
        if type(self.staleness_duration) is not int or self.staleness_duration <= 0:
            raise PhotonConfigError("staleness_duration must be a positive whole number of seconds")
