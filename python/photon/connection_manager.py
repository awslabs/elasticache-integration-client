"""Owns the connection to the cache for its whole life: brings it up, replaces it, gives it up.

:class:`ConnectionManager` keeps exactly one live connector. It opens the first one, hands each new
one to an injected ``bring_up`` callback for the Photon handshake, watches for the connection ending,
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
from photon.policy import ReconnectPolicy

logger = logging.getLogger(__name__)

_RECONNECT_JOIN_TIMEOUT = 2.0  # close() waits this long for the reconnect thread to stop
_BRING_UP_THREAD = "photon-bring-up"


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
        bring_up: Callable[[PhotonConnector], None],
        policy: ReconnectPolicy,
    ) -> None:
        """Create a connection manager. Nothing is connected until :meth:`start`.

        Args:
            connector_factory: Returns a new, unconnected connector each time it is called. It has
                to be a new one every time — a connector cannot be reconnected, and reusing one
                would make it retry forever.
            bring_up: Called with each freshly connected connector to make it usable (the Photon
                handshake). Anything it raises fails that attempt, and the connector is closed.
            policy: Reconnect and fallback behavior; see :class:`~photon.policy.ReconnectPolicy`.
        """
        self._factory = connector_factory
        self._bring_up = bring_up
        self._policy = policy

        self._lock = threading.Lock()
        self._state = ConnectionState.INIT
        self._connector: PhotonConnector | None = None
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

    def start(self) -> None:
        """Open the first connection, then watch it on a background thread.

        Bounded, unlike a reconnect: at most ``policy.connect_attempts`` attempts, each one capped
        by ``policy.connection_timeout``, so the caller's startup path cannot hang.

        Raises:
            PhotonError: No attempt succeeded. Starting fails loudly rather than handing back a
                manager that never caches. Whatever the last attempt raised propagates unchanged,
                so a ``bring_up`` that raises something else surfaces that instead.
        """
        for attempt in range(1, self._policy.connect_attempts + 1):
            try:
                self._connector = self._open_connection_within(
                    self._policy.connection_timeout
                )
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

    def _new_connector(self) -> PhotonConnector:
        """A fresh, still unconnected connector, wired to signal us when its connection ends."""
        connector = self._factory()
        # On disconnect - signal the reconnect thread
        connector.on_disconnect = self._reconnect_needed.set
        return connector

    def _bring_up_connection(self, connector: PhotonConnector) -> None:
        """Connect ``connector`` and hand it the Photon handshake, closing it if either fails.

        Every connection is opened this way, so each one gets fresh credentials and the latest
        resolved config from ``bring_up``.
        """
        try:
            connector.connect()
            self._bring_up(connector)
        except BaseException:
            connector.close()
            raise

    def _open_connection(self) -> PhotonConnector:
        """Build a connection and return it live: connected, authenticated, and configured.

        Blocks for however long the connector's own step timeouts allow, which suits the reconnect
        thread — waiting is that thread's whole job. A caller that cannot be left waiting wants
        :meth:`_open_connection_within` instead.

        Either returns a usable connection or raises, having closed the one it was building, so the
        caller never has a half-open connector to clean up.
        """
        connector = self._new_connector()
        self._bring_up_connection(connector)
        return connector

    def _open_connection_within(self, timeout: float) -> PhotonConnector:
        """:meth:`_open_connection`, given up on after ``timeout`` seconds.

        For the application's own thread, which is creating the session and must not be left waiting
        on a peer that accepts the connection and then goes quiet. Only the bring-up moves to a
        thread of its own; this one keeps the connector, so giving up can close it — which is also
        what stops that thread instead of leaving it to run on unwatched.
        """
        connector = self._new_connector()
        outcome: Future = Future()
        threading.Thread(target=_settle, args=(outcome, self._bring_up_connection, connector),
                         name=_BRING_UP_THREAD, daemon=True).start()
        try:
            outcome.result(timeout=timeout)
        except FuturesTimeout as exc:
            connector.close()
            raise PhotonConnectionError(f"connection bring-up timed out after {timeout}s") from exc
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
        with self._lock:
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
        with self._lock:
            if self._state is ConnectionState.CLOSED or self._closing.is_set():
                return False
            self._connector = candidate
            self._state = ConnectionState.ALIVE
        logger.info("photon connection re-established after %d attempt(s)", attempt)
        return True

    def close(self) -> None:
        """Close the connection and stop all reconnect activity. Idempotent."""
        with self._lock:
            self._state = ConnectionState.CLOSED
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
