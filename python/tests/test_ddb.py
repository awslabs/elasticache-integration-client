"""End-to-end integration tests.

Drive the full client path:

    photon.client("dynamodb") -> boto3 GetItem -> before-send hook -> PhotonSession
        -> real PhotonConnector (verifying TLS) -> MockCache -> response

These exercise the **real** pure-Python ``PhotonConnector`` over **verifying TLS** (not a double) —
the one place in the suite that does so. The mock cache is started with ``tls=True`` so it mints a
self-signed cert, which the client trusts via ``SSL_CERT_FILE`` (honored by the connector's
default ``load_default_certs()`` path). No real cache or DynamoDB is contacted.

How the mock behaves: ``MockCache`` is a recorder + answering machine — it speaks RESP but knows
nothing about DynamoDB. ``AUTH`` replies ``+OK``; ``photon.config`` replies a canned resolved
config; ``photon.request`` **records** the request (``cache.requests``,
``cache.last_request_args``, ``cache.photon_command_log``) and replies the **canned** response it
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

import pytest
from botocore.hooks import first_non_none_response

import photon
from tests.mock import MockCache

ITEM = {"id": {"S": "42"}, "name": {"S": "widget"}}


@pytest.fixture(autouse=True)
def aws_creds(monkeypatch):
    # Dummy credentials so botocore can sign the request before the hook short-circuits it.
    monkeypatch.setenv("AWS_ACCESS_KEY_ID", "testkey")
    monkeypatch.setenv("AWS_SECRET_ACCESS_KEY", "testsecret")
    monkeypatch.setenv("AWS_SESSION_TOKEN", "")


@pytest.fixture
def cache(monkeypatch):
    """A running MockCache (verifying TLS) whose self-signed cert is trusted for this test only."""
    cache = MockCache(tls=True, response_body=json.dumps({"Item": ITEM}).encode("utf-8"))
    cache.start()
    monkeypatch.setenv("SSL_CERT_FILE", cache.cert_path)  # restored automatically after the test
    yield cache
    cache.stop()


def test_get_item(cache):
    """get_item is served by the mock cache, and the request arrives there exactly as sent."""
    ddb = photon.client("dynamodb", region_name="us-east-1",
                        endpoint=cache.endpoint, cache_name="test-cache")

    result = ddb.get_item(TableName="my-table", Key={"id": {"S": "42"}})
    assert result["ResponseMetadata"]["HTTPStatusCode"] == 200
    assert result["Item"] == ITEM

    # Handshake ordering: auth -> config -> request.
    log = cache.photon_command_log
    assert log.index("auth") < log.index("photon.config") < log.index("photon.request")

    # Check what photon sent to the cache.
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


def test_connection_reuse(cache):
    """Two calls on one client reuse one connection: one config handshake, two requests."""
    ddb = photon.client("dynamodb", region_name="us-east-1",
                        endpoint=cache.endpoint, cache_name="test-cache")

    ddb.get_item(TableName="my-table", Key={"id": {"S": "42"}})
    ddb.get_item(TableName="my-table", Key={"id": {"S": "42"}})

    assert cache.photon_command_log.count("photon.config") == 1
    assert cache.photon_command_log.count("photon.request") == 2
    ddb.close()


def test_unmatched_operation_never_reaches_the_cache(cache):
    """A write matches no resolved predicate, so the hook declines and botocore would send it to DDB.

    The event is emitted manually (rather than calling ddb.put_item) so the test stays offline: a
    declined request falls through to the real AWS endpoint, which is exactly what must not be
    contacted here. What matters is the handler's answer and that the cache saw nothing.
    """
    ddb = photon.client("dynamodb", region_name="us-east-1",
                        endpoint=cache.endpoint, cache_name="test-cache")
    requests_before = len(cache.requests)

    request = mock.Mock(method="POST", url="https://dynamodb.us-east-1.amazonaws.com/",
                        headers={}, body=json.dumps({"TableName": "my-table"}).encode())
    responses = ddb.meta.events.emit("before-send.dynamodb.PutItem", request=request)

    assert first_non_none_response(responses) is None   # declined: botocore sends it itself
    assert len(cache.requests) == requests_before       # nothing was routed to the cache
    assert cache.photon_command_log.count("photon.config") == 1  # handshake still happened
    ddb.close()


def test_close_lifecycle(cache):
    """close() disconnects Photon (nothing reaches the cache afterwards) and is idempotent."""
    ddb = photon.client("dynamodb", region_name="us-east-1",
                        endpoint=cache.endpoint, cache_name="test-cache")
    ddb.get_item(TableName="my-table", Key={"id": {"S": "42"}})  # served by the cache
    requests_before = len(cache.requests)

    ddb.close()

    # Hook removed: before-send yields no response (emitted manually to stay offline).
    request = mock.Mock(method="POST", url="https://x/", headers={}, body=b"{}")
    responses = ddb.meta.events.emit("before-send.dynamodb.GetItem", request=request)
    assert first_non_none_response(responses) is None
    assert len(cache.requests) == requests_before  # nothing new reached the cache

    ddb.close()  # idempotent: second close is pure boto3, must not raise
