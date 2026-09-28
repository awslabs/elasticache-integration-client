"""Tests for photon.session.

The connector double comes from :func:`tests.mock.mock_connector` — a ``create_autospec`` bound to
the real :class:`PhotonConnector`, so the session is verified against the real connector signatures
without a network. Construction starts real :class:`~photon.refresh.Refresher` daemon
thread (the connector is a mock, but the refresher is not), so any test that constructs a real
``PhotonSession`` and reaches the refresher must call ``session.close()`` to avoid leaking it.

Command building/normalization details are protocol.py's contract and are covered in
test_protocol.py; these tests assert only session behavior (lifecycle + delegation).
"""

import json
from unittest import mock

import pytest

from photon.exceptions import PhotonConfigError, PhotonError
from photon.models import PhotonRequest, PhotonResponse, RequestMetadata
from photon.reconnect_policy import ReconnectPolicy
from photon.resp import RespError
from photon.refresh import Refresher
from photon.session import PhotonSession, parse_endpoint
from tests.mock import mock_connector


# --- lifecycle: opening the connection --------------------------------------------------------

def test_connect_and_configure_order():
    """Construction drives connect(), handshake(), then the photon.config exchange."""
    connector = mock_connector()
    session = PhotonSession(lambda: connector, overrides={"flag": True})
    session.close()
    assert [c[0] for c in connector.method_calls][:3] == [
        "connect",
        "handshake",
        "execute",
    ]
    connector.execute.assert_called_once_with("photon.config", b'{"flag":true}',
                                              timeout=connector.config_timeout)


def test_resolved_config_stored_on_construction():
    """The resolved config is stored by the time construction returns."""
    connector = mock_connector()
    session = PhotonSession(lambda: connector)
    assert session._config is not None
    assert session._config.version == "1.0"
    assert session._config.refresh_interval == 60
    session.close()


@pytest.mark.parametrize("failing_step", ["handshake", "execute"])
def test_a_failed_configure_closes_everything(failing_step):
    """If any configure step after connect() raises, construction closes the connector
    (no leaked connection) and the session object never exists."""
    connector = mock_connector()
    getattr(connector, failing_step).side_effect = PhotonError(f"{failing_step} failed")
    # One attempt, so the assertion is about closing rather than about how often we retry.
    with pytest.raises(PhotonError):
        PhotonSession(lambda: connector, policy=ReconnectPolicy(connect_attempts=1))
    connector.close.assert_called_once_with()


def test_policy_none_uses_the_default_policy():
    """Passing ``policy=None`` explicitly behaves like omitting it."""
    connector = mock_connector()
    session = PhotonSession(lambda: connector, policy=None)
    assert session.is_open
    session.close()


# --- lifecycle: refresher --------------------------------------------------------

def test_construction_starts_refresher():
    """Construction starts a live refresher thread; close() stops it."""
    connector = mock_connector()
    session = PhotonSession(lambda: connector)
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

    monkeypatch.setattr("photon.session.Refresher", _SpyRunner)

    connector = mock_connector()
    connector.close.side_effect = lambda: calls.append("connector.close")
    session = PhotonSession(lambda: connector)
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
        PhotonSession(lambda: connector)
    connector.close.assert_called_once_with()


# --- refresh: fetch and apply ----------------------------------------------------

def test_refresh_once_sends_the_no_argument_form():
    """A refresh tick sends photon.config with no argument: the connection was configured with
    the current overrides before it was published, so the server already has them."""
    connector = mock_connector()
    session = PhotonSession(lambda: connector, overrides={"flag": True})
    connector.execute.reset_mock()  # discard the configure call
    session._refresh_once()
    connector.execute.assert_called_once_with("photon.config", timeout=connector.config_timeout)
    session.close()


def test_refresh_once_null_reply_leaves_config_and_predicates_unchanged():
    """A RESP3 null reply (decoded to None) means the server's config is unchanged too: no swap,
    no reschedule."""
    connector = mock_connector()
    session = PhotonSession(lambda: connector)
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
    session = PhotonSession(lambda: connector)
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
    session = PhotonSession(lambda: connector)
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
    session = PhotonSession(lambda: connector)
    reconnected = mock_connector()
    reconnected.execute.side_effect = None
    reconnected.execute.return_value = json.dumps(
        {"version": "1.0", "refresh_interval": 45, "predicates": []}
    ).encode()
    session._configure(reconnected)
    assert session._config_refresher._interval == 45
    session.close()


def test_refresh_once_swallows_connector_error_and_keeps_current_config():
    """A PhotonError from execute() must not escape _refresh_once: it would propagate out of the
    refresher loop and silently kill the thread, stopping every future refresh."""
    connector = mock_connector()
    session = PhotonSession(lambda: connector)
    original_config = session._config
    connector.execute.side_effect = PhotonError("connection lost")
    session._refresh_once()  # must not raise
    assert session._config is original_config
    session.close()


def test_refresh_once_swallows_malformed_reply_and_keeps_current_config():
    """A reply that fails to parse leaves the last known good config installed — _replace_config
    is never reached, so no code is needed to retain it."""
    connector = mock_connector()
    session = PhotonSession(lambda: connector)
    original_config = session._config
    connector.execute.side_effect = None
    connector.execute.return_value = b'{"version":"1.0"}'  # no refresh_interval: malformed
    session._refresh_once()  # must not raise
    assert session._config is original_config
    session.close()


def test_predicates_swap_is_visible_to_the_next_request():
    """A predicate recompiled by _replace_config takes effect on the very next request() call."""
    connector = mock_connector()
    session = PhotonSession(lambda: connector)
    # A config with no predicates at all: every request must now pass through (None).
    empty_config = {"version": "1.0", "refresh_interval": 60, "predicates": []}
    connector.execute.side_effect = None
    connector.execute.return_value = json.dumps(empty_config).encode()
    session._refresh_once()
    request = PhotonRequest(
        method="POST",
        url="https://dynamodb.us-east-1.amazonaws.com/",
        metadata=RequestMetadata(service="dynamodb", operation="GetItem"),
    )
    assert session.request(request) is None  # no predicate left to match
    session.close()


# --- request path ---------------------------------------------------------------

def test_request_delegates_to_connector():
    """request() serializes the photon.request command, forwards it to execute(), and returns
    the parsed response."""
    connector = mock_connector()
    session = PhotonSession(lambda: connector)
    request = PhotonRequest(
        method="POST",
        url="https://dynamodb.us-east-1.amazonaws.com/",
        headers={"Content-Type": "application/x-amz-json-1.0"},
        body=b"{}",
        metadata=RequestMetadata(service="dynamodb", operation="GetItem"),
    )
    resp = session.request(request)
    assert isinstance(resp, PhotonResponse)
    assert resp.status_code == 200
    args, kwargs = connector.execute.call_args
    assert args[0] == "photon.request"
    assert len(args[1:]) == 5  # [method, url, headers, body, metadata]
    assert kwargs == {}
    session.close()


def test_auth_error_invalidates_connection_and_passes_request_through():
    connector = mock_connector()
    session = PhotonSession(lambda: connector)
    connector.execute.side_effect = None
    connector.execute.return_value = RespError("PHOTON_AUTH_ERROR", "missing FAS")
    session._connection_manager.invalidate_connection = mock.Mock()

    response = session.request(PhotonRequest(
        method="POST",
        url="https://dynamodb.us-east-1.amazonaws.com/",
        metadata=RequestMetadata(service="dynamodb", operation="UpdateItem"),
    ))

    assert response is None
    session._connection_manager.invalidate_connection.assert_called_once_with(connector)
    session.close()


def test_request_after_close_is_rejected_before_connector_use():
    connector = mock_connector()
    session = PhotonSession(lambda: connector)
    session.close()
    connector.execute.reset_mock()  # discard the constructor's config call
    with pytest.raises(PhotonError):
        session.request(PhotonRequest(method="POST", url="https://x/",
                                      metadata=RequestMetadata(service="dynamodb", operation="GetItem")))
    connector.execute.assert_not_called()


# --- lifecycle: close -----------------------------------------------------------

def test_close_delegates_to_connector():
    """close() delegates to the connector; the connector's own close() is idempotent."""
    connector = mock_connector()
    session = PhotonSession(lambda: connector)
    session.close()
    session.close()
    assert connector.close.call_count == 2  # session adds no state; connector handles idempotence


def test_is_open_tracks_lifecycle():
    connector = mock_connector()
    session = PhotonSession(lambda: connector)
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
    with pytest.raises(PhotonConfigError):
        parse_endpoint(bad)
