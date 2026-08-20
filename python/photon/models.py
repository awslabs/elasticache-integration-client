"""Core data models for the Photon client.

These are plain data containers shared across every component (protocol, session, hooks). The
error taxonomy lives in :mod:`photon.exceptions`.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING

if TYPE_CHECKING:  # import-time cycle: predicates imports PhotonRequest from this module
    from photon.predicates import Predicate


@dataclass(slots=True, frozen=True, kw_only=True)
class RequestMetadata:
    """Service-agnostic request metadata."""

    service: str
    operation: str


@dataclass(slots=True, frozen=True, kw_only=True)
class PhotonRequest:
    """An intercepted AWS SDK request to be forwarded to the cache."""

    method: str
    url: str
    headers: dict[str, str] = field(default_factory=dict)
    body: bytes = b""
    metadata: RequestMetadata


@dataclass(slots=True, frozen=True)
class PhotonResponse:
    """A response returned by the cache, to be adapted into an SDK response."""

    status_code: int
    headers: dict[str, str] = field(default_factory=dict)
    body: bytes = b""


@dataclass(slots=True, frozen=True)
class ResolvedConfig:
    """Resolved configuration returned by the ``photon.config`` handshake.

    Frozen, and ``predicates`` is a tuple, so the whole object can be swapped in one atomic
    rebind when the config is re-fetched — readers need no lock, they just take a snapshot.
    """

    version: str | None = None
    refresh_interval: int | None = None
    raw: dict | None = None
    # Ordered, first-match-wins. Empty means "route nothing" — every request passes through.
    predicates: tuple[Predicate, ...] = ()
