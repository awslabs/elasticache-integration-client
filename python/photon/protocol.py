"""RESP3 protocol layer for the Photon client.

This module builds the two Photon commands and interprets their replies:

- photon.config: submit configuration overrides, parse the resolved config reply.
- photon.request: send an intercepted HTTP request (method, url, normalized headers,
  body, metadata), parse the [status, headers, body] reply into a PhotonResponse.
"""

from __future__ import annotations

import json
from photon.exceptions import PhotonError, PhotonProtocolError, PhotonRejectedError
from photon.models import PhotonRequest, PhotonResponse, ResolvedConfig
from photon.resp import RespError, RespPush, RespValue

CONFIG_COMMAND = "photon.config"
REQUEST_COMMAND = "photon.request"

PUSH_KIND_RESPONSE = b"photon.response"
"""Push kind for an out-of-band response. The only kind defined today."""

# Headers excluded from the normalized form (case-insensitive). Auth/volatile headers must not
# affect the cache key or be forwarded.
_EXCLUDED_HEADERS = frozenset(
    {
        "authorization",
        "x-amz-date",
        "x-amz-security-token",
        "x-amz-request-id",
        "amz-sdk-request",
    }
)

_MIN_STATUS = 100
_MAX_STATUS = 599


# --------------------------------------------------------------------------- #
# Header normalization & request/response (de)serialization
# --------------------------------------------------------------------------- #
def normalize_headers(headers: dict[str, str]) -> bytes:
    """Produce the deterministic normalized header string as bytes.

    Lowercase names, drop excluded headers, trim values, sort by name, join ``name:value`` with
    LF. Returns ``b""`` when no headers remain.
    """
    pairs = sorted(
        (n, v)
        for n, v in ((k.strip().lower(), v.strip()) for k, v in headers.items())
        if n not in _EXCLUDED_HEADERS
    )
    return "\n".join(f"{n}:{v}" for n, v in pairs).encode()


def _parse_headers(raw_headers: bytes) -> dict[str, str]:
    """Parse a normalized header blob (LF-separated ``name:value``) into a dict."""
    try:
        text = raw_headers.decode()
    except UnicodeDecodeError as exc:
        raise PhotonProtocolError("Invalid UTF-8 in response headers") from exc
    return {
        key.strip(): value.strip()
        for line in text.split("\n")
        for key, sep, value in [line.partition(":")]
        if sep
    }


def serialize_config(overrides: dict | None) -> tuple[str, list[bytes]]:
    """Build the ``photon.config`` command. Three arguments, three distinct meanings::

        None           photon.config              my overrides are unchanged
        {}             photon.config {}           reset me to your defaults
        {"k": "v"}     photon.config {"k":"v"}    these are my overrides

    ``None`` and ``{}`` are opposite intents, so they are told apart with ``is None`` rather
    than falsiness: collapsing them would discard the session's overrides on every refresh
    tick. The refresher sends ``None``; connection bring-up sends the dict.

    Reading an explicit ``{}`` as "discard the stored overrides" is the server's behavior, not
    this function's — here it is serialized and sent like any other dict.
    """
    if overrides is None:
        return CONFIG_COMMAND, []
    return CONFIG_COMMAND, [json.dumps(overrides, separators=(",", ":")).encode()]


def parse_config_response(value: RespValue) -> ResolvedConfig:
    """Parse the ``photon.config`` reply (a JSON bulk string) into a ResolvedConfig.

    The reply is a JSON bulk string; a RESP-array shape is intentionally not supported.
    """
    if isinstance(value, RespError):
        raise PhotonError(f"photon.config failed: {value}")
    if not isinstance(value, bytes):
        raise PhotonError(
            f"Malformed config response: expected JSON bulk string, got {type(value).__name__}"
        )
    try:
        raw = json.loads(value)
    except (ValueError, TypeError) as exc:
        raise PhotonError(f"Malformed config JSON: {value!r}") from exc
    if not isinstance(raw, dict):
        raise PhotonError(f"Config JSON must be an object, got {type(raw).__name__}")
    refresh_interval = _parse_refresh_interval(raw.get("refresh_interval"))
    return ResolvedConfig(
        version=raw.get("version"),
        refresh_interval=refresh_interval,
        raw=raw,
    )


def _parse_refresh_interval(value: object) -> int:
    """Validate the ``refresh_interval`` field: required, ``int``, and positive.
    ``refresh_interval`` feeds directly into :class:`~photon.refresh.Refresher`'s jittered
    delay arithmetic, so a missing, wrongly-typed, or non-positive value is considered malformed.
    """
    if value is None:
        raise PhotonError("Config JSON missing required field 'refresh_interval'")
    if type(value) is not int:
        raise PhotonError(
            f"Invalid 'refresh_interval': expected int, got {type(value).__name__}: {value!r}"
        )
    if value <= 0:
        raise PhotonError(f"Invalid 'refresh_interval': must be positive, got {value}")
    return value


def serialize_request(request: PhotonRequest) -> tuple[str, list[bytes]]:
    """Build the complete ``photon.request`` command from a PhotonRequest.

    Args are ``[method, url, normalized_headers, body, metadata]``.
    """
    method = request.method.encode()
    url = request.url.encode()
    headers = normalize_headers(request.headers)
    body = request.body
    metadata = f"service={request.metadata.service}&op={request.metadata.operation}".encode()
    return REQUEST_COMMAND, [method, url, headers, body, metadata]


def parse_request_response(value: RespValue) -> PhotonResponse:
    """Interpret a ``photon.request`` reply into a PhotonResponse.

    Expects a 3-element array ``[status, headers, body]``.
    """
    if isinstance(value, RespError):
        raise PhotonRejectedError(value.code, f"photon.request failed: {value}")
    if not isinstance(value, list) or len(value) != 3:
        raise PhotonProtocolError(
            f"Malformed request response: expected 3-element array, got {type(value).__name__}"
        )
    status_raw, headers_raw, body_raw = value
    if not isinstance(headers_raw, bytes) or not isinstance(body_raw, bytes):
        raise PhotonProtocolError(
            f"Malformed request response elements: "
            f"headers={type(headers_raw).__name__}, body={type(body_raw).__name__}"
        )
    try:
        status = int(status_raw)
    except (TypeError, ValueError) as exc:
        raise PhotonProtocolError(f"Invalid status code: {status_raw!r}") from exc
    if not (_MIN_STATUS <= status <= _MAX_STATUS):
        raise PhotonProtocolError(f"Status code out of range (100-599): {status}")
    return PhotonResponse(
        status_code=status,
        headers=_parse_headers(headers_raw),
        body=body_raw,
    )


def parse_response_push(push: RespPush) -> tuple[str, RespValue]:
    """Split a ``photon.response`` push into the request id it answers and the reply for it.

    Raises :class:`~photon.exceptions.PhotonProtocolError`, not :class:`PhotonError`: a push
    that cannot be read leaves the whole stream untrustworthy.
    """
    if len(push.items) != 2:
        raise PhotonProtocolError(f"photon.response push takes 2 items, got {len(push.items)}")
    request_id, reply = push.items
    if not isinstance(request_id, bytes):
        raise PhotonProtocolError(f"Invalid push request id: {request_id!r}")
    return request_id.decode(), reply
