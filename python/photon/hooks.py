"""boto3 ``before-send`` interception for the Photon client.

Registers a ``before-send`` handler on a botocore client's event system. The handler builds an
:class:`PhotonRequest` from the prepared botocore request, calls the :class:`PhotonSession`, and
returns a botocore ``AWSResponse`` to short-circuit the real AWS call.

Where Photon hooks into the boto3 client event lifecycle
(see https://docs.aws.amazon.com/boto3/latest/guide/events.html)::

    1. creating-client-class
    2. creating-resource-class
    3. provide-client-params
    4. before-call
    5. request-created
    6. before-send   <=== PHOTON HANDLER
    7. needs-retry
    8. after-call
    9. after-call-error

At ``before-send`` the HTTP request is fully built and SigV4-signed, so the handler has the final
method/url/headers/body to key on. botocore takes the first non-None ``before-send`` result: the
Photon handler adapts the request, queries the ElastiCache cache, and returns a synthesized
``AWSResponse`` to serve the call without touching the network. The synthesized response then
re-enters the normal pipeline (steps 7-9), so retry and parsing behave exactly as for a real call.
(Returning ``None`` instead would let botocore fall through to the real AWS send.)
"""

from __future__ import annotations

import logging

from io import BytesIO

from photon.exceptions import (
    PhotonConnectionError,
    PhotonConnectionLost,
    PhotonError,
    PhotonOriginError,
    PhotonRejectedError,
    PhotonTimeoutError,
)
from photon.models import PhotonRequest, PhotonResponse, RequestMetadata
from photon.idempotency import is_idempotent_operation
from photon.session import PhotonSession

from botocore.awsrequest import AWSPreparedRequest, AWSResponse
from botocore.client import BaseClient
from botocore.exceptions import ConnectionClosedError, ReadTimeoutError
from urllib3.response import HTTPResponse as Urllib3Response

_BEFORE_SEND = "before-send"

_UNIQUE_ID = "photon"

logger = logging.getLogger(__name__)

_ORIGIN_ERROR = "ORIGIN_ERROR"


#: Prepared-body types Photon can put in a single ``photon.request`` frame. Anything else is a
#: stream: reading it to serialize would consume it, leaving nothing for the real send.
_CARRIABLE_BODY_TYPES = (bytes, bytearray, memoryview, str)


def _is_carriable_body(body: object) -> bool:
    """Whether ``body`` fits in one frame. ``None`` means no body at all, which does."""
    return body is None or isinstance(body, _CARRIABLE_BODY_TYPES)


def _body_as_bytes(body: bytes | bytearray | memoryview | str | None) -> bytes:
    """Normalize a carriable prepared body to bytes. A missing body is an empty body."""
    if body is None:
        return b""
    if isinstance(body, bytes):  # the common path (json protocols); no copy
        return body
    if isinstance(body, str):  # query protocols: sts, ec2, cloudformation, iam, rds
        return body.encode()
    return bytes(body)


def _aws_to_photon_request(request: AWSPreparedRequest, event_name: str) -> PhotonRequest | None:
    """Build an internal PhotonRequest from a botocore prepared request and event name.

    Returns None for a streaming body; the caller passes the request through.

    boto3 builds an ``AWSPreparedRequest`` for every SDK call: the final HTTP request —
    method, url, headers, body — after serialization and signing, exactly what would be
    sent to AWS (https://docs.aws.amazon.com/boto3/latest/guide/events.html#before-send).

    ``event_name`` is the name of the event this handler was called for. It carries the
    service and operation of the SDK call::

        before-send.dynamodb.GetItem
                 │      │        │
           base event  service   operation

    We take the service and operation from the event name because their location in the
    HTTP request differs between AWS services, while the event name always has them in
    the same place. They are stored as the request's metadata and will later be used to
    decide which requests are cacheable (predicates).

    The event name is required: botocore always supplies it, so a missing or malformed
    name is a bug and raises PhotonError.
    """
    try:
        _, service, operation = event_name.split(".", 2)
    except (AttributeError, ValueError) as exc:
        raise PhotonError(f"Malformed before-send event name: {event_name!r}") from exc
    # header values arrive from botocore as bytes; convert them to strings
    try:
        headers = {
            k: v.decode() if isinstance(v, bytes) else v for k, v in (request.headers or {}).items()
        }
    except UnicodeDecodeError as exc:
        raise PhotonError("Undecodable header value in prepared request") from exc
    if not _is_carriable_body(request.body):
        return None
    return PhotonRequest(
        method=request.method,
        url=request.url,
        headers=headers,
        body=_body_as_bytes(request.body),
        metadata=RequestMetadata(service=service, operation=operation),
    )


def _photon_to_aws_response(url: str, response: PhotonResponse) -> AWSResponse:
    """Adapt a PhotonResponse into a botocore ``AWSResponse`` that short-circuits the SDK call."""
    # wrap body so botocore can read it as a stream
    raw = Urllib3Response(
        body=BytesIO(response.body), status=response.status_code, preload_content=False
    )
    return AWSResponse(
        url=url,
        status_code=response.status_code,
        headers=response.headers,
        raw=raw,
    )


class PhotonHandle:
    """Attaches Photon to a boto3 client for the lifetime of the client."""

    def __init__(self, sdk_client: BaseClient, session: PhotonSession) -> None:
        self._sdk_client = sdk_client
        self._session = session
        self._sdk_close = sdk_client.close  # kept so _close can chain to the original
        sdk_client.meta.events.register(_BEFORE_SEND, self._handle, unique_id=_UNIQUE_ID)
        sdk_client.close = self._close  # override so closing the client also tears Photon down

    def _close(self) -> None:
        """Replaces sdk_client.close: detach Photon, close the session, then the client itself.

        boto3's close() does not touch the event emitter, so the handler must be
        unregistered here; otherwise it keeps firing (and warning) on a client
        used after close.
        """
        self._sdk_client.meta.events.unregister(_BEFORE_SEND, unique_id=_UNIQUE_ID)
        self._session.close()
        self._sdk_close()

    def _handle(self, request: AWSPreparedRequest, **kwargs) -> AWSResponse | None:
        """The before-send handler: serve the request from Photon, or pass it through.

        Returning None tells botocore no handler served the request, so it sends the original
        request down the pipeline. A request with a streaming body is declined here, before it
        reaches the session, because serializing it would consume the stream. The session declines
        before sending when no predicate matched or no cache connection is available within the
        fallback window, which is safe for every operation. After sending, a timeout or lost
        connection becomes SDK-retryable only for a replay-safe operation, so the retry goes
        through the cache again. A healthy-connection ORIGIN_ERROR becomes SDK-retryable only for
        a replay-safe operation; other RESP errors remain terminal. Non-Photon exceptions
        propagate.
        """
        try:
            http_request = _aws_to_photon_request(request, kwargs.get("event_name", ""))
        except PhotonError:
            logger.warning(
                "Photon could not adapt the request; passing through to the service",
                exc_info=True,
            )
            return None
        if http_request is None:
            # Expected for every streaming upload, so debug rather than the warning above.
            logger.debug("Streaming request body; passing through to the service")
            return None

        try:
            response = self._session.request(http_request)
            if response is None:
                logger.debug(
                    "Photon declined %s; passing through to the service",
                    http_request.metadata.operation,
                )
                return None
            return _photon_to_aws_response(request.url, response)
        except PhotonError as exc:
            idempotent = is_idempotent_operation(
                http_request.metadata.service, http_request.metadata.operation
            )
            if isinstance(exc, PhotonRejectedError):
                if exc.code == _ORIGIN_ERROR and self._session.is_connection_healthy and idempotent:
                    raise PhotonOriginError(endpoint_url=request.url) from exc
                raise
            if (
                isinstance(
                    exc,
                    (PhotonTimeoutError, PhotonConnectionLost, PhotonConnectionError),
                )
                and self._session.is_open
                and idempotent
            ):
                # Let the SDK retry. The retry goes through the cache again, waits briefly for a
                # reconnect, and goes to the service only if the cache is still down.
                if isinstance(exc, PhotonTimeoutError):
                    raise ReadTimeoutError(endpoint_url=request.url) from exc
                raise ConnectionClosedError(endpoint_url=request.url) from exc
            raise
