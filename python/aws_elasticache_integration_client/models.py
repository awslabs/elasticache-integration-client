"""Core data models for the ElastiCacheIntegrations client.

These are plain data containers shared across every component (protocol, session, hooks). The
error taxonomy lives in :mod:`aws_elasticache_integration_client.exceptions`.
"""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(slots=True, frozen=True, kw_only=True)
class RequestMetadata:
    """Service-agnostic request metadata."""

    service: str
    operation: str


@dataclass(slots=True, frozen=True, kw_only=True)
class ElastiCacheIntegrationsRequest:
    """An intercepted AWS SDK request to be forwarded to the cache."""

    method: str
    url: str
    headers: dict[str, str] = field(default_factory=dict)
    body: bytes = b""
    metadata: RequestMetadata


@dataclass(slots=True, frozen=True)
class ElastiCacheIntegrationsResponse:
    """A response returned by the cache, to be adapted into an SDK response."""

    status_code: int
    headers: dict[str, str] = field(default_factory=dict)
    body: bytes = b""


@dataclass(slots=True, frozen=True)
class ResolvedConfig:
    """Resolved configuration returned by the ``aws.config`` handshake."""

    refresh_interval: int
    version: str | None = None
    raw: dict | None = None
