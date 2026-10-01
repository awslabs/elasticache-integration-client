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
    PhotonOriginError,
    PhotonProtocolError,
    PhotonRejectedError,
    PhotonTimeoutError,
)
from tests.mock import MockClient, mock_session

from botocore.exceptions import (
    ConnectionClosedError,
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
    assert sdk.closed is True  # original close ran
    session.close.assert_called_once()
    assert _emit(sdk) is None  # handler unregistered: emit reaches no handler
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
    request = mock.Mock(
        method="POST", url="https://host/", headers={"x-odd": b"\xff\xfe"}, body=b"{}"
    )
    with pytest.raises(PhotonError):
        hooks._aws_to_photon_request(request, "before-send.service.Operation")


def test_undecodable_header_value_passes_through():
    """Through the handler, an undecodable header degrades to pass-through: the app's request
    proceeds to the service instead of crashing."""
    sdk = MockClient()
    session = mock_session()
    hooks.PhotonHandle(sdk, session)
    request = mock.Mock(
        method="POST", url="https://host/", headers={"x-odd": b"\xff\xfe"}, body=b"{}"
    )
    responses = sdk.meta.events.emit("before-send.service.Operation", request=request)
    assert first_non_none_response(responses) is None
    session.request.assert_not_called()


@pytest.mark.parametrize(
    "event_name,expected",
    [
        ("before-send.service.Operation", ("service", "Operation")),
        (
            "before-send.application-autoscaling.RegisterScalableTarget",
            ("application-autoscaling", "RegisterScalableTarget"),
        ),
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


def test_dropped_idempotent_photon_failure_is_sdk_retryable():
    # The SDK retries it, and the retry goes through the cache again.
    sdk = MockClient()
    session = mock_session()
    session.is_connection_healthy = False
    session.request.side_effect = PhotonConnectionLost("socket died")
    hooks.PhotonHandle(sdk, session)
    request = mock.Mock(method="POST", url="https://host/", headers={}, body=b"{}")
    with pytest.raises(ConnectionClosedError):
        sdk.meta.events.emit("before-send.dynamodb.GetItem", request=request)


def test_dropped_idempotent_timeout_is_sdk_retryable():
    sdk = MockClient()
    session = mock_session()
    session.is_connection_healthy = False
    session.request.side_effect = PhotonTimeoutError("timed out")
    hooks.PhotonHandle(sdk, session)
    request = mock.Mock(method="POST", url="https://host/", headers={}, body=b"{}")
    with pytest.raises(ReadTimeoutError):
        sdk.meta.events.emit("before-send.dynamodb.GetItem", request=request)


@pytest.mark.parametrize(
    "photon_error",
    [
        PhotonTimeoutError("timed out"),
        PhotonConnectionLost("socket died"),
        PhotonConnectionError("connect failed"),
    ],
)
def test_non_idempotent_transport_failure_is_non_retryable(photon_error):
    """A non-idempotent transport failure is not marked retryable."""
    sdk = MockClient()
    session = mock_session()
    session.request.side_effect = photon_error
    hooks.PhotonHandle(sdk, session)
    with pytest.raises(type(photon_error)) as exc_info:
        request = mock.Mock(method="POST", url="https://host/", headers={}, body=b"{}")
        sdk.meta.events.emit("before-send.dynamodb.UpdateItem", request=request)
    assert exc_info.value is photon_error


def test_healthy_idempotent_deferred_timeout_is_sdk_retryable():
    sdk = MockClient()
    session = mock_session()
    session.request.side_effect = PhotonTimeoutError("timed out")
    hooks.PhotonHandle(sdk, session)
    request = mock.Mock(method="POST", url="https://host/", headers={}, body=b"{}")
    with pytest.raises(ReadTimeoutError):
        sdk.meta.events.emit("before-send.dynamodb.GetItem", request=request)


@pytest.mark.parametrize(
    "photon_error",
    [
        PhotonConnectionLost("socket died"),
        PhotonConnectionError("connect failed"),
    ],
)
def test_idempotent_connection_errors_are_sdk_retryable(photon_error):
    sdk = MockClient()
    session = mock_session()
    session.request.side_effect = photon_error
    hooks.PhotonHandle(sdk, session)
    request = mock.Mock(method="POST", url="https://host/", headers={}, body=b"{}")
    with pytest.raises(ConnectionClosedError) as exc_info:
        sdk.meta.events.emit("before-send.dynamodb.GetItem", request=request)
    assert exc_info.value.__cause__ is photon_error


def test_non_origin_rejection_is_non_retryable_and_does_not_fallback():
    sdk = MockClient()
    session = mock_session()
    session.is_connection_healthy = False
    rejection = PhotonRejectedError("UNSUPPORTED", "rejected")
    session.request.side_effect = rejection
    hooks.PhotonHandle(sdk, session)
    request = mock.Mock(method="POST", url="https://host/", headers={}, body=b"{}")
    with pytest.raises(PhotonRejectedError) as exc_info:
        sdk.meta.events.emit("before-send.dynamodb.GetItem", request=request)
    assert exc_info.value is rejection


def test_idempotent_origin_error_is_sdk_retryable_without_fallback():
    sdk = MockClient()
    session = mock_session()
    rejection = PhotonRejectedError("ORIGIN_ERROR", "origin unavailable")
    session.request.side_effect = rejection
    hooks.PhotonHandle(sdk, session)
    request = mock.Mock(method="POST", url="https://host/", headers={}, body=b"{}")
    with pytest.raises(PhotonOriginError) as exc_info:
        sdk.meta.events.emit("before-send.dynamodb.GetItem", request=request)
    assert exc_info.value.__cause__ is rejection


def test_non_idempotent_origin_error_is_non_retryable_without_fallback():
    sdk = MockClient()
    session = mock_session()
    rejection = PhotonRejectedError("ORIGIN_ERROR", "origin unavailable")
    session.request.side_effect = rejection
    hooks.PhotonHandle(sdk, session)
    request = mock.Mock(method="POST", url="https://host/", headers={}, body=b"{}")
    with pytest.raises(PhotonRejectedError) as exc_info:
        sdk.meta.events.emit("before-send.dynamodb.UpdateItem", request=request)
    assert exc_info.value is rejection


@pytest.mark.parametrize("operation", ["GetItem", "UpdateItem"])
def test_malformed_response_is_non_retryable(operation):
    sdk = MockClient()
    session = mock_session()
    session.is_connection_healthy = False
    protocol_error = PhotonProtocolError("malformed reply")
    session.request.side_effect = protocol_error
    hooks.PhotonHandle(sdk, session)
    request = mock.Mock(method="POST", url="https://host/", headers={}, body=b"{}")
    with pytest.raises(PhotonProtocolError) as exc_info:
        sdk.meta.events.emit(f"before-send.dynamodb.{operation}", request=request)
    assert exc_info.value is protocol_error


def test_shutdown_failure_does_not_start_idempotent_fallback():
    sdk = MockClient()
    session = mock_session()
    session.is_open = False
    timeout = PhotonTimeoutError("closed")
    session.request.side_effect = timeout
    hooks.PhotonHandle(sdk, session)
    request = mock.Mock(method="POST", url="https://host/", headers={}, body=b"{}")
    with pytest.raises(PhotonTimeoutError) as exc_info:
        sdk.meta.events.emit("before-send.dynamodb.GetItem", request=request)
    assert exc_info.value is timeout


def test_non_photon_error_propagates():
    """A non-Photon exception propagates instead of passing through."""
    sdk = MockClient()
    session = mock_session()
    session.request.side_effect = RuntimeError("bug")
    hooks.PhotonHandle(sdk, session)
    with pytest.raises(RuntimeError):
        _emit(sdk)


@pytest.mark.parametrize(
    "caught_as",
    [
        PhotonCacheUnavailable,  # reaches the application instead of being passed through
        ConnectionClosedError,  # under the botocore name an application already handles
    ],
)
def test_cache_unavailable_reaches_the_application(caught_as):
    """An escalated cache-unavailable error propagates, under either of its names."""
    sdk = MockClient()
    session = mock_session()
    session.request.side_effect = PhotonCacheUnavailable(endpoint_url="https://host/")
    hooks.PhotonHandle(sdk, session)

    with pytest.raises(caught_as):
        _emit(sdk)
