"""Owns the connection to the cache for its whole life: brings it up, replaces it, gives it up.

:class:`ConnectionManager` keeps exactly one live connector. It opens the first one, hands each new
one to an injected ``configure`` callback, watches for the connection ending,
and builds a replacement on a background thread. It knows nothing about Photon's commands,
predicates, or responses — that all stays in :mod:`photon.session`.

While a replacement is being built there is no connector to serve with, so the manager opens a
fallback window: for as long as it lasts, the session may decline requests and let the caller send
them to the origin service itself.
"""

from __future__ import annotations

import logging
import threading
import time
from collections.abc import Callable
from concurrent.futures import Future
from concurrent.futures import TimeoutError as FuturesTimeout
from enum import Enum

from photon.connection import PhotonConnector
from photon.exceptions import PhotonConnectionError
from photon.reconnect_policy import ReconnectPolicy

logger = logging.getLogger(__name__)

_RECONNECT_JOIN_TIMEOUT = 2.0  # close() waits this long for the reconnect thread to stop
_CONNECT_THREAD = "photon-connect"


def _settle(outcome: Future, run: Callable[..., None], *args) -> None:
    """Run ``run(*args)`` and hand whatever it does to ``outcome``, for another thread to wait on."""
    try:
        run(*args)
    except BaseException as exc:
        outcome.set_exception(exc)   # re-raised on the waiting thread, type intact
    else:
        outcome.set_result(None)


class ConnectionState(Enum):
    """Lifecycle of the connection the manager owns."""

    INIT = "init"                  # created, connection not open yet
    ALIVE = "alive"                # live connector; requests go to the cache
    RECONNECTING = "reconnecting"  # no live connector; requests fall back or escalate
    CLOSED = "closed"              # terminal; no further reconnects are started


class ConnectionManager:
    """Owns the live connector: brings it up, watches it, and rebuilds it when it dies."""

    def __init__(
        self,
        connector_factory: Callable[[], PhotonConnector],
        configure: Callable[[PhotonConnector], None],
        policy: ReconnectPolicy,
    ) -> None:
        """Create a connection manager. Nothing is connected until :meth:`init_connection`.

        Args:
            connector_factory: Returns a new, unconnected connector each time it is called. It has
                to be a new one every time — a connector cannot be reconnected, and reusing one
                would make it retry forever.
            configure: Called with each freshly connected connector to make it usable — AUTH and
                the ``photon.config`` exchange. Anything it raises fails that attempt, and the
                connector is closed.
            policy: Reconnect and fallback behavior; see
                :class:`~photon.reconnect_policy.ReconnectPolicy`.
        """
        self._factory = connector_factory
        self._configure = configure
        self._policy = policy

        # Guards which connection is installed and what state we are in: _state,
        # _connector, _candidate and _fallback_deadline all move under it.
        self._state_lock = threading.Lock()
        self._state = ConnectionState.INIT
        self._connector: PhotonConnector | None = None
        self._candidate: PhotonConnector | None = None  # being brought up, not serving yet
        # monotonic deadline until which requests may pass through; only meaningful while RECONNECTING.
        self._fallback_deadline = 0.0

        self._closing = threading.Event()      # set by close(); also interrupts a backoff sleep
        self._reconnect_needed = threading.Event()  # "the connection needs rebuilding"
        self._reconnect_thread: threading.Thread | None = None

    @property
    def state(self) -> ConnectionState:
        return self._state

    @property
    def policy(self) -> ReconnectPolicy:
        """The reconnect and fallback settings in force. Frozen, so callers cannot change them."""
        return self._policy

    @property
    def connector(self) -> PhotonConnector | None:
        """The live connector, or ``None`` while there is none.

        Read once per request and then used: the reconnect thread may replace it concurrently.
        """
        return self._connector

    def fallback_allowed(self) -> bool:
        """Whether a request may still be passed through while there is no connector."""
        return time.monotonic() < self._fallback_deadline

    @property
    def connection_healthy(self) -> bool:
        """Whether a live configured connector is currently available."""
        connector = self._connector
        return (
            self._state is ConnectionState.ALIVE
            and connector is not None
            and connector.is_alive
        )

    def init_connection(self) -> None:
        """Open the first connection, then watch it on a background thread.

        Bounded, unlike a reconnect: at most ``policy.connect_attempts`` attempts, each one capped
        by ``policy.connection_timeout``, so the caller's startup path cannot hang.

        Raises:
            PhotonError: No attempt succeeded. Starting fails loudly rather than handing back a
                manager that never caches. Whatever the last attempt raised propagates unchanged,
                so a ``configure`` that raises something else surfaces that instead.
        """
        for attempt in range(1, self._policy.connect_attempts + 1):
            try:
                self._connector = self._open_connection(self._policy.connection_timeout)
                break
            except BaseException as exc:
                # Not an Exception (KeyboardInterrupt, SystemExit): never retried.
                if not isinstance(exc, Exception) or attempt == self._policy.connect_attempts:
                    self.close()
                    raise
                logger.debug("photon connect attempt %d failed (%s); retrying", attempt, exc)
                time.sleep(self._policy.delay_for(attempt))
        self._state = ConnectionState.ALIVE
        self._reconnect_thread = threading.Thread(
            target=self._reconnect_loop, name="photon-reconnect", daemon=True
        )
        self._reconnect_thread.start()

    def _connect_and_configure(self, connector: PhotonConnector) -> None:
        """Open ``connector``'s transport and configure it, closing it if either step fails.

        Every connection goes through here, so each one gets fresh credentials and the latest
        resolved config.
        """
        try:
            connector.connect()
            self._configure(connector)
        except BaseException:
            connector.close()
            raise

    def _open_connection(self, timeout: float | None = None) -> PhotonConnector:
        """Build a connection and return it live: connected, authenticated, and configured.

        Without a ``timeout`` this runs here and blocks for as long as the connector's own
        step timeouts allow, which suits the reconnect thread — waiting is that thread's whole job.

        With one, the work moves to a thread of its own so this one can stop waiting for it,
        because the application's thread creating the session must not hang on a peer that accepts
        the connection and then goes quiet. The connector stays with this thread either way, so
        giving up can close it — which is also what stops that thread rather than leaving it to run
        on unwatched.

        Either returns a usable connection or raises, having closed the one it was building, so the
        caller never has a half-open connector to clean up.
        """
        connector = self._factory()
        # On disconnect - signal the reconnect thread
        connector.on_disconnect = self._reconnect_needed.set
        with self._state_lock:
            if self._state is ConnectionState.CLOSED:
                connector.close()
                raise PhotonConnectionError("connection manager is closed")
            # Reachable by close() until it is published: no step below watches for a close, so
            # closing the connection under one is what ends it early.
            self._candidate = connector
        try:
            if timeout is None:
                self._connect_and_configure(connector)
            else:
                outcome: Future = Future()
                threading.Thread(target=_settle,
                                 args=(outcome, self._connect_and_configure, connector),
                                 name=_CONNECT_THREAD, daemon=True).start()
                try:
                    outcome.result(timeout=timeout)
                except FuturesTimeout as exc:
                    connector.close()
                    raise PhotonConnectionError(
                        f"opening a connection timed out after {timeout}s"
                    ) from exc
        finally:
            with self._state_lock:
                self._candidate = None
        return connector

    def _reconnect_loop(self) -> None:
        """Wait for a reconnect signal and rebuild the connection.

        Runs on a dedicated thread until ``close()``. All connection replacements happen on this thread,
        ensuring that only one reconnect can run at a time.
        """
        while True:
            self._reconnect_needed.wait() # sleeps until a reconnect was triggered
            # Cleared before the check, never after the rebuild: a connector that dies mid-rebuild
            # must leave the flag set, and it only ever notifies once.
            self._reconnect_needed.clear()
            if self._closing.is_set():   # close() sets this too, to wake us up so we can exit
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
        """Begin reconnecting if the current connector is unavailable.

        The signal only tells us that a connector reported that it closed, so the
        state is checked here before starting a reconnect.

        Returns:
            True: The caller should proceed with rebuilding the connection.
            False: The signal is stale or we are closed; nothing to rebuild.
        """
        with self._state_lock:
            if self._state is ConnectionState.CLOSED:
                return False
            if self._state is ConnectionState.RECONNECTING:
                return True
            current_connector = self._connector
            if current_connector and current_connector.is_alive:
                # A previous connector may report its close after it was replaced.
                # The current connector is still healthy, so this signal can be ignored.
                return False
            # Open the window before clearing the connector. A request reads both without
            # the lock, so a request that sees no connector must already see a fresh deadline.
            self._fallback_deadline = time.monotonic() + self._policy.fallback_window
            self._connector = None
            self._state = ConnectionState.RECONNECTING
        logger.warning(
            "photon connection lost; reconnecting in the background, requests fall back to the "
            "origin service meanwhile"
        )
        if current_connector:
            current_connector.close()
        return True

    def _reconnect_forever(self) -> None:
        """Keep trying to establish a connection until one succeeds or we close.

        No attempt cap: giving up would leave the session permanently uncached while fallback stays
        permanently on, which is invisible to the application. The delay grows to a ceiling and stays
        there, so a long outage settles into a steady low-rate probe rather than stopping.
        """
        attempt = 0
        while not self._closing.is_set():
            attempt += 1
            try:
                candidate = self._open_connection()
            except Exception as exc:
                delay = self._policy.delay_for(attempt)
                logger.debug("photon reconnect attempt %d failed (%s); retrying in %.3fs",
                             attempt, exc, delay)
                if self._closing.wait(delay):
                    return # close() happened during the backoff
                continue

            if self._finish_reconnect(candidate, attempt):
                return   # published, the connection is ALIVE
            candidate.close()   # brought up but never published: ours to close, unlike a failed one
            return

    def _finish_reconnect(self, candidate: PhotonConnector, attempt: int) -> bool:
        """Publish a successfully brought-up connector.

        Returns ``True`` when the candidate is published, or ``False`` if we closed
        while it was being built. On ``False``, the caller owns the candidate and
        must close it.
        """
        with self._state_lock:
            if self._state is ConnectionState.CLOSED or self._closing.is_set():
                return False
            self._connector = candidate
            self._state = ConnectionState.ALIVE
        logger.info("photon connection re-established after %d attempt(s)", attempt)
        return True

    def close(self) -> None:
        """Close the connection and stop all reconnect activity. Idempotent."""
        with self._state_lock:
            self._state = ConnectionState.CLOSED
            connector, candidate = self._connector, self._candidate
        self._closing.set()
        self._reconnect_needed.set()     # wake the reconnect thread so it can exit
        if candidate:
            # A candidate still being opened blocks on the network and never looks at _closing, so
            # closing it is what frees the reconnect thread. Without this, the join below waits out
            # whichever step it was in.
            candidate.close()

        thread = self._reconnect_thread
        if thread and thread is not threading.current_thread():
            thread.join(timeout=_RECONNECT_JOIN_TIMEOUT)
            if thread.is_alive():
                logger.warning("photon reconnect thread did not stop within %ss",
                               _RECONNECT_JOIN_TIMEOUT)

        if connector:
            connector.close()
