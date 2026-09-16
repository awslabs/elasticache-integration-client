"""PhotonSession owns and holds the connection to the cache: it brings the connection up during init,
serves requests through it, and reconnects if the connection is lost.

A background thread monitors the connection and rebuilds it when needed. While a reconnect is in
progress and no connection is available, requests are declined so the caller sends them to the origin
service itself.
"""

from __future__ import annotations

import logging
import threading
import time
from collections.abc import Callable
from enum import Enum

from photon.connection import PhotonConnector
from photon.exceptions import PhotonCacheUnavailable, PhotonError
from photon.models import PhotonRequest, PhotonResponse
from photon.policy import ReconnectPolicy
from photon.predicates import compile_predicates, should_route_to_photon
from photon.protocol import (
    parse_config_response,
    parse_request_response,
    serialize_config,
    serialize_request,
)

logger = logging.getLogger(__name__)

_RECONNECT_JOIN_TIMEOUT = 2.0  # close() waits this long for the reconnect thread to stop
_DRAIN_POLL_INTERVAL = 0.005    # how often a graceful replacement re-checks the in-flight count


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


class SessionState(Enum):
    """Session lifecycle."""

    INIT = "init"                  # created, connection not open yet
    ALIVE = "alive"                # live connector; requests go to the cache
    RECONNECTING = "reconnecting"  # no live connector; requests fall back or escalate
    CLOSED = "closed"              # terminal; no further reconnects are started


class PhotonSession:
    """Owns the connection to the cache, and rebuilds it if needed. Stays open until ``close()``."""

    def __init__(
        self,
        connector_factory: Callable[[], PhotonConnector],
        overrides: dict | None = None,
        *,
        policy: ReconnectPolicy | None = None,
    ) -> None:
        """Create a session and open its connection, so it is ready to serve requests.

        Args:
            connector_factory: Returns a new, unconnected connector each time it is called. It has to
                be a new one every time — a connector cannot be reconnected, and reusing one would
                make it retry forever.
            overrides: ``photon.config`` overrides to send during the handshake. The proxy resolves
                them and answers with the config the session then routes by.
            policy: Reconnect and fallback behavior; see :class:`~photon.policy.ReconnectPolicy`.

        Raises:
            PhotonError: The connection could not be opened within ``policy.connect_attempts`` tries
                or ``policy.connection_timeout`` seconds, whichever comes first. Creating a session fails
                loudly rather than handing back one that never caches.
        """
        self._factory = connector_factory
        self._overrides = overrides or {}
        self._policy = policy or ReconnectPolicy()

        self._lock = threading.Lock()
        self._state = SessionState.INIT
        self._connector: PhotonConnector | None = None
        self._config = None
        self._predicates = None
        # monotonic deadline until which requests may pass through; only meaningful while RECONNECTING.
        self._fallback_deadline = 0.0

        self._closing = threading.Event()      # set by close(); also interrupts a backoff sleep
        self._reconnect_needed = threading.Event()  # "the connection needs rebuilding"
        self._reconnect_thread: threading.Thread | None = None

        deadline = time.monotonic() + self._policy.connection_timeout
        for attempt in range(1, self._policy.connect_attempts + 1):
            candidate = None
            try:
                candidate = self._factory()
                self._connect_and_configure(candidate)
                self._connector = candidate
                break
            except BaseException as exc:
                if candidate:
                    candidate.close()       # never leave a half-open connection behind
                delay = self._policy.delay_for(attempt)
                retryable = (
                    isinstance(exc, Exception)
                    and attempt < self._policy.connect_attempts
                    and time.monotonic() + delay < deadline
                )
                if not retryable:
                    self.close()
                    raise
                logger.debug("photon connect attempt %d failed (%s); retrying", attempt, exc)
                time.sleep(delay)
        self._state = SessionState.ALIVE
        self._reconnect_thread = threading.Thread(
            target=self._reconnect_loop, name="photon-reconnect", daemon=True
        )
        self._reconnect_thread.start()

    @property
    def state(self) -> SessionState:
        return self._state

    def _connect_and_configure(self, connector: PhotonConnector) -> None:
        """Open the connection and configure the Photon session.

        The same flow is used for the initial connection and for reconnects, ensuring each connection
        gets fresh credentials and the latest resolved config.

        The caller is responsible for closing the connector if any step fails.
        """
        # On disconnect - signal the reconnect thread
        connector.on_disconnect = self._reconnect_needed.set
        connector.connect()
        connector.authenticate()
        command, args = serialize_config(self._overrides)
        self._config = parse_config_response(
            connector.execute(command, *args, timeout=connector.config_timeout)
        )
        # Compile the resolved predicates once; the request path evaluates them per request.
        self._predicates = compile_predicates(self._config.raw)


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

        # Capture the connector once; the reconnect thread may replace it concurrently.
        connector = self._connector
        if not connector:
            self._ensure_fallback_allowed(request)
            return     # pass through

        command, args = serialize_request(request)
        return parse_request_response(connector.execute(command, *args))

    def _ensure_fallback_allowed(self, request: PhotonRequest) -> None:
        """Returns None if fallback is allowed; raise if the fallback window has closed."""
        if time.monotonic() < self._fallback_deadline:
            return
        raise PhotonCacheUnavailable(endpoint_url=request.url)

    def _reconnect_loop(self) -> None:
        """Wait for a reconnect signal and rebuild the connection.

        Runs on a dedicated thread until ``close()``. All connection replacements happen on this thread,
        ensuring that only one reconnect can run at a time.
        """
        while True:
            self._reconnect_needed.wait()
            self._reconnect_needed.clear()
            if self._closing.is_set():
                return
            try:
                if self._begin_reconnect():
                    self._reconnect_forever()
            except Exception:
                logger.exception("unexpected error in the photon reconnect loop")
                if self._closing.wait(self._policy.max_delay):
                    return
                self._reconnect_needed.set()

    def _begin_reconnect(self) -> bool:
        """Begin reconnecting if the session's current connector is unavailable.

        The signal only tells us that a connector reported that it closed, so the
        session's state is checked here before starting a reconnect.

        Returns:
            True: The caller should proceed with rebuilding the connection.
            False: The signal is stale or the session is closed; nothing to rebuild.
        """
        with self._lock:
            if self._state is SessionState.CLOSED:
                return False
            if self._state is SessionState.RECONNECTING:
                return True
            connector = self._connector
            if connector and connector.is_alive:
                # A previous connector may report its close after it was replaced.
                # The current connector is still healthy, so this signal can be ignored.
                return False
            # Open the window before clearing the connector. ``request()`` reads both without
            # the lock, so a request that sees no connector must already see a fresh deadline.
            self._fallback_deadline = time.monotonic() + self._policy.fallback_window
            self._connector = None
            self._state = SessionState.RECONNECTING
        logger.warning(
            "photon connection lost; reconnecting in the background, requests fall back to the "
            "origin service meanwhile"
        )
        if connector:
            connector.close()
        return True

    def _reconnect_forever(self) -> None:
        """Keep trying to establish a connection until one succeeds or the session closes.

        No attempt cap: giving up would leave the session permanently uncached while fallback stays
        permanently on, which is invisible to the application. The delay grows to a ceiling and stays
        there, so a long outage settles into a steady low-rate probe rather than stopping.
        """
        attempt = 0
        while not self._closing.is_set():
            attempt += 1
            candidate = None
            try:
                candidate = self._factory()
                self._connect_and_configure(candidate)
            except Exception as exc:
                if candidate:
                    candidate.close()
                delay = self._policy.delay_for(attempt)
                logger.debug("photon reconnect attempt %d failed (%s); retrying in %.3fs",
                             attempt, exc, delay)
                if self._closing.wait(delay):
                    return # close() happened during the backoff
                continue

            if self._finish_reconnect(candidate, attempt):
                return   # published, session is ALIVE
            candidate.close()
            return

    def _finish_reconnect(self, candidate: PhotonConnector, attempt: int) -> bool:
        """Publish a successfully configured connector.

        Returns ``True`` when the candidate is published, or ``False`` if the
        session closed while it was being built. On ``False``, the caller owns
        the candidate and must close it.
        """
        with self._lock:
            if self._state is SessionState.CLOSED or self._closing.is_set():
                return False
            self._connector = candidate
            self._state = SessionState.ALIVE
        logger.info("photon connection re-established after %d attempt(s)", attempt)
        return True

    def _replace_connection(self, *, drain: bool = True) -> None:
        """Replace the current connection through the normal reconnect path.

        The current connection is removed and closed, then the reconnect thread is
        signaled to build its replacement. This keeps intentional replacements on
        the same single reconnect path as unexpected disconnects

        With ``drain=True``, new requests are quiesced and in-flight requests are
        given up to ``drain_timeout`` to finish before the connection is closed.
        This allows a graceful replacement without failing in-flight requests.

        Safe to call concurrently and during shutdown; both collapse into no-ops.
        """
        with self._lock:
            if self._state is not SessionState.ALIVE:
                return      # closed, still starting, or already reconnecting
            connector = self._connector
            self._fallback_deadline = time.monotonic() + self._policy.fallback_window
            self._connector = None
            self._state = SessionState.RECONNECTING
        logger.info("photon replacing its connection")

        if connector:
            if drain:
                self._drain(connector)
            connector.close()
        self._reconnect_needed.set()

    def _drain(self, connector: PhotonConnector) -> None:
        """Wait for in-flight requests to finish, up to ``drain_timeout``.

        Requests that already captured the connector may still arrive while it is
        draining. They either finish within the window or fail when the connector
        is closed and follow the normal reactive path.
        """
        deadline = time.monotonic() + self._policy.drain_timeout
        while connector.pending_count and time.monotonic() < deadline:
            time.sleep(_DRAIN_POLL_INTERVAL)
        if connector.pending_count:
            logger.warning(
                "photon drained for %.3fs with %d request(s) still in flight; they will fail",
                self._policy.drain_timeout, connector.pending_count,
            )

    def close(self) -> None:
        """Close the session and stop all reconnect activity. Idempotent."""
        with self._lock:
            self._state = SessionState.CLOSED
            connector = self._connector
        self._closing.set()
        self._reconnect_needed.set()     # wake the reconnect thread so it can exit

        thread = self._reconnect_thread
        if thread and thread is not threading.current_thread():
            thread.join(timeout=_RECONNECT_JOIN_TIMEOUT)
            if thread.is_alive():
                logger.warning("photon reconnect thread did not stop within %ss",
                               _RECONNECT_JOIN_TIMEOUT)

        if connector:
            connector.close()
