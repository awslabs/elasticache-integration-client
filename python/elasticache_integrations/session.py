"""ElastiCacheIntegrationsSession serves requests over a cache connection, and decides what to do when there is none.

The connection itself is owned by a :class:`~elasticache_integrations.connection_manager.ConnectionManager`, which
brings it up, rebuilds it when it is lost, and holds the fallback window open meanwhile. The session
supplies the ElastiCacheIntegrations half: the AUTH and config exchange each new connection needs, the predicates
cacheable, and the request path.

While no connection is available, requests are declined so the caller sends them to the origin
service itself — for as long as the fallback window allows.

A :class:`~elasticache_integrations.refresh.Refresher` runs for as long as the session is open, so the config
can be re-resolved on the live connection rather than only when a new one is built. A refresh that
fails leaves the current config in place. Once the staleness window passes with no config confirmed
by the server, the config is no longer trusted: the connection is dropped so a new one re-resolves
it, and requests fall back meanwhile.
"""

from __future__ import annotations

import logging
import threading
import time
from collections.abc import Callable

from elasticache_integrations.config_policy import ConfigPolicy
from elasticache_integrations.connection import ElastiCacheIntegrationsConnector
from elasticache_integrations.connection_manager import ConnectionManager, ConnectionState
from elasticache_integrations.exceptions import (
    ElastiCacheIntegrationsCacheUnavailable,
    ElastiCacheIntegrationsConfigError,
    ElastiCacheIntegrationsConfigUnevaluable,
    ElastiCacheIntegrationsConnectionError,
    ElastiCacheIntegrationsError,
    ElastiCacheIntegrationsOverridesRejected,
    ElastiCacheIntegrationsRejectedError,
)
from elasticache_integrations.models import (
    ElastiCacheIntegrationsRequest,
    ElastiCacheIntegrationsResponse,
    ResolvedConfig,
)
from elasticache_integrations.refresh import Refresher
from elasticache_integrations.reconnect_policy import ReconnectPolicy
from elasticache_integrations.predicates import (
    CompiledPredicate,
    compile_predicates,
    should_route_to_cache,
)
from elasticache_integrations.protocol import (
    parse_config_response,
    parse_request_response,
    serialize_config,
    serialize_request,
)

logger = logging.getLogger(__name__)

_AUTH_ERROR = "AUTH_ERROR"


def _config_failure_cause(exc: ElastiCacheIntegrationsError) -> str:
    """The cause of a config failure that cannot fix itself, worded the same on every path."""
    if isinstance(exc, ElastiCacheIntegrationsOverridesRejected):
        return "the proxy rejected the overrides (CONFIG_INVALID)"
    return "the config has a predicate this client cannot evaluate"


def parse_endpoint(endpoint: str) -> tuple[str, int]:
    """Parse a ``host:port`` endpoint into ``(host, port)``.

    Lives in the session layer (not the transport) so the transport carries no parsing logic.
    Uses ``rpartition(":")`` so IPv6/host forms with colons keep the final ``:port``.
    """
    if not endpoint or ":" not in endpoint:
        raise ElastiCacheIntegrationsConfigError(
            f"Invalid endpoint '{endpoint}': expected host:port"
        )
    host, _, port_str = endpoint.rpartition(":")
    if not host:
        raise ElastiCacheIntegrationsConfigError(
            f"Invalid endpoint '{endpoint}': host cannot be empty"
        )
    try:
        port = int(port_str)
    except ValueError as exc:
        raise ElastiCacheIntegrationsConfigError(f"Invalid port in endpoint '{endpoint}'") from exc
    if not (1 <= port <= 65535):
        raise ElastiCacheIntegrationsConfigError(
            f"Port out of range in endpoint '{endpoint}': {port}"
        )
    return host, port


class ElastiCacheIntegrationsSession:
    """Serves requests over the connection a :class:`ConnectionManager` keeps alive.

    Stays open until ``close()``.
    """

    def __init__(
        self,
        connector_factory: Callable[[], ElastiCacheIntegrationsConnector],
        overrides: dict | None = None,
        *,
        policy: ReconnectPolicy | None = None,
        config_policy: ConfigPolicy | None = None,
        close_callback: Callable[[], None] | None = None,
    ) -> None:
        """Create a session and open its connection, so it is ready to serve requests.

        Args:
            connector_factory: Returns a new, unconnected connector each time it is called.
            overrides: ``aws.config`` overrides to send when configuring. The proxy resolves
                them and answers with the config the session then routes by.
            policy: Reconnect and fallback behavior; see
                :class:`~elasticache_integrations.reconnect_policy.ReconnectPolicy`. ``None`` uses the defaults.
            config_policy: How long the resolved config stays trusted while refreshes are failing;
                see :class:`~elasticache_integrations.config_policy.ConfigPolicy`. ``None`` uses the defaults.
            close_callback: Optional owned-resource cleanup run once when the session closes.

        Raises:
            ElastiCacheIntegrationsError: The connection could not be opened; see
                :meth:`ConnectionManager.init_connection`.
                Creating a session fails loudly rather than handing back one that never caches.
            RuntimeError: The refresher thread could not be started. The connection is closed
                first, so a session that cannot refresh never leaves one open behind it.
        """
        policy = policy or ReconnectPolicy()
        self._overrides = overrides or {}
        # Both are set by _configure() during init_connection(), which raises if it fails.
        self._config: ResolvedConfig
        self._predicates: list[CompiledPredicate]
        self._config_policy = config_policy or ConfigPolicy()
        self._config_deadline = 0.0
        self._install_config_lock = (
            threading.Lock()
        )  # serializes checking a reply's connector and installing its config
        self._config_refresher: Refresher | None = None
        self._reauth_runner: Refresher | None = None
        self._close_callback = close_callback
        self._close_callback_called = False
        self._connection_manager = ConnectionManager(connector_factory, self._configure, policy)
        try:
            self._connection_manager.init_connection()
            self._config_refresher = Refresher(
                self._config.refresh_interval,
                self._refresh_once,
                name="elasticache-integrations-refresher",
                jitter=policy.jitter,
            )
            self._config_refresher.start()
            # Keep the connection's FAS fresh in the background. Started only after bring-up
            # succeeds, so a failed bring-up still throws without leaving a thread behind.
            self._reauth_runner = Refresher(
                policy.auth_refresh_interval,
                self._reauth_once,
                name="elasticache-integrations-reauth",
                jitter=policy.jitter,
                failure_delay=policy.delay_for,
            )
            self._reauth_runner.start()
        except BaseException:
            try:
                self.close()
            except Exception:
                logger.warning(
                    "ElastiCacheIntegrations session cleanup failed during initialization",
                    exc_info=True,
                )
            raise

    @property
    def state(self) -> ConnectionState:
        return self._connection_manager.state

    @property
    def config_stale(self) -> bool:
        """Whether the config has gone unconfirmed long enough to stop trusting it."""
        return time.monotonic() >= self._config_deadline

    def _configure(self, connector: ElastiCacheIntegrationsConnector) -> None:
        """Handshake with a freshly connected connector and resolve its config, making it usable.

        Called for the initial connection and for every reconnect, so each connection negotiates
        RESP3, authenticates with fresh credentials, and routes by the latest resolved config.

        Nothing is installed unless the whole exchange succeeds, so a configure that fails
        half-way leaves the session routing by the config it was already using.

        The connection manager owns the connector and closes it if this raises. A config failure
        that cannot fix itself is logged at ERROR on every attempt.
        """
        connector.handshake()
        command, args = serialize_config(self._overrides)
        try:
            config = parse_config_response(
                connector.execute(command, *args, timeout=connector.config_timeout)
            )
            installed = self._replace_config(config, connector)
        except (
            ElastiCacheIntegrationsOverridesRejected,
            ElastiCacheIntegrationsConfigUnevaluable,
        ) as exc:
            logger.error(
                "Configuring a connection failed: %s. The connection is closed and retried",
                _config_failure_cause(exc),
                exc_info=True,
            )
            raise
        if not installed:
            raise ElastiCacheIntegrationsConnectionError(
                "connection closed before its config could be installed"
            )

    def _replace_config(
        self, config: ResolvedConfig, source: ElastiCacheIntegrationsConnector
    ) -> bool:
        """Install a resolved config, the predicates compiled from it, and its refresh interval.

        Installs only while ``source`` (the connector the config came from) is still alive, under
        ``_install_config_lock``, so a reply from a replaced connection is dropped rather than
        overwriting its replacement's. Both stores are replaced outright, not mutated, and are not
        updated atomically together, but nothing reads both.

        Returns:
            False if ``source`` had closed and nothing was installed.
        """
        predicates = compile_predicates(config.raw)
        with self._install_config_lock:
            if not source.is_alive:
                return False
            self._config, self._predicates = config, predicates
            if self._config_refresher:
                self._config_refresher.set_interval(config.refresh_interval)
            self._restart_staleness_window()
        return True

    def _confirm_config(self, source: ElastiCacheIntegrationsConnector) -> bool:
        """Restart the staleness window on an "unchanged" reply, under the same rule as an install.

        Returns:
            False if ``source`` had closed and the window was left as it was.
        """
        with self._install_config_lock:
            if not source.is_alive:
                return False
            self._restart_staleness_window()
        return True

    def _restart_staleness_window(self) -> None:
        """Restart the staleness window after any confirmation from the server.

        Both a sent config and an empty "unchanged" reply prove the config in hand is current.
        Called with ``_install_config_lock`` held.
        """
        self._config_deadline = time.monotonic() + self._config_policy.staleness_duration

    def _refresh_once(self) -> None:
        """Send one no-argument ``aws.config`` refresh and apply the result.

        The overrides were sent when the connection was configured, so the server already has them.
        A null reply installs nothing; any other reply goes through ``_replace_config``. Either way
        the server confirmed the config, restarting the staleness window. Nothing is sent while no
        connection is available.

        A failed refresh keeps the current config until the staleness window passes, then drops
        the connection so a new one re-resolves it. A successful refresh never drops.
        """
        connector = self._connection_manager.connector
        if not connector:
            return
        command, args = serialize_config(None)
        try:
            reply = connector.execute(command, *args, timeout=connector.config_timeout)
            if reply is None:
                installed = self._confirm_config(connector)
            else:
                installed = self._replace_config(parse_config_response(reply), connector)
        except ElastiCacheIntegrationsError as exc:
            self._refresh_failed(exc, connector)
            return
        if not installed:
            logger.debug("Config refresh reply arrived after its connection closed; dropped")

    def _refresh_failed(
        self, exc: ElastiCacheIntegrationsError, connector: ElastiCacheIntegrationsConnector
    ) -> None:
        """Log a failed refresh, and drop ``connector`` once the staleness window has passed."""
        if isinstance(
            exc,
            (ElastiCacheIntegrationsOverridesRejected, ElastiCacheIntegrationsConfigUnevaluable),
        ):
            logger.error(
                "Config refresh failed: %s. Keeping the last good config until the staleness "
                "window drops the connection",
                _config_failure_cause(exc),
                exc_info=True,
            )
        else:
            logger.warning("Config refresh failed; keeping the current config", exc_info=True)
        if self.config_stale:
            logger.warning(
                "No config confirmed for %ss; dropping the connection so a new one resolves it",
                self._config_policy.staleness_duration,
            )
            self._connection_manager.invalidate_connection(connector)

    def _reauth_once(self) -> None:
        """Re-run AUTH on the live connector to refresh its FAS. Runs in the background.

        Sends plain ``AUTH`` (not the ``HELLO 3`` bring-up handshake): the connection has already
        negotiated RESP3, so a refresh only needs to re-authenticate with a fresh token.
        """
        connector = self._connection_manager.connector
        if connector is not None:
            connector.authenticate()

    def request(
        self, request: ElastiCacheIntegrationsRequest
    ) -> ElastiCacheIntegrationsResponse | None:
        """Route a request to ElastiCacheIntegrations, or return None to let the caller handle it.

        A request is passed through when it does not match any resolved predicate,
        or when the cache is disconnected and the fallback window is still open.
        In both cases, the request has not been sent, so falling back is safe.

        Right after a drop, a request with no connector waits a short, bounded time
        (``reconnect_timeout``) for the reconnect to land before falling back — so a quick
        reconnect is ridden out rather than sending the request to the origin.

        Once a request is sent to ElastiCacheIntegrations, failures propagate to the hook. For a
        replay-safe operation the hook returns them through botocore as retryable, so
        the SDK or application decides whether to retry them.

        Raises:
            ElastiCacheIntegrationsCacheUnavailable: If the cache is disconnected and the fallback
                window has closed.
        """
        if not self.is_open:
            raise ElastiCacheIntegrationsError("request() called on a closed session")
        if not should_route_to_cache(self._predicates, request):
            return None

        # Read the connector once; the reconnect thread may replace it concurrently.
        connector = self._connection_manager.connector
        if connector is None or not connector.is_alive:
            # No live connector: wait briefly for a reconnect before falling back
            connector = self._connection_manager.wait_for_connector()
        if connector is None:
            if not self._connection_manager.fallback_allowed():
                raise ElastiCacheIntegrationsCacheUnavailable(endpoint_url=request.url)
            return None  # pass through

        command, args = serialize_request(request)
        try:
            return parse_request_response(connector.execute(command, *args))
        except ElastiCacheIntegrationsRejectedError as exc:
            if exc.code != _AUTH_ERROR:
                raise
            self._connection_manager.invalidate_connection(connector)
            return None

    def close(self) -> None:
        """Stop background work, then close the connection and all reconnect activity. Idempotent."""
        try:
            if self._reauth_runner:
                self._reauth_runner.stop()
            if self._config_refresher:
                self._config_refresher.stop()
            self._connection_manager.close()
        finally:
            if not self._close_callback_called:
                self._close_callback_called = True
                if self._close_callback:
                    self._close_callback()

    @property
    def is_open(self) -> bool:
        """Whether the session can safely initiate fallback work."""
        return self.state is not ConnectionState.CLOSED

    @property
    def is_connection_healthy(self) -> bool:
        """Whether requests can currently use a live configured connection."""
        return self._connection_manager.connection_healthy
