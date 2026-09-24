"""Public entry point: the :func:`photon.client` factory."""

from __future__ import annotations

import boto3

from photon.auth import build_cache_auth_provider
from photon.connection import PhotonConnector
from photon.hooks import PhotonHandle
from photon.reconnect_policy import ReconnectPolicy
from photon.session import PhotonSession, parse_endpoint


def client(service: str, *, endpoint: str, cache_name: str,
           region_name: str | None = None, user_id: str | None = None,
           verify: bool = True, ca_certs: str | None = None,
           fallback_duration: int | None = None, **overrides):
    """Create a boto3 client for ``service`` wired up with Photon.

    Returns the boto3 client (identical API). Calling ``close()`` on it disconnects
    Photon before closing the client as usual.

    Args:
        service: AWS service name (e.g., "dynamodb").
        endpoint: Cache endpoint as ``host:port``.
        cache_name: Required. The ElastiCache Serverless cache name.
        region_name: AWS region. If not provided, resolved from the environment (same as boto3).
        user_id: Optional ElastiCache IAM user id (defaults to ``default.iam-user``).
        verify: Verify the cache's TLS certificate. False is for self-signed test rigs only.
        ca_certs: CA bundle to verify against instead of the system trust store.
        fallback_duration: How long, in seconds, requests may be served directly by the origin
            service while Photon has no connection to the cache. ``None`` (the default) or any
            negative value means indefinitely, so a cache outage never becomes an application
            outage. ``0`` means never — the first request after a connection is lost raises
            :class:`~photon.exceptions.PhotonCacheUnavailable`.
        **overrides: Additional photon.config overrides.

    Example::

        ddb = photon.client("dynamodb", endpoint="my-cache:6379",
                            cache_name="my-cache", region_name="us-east-1")
    """
    sdk_client = boto3.Session(region_name=region_name).client(service)

    try:
        host, port = parse_endpoint(endpoint)
        auth_provider = build_cache_auth_provider(
            cache_name=cache_name, user_id=user_id, region=region_name
        )

        def connector_factory() -> PhotonConnector:
            return PhotonConnector(host, port, verify=verify, ca_certs=ca_certs,
                                   auth_provider=auth_provider)

        # The session connects (HELLO 3 handshake + photon.config exchange) before the hook is installed,
        # so the first intercepted request already has a ready session.
        session = PhotonSession(
            connector_factory,
            overrides=overrides or None,
            policy=ReconnectPolicy(fallback_duration=fallback_duration),
        )
        PhotonHandle(sdk_client, session)
    except BaseException:
        # Bring-up failed (auth, the config handshake, or wiring). Close the boto3 client
        # we already created so its connection pool does not leak, then re-raise the original
        # error.
        try:
            sdk_client.close()
        except Exception:
            pass  # preserve the original bring-up error
        raise

    return sdk_client
