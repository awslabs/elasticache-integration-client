# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

"""RESP3 protocol layer for the ElastiCacheIntegrations client.

This module builds the two ElastiCacheIntegrations commands and interprets their replies:

- aws.config: submit configuration overrides, parse the resolved config reply.
- aws.request: send an intercepted HTTP request (method, url, normalized headers,
  body, metadata), parse the [status, headers, body] reply into a ElastiCacheIntegrationsResponse.
"""

from __future__ import annotations

import json
from aws_elasticache_integration_client.exceptions import (
    ElastiCacheIntegrationsError,
    ElastiCacheIntegrationsOverridesRejected,
    ElastiCacheIntegrationsProtocolError,
    ElastiCacheIntegrationsRejectedError,
)
from aws_elasticache_integration_client.models import (
    ElastiCacheIntegrationsRequest,
    ElastiCacheIntegrationsResponse,
    ResolvedConfig,
)
from aws_elasticache_integration_client.resp import RespError, RespPush, RespValue

CONFIG_COMMAND = "aws.config"
REQUEST_COMMAND = "aws.request"

PROTOCOL_VERSION = "1.0"
"""Protocol version this client speaks, sent in every ``aws.config`` overrides payload.

Mandatory server-side: an exchange without it is rejected rather than defaulted. The server
answers in the newest version it supports at or below this one, so a client ahead of the server
is clamped down rather than refused -- and learns which version it got from
:attr:`ResolvedConfig.version`, which carries the *negotiated* version, not an echo of this.
"""

CONFIG_INVALID_CODE = "CONFIG_INVALID"
"""RESP error code the cache returns when it rejects the ``aws.config`` overrides."""

PUSH_KIND_RESPONSE = b"aws.response"
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
        raise ElastiCacheIntegrationsProtocolError("Invalid UTF-8 in response headers") from exc
    return {
        key.strip(): value.strip()
        for line in text.split("\n")
        for key, sep, value in [line.partition(":")]
        if sep
    }


def serialize_config(overrides: dict | None) -> tuple[str, list[bytes]]:
    """Build the ``aws.config`` command. Two arguments, two distinct meanings::

        None           aws.config              my overrides are unchanged
        {"k": "v"}     aws.config {...}        these are my overrides

    ``None`` and ``{}`` are opposite intents, so they are told apart with ``is None`` rather
    than falsiness: collapsing them would discard the session's overrides on every refresh
    tick. The refresher sends ``None``; connection bring-up sends the dict.

    ``{}`` is no longer "reset me to your defaults": the server requires a ``version``, so the
    bare-defaults form is ``{"version": PROTOCOL_VERSION}``. This function supplies the version
    on every dict it sends, so a caller passing ``{}`` still gets the defaults form -- it just
    goes out carrying a version.

    The version is injected first and can be overridden by the caller's own ``version`` key,
    which is how a test drives a version the client would not otherwise send.
    """
    if overrides is None:
        return CONFIG_COMMAND, []
    payload = {"version": PROTOCOL_VERSION, **overrides}
    return CONFIG_COMMAND, [json.dumps(payload, separators=(",", ":")).encode()]


def parse_config_response(value: RespValue) -> ResolvedConfig:
    """Parse the ``aws.config`` reply (a JSON bulk string) into a ResolvedConfig.

    The reply is a JSON bulk string; a RESP-array shape is intentionally not supported.
    """
    if isinstance(value, RespError):
        if value.code == CONFIG_INVALID_CODE:
            raise ElastiCacheIntegrationsOverridesRejected(f"aws.config rejected: {value}")
        raise ElastiCacheIntegrationsError(f"aws.config failed: {value}")
    if not isinstance(value, bytes):
        raise ElastiCacheIntegrationsError(
            f"Malformed config response: expected JSON bulk string, got {type(value).__name__}"
        )
    try:
        raw = json.loads(value)
    except (ValueError, TypeError) as exc:
        raise ElastiCacheIntegrationsError(f"Malformed config JSON: {value!r}") from exc
    if not isinstance(raw, dict):
        raise ElastiCacheIntegrationsError(
            f"Config JSON must be an object, got {type(raw).__name__}"
        )
    refresh_interval = _parse_refresh_interval(raw.get("refresh_interval"))
    return ResolvedConfig(
        version=raw.get("version"),
        refresh_interval=refresh_interval,
        raw=raw,
    )


def _parse_refresh_interval(value: object) -> int:
    """Validate the ``refresh_interval`` field: required, ``int``, and positive.
    ``refresh_interval`` feeds directly into :class:`~aws_elasticache_integration_client.refresh.Refresher`'s jittered
    delay arithmetic, so a missing, wrongly-typed, or non-positive value is considered malformed.
    """
    if value is None:
        raise ElastiCacheIntegrationsError("Config JSON missing required field 'refresh_interval'")
    if type(value) is not int:
        raise ElastiCacheIntegrationsError(
            f"Invalid 'refresh_interval': expected int, got {type(value).__name__}: {value!r}"
        )
    if value <= 0:
        raise ElastiCacheIntegrationsError(
            f"Invalid 'refresh_interval': must be positive, got {value}"
        )
    return value


def serialize_request(request: ElastiCacheIntegrationsRequest) -> tuple[str, list[bytes]]:
    """Build the complete ``aws.request`` command from a ElastiCacheIntegrationsRequest.

    Args are ``[method, url, normalized_headers, body, metadata]``.
    """
    method = request.method.encode()
    url = request.url.encode()
    headers = normalize_headers(request.headers)
    body = request.body
    metadata = f"service={request.metadata.service}&op={request.metadata.operation}".encode()
    return REQUEST_COMMAND, [method, url, headers, body, metadata]


def parse_request_response(value: RespValue) -> ElastiCacheIntegrationsResponse:
    """Interpret a ``aws.request`` reply into a ElastiCacheIntegrationsResponse.

    Expects a 3-element array ``[status, headers, body]``.
    """
    if isinstance(value, RespError):
        raise ElastiCacheIntegrationsRejectedError(value.code, f"aws.request failed: {value}")
    if not isinstance(value, list) or len(value) != 3:
        raise ElastiCacheIntegrationsProtocolError(
            f"Malformed request response: expected 3-element array, got {type(value).__name__}"
        )
    status_raw, headers_raw, body_raw = value
    if not isinstance(headers_raw, bytes) or not isinstance(body_raw, bytes):
        raise ElastiCacheIntegrationsProtocolError(
            f"Malformed request response elements: "
            f"headers={type(headers_raw).__name__}, body={type(body_raw).__name__}"
        )
    try:
        if not isinstance(status_raw, (int, str, bytes)):
            raise TypeError(f"status is {type(status_raw).__name__}")
        status = int(status_raw)
    except (TypeError, ValueError) as exc:
        raise ElastiCacheIntegrationsProtocolError(f"Invalid status code: {status_raw!r}") from exc
    if not (_MIN_STATUS <= status <= _MAX_STATUS):
        raise ElastiCacheIntegrationsProtocolError(f"Status code out of range (100-599): {status}")
    return ElastiCacheIntegrationsResponse(
        status_code=status,
        headers=_parse_headers(headers_raw),
        body=body_raw,
    )


def parse_response_push(push: RespPush) -> tuple[str, RespValue]:
    """Split a ``aws.response`` push into the request id it answers and the reply for it.

    Raises :class:`~aws_elasticache_integration_client.exceptions.ElastiCacheIntegrationsProtocolError`, not :class:`ElastiCacheIntegrationsError`: a push
    that cannot be read leaves the whole stream untrustworthy.
    """
    if len(push.items) != 2:
        raise ElastiCacheIntegrationsProtocolError(
            f"aws.response push takes 2 items, got {len(push.items)}"
        )
    request_id, reply = push.items
    if not isinstance(request_id, bytes):
        raise ElastiCacheIntegrationsProtocolError(f"Invalid push request id: {request_id!r}")
    return request_id.decode(), reply
