"""Unit tests for aws_elasticache_integration_client.hooks."""

import io

from array import array
from unittest import mock

import pytest
from botocore.hooks import first_non_none_response
from botocore.httpchecksum import AwsChunkedWrapper

from aws_elasticache_integration_client import hooks
from aws_elasticache_integration_client.exceptions import (
    ElastiCacheIntegrationsAuthError,
    ElastiCacheIntegrationsCacheUnavailable,
    ElastiCacheIntegrationsConnectionError,
    ElastiCacheIntegrationsConnectionLost,
    ElastiCacheIntegrationsError,
    ElastiCacheIntegrationsOriginError,
    ElastiCacheIntegrationsProtocolError,
    ElastiCacheIntegrationsRejectedError,
    ElastiCacheIntegrationsRequestNotSent,
    ElastiCacheIntegrationsTimeoutError,
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
    hooks.ElastiCacheIntegrationsHandle(sdk, session)
    response = _emit(sdk)
    assert response is not None
    assert response.status_code == 200
    session.request.assert_called_once()


def test_close_tears_down_the_cache_session():
    """Calling the client's own close() detaches the handler, closes the ElastiCacheIntegrations session, and
    chains to the original close. After close, an emit no longer reaches ElastiCacheIntegrations."""
    sdk = MockClient()
    session = mock_session()
    hooks.ElastiCacheIntegrationsHandle(sdk, session)
    sdk.close()
    assert sdk.closed is True  # original close ran
    session.close.assert_called_once()
    assert _emit(sdk) is None  # handler unregistered: emit reaches no handler
    session.request.assert_not_called()


def test_close_closes_the_client_even_if_the_session_close_raises():
    """A failing session close must not leave the client open with our close still installed."""
    sdk = MockClient()
    boto3_close = sdk.close
    session = mock_session()
    session.close.side_effect = RuntimeError("connector close failed")
    hooks.ElastiCacheIntegrationsHandle(sdk, session)

    with pytest.raises(RuntimeError, match="connector close failed"):
        sdk.close()

    assert sdk.closed is True  # the client was closed anyway
    assert sdk.close == boto3_close  # and its own close is back
    assert _emit(sdk) is None  # the handler was detached before the failure


def test__aws_to_cache_request_maps_fields():
    """Maps a botocore prepared request to a ElastiCacheIntegrationsRequest, normalizing a None body to empty bytes."""
    request = mock.Mock(method="GET", url="https://host/path", headers={"Host": "host"}, body=None)

    result = hooks._aws_to_cache_request(request, "before-send.service.Operation")

    assert result.method == "GET"
    assert result.url == "https://host/path"
    assert result.headers == {"Host": "host"}
    assert result.body == b""  # a None body is normalized to empty bytes


def test_undecodable_header_value_raises_elasticache_integrations_error():
    """A non-UTF-8 bytes header value raises ElastiCacheIntegrationsError, not a bare UnicodeDecodeError."""
    request = mock.Mock(
        method="POST", url="https://host/", headers={"x-odd": b"\xff\xfe"}, body=b"{}"
    )
    with pytest.raises(ElastiCacheIntegrationsError):
        hooks._aws_to_cache_request(request, "before-send.service.Operation")


def test_undecodable_header_value_passes_through():
    """Through the handler, an undecodable header degrades to pass-through: the app's request
    proceeds to the service instead of crashing."""
    sdk = MockClient()
    session = mock_session()
    hooks.ElastiCacheIntegrationsHandle(sdk, session)
    request = mock.Mock(
        method="POST", url="https://host/", headers={"x-odd": b"\xff\xfe"}, body=b"{}"
    )
    responses = sdk.meta.events.emit("before-send.service.Operation", request=request)
    assert first_non_none_response(responses) is None
    session.request.assert_not_called()


@pytest.mark.parametrize(
    "body,expected",
    [
        (None, b""),
        ("", b""),
        (b"raw", b"raw"),
        (
            "Action=GetCallerIdentity&Version=2011-06-15",
            b"Action=GetCallerIdentity&Version=2011-06-15",
        ),
        (bytearray(b"mutable"), b"mutable"),
        (memoryview(b"buffered"), b"buffered"),
        (bytearray(), b""),
    ],
)
def test_body_conversion(body, expected):
    request = mock.Mock(method="POST", url="https://host/", headers={}, body=body)
    result = hooks._aws_to_cache_request(request, "before-send.sts.GetCallerIdentity")
    assert result.body == expected
    assert type(result.body) is bytes  # encode_resp needs real bytes, not a buffer view


def test_body_conversion_does_not_copy_bytes():
    """The json-protocol path is every DynamoDB request, so normalizing must not copy the body."""
    payload = b'{"TableName": "t"}'
    request = mock.Mock(method="POST", url="https://host/", headers={}, body=payload)
    result = hooks._aws_to_cache_request(request, "before-send.dynamodb.GetItem")
    assert result.body is payload


@pytest.mark.parametrize(
    "body",
    [
        io.BytesIO(b"payload"),
        AwsChunkedWrapper(io.BytesIO(b"payload")),  # what s3 put_object actually delivers
        array("b"),  # falsy, so classification must not rely on truthiness
        0,
        False,
    ],
)
def test_uncarriable_bodies_are_declined(body):
    """A body is classified by type, never by truthiness. Anything that is not bytes-like is a
    stream, and sending a falsy one as ``b""`` would put a request ElastiCacheIntegrations never read in the cache.
    """
    request = mock.Mock(method="PUT", url="https://host/", headers={}, body=body)
    assert hooks._aws_to_cache_request(request, "before-send.s3.PutObject") is None


def test_streaming_body_passes_through():
    sdk = MockClient()
    session = mock_session()
    hooks.ElastiCacheIntegrationsHandle(sdk, session)
    assert _emit(sdk, body=io.BytesIO(b"payload")) is None
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
    metadata = hooks._aws_to_cache_request(request, event_name).metadata
    assert (metadata.service, metadata.operation) == expected


@pytest.mark.parametrize("event_name", [None, "", "service", "before-send"])
def test_missing_or_malformed_event_name_raises(event_name):
    """A missing or malformed event name is misuse and raises ElastiCacheIntegrationsError."""
    request = mock.Mock(method="GET", url="https://host/path", headers={}, body=None)
    with pytest.raises(ElastiCacheIntegrationsError):
        hooks._aws_to_cache_request(request, event_name)


def test_dropped_idempotent_elasticache_integrations_failure_is_sdk_retryable():
    # The SDK retries it, and the retry goes through the cache again.
    sdk = MockClient()
    session = mock_session()
    session.is_connection_healthy = False
    session.request.side_effect = ElastiCacheIntegrationsConnectionLost("socket died")
    hooks.ElastiCacheIntegrationsHandle(sdk, session)
    request = mock.Mock(method="POST", url="https://host/", headers={}, body=b"{}")
    with pytest.raises(ConnectionClosedError):
        sdk.meta.events.emit("before-send.dynamodb.GetItem", request=request)


def test_dropped_idempotent_timeout_is_sdk_retryable():
    sdk = MockClient()
    session = mock_session()
    session.is_connection_healthy = False
    session.request.side_effect = ElastiCacheIntegrationsTimeoutError("timed out")
    hooks.ElastiCacheIntegrationsHandle(sdk, session)
    request = mock.Mock(method="POST", url="https://host/", headers={}, body=b"{}")
    with pytest.raises(ReadTimeoutError):
        sdk.meta.events.emit("before-send.dynamodb.GetItem", request=request)


@pytest.mark.parametrize(
    "cache_error",
    [
        ElastiCacheIntegrationsTimeoutError("timed out"),
        ElastiCacheIntegrationsConnectionLost("socket died"),
        ElastiCacheIntegrationsConnectionError("connect failed"),
    ],
)
def test_non_idempotent_transport_failure_is_non_retryable(cache_error):
    """A non-idempotent transport failure is not marked retryable."""
    sdk = MockClient()
    session = mock_session()
    session.request.side_effect = cache_error
    hooks.ElastiCacheIntegrationsHandle(sdk, session)
    with pytest.raises(type(cache_error)) as exc_info:
        request = mock.Mock(method="POST", url="https://host/", headers={}, body=b"{}")
        sdk.meta.events.emit("before-send.dynamodb.UpdateItem", request=request)
    assert exc_info.value is cache_error


def test_non_idempotent_request_not_sent_passes_through_to_origin():
    sdk = MockClient()
    session = mock_session()
    session.request.side_effect = ElastiCacheIntegrationsRequestNotSent("admission timed out")
    hooks.ElastiCacheIntegrationsHandle(sdk, session)
    request = mock.Mock(method="POST", url="https://host/", headers={}, body=b"{}")

    responses = sdk.meta.events.emit("before-send.dynamodb.UpdateItem", request=request)

    assert first_non_none_response(responses) is None


def test_healthy_idempotent_deferred_timeout_is_sdk_retryable():
    sdk = MockClient()
    session = mock_session()
    session.request.side_effect = ElastiCacheIntegrationsTimeoutError("timed out")
    hooks.ElastiCacheIntegrationsHandle(sdk, session)
    request = mock.Mock(method="POST", url="https://host/", headers={}, body=b"{}")
    with pytest.raises(ReadTimeoutError):
        sdk.meta.events.emit("before-send.dynamodb.GetItem", request=request)


@pytest.mark.parametrize(
    "cache_error",
    [
        ElastiCacheIntegrationsConnectionLost("socket died"),
        ElastiCacheIntegrationsConnectionError("connect failed"),
    ],
)
def test_idempotent_connection_errors_are_sdk_retryable(cache_error):
    sdk = MockClient()
    session = mock_session()
    session.request.side_effect = cache_error
    hooks.ElastiCacheIntegrationsHandle(sdk, session)
    request = mock.Mock(method="POST", url="https://host/", headers={}, body=b"{}")
    with pytest.raises(ConnectionClosedError) as exc_info:
        sdk.meta.events.emit("before-send.dynamodb.GetItem", request=request)
    assert exc_info.value.__cause__ is cache_error


def test_non_origin_rejection_is_non_retryable_and_does_not_fallback():
    sdk = MockClient()
    session = mock_session()
    session.is_connection_healthy = False
    rejection = ElastiCacheIntegrationsRejectedError("UNSUPPORTED", "rejected")
    session.request.side_effect = rejection
    hooks.ElastiCacheIntegrationsHandle(sdk, session)
    request = mock.Mock(method="POST", url="https://host/", headers={}, body=b"{}")
    with pytest.raises(ElastiCacheIntegrationsRejectedError) as exc_info:
        sdk.meta.events.emit("before-send.dynamodb.GetItem", request=request)
    assert exc_info.value is rejection


def test_auth_rejection_is_not_converted_to_a_connection_error():
    sdk = MockClient()
    session = mock_session()
    rejection = ElastiCacheIntegrationsAuthError("IAM AUTH rejected: WRONGPASS")
    session.request.side_effect = rejection
    hooks.ElastiCacheIntegrationsHandle(sdk, session)
    request = mock.Mock(method="POST", url="https://host/", headers={}, body=b"{}")

    with pytest.raises(ElastiCacheIntegrationsAuthError) as exc_info:
        sdk.meta.events.emit("before-send.dynamodb.GetItem", request=request)

    assert exc_info.value is rejection


def test_idempotent_origin_error_is_sdk_retryable_without_fallback():
    sdk = MockClient()
    session = mock_session()
    rejection = ElastiCacheIntegrationsRejectedError("ORIGIN_ERROR", "origin unavailable")
    session.request.side_effect = rejection
    hooks.ElastiCacheIntegrationsHandle(sdk, session)
    request = mock.Mock(method="POST", url="https://host/", headers={}, body=b"{}")
    with pytest.raises(ElastiCacheIntegrationsOriginError) as exc_info:
        sdk.meta.events.emit("before-send.dynamodb.GetItem", request=request)
    assert exc_info.value.__cause__ is rejection


def test_non_idempotent_origin_error_is_non_retryable_without_fallback():
    sdk = MockClient()
    session = mock_session()
    rejection = ElastiCacheIntegrationsRejectedError("ORIGIN_ERROR", "origin unavailable")
    session.request.side_effect = rejection
    hooks.ElastiCacheIntegrationsHandle(sdk, session)
    request = mock.Mock(method="POST", url="https://host/", headers={}, body=b"{}")
    with pytest.raises(ElastiCacheIntegrationsRejectedError) as exc_info:
        sdk.meta.events.emit("before-send.dynamodb.UpdateItem", request=request)
    assert exc_info.value is rejection


@pytest.mark.parametrize("operation", ["GetItem", "UpdateItem"])
def test_malformed_response_is_non_retryable(operation):
    sdk = MockClient()
    session = mock_session()
    session.is_connection_healthy = False
    protocol_error = ElastiCacheIntegrationsProtocolError("malformed reply")
    session.request.side_effect = protocol_error
    hooks.ElastiCacheIntegrationsHandle(sdk, session)
    request = mock.Mock(method="POST", url="https://host/", headers={}, body=b"{}")
    with pytest.raises(ElastiCacheIntegrationsProtocolError) as exc_info:
        sdk.meta.events.emit(f"before-send.dynamodb.{operation}", request=request)
    assert exc_info.value is protocol_error


def test_shutdown_failure_does_not_start_idempotent_fallback():
    sdk = MockClient()
    session = mock_session()
    session.is_open = False
    timeout = ElastiCacheIntegrationsTimeoutError("closed")
    session.request.side_effect = timeout
    hooks.ElastiCacheIntegrationsHandle(sdk, session)
    request = mock.Mock(method="POST", url="https://host/", headers={}, body=b"{}")
    with pytest.raises(ElastiCacheIntegrationsTimeoutError) as exc_info:
        sdk.meta.events.emit("before-send.dynamodb.GetItem", request=request)
    assert exc_info.value is timeout


def test_non_elasticache_integrations_error_propagates():
    """A non-ElastiCacheIntegrations exception propagates instead of passing through."""
    sdk = MockClient()
    session = mock_session()
    session.request.side_effect = RuntimeError("bug")
    hooks.ElastiCacheIntegrationsHandle(sdk, session)
    with pytest.raises(RuntimeError):
        _emit(sdk)


@pytest.mark.parametrize(
    "caught_as",
    [
        ElastiCacheIntegrationsCacheUnavailable,  # reaches the application instead of being passed through
        ConnectionClosedError,  # under the botocore name an application already handles
    ],
)
def test_cache_unavailable_reaches_the_application(caught_as):
    """An escalated cache-unavailable error propagates, under either of its names."""
    sdk = MockClient()
    session = mock_session()
    session.request.side_effect = ElastiCacheIntegrationsCacheUnavailable(
        endpoint_url="https://host/"
    )
    hooks.ElastiCacheIntegrationsHandle(sdk, session)

    with pytest.raises(caught_as):
        _emit(sdk)
