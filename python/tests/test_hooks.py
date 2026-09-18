"""Unit tests for photon.hooks."""

from unittest import mock

import pytest
from botocore.hooks import first_non_none_response

from photon import hooks
from photon.exceptions import (
    PhotonCacheUnavailable,
    PhotonConnectionError,
    PhotonConnectionLost,
    PhotonError,
    PhotonProtocolError,
    PhotonRejectedError,
    PhotonTimeoutError,
)
from tests.mock import MockClient, mock_session

from botocore.exceptions import (
    ConnectionClosedError,
    EndpointConnectionError,
    ReadTimeoutError,
)


def _emit(client, body=b"{}"):
    """Trigger a before-send event on the client's emitter; return the response (or None)."""
    request = mock.Mock(method="POST", url="https://host/", headers={}, body=body)
    responses = client.meta.events.emit("before-send.service.Operation", request=request)
    return first_non_none_response(responses)


def test_construction_routes_requests():
    """Constructing a handle registers the handler: emitting before-send calls session.request
    and returns an AWSResponse that short-circuits the SDK call."""
    sdk = MockClient()
    session = mock_session()
    hooks.PhotonHandle(sdk, session)
    response = _emit(sdk)
    assert response is not None
    assert response.status_code == 200
    session.request.assert_called_once()


def test_close_tears_down_photon():
    """Calling the client's own close() detaches the handler, closes the Photon session, and
    chains to the original close. After close, an emit no longer reaches Photon."""
    sdk = MockClient()
    session = mock_session()
    hooks.PhotonHandle(sdk, session)
    sdk.close()
    assert sdk.closed is True          # original close ran
    session.close.assert_called_once()
    assert _emit(sdk) is None          # handler unregistered: emit reaches no handler
    session.request.assert_not_called()


def test__aws_to_photon_request_maps_fields():
    """Maps a botocore prepared request to a PhotonRequest, normalizing a None body to empty bytes."""
    request = mock.Mock(method="GET", url="https://host/path", headers={"Host": "host"}, body=None)

    result = hooks._aws_to_photon_request(request, "before-send.service.Operation")

    assert result.method == "GET"
    assert result.url == "https://host/path"
    assert result.headers == {"Host": "host"}
    assert result.body == b""  # a None body is normalized to empty bytes


def test_undecodable_header_value_raises_photon_error():
    """A non-UTF-8 bytes header value raises PhotonError, not a bare UnicodeDecodeError."""
    request = mock.Mock(method="POST", url="https://host/",
                        headers={"x-odd": b"\xff\xfe"}, body=b"{}")
    with pytest.raises(PhotonError):
        hooks._aws_to_photon_request(request, "before-send.service.Operation")


def test_undecodable_header_value_passes_through():
    """Through the handler, an undecodable header degrades to pass-through: the app's request
    proceeds to the service instead of crashing."""
    sdk = MockClient()
    session = mock_session()
    hooks.PhotonHandle(sdk, session)
    request = mock.Mock(method="POST", url="https://host/",
                        headers={"x-odd": b"\xff\xfe"}, body=b"{}")
    responses = sdk.meta.events.emit("before-send.service.Operation", request=request)
    assert first_non_none_response(responses) is None
    session.request.assert_not_called()


@pytest.mark.parametrize(
    "event_name,expected",
    [
        ("before-send.service.Operation", ("service", "Operation")),
        ("before-send.application-autoscaling.RegisterScalableTarget",
         ("application-autoscaling", "RegisterScalableTarget")),
    ],
)
def test_parse_event_name(event_name, expected):
    """Extracts (service, operation) from the event name."""
    request = mock.Mock(method="GET", url="https://host/path", headers={}, body=None)
    metadata = hooks._aws_to_photon_request(request, event_name).metadata
    assert (metadata.service, metadata.operation) == expected


@pytest.mark.parametrize("event_name", [None, "", "service", "before-send"])
def test_missing_or_malformed_event_name_raises(event_name):
    """A missing or malformed event name is misuse and raises PhotonError."""
    request = mock.Mock(method="GET", url="https://host/path", headers={}, body=None)
    with pytest.raises(PhotonError):
        hooks._aws_to_photon_request(request, event_name)

@pytest.mark.parametrize("error", [
    PhotonRejectedError("rejected"),
    PhotonProtocolError("malformed reply"),
])
def test_idempotent_photon_failure_passes_through(error):
    sdk = MockClient()
    session = mock_session()
    session.request.side_effect = error
    hooks.PhotonHandle(sdk, session)
    request = mock.Mock(method="POST", url="https://host/", headers={}, body=b"{}")
    responses = sdk.meta.events.emit("before-send.dynamodb.GetItem", request=request)
    assert first_non_none_response(responses) is None


@pytest.mark.parametrize("photon_error,botocore_error", [
    (PhotonTimeoutError("timed out"), ReadTimeoutError),
    (PhotonConnectionLost("socket died"), ConnectionClosedError),
    (PhotonConnectionError("connect failed"), EndpointConnectionError),
])
def test_non_idempotent_transport_failure_raises_botocore_error(photon_error, botocore_error):
    """A non-idempotent transport failure raises botocore's own error."""
    sdk = MockClient()
    session = mock_session()
    session.request.side_effect = photon_error
    hooks.PhotonHandle(sdk, session)
    with pytest.raises(botocore_error) as exc_info:
        request = mock.Mock(method="POST", url="https://host/", headers={}, body=b"{}")
        sdk.meta.events.emit("before-send.dynamodb.UpdateItem", request=request)
    assert exc_info.value.__cause__ is photon_error


@pytest.mark.parametrize("photon_error", [
    PhotonTimeoutError("timed out"),
    PhotonConnectionLost("socket died"),
    PhotonConnectionError("connect failed"),
])
def test_idempotent_transport_failure_passes_through(photon_error):
    sdk = MockClient()
    session = mock_session()
    session.request.side_effect = photon_error
    hooks.PhotonHandle(sdk, session)
    request = mock.Mock(method="POST", url="https://host/", headers={}, body=b"{}")
    responses = sdk.meta.events.emit("before-send.dynamodb.GetItem", request=request)
    assert first_non_none_response(responses) is None


def test_non_idempotent_rejection_passes_through():
    sdk = MockClient()
    session = mock_session()
    rejection = PhotonRejectedError("rejected")
    session.request.side_effect = rejection
    hooks.PhotonHandle(sdk, session)
    request = mock.Mock(method="POST", url="https://host/", headers={}, body=b"{}")
    responses = sdk.meta.events.emit(
        "before-send.dynamodb.UpdateItem", request=request
    )
    assert first_non_none_response(responses) is None


def test_non_idempotent_malformed_response_raises_connection_closed():
    sdk = MockClient()
    session = mock_session()
    protocol_error = PhotonProtocolError("malformed reply")
    session.request.side_effect = protocol_error
    hooks.PhotonHandle(sdk, session)
    request = mock.Mock(method="POST", url="https://host/", headers={}, body=b"{}")
    with pytest.raises(ConnectionClosedError) as exc_info:
        sdk.meta.events.emit("before-send.dynamodb.UpdateItem", request=request)
    assert exc_info.value.__cause__ is protocol_error


def test_shutdown_failure_does_not_start_idempotent_fallback():
    sdk = MockClient()
    session = mock_session()
    session.is_open = False
    timeout = PhotonTimeoutError("closed")
    session.request.side_effect = timeout
    hooks.PhotonHandle(sdk, session)
    request = mock.Mock(method="POST", url="https://host/", headers={}, body=b"{}")
    with pytest.raises(ReadTimeoutError) as exc_info:
        sdk.meta.events.emit("before-send.dynamodb.GetItem", request=request)
    assert exc_info.value.__cause__ is timeout


def test_non_photon_error_propagates():
    """A non-Photon exception propagates instead of passing through."""
    sdk = MockClient()
    session = mock_session()
    session.request.side_effect = RuntimeError("bug")
    hooks.PhotonHandle(sdk, session)
    with pytest.raises(RuntimeError):
        _emit(sdk)


@pytest.mark.parametrize("caught_as", [
    PhotonCacheUnavailable,  # reaches the application instead of being passed through
    ConnectionClosedError,   # under the botocore name an application already handles
])
def test_cache_unavailable_reaches_the_application(caught_as):
    """An escalated cache-unavailable error propagates, under either of its names."""
    sdk = MockClient()
    session = mock_session()
    session.request.side_effect = PhotonCacheUnavailable(endpoint_url="https://host/")
    hooks.PhotonHandle(sdk, session)

    with pytest.raises(caught_as):
        _emit(sdk)
