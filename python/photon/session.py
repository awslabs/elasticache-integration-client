"""PhotonSession: connection bring-up and the synchronous request API.

:class:`PhotonSession` owns a :class:`~photon.connection.PhotonConnector`, performs connection
bring-up (transport connect + AUTH + the mandatory ``photon.config`` handshake) on construction,
and exposes a **synchronous** ``request()`` for the boto3 ``before-send`` hook to call.

Scope: no predicate filtering, no degradation/circuit-breaking/retry/pass-through, and no periodic
``photon.config`` refresh. The resolved config is fetched once and stored for protocol compliance
only. Errors propagate.
"""

from __future__ import annotations

from photon.connection import PhotonConnector
from photon.exceptions import PhotonError
from photon.models import PhotonRequest, PhotonResponse
from photon.protocol import (
    parse_config_response,
    parse_request_response,
    serialize_config,
    serialize_request,
)


def parse_endpoint(endpoint: str) -> tuple[str, int]: 
    """Parse a ``host:port`` endpoint into ``(host, port)``.

    Lives in the session layer (not the transport) so the transport carries no parsing logic.
    Uses ``rpartition(":")`` so IPv6/host forms with colons keep the final ``:port``.
    """
    if not endpoint or ":" not in endpoint:
        raise PhotonError(f"Invalid endpoint '{endpoint}': expected host:port")
    host, _, port_str = endpoint.rpartition(":")
    if not host:
        raise PhotonError(f"Invalid endpoint '{endpoint}': host cannot be empty")
    try:
        port = int(port_str)
    except ValueError as exc:
        raise PhotonError(f"Invalid port in endpoint '{endpoint}'") from exc
    if not (1 <= port <= 65535):
        raise PhotonError(f"Port out of range in endpoint '{endpoint}': {port}")
    return host, port


class PhotonSession:
    """Owns a connection to the cache. Open on construction, closed explicitly.

    The session owns the connector and closes it, on failure or on close().
    A closed connector cannot be reopened.
    """

    def __init__(self, connector: PhotonConnector, overrides: dict | None = None) -> None:
        self._connector = connector
        self._overrides = overrides or {}
        self._connector.connect()
        try:
            self._connector.authenticate()
            command, args = serialize_config(self._overrides)
            self._config = parse_config_response(
                self._connector.execute(command, *args, timeout=self._connector.config_timeout)
            )
        except BaseException:
            self.close()
            raise

    def request(self, request: PhotonRequest) -> PhotonResponse:
        """Build the ``photon.request`` command, send it, and return the response.

        Forwards unconditionally (no filtering); connector/protocol errors propagate.
        """
        command, args = serialize_request(request)
        return parse_request_response(self._connector.execute(command, *args))

    def close(self) -> None:
        """Close the connector. Idempotent (connector.close() handles it)."""
        self._connector.close()
