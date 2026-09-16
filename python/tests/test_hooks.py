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
    PhotonError("boom"),
    PhotonProtocolError("malformed reply"),
])
def test_response_level_failure_passes_through(error):
    """When Photon replied with something unusable, the handler returns None so botocore
    sends the real request."""
    sdk = MockClient()
    session = mock_session()
    session.request.side_effect = error
    hooks.PhotonHandle(sdk, session)
    assert _emit(sdk) is None


@pytest.mark.parametrize("photon_error,botocore_error", [
    (PhotonTimeoutError("timed out"), ReadTimeoutError),
    (PhotonConnectionLost("socket died"), ConnectionClosedError),
    (PhotonConnectionError("connect failed"), EndpointConnectionError),
])
def test_transport_failure_raises_botocore_error(photon_error, botocore_error):
    """A transport failure raises botocore's own error, chained to the Photon error, and
    never passes through."""
    sdk = MockClient()
    session = mock_session()
    session.request.side_effect = photon_error
    hooks.PhotonHandle(sdk, session)
    with pytest.raises(botocore_error) as exc_info:
        _emit(sdk)
    assert exc_info.value.__cause__ is photon_error


def test_non_photon_error_propagates():
    """A non-Photon exception propagates instead of passing through."""
    sdk = MockClient()
    session = mock_session()
    session.request.side_effect = RuntimeError("bug")
    hooks.PhotonHandle(sdk, session)
    with pytest.raises(RuntimeError):
        _emit(sdk)


def test_cache_unavailable_is_not_swallowed_by_hooks():
    """Escalated cache-unavailable errors reach the application instead of passing through."""
    sdk = MockClient()
    session = mock_session()
    session.request.side_effect = PhotonCacheUnavailable(endpoint_url="https://host/")
    hooks.PhotonHandle(sdk, session)

    with pytest.raises(PhotonCacheUnavailable):
        _emit(sdk)


def test_cache_unavailable_is_catchable_as_a_botocore_transport_error():
    """Cache-unavailable errors remain catchable as botocore transport errors.

    This keeps Photon compatible with applications that already handle AWS
    transport errors.
    """
    sdk = MockClient()
    session = mock_session()
    session.request.side_effect = PhotonCacheUnavailable(endpoint_url="https://host/")
    hooks.PhotonHandle(sdk, session)

    with pytest.raises(ConnectionClosedError):
        _emit(sdk)
