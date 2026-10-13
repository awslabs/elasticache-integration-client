"""Run the cross-client SDK request cases in shared/sdk-request-cases.json.

Each case goes through the real boto3 client into the mock cache. Node and Java run the same
file through their own SDKs, so a client whose adapter builds a different request fails its gate.
"""

import json
from pathlib import Path

import pytest
from botocore import xform_name

import photon
from tests.mock import MockCache

CASES = json.loads(
    (Path(__file__).parents[2] / "shared" / "sdk-request-cases.json").read_text(encoding="utf-8")
)


@pytest.fixture(autouse=True)
def aws_creds(monkeypatch):
    # Dummy credentials so botocore can sign the request before the hook short-circuits it.
    monkeypatch.setenv("AWS_ACCESS_KEY_ID", "testkey")
    monkeypatch.setenv("AWS_SECRET_ACCESS_KEY", "testsecret")
    # A session token makes botocore send x-amz-security-token, which Photon must strip.
    monkeypatch.setenv("AWS_SESSION_TOKEN", "testtoken")


@pytest.fixture(autouse=True)
def no_origin(monkeypatch):
    # Photon must carry every case; a request that falls back to the origin fails here instead of
    # reaching real AWS.
    def send(self, request):
        raise AssertionError(f"request went to the origin: {request.url}")

    monkeypatch.setattr("botocore.httpsession.URLLib3Session.send", send)


@pytest.fixture
def cache(monkeypatch):
    cache = MockCache(CASES["config"], tls=True, response_body=b"{}")
    cache.start()
    monkeypatch.setenv("SSL_CERT_FILE", cache.cert_path)
    yield cache
    cache.stop()


@pytest.mark.parametrize("case", CASES["cases"], ids=lambda c: c["name"])
def test_sdk_request_case(cache, case):
    client = photon.client(
        case["service"], region_name="us-east-1", endpoint=cache.endpoint, cache_name="test-cache"
    )
    try:
        getattr(client, xform_name(case["operation"]))(**case["params"])
    finally:
        client.close()

    assert len(cache.requests) == 1
    request = cache.requests[0]
    expected = case["expected"]
    assert request.method.decode() == expected["method"]
    assert request.url.decode() == expected["url"]
    assert request.raw_args[4].decode() == expected["metadata"]
    assert json.loads(request.body) == case["params"]
    # Normalized headers are one "name:value" line each, so compare whole lines.
    lines = request.headers.decode().split("\n")
    for name, value in expected["headers"].items():
        assert f"{name}:{value}" in lines
    for name in CASES["excluded_headers"]:
        assert not any(line.startswith(f"{name}:") for line in lines), name


def test_cases_use_only_known_fields():
    """This runner skips any field it doesn't know, so an unknown or missing field fails here."""
    assert set(CASES) == {"description", "config", "excluded_headers", "cases"}
    for case in CASES["cases"]:
        assert set(case) == {"name", "service", "operation", "params", "expected"}, case["name"]
        assert set(case["expected"]) == {"method", "url", "metadata", "headers"}, case["name"]
