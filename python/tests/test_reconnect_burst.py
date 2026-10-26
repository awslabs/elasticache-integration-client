"""Tests for the reconnect hold (CR-N3): a request with no connector waits a bounded time for a
quick reconnect before falling back to the origin.

Deterministic — no reconnect-thread timing: the session request path and the manager's
wait_for_connector are driven directly.
"""

import threading
import time
from unittest.mock import MagicMock

import pytest

import aws_elasticache_integration_client.session as session_mod
from aws_elasticache_integration_client.connection_manager import ConnectionManager, ConnectionState
from aws_elasticache_integration_client.exceptions import (
    ElastiCacheIntegrationsCacheUnavailable,
    ElastiCacheIntegrationsConnectionLost,
)
from aws_elasticache_integration_client.models import (
    ElastiCacheIntegrationsRequest,
    RequestMetadata,
)
from aws_elasticache_integration_client.reconnect_policy import ReconnectPolicy
from aws_elasticache_integration_client.session import ElastiCacheIntegrationsSession
from tests.mock import mock_connector


def _req() -> ElastiCacheIntegrationsRequest:
    return ElastiCacheIntegrationsRequest(
        method="POST",
        url="https://dynamodb/",
        headers={},
        body=b"{}",
        metadata=RequestMetadata(service="dynamodb", operation="GetItem"),
    )


def _session(monkeypatch, **policy_kwargs) -> ElastiCacheIntegrationsSession:
    conn = mock_connector()
    monkeypatch.setattr(session_mod, "should_route_to_cache", lambda predicates, request: True)
    return ElastiCacheIntegrationsSession(lambda: conn, policy=ReconnectPolicy(**policy_kwargs))


# --- session request routing while there is no connector -------------------------------------


def test_request_waits_then_passes_through_when_window_open(monkeypatch):
    # No reconnect arrives within connection_timeout, and the fallback window is open -> pass through.
    session = _session(monkeypatch, connection_timeout=0.05, fallback_duration=None)
    try:
        cm = session._connection_manager
        cm._connector = None
        cm._connected.clear()
        cm._ready_deadline = time.monotonic() + 0.05
        cm._fallback_deadline = time.monotonic() + 1000
        started = time.monotonic()
        assert session.request(_req()) is None  # fell back after the wait
        assert time.monotonic() - started >= 0.05  # it actually waited
    finally:
        session.close()


def test_request_waits_then_escalates_when_window_spent(monkeypatch):
    # No reconnect, and the window is spent (or strict) -> cache unavailable after the wait.
    session = _session(monkeypatch, connection_timeout=0.05, fallback_duration=0)
    try:
        cm = session._connection_manager
        cm._connector = None
        cm._connected.clear()
        cm._ready_deadline = time.monotonic() + 0.05
        cm._fallback_deadline = 0.0
        with pytest.raises(ElastiCacheIntegrationsCacheUnavailable):
            session.request(_req())
    finally:
        session.close()


def test_request_served_when_reconnect_lands_within_the_wait(monkeypatch):
    # A reconnect arrives during the wait: the request is served from the cache, not fallen back.
    session = _session(monkeypatch, connection_timeout=1.0, fallback_duration=0)
    try:
        cm = session._connection_manager
        reconnected = cm._connector  # the live mock connector from bring-up
        cm._connector = None
        cm._connected.clear()
        cm._ready_deadline = time.monotonic() + 1.0

        def reconnect_soon():
            time.sleep(0.05)
            cm._connector = reconnected
            cm._connected.set()

        threading.Thread(target=reconnect_soon, daemon=True).start()
        assert session.request(_req()) is not None  # served, since the connector came back
    finally:
        session.close()


def _write() -> ElastiCacheIntegrationsRequest:
    return ElastiCacheIntegrationsRequest(
        method="POST",
        url="https://dynamodb/",
        headers={},
        body=b"{}",
        metadata=RequestMetadata(service="dynamodb", operation="PutItem"),
    )


def test_request_on_a_dead_connector_waits_for_the_reconnect(monkeypatch):
    # The connector died but the reconnect thread has not taken it down yet. A write must not be
    # sent on it and fail: it waits for the reconnect and is served on the new connector.
    session = _session(monkeypatch, connection_timeout=2.0)
    try:
        cm = session._connection_manager
        dead = mock_connector()
        dead.is_alive = False
        cm._connector = dead
        assert session.request(_write()) is not None
        dead.execute.assert_not_called()
        assert cm.connector is not dead
    finally:
        session.close()


def test_retry_after_an_in_flight_loss_waits_for_the_reconnect(monkeypatch):
    # The loss goes up for the SDK to retry. The retry finds the dead connector, removes it,
    # and is served on the new connector instead of failing again.
    monkeypatch.setattr(session_mod, "should_route_to_cache", lambda predicates, request: True)
    session = ElastiCacheIntegrationsSession(
        mock_connector, policy=ReconnectPolicy(connection_timeout=2.0)
    )
    try:
        cm = session._connection_manager
        dying = cm.connector

        def lose(*args, **kwargs):
            dying.is_alive = False
            raise ElastiCacheIntegrationsConnectionLost("socket died")

        dying.execute.side_effect = lose
        with pytest.raises(ElastiCacheIntegrationsConnectionLost):
            session.request(_req())
        assert session.request(_req()) is not None
        assert cm.connector is not dying
    finally:
        session.close()


# --- ConnectionManager wait_for_connector ----------------------------------------------------


def _manager() -> ConnectionManager:
    return ConnectionManager(lambda: MagicMock(), lambda c: None, ReconnectPolicy())


def test_wait_for_connector_returns_immediately_when_present():
    cm = _manager()
    conn = MagicMock()
    cm._connector = conn
    assert cm.wait_for_connector() is conn


def test_wait_for_connector_times_out_to_none():
    cm = _manager()
    cm._connector = None
    cm._connected.clear()
    cm._ready_deadline = time.monotonic() + 0.05
    started = time.monotonic()
    assert cm.wait_for_connector() is None
    assert time.monotonic() - started >= 0.05


def test_wait_for_connector_does_not_wait_after_the_hold():
    # A long outage: the hold set at the drop has passed, so a request falls back at once.
    cm = _manager()
    cm._connector = None
    cm._connected.clear()
    cm._ready_deadline = time.monotonic() - 1.0
    started = time.monotonic()
    assert cm.wait_for_connector() is None
    assert time.monotonic() - started < 0.05


def test_strict_mode_does_not_allow_fallback_before_ready_deadline():
    policy = ReconnectPolicy(connection_timeout=1.0, fallback_duration=0)
    cm = ConnectionManager(lambda: MagicMock(), lambda c: None, policy)
    cm._ready_deadline = time.monotonic() + 1.0
    cm._fallback_deadline = cm._ready_deadline

    assert not cm.fallback_allowed()


def test_wait_for_connector_takes_a_dead_connector_out_of_service():
    cm = ConnectionManager(
        lambda: MagicMock(), lambda c: None, ReconnectPolicy(connection_timeout=0.05)
    )
    dead = MagicMock()
    dead.is_alive = False
    cm._connector = dead
    cm._state = ConnectionState.ALIVE
    cm._connected.set()
    assert cm.wait_for_connector() is None
    assert cm.connector is None
    assert cm.state is ConnectionState.RECONNECTING
    dead.close.assert_called_once()


def test_fallback_window_starts_when_the_wait_ends():
    # A connection_timeout longer than fallback_duration must still leave the full fallback window.
    policy = ReconnectPolicy(connection_timeout=5.0, fallback_duration=1)
    cm = ConnectionManager(lambda: MagicMock(), lambda c: None, policy)
    conn = MagicMock()
    cm._connector = conn
    cm._state = ConnectionState.ALIVE
    cm.invalidate_connection(conn)
    assert cm._fallback_deadline == cm._ready_deadline + 1


def test_invalidate_connection_starts_a_hold():
    # Dropping a connection on AUTH_ERROR clears the connected flag and opens a hold, like a
    # lost connection does, so the next request waits briefly for the reconnect.
    cm = ConnectionManager(
        lambda: MagicMock(), lambda c: None, ReconnectPolicy(connection_timeout=0.5)
    )
    conn = MagicMock()
    cm._connector = conn
    cm._state = ConnectionState.ALIVE
    cm._connected.set()
    cm.invalidate_connection(conn)
    assert not cm._connected.is_set()
    assert cm._ready_deadline > time.monotonic()
