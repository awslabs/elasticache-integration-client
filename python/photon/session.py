"""PhotonSession serves requests over a cache connection, and decides what to do when there is none.

The connection itself is owned by a :class:`~photon.connection_manager.ConnectionManager`, which
brings it up, rebuilds it when it is lost, and holds the fallback window open meanwhile. The session
supplies the Photon half: the handshake each new connection needs, the predicates that decide what is
cacheable, and the request path.

While no connection is available, requests are declined so the caller sends them to the origin
service itself — for as long as the fallback window allows.
"""

from __future__ import annotations

from collections.abc import Callable

from photon.connection import PhotonConnector
from photon.connection_manager import ConnectionManager, ConnectionState
from photon.exceptions import PhotonCacheUnavailable, PhotonConfigError
from photon.models import PhotonRequest, PhotonResponse
from photon.policy import ReconnectPolicy
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
        raise PhotonConfigError(f"Invalid endpoint '{endpoint}': expected host:port")
    host, _, port_str = endpoint.rpartition(":")
    if not host:
        raise PhotonConfigError(f"Invalid endpoint '{endpoint}': host cannot be empty")
    try:
        port = int(port_str)
    except ValueError as exc:
        raise PhotonConfigError(f"Invalid port in endpoint '{endpoint}'") from exc
    if not (1 <= port <= 65535):
        raise PhotonConfigError(f"Port out of range in endpoint '{endpoint}': {port}")
    return host, port


class PhotonSession:
    """Serves requests over the connection a :class:`ConnectionManager` keeps alive.

    Stays open until ``close()``.
    """

    def __init__(
        self,
        connector_factory: Callable[[], PhotonConnector],
        overrides: dict | None = None,
        *,
        policy: ReconnectPolicy | None = None,
    ) -> None:
        """Create a session and open its connection, so it is ready to serve requests.

        Args:
            connector_factory: Returns a new, unconnected connector each time it is called.
            overrides: ``photon.config`` overrides to send during the handshake. The proxy resolves
                them and answers with the config the session then routes by.
            policy: Reconnect and fallback behavior; see :class:`~photon.policy.ReconnectPolicy`.

        Raises:
            PhotonError: The connection could not be opened; see :meth:`ConnectionManager.start`. 
                Creating a session fails loudly rather than handing back one that never caches.
        """
        self._overrides = overrides or {}
        self._config = None
        self._predicates = None
        self._connection_manager = ConnectionManager(
            connector_factory, self._bring_up, policy or ReconnectPolicy()
        )
        self._connection_manager.start()

    @property
    def state(self) -> ConnectionState:
        return self._connection_manager.state

    def _bring_up(self, connector: PhotonConnector) -> None:
        """Give a freshly connected connector the Photon handshake, making it usable.

        Called for the initial connection and for every reconnect, so each connection
        authenticates with fresh credentials and routes by the latest resolved config.

        Neither result is published unless both succeed, so a bring-up that fails half-way leaves
        the session routing by the config it was already using rather than pairing a new config with
        the predicates of an older one. The two stores are not atomic together, so a request can
        briefly see the new config beside the old predicates; nothing reads both today.

        The connection manager owns the connector and closes it if this raises.
        """
        connector.authenticate()
        command, args = serialize_config(self._overrides)
        config = parse_config_response(
            connector.execute(command, *args, timeout=connector.config_timeout)
        )
        # Compile the resolved predicates once; the request path evaluates them per request.
        predicates = compile_predicates(config.raw)
        self._config, self._predicates = config, predicates

    def request(self, request: PhotonRequest) -> PhotonResponse | None:
        """Route a request to Photon, or return None to let the caller handle it.

        A request is passed through when it does not match any resolved predicate,
        or when the cache is disconnected and the fallback window is still open.
        In both cases, the request has not been sent, so falling back is safe.

        Once a request is sent to Photon, failures are propagated rather than
        retried or passed through, since the request may have reached the cache.
        These failures are converted to botocore transport errors, and the SDK
        decides whether to retry them.

        Raises:
            PhotonCacheUnavailable: If the cache is disconnected and the fallback
                window has closed.
        """
        if not should_route_to_photon(self._predicates, request):
            return None

        # Read the connector once; the reconnect thread may replace it concurrently.
        connector = self._connection_manager.connector
        if not connector:
            if not self._connection_manager.fallback_allowed():
                raise PhotonCacheUnavailable(endpoint_url=request.url)
            return     # pass through

        command, args = serialize_request(request)
        return parse_request_response(connector.execute(command, *args))

    def close(self) -> None:
        """Close the session's connection and stop all reconnect activity. Idempotent."""
        self._connection_manager.close()
