"""Tests for photon.session.

The connector double comes from :func:`tests.mock.mock_connector` — a ``create_autospec`` bound to
the real :class:`PhotonConnector`, so the session is verified against the real connector signatures
without a network. Because the connector is a mock (no real socket/thread), the session needs no
teardown.

Command building/normalization details are protocol.py's contract and are covered in
test_protocol.py; these tests assert only session behavior (lifecycle + delegation).
"""

import json

import pytest

from photon.exceptions import PhotonError
from photon.models import PhotonRequest, PhotonResponse, RequestMetadata
from photon.session import PhotonSession, parse_endpoint
from tests.mock import body_predicate_config, mock_connector, resolved_config


# --- lifecycle: bring-up --------------------------------------------------------

def test_bring_up_order():
    """Construction drives connect(), authenticate(), then the config handshake via execute()."""
    connector = mock_connector()
    PhotonSession(connector, overrides={"flag": True})
    assert [c[0] for c in connector.method_calls] == [
        "connect",
        "authenticate",
        "execute",
    ]
    connector.execute.assert_called_once_with("photon.config", b'{"flag":true}',
                                              timeout=connector.config_timeout)


def test_resolved_config_stored_on_construction():
    """The handshake's resolved config — including its parsed predicates — is stored by the time
    construction returns."""
    connector = mock_connector()
    session = PhotonSession(connector)
    assert session._config is not None
    assert session._config.version == "1.0"
    assert session._config.refresh_interval == 60
    assert len(session._config.predicates) == 1


@pytest.mark.parametrize("failing_step", ["authenticate", "execute"])
def test_failed_bring_up_closes_everything(failing_step):
    """If any bring-up step after connect() raises, construction closes the connector
    (no leaked connection) and the session object never exists."""
    connector = mock_connector()
    getattr(connector, failing_step).side_effect = PhotonError(f"{failing_step} failed")
    with pytest.raises(PhotonError):
        PhotonSession(connector)
    connector.close.assert_called_once_with()


# --- request path ---------------------------------------------------------------

def test_request_delegates_to_connector():
    """A request matching a resolved predicate is serialized, forwarded to execute(), and its reply
    parsed and returned."""
    connector = mock_connector()
    session = PhotonSession(connector)
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


# --- routing: which requests reach the cache ------------------------------------

def _request(operation="GetItem", service="dynamodb", body=b"{}"):
    return PhotonRequest(
        method="POST",
        url="https://dynamodb.us-east-1.amazonaws.com/",
        headers={},
        body=body,
        metadata=RequestMetadata(service=service, operation=operation),
    )


def _wire_commands(connector):
    """The commands the session actually sent, excluding the constructor's config handshake."""
    return [call.args[0] for call in connector.execute.call_args_list
            if call.args[0] != "photon.config"]


@pytest.mark.parametrize("request_", [
    _request("PutItem"),                 # a write: never in a resolved predicate
    _request("CreateTable"),             # not a cacheable operation at all
    _request(service="s3"),              # another service
])
def test_unmatched_request_is_not_sent_to_the_cache(request_):
    """No predicate matched: request() returns None and nothing goes on the wire."""
    connector = mock_connector()
    session = PhotonSession(connector)
    assert session.request(request_) is None
    assert _wire_commands(connector) == []


def test_empty_predicate_set_routes_nothing():
    """A config with no predicates (or none this client understands) passes everything through."""
    connector = mock_connector(config=resolved_config(predicates=[]))
    session = PhotonSession(connector)
    assert session.request(_request("GetItem")) is None
    assert _wire_commands(connector) == []


def test_table_allowlist_routes_only_allowlisted_tables():
    """With a body predicate, the table in the request body decides cache vs pass-through."""
    connector = mock_connector(config=body_predicate_config("Users"))
    session = PhotonSession(connector)
    allowed = _request(body=json.dumps({"TableName": "Users", "Key": {}}).encode())
    other = _request(body=json.dumps({"TableName": "Orders", "Key": {}}).encode())

    assert isinstance(session.request(allowed), PhotonResponse)
    assert session.request(other) is None
    assert _wire_commands(connector) == ["photon.request"]  # only the allowlisted one


def test_batch_read_routes_only_when_every_table_is_allowlisted():
    """A batch spanning a non-allowlisted table passes through whole (all-or-nothing).

    Exercises the second predicate the proxy emits for an allowlist, which matches on the keys of
    `RequestItems` rather than a `TableName` string.
    """
    connector = mock_connector(config=body_predicate_config("Users", "Sessions"))
    session = PhotonSession(connector)
    all_allowed = _request(
        "BatchGetItem",
        body=json.dumps({"RequestItems": {"Users": {}, "Sessions": {}}}).encode())
    mixed = _request(
        "BatchGetItem",
        body=json.dumps({"RequestItems": {"Users": {}, "Orders": {}}}).encode())

    assert isinstance(session.request(all_allowed), PhotonResponse)
    assert session.request(mixed) is None
    assert _wire_commands(connector) == ["photon.request"]


def test_request_after_close_propagates_connector_error():
    """After close(), request() propagates the connector's error (no session-level state check)."""
    connector = mock_connector()
    session = PhotonSession(connector)
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
    session = PhotonSession(connector)
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
    with pytest.raises(PhotonError):
        parse_endpoint(bad)
