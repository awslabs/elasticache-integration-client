"""Unit tests for photon.hooks."""

import logging
from unittest import mock

import pytest
from botocore.hooks import first_non_none_response

from photon import hooks
from photon.exceptions import PhotonConnectionError, PhotonError, PhotonTimeoutError
from tests.mock import MockClient, mock_session


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


# --- fall-through: a failed Photon path never reaches the application ----------

def test_declined_request_passes_through_without_warning(caplog):
    """No predicate matched: the handler returns None so botocore sends the real request.

    A decline is the normal per-request steady state (e.g. a write, or an excluded operation), so it
    must not be logged as a warning — that would flood a customer's logs.
    """
    sdk = MockClient()
    session = mock_session()
    session.request.return_value = None
    hooks.PhotonHandle(sdk, session)

    with caplog.at_level(logging.WARNING, logger="photon.hooks"):
        assert _emit(sdk) is None
    session.request.assert_called_once()
    assert caplog.records == []


@pytest.mark.parametrize("error", [
    PhotonError("boom"),
    PhotonConnectionError("connect failed"),
    PhotonTimeoutError("timed out"),
])
def test_photon_failure_passes_through(error, caplog):
    """When the Photon path fails, the handler returns None so botocore sends the real request.

    Unlike a decline, a failure IS logged — it means the cache did not serve a request it should
    have (counterpart to test_declined_request_passes_through_without_warning).
    """
    sdk = MockClient()
    session = mock_session()
    session.request.side_effect = error
    hooks.PhotonHandle(sdk, session)
    with caplog.at_level(logging.WARNING, logger="photon.hooks"):
        assert _emit(sdk) is None
    assert len(caplog.records) == 1


def test_non_photon_error_propagates():
    """A non-Photon exception is our bug and propagates instead of passing through."""
    sdk = MockClient()
    session = mock_session()
    session.request.side_effect = RuntimeError("bug")
    hooks.PhotonHandle(sdk, session)
    with pytest.raises(RuntimeError):
        _emit(sdk)
