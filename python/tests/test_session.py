"""Tests for photon.session.

The connection double comes from :func:`tests.mock.mock_connection` — a ``create_autospec`` bound to
the real :class:`PhotonConnection`, so the session is verified against the real connection signatures
without a network. Because the connection is a mock (no real socket/thread), the session needs no
teardown.

Command building/normalization details are protocol.py's contract and are covered in
test_protocol.py; these tests assert only session behavior (lifecycle + delegation).
"""

import pytest

from photon.exceptions import PhotonError
from photon.models import PhotonRequest, PhotonResponse, RequestMetadata
from photon.session import PhotonSession, parse_endpoint
from tests.mock import mock_connection


# --- lifecycle: bring-up --------------------------------------------------------

def test_bring_up_order():
    """Construction drives connect(), authenticate(), then the config handshake via execute()."""
    connection = mock_connection()
    PhotonSession(connection, overrides={"flag": True})
    assert [c[0] for c in connection.method_calls] == [
        "connect",
        "authenticate",
        "execute",
    ]
    connection.execute.assert_called_once_with("photon.config", b'{"flag":true}',
                                              timeout=connection.config_timeout)


def test_resolved_config_stored_on_construction():
    """The handshake's resolved config is stored by the time construction returns."""
    connection = mock_connection()
    session = PhotonSession(connection)
    assert session.resolved_config is not None
    assert session.resolved_config.version == "v1"
    assert session.resolved_config.refresh_interval == 60


@pytest.mark.parametrize("failing_step", ["authenticate", "execute"])
def test_failed_bring_up_closes_everything(failing_step):
    """If any bring-up step after connect() raises, construction closes the connection
    (no leaked connection) and the session object never exists."""
    connection = mock_connection()
    getattr(connection, failing_step).side_effect = PhotonError(f"{failing_step} failed")
    with pytest.raises(PhotonError):
        PhotonSession(connection)
    connection.close.assert_called_once_with()


# --- request path ---------------------------------------------------------------

def test_request_delegates_to_connection():
    """request() serializes the photon.request command, forwards it to execute(), and returns
    the parsed response."""
    connection = mock_connection()
    session = PhotonSession(connection)
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
    args, kwargs = connection.execute.call_args
    assert args[0] == "photon.request"
    assert len(args[1:]) == 5  # [method, url, headers, body, metadata]
    assert kwargs == {}


def test_request_after_close_propagates_connection_error():
    """After close(), request() propagates the connection's error (no session-level state check)."""
    connection = mock_connection()
    session = PhotonSession(connection)
    session.close()
    connection.execute.reset_mock()  # discard the constructor's config call
    connection.execute.side_effect = PhotonError("connection is closed")
    with pytest.raises(PhotonError):
        session.request(PhotonRequest(method="POST", url="https://x/",
                                      metadata=RequestMetadata(service="dynamodb", operation="GetItem")))
    connection.execute.assert_called_once()  # the call reached the connection: no session-level gate


# --- lifecycle: close -----------------------------------------------------------

def test_close_delegates_to_connection():
    """close() delegates to the connection; the connection's own close() is idempotent."""
    connection = mock_connection()
    session = PhotonSession(connection)
    session.close()
    session.close()
    assert connection.close.call_count == 2  # session adds no state; connection handles idempotence


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
    with pytest.raises(PhotonError):
        parse_endpoint(bad)
