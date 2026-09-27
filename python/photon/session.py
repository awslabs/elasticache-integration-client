"""PhotonSession serves requests over a cache connection, and decides what to do when there is none.

The connection itself is owned by a :class:`~photon.connection_manager.ConnectionManager`, which
brings it up, rebuilds it when it is lost, and holds the fallback window open meanwhile. The session
supplies the Photon half: the AUTH and config exchange each new connection needs, the predicates
cacheable, and the request path.

While no connection is available, requests are declined so the caller sends them to the origin
service itself — for as long as the fallback window allows.

A :class:`~photon.refresh.Refresher` runs for as long as the session is open, so the config
can be re-resolved on the live connection rather than only when a new one is built. A refresh that
fails leaves the current config in place; recording failures and raising a staleness flag are not
yet implemented.
"""

from __future__ import annotations

import logging
from collections.abc import Callable

from photon.connection import PhotonConnector
from photon.connection_manager import ConnectionManager, ConnectionState
from photon.exceptions import (
    PhotonCacheUnavailable,
    PhotonConfigError,
    PhotonError,
    PhotonRejectedError,
)
from photon.models import PhotonRequest, PhotonResponse, ResolvedConfig
from photon.refresh import Refresher
from photon.reconnect_policy import ReconnectPolicy
from photon.predicates import compile_predicates, should_route_to_photon
from photon.protocol import (
    parse_config_response,
    parse_request_response,
    serialize_config,
    serialize_request,
)

logger = logging.getLogger(__name__)

_PHOTON_AUTH_ERROR = "PHOTON_AUTH_ERROR"


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
                :class:`~photon.reconnect_policy.ReconnectPolicy`. ``None`` uses the defaults.

        Raises:
            PhotonError: The connection could not be opened; see
                :meth:`ConnectionManager.init_connection`.
                Creating a session fails loudly rather than handing back one that never caches.
            RuntimeError: The refresher thread could not be started. The connection is closed
                first, so a session that cannot refresh never leaves one open behind it.
        """
        policy = policy or ReconnectPolicy()
        self._overrides = overrides or {}
        self._config = None
        self._predicates = None
        self._config_refresher: Refresher | None = None
        self._reauth_runner: Refresher | None = None
        self._connection_manager = ConnectionManager(
            connector_factory, self._configure, policy
        )
        self._connection_manager.init_connection()
        try:
            self._config_refresher = Refresher(
                self._config.refresh_interval,
                self._refresh_once,
                name="photon-refresher",
                jitter=policy.jitter,
            )
            self._config_refresher.start()
            # Keep the connection's FAS fresh in the background. Started only after bring-up
            # succeeds, so a failed bring-up still throws without leaving a thread behind.
            self._reauth_runner = Refresher(
                policy.auth_refresh_interval,
                self._reauth_once,
                name="photon-reauth",
                jitter=policy.jitter,
                failure_delay=policy.delay_for,
            )
            self._reauth_runner.start()
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
        if self._config_refresher:
            self._config_refresher.set_interval(config.refresh_interval)

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

    def _reauth_once(self) -> None:
        """Re-run AUTH on the live connector to refresh its FAS. Runs in the background.

        Sends plain ``AUTH`` (not the ``HELLO 3`` bring-up handshake): the connection has already
        negotiated RESP3, so a refresh only needs to re-authenticate with a fresh token.
        """
        connector = self._connection_manager.connector
        if connector is not None:
            connector.authenticate()

    def request(self, request: PhotonRequest) -> PhotonResponse | None:
        """Route a request to Photon, or return None to let the caller handle it.

        A request is passed through when it does not match any resolved predicate,
        or when the cache is disconnected and the fallback window is still open.
        In both cases, the request has not been sent, so falling back is safe.

        Once a request is sent to Photon, failures propagate to the hook. Direct
        origin fallback is limited to a dropped connection and a replay-safe
        operation. Retryable healthy-connection failures are returned through
        botocore so the SDK or application decides whether to retry them.

        Raises:
            PhotonCacheUnavailable: If the cache is disconnected and the fallback
                window has closed.
        """
        if not self.is_open:
            raise PhotonError("request() called on a closed session")
        if not should_route_to_photon(self._predicates, request):
            return None

        # Read the connector once; the reconnect thread may replace it concurrently.
        connector = self._connection_manager.connector
        if not connector:
            if not self._connection_manager.fallback_allowed():
                raise PhotonCacheUnavailable(endpoint_url=request.url)
            return     # pass through

        command, args = serialize_request(request)
        try:
            return parse_request_response(connector.execute(command, *args))
        except PhotonRejectedError as exc:
            if exc.code != _PHOTON_AUTH_ERROR:
                raise
            self._connection_manager.invalidate_connection(connector)
            return None

    def close(self) -> None:
        """Stop background work, then close the connection and all reconnect activity. Idempotent."""
        if self._reauth_runner:
            self._reauth_runner.stop()
        if self._config_refresher:
            self._config_refresher.stop()
        self._connection_manager.close()

    @property
    def is_open(self) -> bool:
        """Whether the session can safely initiate fallback work."""
        return self.state is not ConnectionState.CLOSED

    @property
    def is_connection_healthy(self) -> bool:
        """Whether requests can currently use a live configured connection."""
        return self._connection_manager.connection_healthy
