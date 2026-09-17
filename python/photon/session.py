"""PhotonSession: connection bring-up and the synchronous request API.

:class:`PhotonSession` owns a :class:`~photon.connection.PhotonConnector`, performs connection
bring-up (transport connect + AUTH + the mandatory ``photon.config`` handshake) on construction,
and exposes a **synchronous** ``request()`` for the boto3 ``before-send`` hook to call.

Scope: no degradation/circuit-breaking/retry and no periodic ``photon.config`` refresh. Requests are
routed to the cache only when a resolved CEL predicate matches; everything else is passed through by
the caller. Errors propagate.
"""

from __future__ import annotations

from photon.connection import PhotonConnector
from photon.exceptions import PhotonError
from photon.models import PhotonRequest, PhotonResponse
from photon.predicates import compile_predicates, should_route_to_photon
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
        self._is_open = False
        self._connector.connect()
        try:
            self._connector.authenticate()
            command, args = serialize_config(self._overrides)
            self._config = parse_config_response(
                self._connector.execute(command, *args, timeout=self._connector.config_timeout)
            )
            # Compile the resolved predicates once; the request path evaluates them per request.
            self._predicates = compile_predicates(self._config.raw)
            self._is_open = True
        except BaseException:
            self.close()
            raise

    def request(self, request: PhotonRequest) -> PhotonResponse | None:
        """Route ``request`` to the cache and return its response, or None to pass it through.

        ``None`` means no resolved predicate matched — the caller sends the request to the origin
        service itself. The two outcomes are disjoint (a served request is always a
        :class:`PhotonResponse`, every failure raises), so ``None`` is unambiguous. Connector and
        protocol errors propagate.
        """
        if not self._is_open:
            raise PhotonError("request() called on a closed session")
        if not should_route_to_photon(self._predicates, request):
            return None
        command, args = serialize_request(request)
        return parse_request_response(self._connector.execute(command, *args))

    def close(self) -> None:
        """Close the connector. Idempotent (connector.close() handles it)."""
        self._is_open = False
        self._connector.close()

    @property
    def is_open(self) -> bool:
        """Whether the session can safely initiate fallback work."""
        return self._is_open
