# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

"""The client never logs, or puts in an error, credentials, the IAM token or customer data.

Every scenario runs a real boto3 client against the mock cache with marker values in the
credentials, the request and the response, and with the client's loggers at DEBUG. It then
checks that no marker, no part of the IAM token the cache received, and nothing shaped like an
AWS key or SigV4 signature appears in the client's log output or in any error it raised.
"""

from __future__ import annotations

import json
import logging
import re
from urllib.parse import parse_qsl, urlsplit

import boto3
import pytest
from botocore.config import Config

import aws_elasticache_integration_client
from aws_elasticache_integration_client import ElastiCacheIntegrationsClientConfiguration
from aws_elasticache_integration_client.models import (
    ElastiCacheIntegrationsRequest,
    RequestMetadata,
)
from tests.mock import MockCache, Reply, _error

ACCESS_KEY = "AKIDLEAKCANARYACCESS"
SECRET_KEY = "LEAKCANARYSECRETKEY"
SESSION_TOKEN = "LEAKCANARYSESSIONTOKEN"
REQUEST_VALUE = "LEAKCANARYREQUEST"
RESPONSE_VALUE = "LEAKCANARYRESPONSE"
MARKERS = (ACCESS_KEY, SECRET_KEY, SESSION_TOKEN, REQUEST_VALUE, RESPONSE_VALUE)

#: What a real secret looks like, in case one reaches a log some way the markers do not cover.
SECRET_SHAPES = re.compile(
    r"(?:AKIA|ASIA)[A-Z0-9]{16}|X-Amz-(?:Signature|Credential|Security-Token)|AWS4-HMAC-SHA256",
    re.IGNORECASE,
)

#: The loggers the client writes to: its own and the IAM toolkit's.
LOGGERS = ("aws_elasticache_integration_client", "developer_toolkit_elasticache")


class RejectingCache(MockCache):
    """A cache that rejects every AUTH, as it does for a token it does not accept."""

    def handle(self, command: list[bytes]) -> bytes:
        if command[0].lower() in (b"hello", b"auth") and len(command) > 2:
            self.received_commands.append(command)
            return _error("WRONGPASS", "invalid username-password pair or user is disabled")
        return super().handle(command)


def _client(**config):
    session = boto3.Session(
        aws_access_key_id=ACCESS_KEY,
        aws_secret_access_key=SECRET_KEY,
        aws_session_token=SESSION_TOKEN,
        region_name="us-east-1",
    )
    return session.client("dynamodb", config=Config(retries={"total_max_attempts": 1}, **config))


def _install(ddb, cache, **configuration):
    aws_elasticache_integration_client.install(
        client=ddb,
        endpoint=cache.endpoint,
        cache_name="test-cache",
        ca_certs=cache.cert_path,
        client_configuration=ElastiCacheIntegrationsClientConfiguration(
            connection_timeout=1000, **configuration
        ),
    )


def _get_item(ddb):
    return ddb.get_item(TableName="my-table", Key={"id": {"S": REQUEST_VALUE}})


def _served(errors):
    cache = MockCache(
        tls=True, response_body=json.dumps({"Item": {"v": {"S": RESPONSE_VALUE}}}).encode()
    )
    cache.start()
    ddb = _client()
    try:
        _install(ddb, cache)
        assert _get_item(ddb)["Item"] == {"v": {"S": RESPONSE_VALUE}}
    finally:
        ddb.close()
        cache.stop()
    return cache


def _rejected_request(errors):
    cache = MockCache(tls=True, response=Reply.error("ORIGIN_ERROR", "origin rejected"))
    cache.start()
    ddb = _client()
    try:
        _install(ddb, cache)
        with pytest.raises(Exception) as caught:
            _get_item(ddb)
        errors.append(caught.value)
    finally:
        ddb.close()
        cache.stop()
    return cache


def _timed_out(errors):
    cache = MockCache(tls=True, failure="hang")
    cache.start()
    ddb = _client()
    try:
        _install(ddb, cache, cache_request_timeout=200)
        with pytest.raises(Exception) as caught:
            _get_item(ddb)
        errors.append(caught.value)
    finally:
        ddb.close()
        cache.stop()
    return cache


def _dropped(errors):
    cache = MockCache(tls=True, failure="drop")
    cache.start()
    ddb = _client()
    try:
        _install(ddb, cache)
        with pytest.raises(Exception) as caught:
            _get_item(ddb)
        errors.append(caught.value)
    finally:
        ddb.close()
        cache.stop()
    return cache


def _auth_rejected(errors):
    cache = RejectingCache(tls=True)
    cache.start()
    ddb = _client()
    try:
        with pytest.raises(Exception) as caught:
            _install(ddb, cache)
        errors.append(caught.value)
    finally:
        ddb.close()
        cache.stop()
    return cache


def _error_text(error: BaseException) -> str:
    """Everything an application could log about an error: its text, repr and causes."""
    parts, seen = [], set()
    while error is not None and id(error) not in seen:
        seen.add(id(error))
        parts += [str(error), repr(error), repr(getattr(error, "__dict__", {}))]
        error = error.__cause__ or error.__context__
    return "\n".join(parts)


def _token_secrets(token: str) -> list[str]:
    """The token and each long value in its query string: the signature, credential and session
    token. Logging any one of them on its own is a leak too."""
    query = urlsplit(token if "://" in token else "https://" + token).query
    return [token] + [value for _, value in parse_qsl(query) if len(value) >= 16]


def _tokens(cache: MockCache) -> list[str]:
    """The IAM tokens the cache received in HELLO ... AUTH and AUTH."""
    tokens = []
    for command in cache.received_commands:
        name = command[0].lower()
        if name == b"hello" and len(command) >= 5:
            tokens.append(command[4].decode())
        elif name == b"auth" and len(command) >= 3:
            tokens.append(command[2].decode())
    return tokens


@pytest.mark.parametrize(
    "scenario",
    [_served, _rejected_request, _timed_out, _dropped, _auth_rejected],
    ids=["served", "rejected-request", "timed-out", "dropped", "auth-rejected"],
)
def test_no_secret_or_customer_data_in_logs_or_errors(caplog, scenario):
    for name in LOGGERS:
        caplog.set_level(logging.DEBUG, logger=name)
    errors: list[BaseException] = []

    cache = scenario(errors)

    formatter = logging.Formatter("%(name)s %(levelname)s %(message)s")
    logs = "\n".join(
        formatter.format(record) for record in caplog.records if record.name.startswith(LOGGERS)
    )
    output = logs + "\n" + "\n".join(_error_text(error) for error in errors)
    tokens = _tokens(cache)
    assert tokens, "the scenario never authenticated, so the IAM token went unchecked"
    if scenario is not _auth_rejected:
        assert "aws.request" in cache.cache_command_log, "the request never reached the cache"
    secrets = [secret for token in tokens for secret in _token_secrets(token)]
    for marker in [*MARKERS, *secrets]:
        assert marker not in output, f"{marker} leaked:\n{output}"
    assert not SECRET_SHAPES.search(output), f"secret-shaped text leaked:\n{output}"


def test_request_repr_omits_headers_and_body():
    """A request still holds the signed headers until it is serialized, so its repr hides them."""
    request = ElastiCacheIntegrationsRequest(
        method="POST",
        url="https://dynamodb.us-east-1.amazonaws.com/",
        headers={"Authorization": "AWS4-HMAC-SHA256 Credential=" + ACCESS_KEY},
        body=REQUEST_VALUE.encode(),
        metadata=RequestMetadata(service="dynamodb", operation="GetItem"),
    )

    shown = repr(request)

    assert ACCESS_KEY not in shown
    assert REQUEST_VALUE not in shown
    assert "GetItem" in shown
