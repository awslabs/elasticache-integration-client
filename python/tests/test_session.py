"""Tests for elasticache_integrations.session.

The connector double comes from :func:`tests.mock.mock_connector` — a ``create_autospec`` bound to
the real :class:`ElastiCacheIntegrationsConnector`, so the session is verified against the real connector signatures
without a network. Construction starts real :class:`~elasticache_integrations.refresh.Refresher` daemon
thread (the connector is a mock, but the refresher is not), so any test that constructs a real
``ElastiCacheIntegrationsSession`` and reaches the refresher must call ``session.close()`` to avoid leaking it.

Command building/normalization details are protocol.py's contract and are covered in
test_protocol.py; these tests assert only session behavior (lifecycle + delegation).
"""

import json
import time
from unittest import mock

import pytest

from elasticache_integrations import session as session_module
from elasticache_integrations.config_policy import ConfigPolicy
from elasticache_integrations.exceptions import (
    ElastiCacheIntegrationsConfigError,
    ElastiCacheIntegrationsOverridesRejected,
    ElastiCacheIntegrationsConfigUnevaluable,
    ElastiCacheIntegrationsConnectionError,
    ElastiCacheIntegrationsError,
)
from elasticache_integrations.models import (
    ElastiCacheIntegrationsRequest,
    ElastiCacheIntegrationsResponse,
    RequestMetadata,
)
from elasticache_integrations.reconnect_policy import ReconnectPolicy
from elasticache_integrations.refresh import Refresher
from elasticache_integrations.resp import RespError
from elasticache_integrations.session import ElastiCacheIntegrationsSession, parse_endpoint
from tests.mock import mock_connector

# Short enough to read, long enough that only a deliberate advance reaches it.
STALENESS_WINDOW = 10


class _FakeClock:
    """A monotonic clock the test moves by hand, to reach a deadline without waiting for it.

    Substituted for the ``time`` module inside :mod:`elasticache_integrations.session`, which reads ``monotonic()``
    to set the staleness deadline and to check it.
    """

    def __init__(self) -> None:
        self.now = time.monotonic()

    def monotonic(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


@pytest.fixture
def clock(monkeypatch):
    """Install a hand-moved clock on the session module before any session is built.

    Installed up front so construction's own deadline is stamped from the same clock the test
    later advances; a session built against the real clock could never be made stale.
    """
    fake = _FakeClock()
    monkeypatch.setattr(session_module, "time", fake)
    return fake


def stale_session(connector) -> ElastiCacheIntegrationsSession:
    """A session whose config stops being trusted ``STALENESS_WINDOW`` seconds after a refresh."""
    return ElastiCacheIntegrationsSession(
        lambda: connector, config_policy=ConfigPolicy(staleness_duration=STALENESS_WINDOW)
    )


# --- lifecycle: opening the connection --------------------------------------------------------


def test_connect_and_configure_order():
    """Construction drives connect(), handshake(), then the aws.config exchange."""
    connector = mock_connector()
    session = ElastiCacheIntegrationsSession(lambda: connector, overrides={"flag": True})
    session.close()
    assert [c[0] for c in connector.method_calls][:3] == [
        "connect",
        "handshake",
        "execute",
    ]
    connector.execute.assert_called_once_with(
        "aws.config",
        b'{"version":"1.0","flag":true}',
        timeout=connector.config_timeout,
    )


def test_resolved_config_stored_on_construction():
    """The resolved config is stored by the time construction returns."""
    connector = mock_connector()
    session = ElastiCacheIntegrationsSession(lambda: connector)
    assert session._config is not None
    assert session._config.version == "1.0"
    assert session._config.refresh_interval == 60
    session.close()


@pytest.mark.parametrize("failing_step", ["handshake", "execute"])
def test_a_failed_configure_closes_everything(failing_step):
    """If any configure step after connect() raises, construction closes the connector
    (no leaked connection) and the session object never exists."""
    connector = mock_connector()
    getattr(connector, failing_step).side_effect = ElastiCacheIntegrationsError(
        f"{failing_step} failed"
    )
    # One attempt, so the assertion is about closing rather than about how often we retry.
    with pytest.raises(ElastiCacheIntegrationsError):
        ElastiCacheIntegrationsSession(
            lambda: connector, policy=ReconnectPolicy(connection_timeout=0.05)
        )
    connector.close.assert_called_once_with()


def test_failed_initialization_releases_owned_resource():
    connector = mock_connector()
    connector.handshake.side_effect = ElastiCacheIntegrationsConnectionError("rejected")
    close_callback = mock.Mock()

    with pytest.raises(
        ElastiCacheIntegrationsConnectionError,
        match="opening a connection timed out after 0.05s",
    ) as exc_info:
        ElastiCacheIntegrationsSession(
            lambda: connector,
            policy=ReconnectPolicy(connection_timeout=0.05),
            close_callback=close_callback,
        )

    assert str(exc_info.value.__cause__) == "rejected"
    close_callback.assert_called_once()


def test_failed_initialization_logs_cleanup_failure_without_masking_cause(caplog):
    connector = mock_connector()
    connector.handshake.side_effect = ElastiCacheIntegrationsConnectionError("bring-up rejected")

    with (
        mock.patch.object(
            ElastiCacheIntegrationsSession, "close", side_effect=RuntimeError("cleanup failed")
        ),
        caplog.at_level("WARNING", logger="elasticache_integrations.session"),
    ):
        with pytest.raises(
            ElastiCacheIntegrationsConnectionError,
            match="opening a connection timed out after 0.05s",
        ) as exc_info:
            ElastiCacheIntegrationsSession(
                lambda: connector, policy=ReconnectPolicy(connection_timeout=0.05)
            )

    assert str(exc_info.value.__cause__) == "bring-up rejected"
    assert "cleanup failed during initialization" in caplog.text


def test_policy_none_uses_the_default_policy():
    """Passing ``policy=None`` explicitly behaves like omitting it."""
    connector = mock_connector()
    session = ElastiCacheIntegrationsSession(lambda: connector, policy=None)
    assert session.is_open
    session.close()


# --- lifecycle: refresher --------------------------------------------------------


def test_construction_starts_refresher():
    """Construction starts a live refresher thread; close() stops it."""
    connector = mock_connector()
    session = ElastiCacheIntegrationsSession(lambda: connector)
    assert session._config_refresher
    assert session._config_refresher._thread.is_alive()
    session.close()
    assert session._config_refresher._thread.is_alive() is False


def test_close_stops_refresher_before_closing_connector(monkeypatch):
    """close() stops every background runner (re-auth + config refresh) strictly before closing the
    connector, so a background tick can never fire into a half-closed connection during shutdown."""
    calls = []

    class _SpyRunner(Refresher):
        def stop(self, timeout=5):
            calls.append("runner.stop")
            super().stop(timeout=timeout)

    monkeypatch.setattr("elasticache_integrations.session.Refresher", _SpyRunner)

    connector = mock_connector()
    connector.close.side_effect = lambda: calls.append("connector.close")
    session = ElastiCacheIntegrationsSession(lambda: connector)
    session.close()
    # Both runners stop before the connector closes, and the connector closes last.
    assert calls[-1] == "connector.close"
    assert calls[:-1] == ["runner.stop", "runner.stop"]


@pytest.mark.parametrize("failing_step", ["__init__", "start"])
def test_a_failed_refresher_closes_everything(failing_step, monkeypatch):
    """The connection is already open and configured by the time the runners are started, so a
    runner that cannot be constructed or started closes it rather than leaking it, and the
    original error propagates."""

    def fail(*args, **kwargs):
        raise RuntimeError("can't start new thread")

    monkeypatch.setattr(Refresher, failing_step, fail)
    connector = mock_connector()
    with pytest.raises(RuntimeError):
        ElastiCacheIntegrationsSession(lambda: connector)
    connector.close.assert_called_once_with()


# --- refresh: fetch and apply ----------------------------------------------------


def test_refresh_once_sends_the_no_argument_form():
    """A refresh tick sends aws.config with no argument: the connection was configured with
    the current overrides before it was published, so the server already has them."""
    connector = mock_connector()
    session = ElastiCacheIntegrationsSession(lambda: connector, overrides={"flag": True})
    connector.execute.reset_mock()  # discard the configure call
    session._refresh_once()
    connector.execute.assert_called_once_with("aws.config", timeout=connector.config_timeout)
    session.close()


def test_refresh_once_null_reply_leaves_config_and_predicates_unchanged():
    """A RESP3 null reply (decoded to None) means the server's config is unchanged too: no swap,
    no reschedule."""
    connector = mock_connector()
    session = ElastiCacheIntegrationsSession(lambda: connector)
    original_config = session._config
    session._config_refresher.set_interval = lambda interval: pytest.fail(
        "must not reschedule on a null reply"
    )
    session._refresh_once()  # mock_connector answers the no-argument form with a null
    assert session._config is original_config
    session.close()


def test_refresh_once_applies_a_server_initiated_change():
    """A resolved-config reply to a no-argument tick is installed and reschedules the loop: the
    branch is driven by the reply received, not by what was sent."""
    connector = mock_connector()
    session = ElastiCacheIntegrationsSession(lambda: connector)
    connector.execute.side_effect = None
    connector.execute.return_value = json.dumps(
        {"version": "1.0", "refresh_interval": 45, "predicates": []}
    ).encode()
    session._refresh_once()
    assert session._config.refresh_interval == 45
    assert session._config_refresher._interval == 45
    session.close()


def test_refresh_once_does_nothing_while_no_connection_is_available():
    """A tick landing mid-reconnect sends nothing and keeps the current config, since whichever
    connection comes back resolves its own config as it is configured."""
    connector = mock_connector()
    session = ElastiCacheIntegrationsSession(lambda: connector)
    original_config = session._config
    session._connection_manager._connector = None
    connector.execute.reset_mock()
    session._refresh_once()  # must not raise
    connector.execute.assert_not_called()
    assert session._config is original_config
    session.close()


def test_configuring_a_connection_reschedules_the_refresher():
    """Configuring a connection installs its config the same way a refresh does, so a reconnect
    that resolves a new interval reschedules the loop."""
    connector = mock_connector()
    session = ElastiCacheIntegrationsSession(lambda: connector)
    reconnected = mock_connector()
    reconnected.execute.side_effect = None
    reconnected.execute.return_value = json.dumps(
        {"version": "1.0", "refresh_interval": 45, "predicates": []}
    ).encode()
    session._configure(reconnected)
    assert session._config_refresher._interval == 45
    session.close()


def test_refresh_once_swallows_connector_error_and_keeps_current_config():
    """A ElastiCacheIntegrationsError from execute() must not escape _refresh_once: it would propagate out of the
    refresher loop and silently kill the thread, stopping every future refresh."""
    connector = mock_connector()
    session = ElastiCacheIntegrationsSession(lambda: connector)
    original_config = session._config
    connector.execute.side_effect = ElastiCacheIntegrationsError("connection lost")
    session._refresh_once()  # must not raise
    assert session._config is original_config
    session.close()


def test_refresh_once_swallows_malformed_reply_and_keeps_current_config():
    """A reply that fails to parse leaves the last known good config installed — _replace_config
    is never reached, so no code is needed to retain it."""
    connector = mock_connector()
    session = ElastiCacheIntegrationsSession(lambda: connector)
    original_config = session._config
    connector.execute.side_effect = None
    connector.execute.return_value = b'{"version":"1.0"}'  # no refresh_interval: malformed
    session._refresh_once()  # must not raise
    assert session._config is original_config
    session.close()


def test_refresh_once_config_invalid_alarms_at_once_without_dropping(caplog):
    """A CONFIG_INVALID on refresh alarms at once (error level) but does not drop the connection:
    the caching behavior rides the staleness window like any failing refresh; only the alarm is
    immediate."""
    connector = mock_connector()
    session = ElastiCacheIntegrationsSession(lambda: connector)
    connector.execute.reset_mock()  # discard the configure call
    connector.execute.side_effect = ElastiCacheIntegrationsOverridesRejected(
        "aws.config rejected: CONFIG_INVALID"
    )
    with caplog.at_level("ERROR", logger="elasticache_integrations.session"):
        session._refresh_once()  # must not raise
    connector.close.assert_not_called()
    assert any(r.levelname == "ERROR" for r in caplog.records)
    session.close()


def test_refresh_once_unevaluable_config_alarms_at_once_without_dropping(caplog):
    """A resolved config carrying a predicate the client cannot compile taints the whole config:
    it alarms but keeps the last good config until the window drops the connection — it does not
    cache by the entries that did compile, and it does not drop immediately."""
    connector = mock_connector()
    session = ElastiCacheIntegrationsSession(lambda: connector)
    original_config = session._config
    connector.execute.reset_mock()  # discard the configure call
    connector.execute.side_effect = None
    connector.execute.return_value = json.dumps(
        {
            "version": "1.0",
            "refresh_interval": 60,
            "predicates": [{"expr": "this is not (valid CEL", "vars": {}}],
        }
    ).encode()
    with caplog.at_level("ERROR", logger="elasticache_integrations.session"):
        session._refresh_once()  # must not raise
    connector.close.assert_not_called()
    assert session._config is original_config  # untrusted config was not installed
    assert any(r.levelname == "ERROR" for r in caplog.records)
    session.close()


@pytest.mark.parametrize(
    "reply, error, cause",
    [
        (
            RespError("CONFIG_INVALID", "table does not exist"),
            ElastiCacheIntegrationsOverridesRejected,
            "CONFIG_INVALID",
        ),
        (
            json.dumps(
                {
                    "version": "1.0",
                    "refresh_interval": 60,
                    "predicates": [{"expr": "this is not (valid CEL", "vars": {}}],
                }
            ).encode(),
            ElastiCacheIntegrationsConfigUnevaluable,
            "cannot evaluate",
        ),
    ],
)
def test_configuring_a_connection_alarms_a_config_failure_on_every_attempt(
    reply, error, cause, caplog
):
    """A config failure that cannot fix itself is logged at ERROR each time a connection is
    configured. It still fails the attempt, so the connection is closed and retried."""
    session = ElastiCacheIntegrationsSession(lambda: mock_connector())
    config = session._config
    with caplog.at_level("ERROR", logger="elasticache_integrations.session"):
        for _ in range(2):
            connector = mock_connector()
            connector.execute.side_effect = None
            connector.execute.return_value = reply
            with pytest.raises(error):
                session._configure(connector)
    assert [r.levelname for r in caplog.records] == ["ERROR", "ERROR"]
    assert all(cause in r.getMessage() for r in caplog.records)
    assert session._config is config
    session.close()


def test_a_config_invalid_refresh_still_reaches_the_staleness_window(clock, caplog):
    """CONFIG_INVALID does not restart the window, so successive rejections reach it and the
    connection is dropped once the window elapses — the shared stale-config degradation path."""
    connector = mock_connector()
    session = stale_session(connector)
    connector.execute.side_effect = ElastiCacheIntegrationsOverridesRejected("rejected")

    clock.advance(STALENESS_WINDOW - 1)
    session._refresh_once()  # alarms, does not confirm the config, does not drop
    assert session.config_stale is False  # still inside the window
    connector.close.assert_not_called()

    clock.advance(1)
    connector.execute.reset_mock()
    with caplog.at_level("WARNING", logger="elasticache_integrations.session"):
        session._refresh_once()  # now past the window: the shared path drops the connection
    connector.close.assert_called_once_with()
    assert "dropping the connection" in caplog.text
    session.close()


def test_predicates_swap_is_visible_to_the_next_request():
    """A predicate recompiled by _replace_config takes effect on the very next request() call."""
    connector = mock_connector()
    session = ElastiCacheIntegrationsSession(lambda: connector)
    # A config with no predicates at all: every request must now pass through (None).
    empty_config = {"version": "1.0", "refresh_interval": 60, "predicates": []}
    connector.execute.side_effect = None
    connector.execute.return_value = json.dumps(empty_config).encode()
    session._refresh_once()
    request = ElastiCacheIntegrationsRequest(
        method="POST",
        url="https://dynamodb.us-east-1.amazonaws.com/",
        metadata=RequestMetadata(service="dynamodb", operation="GetItem"),
    )
    assert session.request(request) is None  # no predicate left to match
    session.close()


# --- refresh: the staleness window ------------------------------------------------


def test_a_configured_connection_starts_the_window_trusted(clock):
    """Configuring the connection confirms a config, so a new session trusts what it routes by."""
    connector = mock_connector()
    session = stale_session(connector)
    assert session.config_stale is False
    clock.advance(STALENESS_WINDOW)
    assert session.config_stale is True  # nothing confirmed it since
    session.close()


def test_a_healthy_refresh_past_the_window_keeps_the_connection(clock):
    """The window bounds how long refreshes may fail, not the time between ticks: a refresh that
    succeeds after the window has passed restarts it, even when ticks are further apart."""
    connector = mock_connector()
    session = stale_session(connector)
    clock.advance(STALENESS_WINDOW)
    connector.execute.reset_mock()  # discard the configure call

    session._refresh_once()

    connector.execute.assert_called_once()
    connector.close.assert_not_called()
    assert session.config_stale is False
    session.close()


def test_a_failing_refresh_past_the_window_drops_the_connection(clock, caplog):
    """A refresh that fails once the window has passed drops the connection, so the one that
    replaces it resolves a config as it is built."""
    connector = mock_connector()
    session = stale_session(connector)
    connector.execute.side_effect = None
    connector.execute.return_value = json.dumps({"version": "1.0"}).encode()  # no refresh_interval
    clock.advance(STALENESS_WINDOW)

    with caplog.at_level("WARNING", logger="elasticache_integrations.session"):
        session._refresh_once()

    connector.close.assert_called_once_with()
    assert "dropping the connection" in caplog.text
    session.close()


def test_a_failure_from_a_replaced_connector_leaves_the_replacement(clock):
    """A refresh that fails after its connector was replaced must not touch the replacement."""
    connector = mock_connector()
    session = stale_session(connector)
    replacement = mock_connector()

    def _replaced_then_failed(command, *args, timeout=None):
        session._connection_manager._connector = replacement
        raise ElastiCacheIntegrationsError("malformed")

    connector.execute.side_effect = _replaced_then_failed
    clock.advance(STALENESS_WINDOW)

    session._refresh_once()

    replacement.close.assert_not_called()
    assert session._connection_manager.connector is replacement
    session.close()


def test_a_null_reply_restarts_the_window(clock):
    """An empty reply installs nothing but still confirms the config: the server would resolve the
    same one now, which is exactly what the window measures."""
    connector = mock_connector()
    session = stale_session(connector)

    clock.advance(STALENESS_WINDOW - 1)
    session._refresh_once()  # mock_connector answers the no-argument form with a null

    # Well past the original deadline: only a restarted window is still trusted here.
    clock.advance(STALENESS_WINDOW - 1)
    assert session.config_stale is False
    session.close()


def test_a_resolved_config_reply_restarts_the_window(clock):
    """A config the server sends confirms it too, so applying one resets the window."""
    connector = mock_connector()
    session = stale_session(connector)

    clock.advance(STALENESS_WINDOW - 1)
    connector.execute.side_effect = None
    connector.execute.return_value = json.dumps(
        {"version": "1.0", "refresh_interval": 45, "predicates": []}
    ).encode()
    session._refresh_once()

    # Well past the original deadline: only a restarted window is still trusted here.
    clock.advance(STALENESS_WINDOW - 1)
    assert session.config_stale is False
    session.close()


def test_configuring_a_new_connection_restarts_the_window(clock):
    """A reconnect resolves its own config as it is configured, which is what makes dropping a
    stale connection a fix rather than only a symptom."""
    connector = mock_connector()
    session = stale_session(connector)
    clock.advance(STALENESS_WINDOW)
    assert session.config_stale is True

    session._configure(mock_connector())
    assert session.config_stale is False
    session.close()


def test_a_failed_refresh_does_not_restart_the_window(clock):
    """Only the server can confirm a config, so a refresh that fails leaves the window running and
    successive failures reach it."""
    connector = mock_connector()
    session = stale_session(connector)
    connector.execute.side_effect = ElastiCacheIntegrationsError("connection lost")

    clock.advance(STALENESS_WINDOW - 1)
    session._refresh_once()
    assert session.config_stale is False  # still inside the window
    clock.advance(1)
    assert session.config_stale is True
    session.close()


def test_the_window_runs_while_no_connection_is_available(clock):
    """The window measures time without a confirmed config, whatever the reason: a tick that finds
    no connection confirms nothing, so a long outage reaches the window like a failing refresh."""
    connector = mock_connector()
    session = stale_session(connector)
    session._connection_manager._connector = None
    connector.reset_mock()

    clock.advance(STALENESS_WINDOW)
    session._refresh_once()  # nothing to send and nothing to drop

    connector.execute.assert_not_called()
    connector.close.assert_not_called()
    assert session.config_stale is True
    session.close()


# --- replies from a closed connection -------------------------------------------------------


def _config_reply(refresh_interval: int) -> bytes:
    return json.dumps(
        {"version": "1.0", "refresh_interval": refresh_interval, "predicates": []}
    ).encode()


def _closes_before_install(connector, reply):
    """An ``execute`` that answers and then marks ``connector`` closed.

    Reproduces the gap between a reply arriving and the session installing it, which is where a
    connection can end and a replacement come up.
    """

    def _execute(command, *args, timeout=None):
        connector.is_alive = False
        return reply

    return _execute


def test_a_refresh_reply_from_a_closed_connection_is_dropped(clock):
    """A config that arrives after its connection closed installs nothing and confirms nothing,
    so it cannot overwrite the config a replacement connection resolved."""
    connector = mock_connector()
    session = stale_session(connector)
    config, predicates = session._config, session._predicates
    connector.execute.side_effect = _closes_before_install(connector, _config_reply(45))

    clock.advance(STALENESS_WINDOW - 1)
    session._refresh_once()

    assert session._config is config
    assert session._predicates is predicates
    assert session._config_refresher._interval == 60
    clock.advance(1)  # the original deadline still stands
    assert session.config_stale is True
    session.close()


def test_a_null_reply_from_a_closed_connection_does_not_restart_the_window(clock):
    """An "unchanged" reply speaks only for the connection that sent it, so once that connection
    has closed it confirms nothing."""
    connector = mock_connector()
    session = stale_session(connector)
    connector.execute.side_effect = _closes_before_install(connector, None)

    clock.advance(STALENESS_WINDOW - 1)
    session._refresh_once()

    clock.advance(1)  # the original deadline still stands
    assert session.config_stale is True
    session.close()


def test_configuring_a_connection_that_closes_before_its_install_fails():
    """A connection that closes between its config reply and the install fails its configure,
    leaving the config already in force: a late install from an abandoned attempt is refused."""
    session = ElastiCacheIntegrationsSession(lambda: mock_connector())
    config = session._config
    late = mock_connector()
    late.execute.side_effect = _closes_before_install(late, _config_reply(45))

    with pytest.raises(ElastiCacheIntegrationsConnectionError):
        session._configure(late)

    assert session._config is config
    assert session._config_refresher._interval == 60
    session.close()


def test_a_first_attempt_that_closes_before_its_install_is_retried():
    """Construction treats a dropped install as a failed attempt and retries, so the session
    starts on the second attempt's config instead of on none."""
    first = mock_connector()
    first.execute.side_effect = _closes_before_install(first, _config_reply(30))
    second = mock_connector()
    second.execute.side_effect = None
    second.execute.return_value = _config_reply(45)
    connectors = iter([first, second])

    session = ElastiCacheIntegrationsSession(
        lambda: next(connectors),
        policy=ReconnectPolicy(base_delay=0.001, max_delay=0.001),
    )

    first.close.assert_called()
    assert session._config.refresh_interval == 45
    assert session._config_refresher._interval == 45
    session.close()


# --- request path ---------------------------------------------------------------


def test_request_delegates_to_connector():
    """request() serializes the aws.request command, forwards it to execute(), and returns
    the parsed response."""
    connector = mock_connector()
    session = ElastiCacheIntegrationsSession(lambda: connector)
    request = ElastiCacheIntegrationsRequest(
        method="POST",
        url="https://dynamodb.us-east-1.amazonaws.com/",
        headers={"Content-Type": "application/x-amz-json-1.0"},
        body=b"{}",
        metadata=RequestMetadata(service="dynamodb", operation="GetItem"),
    )
    resp = session.request(request)
    assert isinstance(resp, ElastiCacheIntegrationsResponse)
    assert resp.status_code == 200
    args, kwargs = connector.execute.call_args
    assert args[0] == "aws.request"
    assert len(args[1:]) == 5  # [method, url, headers, body, metadata]
    assert kwargs == {}
    session.close()


def test_auth_error_invalidates_connection_and_passes_request_through():
    connector = mock_connector()
    session = ElastiCacheIntegrationsSession(lambda: connector)
    connector.execute.side_effect = None
    connector.execute.return_value = RespError("AUTH_ERROR", "missing FAS")
    session._connection_manager.invalidate_connection = mock.Mock()

    response = session.request(
        ElastiCacheIntegrationsRequest(
            method="POST",
            url="https://dynamodb.us-east-1.amazonaws.com/",
            metadata=RequestMetadata(service="dynamodb", operation="UpdateItem"),
        )
    )

    assert response is None
    session._connection_manager.invalidate_connection.assert_called_once_with(connector)
    session.close()


def test_request_after_close_is_rejected_before_connector_use():
    connector = mock_connector()
    session = ElastiCacheIntegrationsSession(lambda: connector)
    session.close()
    connector.execute.reset_mock()  # discard the constructor's config call
    with pytest.raises(ElastiCacheIntegrationsError):
        session.request(
            ElastiCacheIntegrationsRequest(
                method="POST",
                url="https://x/",
                metadata=RequestMetadata(service="dynamodb", operation="GetItem"),
            )
        )
    connector.execute.assert_not_called()


# --- lifecycle: close -----------------------------------------------------------


def test_close_delegates_to_connector():
    """close() delegates to the connector; the connector's own close() is idempotent."""
    connector = mock_connector()
    session = ElastiCacheIntegrationsSession(lambda: connector)
    session.close()
    session.close()
    assert connector.close.call_count == 2  # session adds no state; connector handles idempotence


def test_close_releases_owned_resource_once():
    connector = mock_connector()
    close_callback = mock.Mock()
    session = ElastiCacheIntegrationsSession(lambda: connector, close_callback=close_callback)

    session.close()
    session.close()

    close_callback.assert_called_once()


def test_close_releases_owned_resource_when_a_refresher_stop_fails():
    connector = mock_connector()
    close_callback = mock.Mock()
    session = ElastiCacheIntegrationsSession(lambda: connector, close_callback=close_callback)
    reauth_runner = session._reauth_runner
    assert reauth_runner is not None
    reauth_runner.stop()
    session._reauth_runner = mock.Mock()
    session._reauth_runner.stop.side_effect = RuntimeError("join failed")

    try:
        with pytest.raises(RuntimeError, match="join failed"):
            session.close()
        close_callback.assert_called_once()
    finally:
        session._config_refresher.stop()
        session._connection_manager.close()


def test_is_open_tracks_lifecycle():
    connector = mock_connector()
    session = ElastiCacheIntegrationsSession(lambda: connector)
    assert session.is_open
    session.close()
    assert not session.is_open


# --- endpoint parsing -----------------------------------------------------------


@pytest.mark.parametrize(
    "endpoint,expected",
    [
        ("cache.example.com:6379", ("cache.example.com", 6379)),
        ("127.0.0.1:11211", ("127.0.0.1", 11211)),
        ("::1:6379", ("::1", 6379)),  # rpartition keeps the trailing :port
    ],
)
def test_parse_endpoint_valid(endpoint, expected):
    """parse_endpoint splits host:port (using rpartition so IPv6 colons are kept)."""
    assert parse_endpoint(endpoint) == expected


@pytest.mark.parametrize(
    "bad",
    ["", "noport", "host:", ":6379", "host:0", "host:70000", "host:abc"],
)
def test_parse_endpoint_invalid(bad):
    """Malformed endpoints (empty, no port, empty port, empty host, port 0, port > 65535, non-numeric port) are rejected."""
    with pytest.raises(ElastiCacheIntegrationsConfigError):
        parse_endpoint(bad)
