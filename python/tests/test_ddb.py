"""End-to-end integration tests.

Drive the full client path:

    aws_elasticache_integration_client.install(client=ddb) -> boto3 GetItem -> before-send hook -> ElastiCacheIntegrationsSession
        -> real ElastiCacheIntegrationsConnector (verifying TLS) -> MockCache -> response

These exercise the **real** pure-Python ``ElastiCacheIntegrationsConnector`` over **verifying TLS** (not a double) —
the one place in the suite that does so. The mock cache is started with ``tls=True`` so it mints a
self-signed cert, which the client trusts via ``SSL_CERT_FILE`` (honored by the connector's
default ``load_default_certs()`` path). No real cache or DynamoDB is contacted.

How the mock behaves: ``MockCache`` is a recorder + answering machine — it speaks RESP but knows
nothing about DynamoDB. ``AUTH`` replies ``+OK``; ``aws.config`` replies a canned resolved
config; ``aws.request`` **records** the request (``cache.requests``,
``cache.last_request_args``, ``cache.cache_command_log``) and replies the **canned** response it
was constructed with, regardless of operation or input. So the canned reply controls what the app
receives, and the recording shows what the client sent.

Scaffolding (mock cache lifecycle, TLS trust, dummy credentials) lives in fixtures; each test
body contains only what a real application would write. Per-test client teardown on assertion
failure is not needed: the connector's reader is a daemon thread and ``cache.stop()`` closes the
transport; ``test_close_lifecycle`` covers teardown explicitly.
"""

from __future__ import annotations

import json
from unittest import mock

import boto3
import pytest
from botocore.config import Config
from botocore.hooks import first_non_none_response

import aws_elasticache_integration_client
from aws_elasticache_integration_client.exceptions import ElastiCacheIntegrationsRejectedError
from tests.mock import MockCache, Reply

ITEM = {"id": {"S": "42"}, "name": {"S": "widget"}}


@pytest.fixture(autouse=True)
def aws_creds(monkeypatch):
    # Dummy credentials so botocore can sign the request before the hook short-circuits it.
    monkeypatch.setenv("AWS_ACCESS_KEY_ID", "testkey")
    monkeypatch.setenv("AWS_SECRET_ACCESS_KEY", "testsecret")
    monkeypatch.setenv("AWS_SESSION_TOKEN", "")


@pytest.fixture
def session():
    """The boto3 session an application would build its client from."""
    return boto3.Session(region_name="us-east-1")


@pytest.fixture
def cache(monkeypatch):
    """A running MockCache (verifying TLS) whose self-signed cert is trusted for this test only."""
    cache = MockCache(tls=True, response_body=json.dumps({"Item": ITEM}).encode("utf-8"))
    cache.start()
    monkeypatch.setenv("SSL_CERT_FILE", cache.cert_path)  # restored automatically after the test
    yield cache
    cache.stop()


def test_get_item(cache, session):
    """get_item is served by the mock cache, and the request arrives there exactly as sent."""
    ddb = session.client("dynamodb")
    aws_elasticache_integration_client.install(
        client=ddb, endpoint=cache.endpoint, cache_name="test-cache"
    )

    result = ddb.get_item(TableName="my-table", Key={"id": {"S": "42"}})
    assert result["ResponseMetadata"]["HTTPStatusCode"] == 200
    assert result["Item"] == ITEM

    # Handshake ordering: hello -> config -> request.
    log = cache.cache_command_log
    assert log.index("hello") < log.index("aws.config") < log.index("aws.request")

    # Check what aws_elasticache_integration_client sent to the cache.
    method, url, headers, req_body, metadata = cache.last_request_args[:5]
    assert method == b"POST"
    # The SDK's request URL is the real DynamoDB endpoint (the cache endpoint is only where
    # the connector connects).
    assert url == b"https://dynamodb.us-east-1.amazonaws.com/"
    # Signal headers preserved and normalized (lowercased names).
    assert b"x-amz-target:DynamoDB_20120810.GetItem" in headers
    assert b"content-type:application/x-amz-json-1.0" in headers
    # Auth/volatile headers stripped — must not reach the cache.
    assert b"authorization:" not in headers
    assert b"x-amz-date:" not in headers
    # Body and metadata carry the operation faithfully.
    assert json.loads(req_body) == {"TableName": "my-table", "Key": {"id": {"S": "42"}}}
    assert metadata == b"service=dynamodb&op=GetItem"
    ddb.close()


def test_connection_reuse(cache, session):
    """Two calls on one client reuse one connection: one config handshake, two requests."""
    ddb = session.client("dynamodb")
    aws_elasticache_integration_client.install(
        client=ddb, endpoint=cache.endpoint, cache_name="test-cache"
    )

    ddb.get_item(TableName="my-table", Key={"id": {"S": "42"}})
    ddb.get_item(TableName="my-table", Key={"id": {"S": "42"}})

    assert cache.cache_command_log.count("aws.config") == 1
    assert cache.cache_command_log.count("aws.request") == 2
    ddb.close()


def test_close_lifecycle(cache, session):
    """close() disconnects ElastiCacheIntegrations (nothing reaches the cache afterwards) and is idempotent."""
    ddb = session.client("dynamodb")
    aws_elasticache_integration_client.install(
        client=ddb, endpoint=cache.endpoint, cache_name="test-cache"
    )
    ddb.get_item(TableName="my-table", Key={"id": {"S": "42"}})  # served by the cache
    requests_before = len(cache.requests)

    ddb.close()

    # Hook removed: before-send yields no response (emitted manually to stay offline).
    request = mock.Mock(method="POST", url="https://x/", headers={}, body=b"{}")
    responses = ddb.meta.events.emit("before-send.dynamodb.GetItem", request=request)
    assert first_non_none_response(responses) is None
    assert len(cache.requests) == requests_before  # nothing new reached the cache

    ddb.close()  # idempotent: second close is pure boto3, must not raise


def _origin_error_cause(error):
    """The ORIGIN_ERROR rejection in the cause chain, as the README tells applications to find it."""
    while error is not None:
        if isinstance(error, ElastiCacheIntegrationsRejectedError) and error.code == "ORIGIN_ERROR":
            return error
        error = error.__cause__
    return None


@pytest.mark.parametrize(
    ("operation", "attempts"),
    [
        pytest.param("get_item", 2, id="cataloged-retried"),
        pytest.param("put_item", 1, id="not-cataloged-not-retried"),
    ],
)
def test_origin_error_is_in_the_cause_chain(monkeypatch, session, operation, attempts):
    """An ORIGIN_ERROR reply reaches the application with the rejection in its cause chain."""
    cache = MockCache(tls=True, response=Reply.error("ORIGIN_ERROR", "origin unavailable"))
    cache.start()
    monkeypatch.setenv("SSL_CERT_FILE", cache.cert_path)
    ddb = session.client("dynamodb", config=Config(retries={"total_max_attempts": 2}))
    try:
        aws_elasticache_integration_client.install(
            client=ddb, endpoint=cache.endpoint, cache_name="test-cache"
        )
        call = getattr(ddb, operation)
        kwargs = {"Key": ITEM} if operation == "get_item" else {"Item": ITEM}
        with pytest.raises(Exception) as caught:
            call(TableName="my-table", **kwargs)

        rejection = _origin_error_cause(caught.value)
        assert rejection is not None, repr(caught.value)
        assert str(rejection) == "aws.request failed: ORIGIN_ERROR origin unavailable"
        assert cache.cache_command_log.count("aws.request") == attempts
    finally:
        ddb.close()
        cache.stop()
