"""Tests for photon.session.

The connector double comes from :func:`tests.mock.mock_connector` — a ``create_autospec`` bound to
the real :class:`PhotonConnector`, so the session is verified against the real connector signatures
without a network. Because the connector is a mock (no real socket/thread), the session needs no
teardown.

Command building/normalization details are protocol.py's contract and are covered in
test_protocol.py; these tests assert only session behavior (lifecycle + delegation).
"""

import pytest

from photon.exceptions import PhotonConfigError, PhotonError
from photon.models import PhotonRequest, PhotonResponse, RequestMetadata
from photon.reconnect_policy import ReconnectPolicy
from photon.session import PhotonSession, parse_endpoint
from tests.mock import mock_connector


# --- lifecycle: opening the connection --------------------------------------------------------

def test_connect_and_configure_order():
    """Construction drives connect(), authenticate(), then the photon.config exchange."""
    connector = mock_connector()
    PhotonSession(lambda: connector, overrides={"flag": True})
    assert [c[0] for c in connector.method_calls] == [
        "connect",
        "authenticate",
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


@pytest.mark.parametrize("failing_step", ["authenticate", "execute"])
def test_a_failed_configure_closes_everything(failing_step):
    """If any configure step after connect() raises, construction closes the connector
    (no leaked connection) and the session object never exists."""
    connector = mock_connector()
    getattr(connector, failing_step).side_effect = PhotonError(f"{failing_step} failed")
    # One attempt, so the assertion is about closing rather than about how often we retry.
    with pytest.raises(PhotonError):
        PhotonSession(lambda: connector, policy=ReconnectPolicy(connect_attempts=1))
    connector.close.assert_called_once_with()


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


def test_request_after_close_propagates_connector_error():
    """After close(), request() propagates the connector's error (no session-level state check)."""
    connector = mock_connector()
    session = PhotonSession(lambda: connector)
    session.close()
    connector.execute.reset_mock()  # discard the constructor's config call
    connector.execute.side_effect = PhotonError("connection is closed")
    with pytest.raises(PhotonError):
        session.request(PhotonRequest(method="POST", url="https://x/",
                                      metadata=RequestMetadata(service="dynamodb", operation="GetItem")))
    connector.execute.assert_called_once()  # the call reached the connector: no session-level gate


# --- lifecycle: close -----------------------------------------------------------

def test_close_delegates_to_connector():
    """close() delegates to the connector; the connector's own close() is idempotent."""
    connector = mock_connector()
    session = PhotonSession(lambda: connector)
    session.close()
    session.close()
    assert connector.close.call_count == 2  # session adds no state; connector handles idempotence


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
