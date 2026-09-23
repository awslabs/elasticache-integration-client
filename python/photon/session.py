"""PhotonSession serves requests over a cache connection, and decides what to do when there is none.

The connection itself is owned by a :class:`~photon.connection_manager.ConnectionManager`, which
brings it up, rebuilds it when it is lost, and holds the fallback window open meanwhile. The session
supplies the Photon half: the AUTH and config exchange each new connection needs, the predicates
cacheable, and the request path.

While no connection is available, requests are declined so the caller sends them to the origin
service itself — for as long as the fallback window allows.

A :class:`~photon.refresh.ConfigRefresher` runs for as long as the session is open, so the config
can be re-resolved on the live connection rather than only when a new one is built. A refresh that
fails leaves the current config in place; recording failures and raising a staleness flag are not
yet implemented.
"""

from __future__ import annotations

import logging
from collections.abc import Callable

from photon.connection import PhotonConnector
from photon.connection_manager import ConnectionManager, ConnectionState
from photon.exceptions import PhotonCacheUnavailable, PhotonConfigError, PhotonError
from photon.models import PhotonRequest, PhotonResponse, ResolvedConfig
from photon.reconnect_policy import ReconnectPolicy
from photon.predicates import compile_predicates, should_route_to_photon
from photon.protocol import (
    parse_config_response,
    parse_request_response,
    serialize_config,
    serialize_request,
)
from photon.refresh import ConfigRefresher

logger = logging.getLogger(__name__)


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
            overrides: ``photon.config`` overrides to send when configuring. The proxy resolves
                them and answers with the config the session then routes by.
            policy: Reconnect and fallback behavior; see
                :class:`~photon.reconnect_policy.ReconnectPolicy`.

        Raises:
            PhotonError: The connection could not be opened; see
                :meth:`ConnectionManager.init_connection`.
                Creating a session fails loudly rather than handing back one that never caches.
            RuntimeError: The refresher thread could not be started. The connection is closed
                first, so a session that cannot refresh never leaves one open behind it.
        """
        self._overrides = overrides or {}
        self._config = None
        self._predicates = None
        self._refresher: ConfigRefresher | None = None
        self._connection_manager = ConnectionManager(
            connector_factory, self._configure, policy or ReconnectPolicy()
        )
        self._connection_manager.init_connection()
        try:
            self._refresher = ConfigRefresher(self._config.refresh_interval, self._refresh_once)
            self._refresher.start()
        except BaseException:
            self.close()
            raise

    @property
    def state(self) -> ConnectionState:
        return self._connection_manager.state

    def _configure(self, connector: PhotonConnector) -> None:
        """Handshake with a freshly connected connector and resolve its config, making it usable.

        Called for the initial connection and for every reconnect, so each connection negotiates
        RESP3, authenticates with fresh credentials, and routes by the latest resolved config.

        Nothing is installed unless the whole exchange succeeds, so a configure that fails
        half-way leaves the session routing by the config it was already using.

        The connection manager owns the connector and closes it if this raises.
        """
        connector.handshake()
        command, args = serialize_config(self._overrides)
        config = parse_config_response(
            connector.execute(command, *args, timeout=connector.config_timeout)
        )
        self._replace_config(config)

    def _replace_config(self, config: ResolvedConfig) -> None:
        """Install a resolved config, the predicates compiled from it, and its refresh interval.

        Called when a connection is configured and after each successful refresh, so both paths
        install a new config the same way, and this is the only place either store is written.

        Both stores are replaced outright rather than mutated, so a request reading ``_predicates``
        from another thread sees either the previous list or the new one. The two stores are not
        atomic together: a request can briefly see the new config beside the old predicates.
        Nothing reads both.
        """
        predicates = compile_predicates(config.raw)
        self._config, self._predicates = config, predicates
        if self._refresher:
            self._refresher.set_interval(config.refresh_interval)

    def _refresh_once(self) -> None:
        """Send one ``photon.config`` refresh and apply the result.

        Always the no-argument form: a connection is configured with the overrides before it is
        published, so the server already has them. A RESP3 null reply means the config has not
        changed and nothing happens; any other reply is installed by ``_replace_config``, which
        also reschedules the refresher on the interval the new config carries.

        Nothing is sent while no connection is available, since the next connection resolves its
        own config as it comes up.

        A :class:`PhotonError` from any step is caught so it cannot escape the refresher thread
        and kill it: the current config and schedule stay in place and the next tick retries.
        """
        connector = self._connection_manager.connector
        if not connector:
            return
        command, args = serialize_config(None)
        try:
            reply = connector.execute(command, *args, timeout=connector.config_timeout)
            if reply is None:
                return
            self._replace_config(parse_config_response(reply))
        except PhotonError:
            logger.warning("Config refresh failed; keeping the current config", exc_info=True)

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
        """Stop the refresher, then close the session's connection and all reconnect activity.

        Idempotent.
        """
        if self._refresher:
            self._refresher.stop()
        self._connection_manager.close()
