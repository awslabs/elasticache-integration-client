"""Run the cross-client wire vectors in shared/protocol-vectors.json.

Node and Java run the same file, so a client that drifts from the shared bytes fails its own gate.
"""

import json
from pathlib import Path

import pytest

from photon.exceptions import (
    PhotonError,
    PhotonOverridesRejected,
    PhotonProtocolError,
    PhotonRejectedError,
)
from photon.models import PhotonRequest, RequestMetadata
from photon.protocol import (
    parse_config_response,
    parse_request_response,
    serialize_config,
    serialize_request,
)
from photon.resp import decode_resp, encode_resp

VECTORS = json.loads(
    (Path(__file__).parents[2] / "shared" / "protocol-vectors.json").read_text(encoding="utf-8")
)

ERRORS = {
    "protocol": PhotonProtocolError,
    "rejected": PhotonRejectedError,
    "overrides_rejected": PhotonOverridesRejected,
    "error": PhotonError,
}


@pytest.mark.parametrize("vector", VECTORS["encode"], ids=lambda v: v["name"])
def test_encode_vector(vector):
    if "request" in vector:
        r = vector["request"]
        command, args = serialize_request(
            PhotonRequest(
                method=r["method"],
                url=r["url"],
                headers=r["headers"],
                body=r["body"].encode(),
                metadata=RequestMetadata(**r["metadata"]),
            )
        )
    else:
        command, args = serialize_config(vector["overrides"])
    assert encode_resp(command, *args) == vector["frame"].encode()


@pytest.mark.parametrize("vector", VECTORS["decode"], ids=lambda v: v["name"])
def test_decode_vector(vector):
    reply_bytes = vector["reply"].encode()
    reply, consumed = decode_resp(reply_bytes)
    assert consumed == len(reply_bytes)
    parse = (
        parse_request_response if vector["command"] == "photon.request" else parse_config_response
    )

    if "error" in vector:
        with pytest.raises(PhotonError) as excinfo:
            parse(reply)
        # "error" accepts any PhotonError; the other kinds name an exact class.
        if vector["error"] != "error":
            assert type(excinfo.value) is ERRORS[vector["error"]]
        if "code" in vector:
            assert excinfo.value.code == vector["code"]
    elif "response" in vector:
        response = parse(reply)
        expected = vector["response"]
        assert response.status_code == expected["status"]
        assert response.headers == expected["headers"]
        assert response.body == expected["body"].encode()
    else:
        config = parse(reply)
        assert config.version == vector["config"]["version"]
        assert config.refresh_interval == vector["config"]["refresh_interval"]
        assert config.raw == vector["config"]["raw"]


def test_vectors_use_only_known_fields():
    """This runner skips any field it doesn't know, so an unknown or missing field fails here."""
    assert set(VECTORS) == {"description", "encode", "decode"}
    for vector in VECTORS["encode"]:
        assert set(vector) in ({"name", "request", "frame"}, {"name", "overrides", "frame"}), vector
        if "request" in vector:
            assert set(vector["request"]) == {"method", "url", "headers", "body", "metadata"}, (
                vector
            )
            assert set(vector["request"]["metadata"]) == {"service", "operation"}, vector
    for vector in VECTORS["decode"]:
        outcome = set(vector) - {"name", "command", "reply"}
        assert outcome in ({"response"}, {"config"}, {"error"}, {"error", "code"}), vector
        if "response" in vector:
            assert set(vector["response"]) == {"status", "headers", "body"}, vector
        if "config" in vector:
            assert set(vector["config"]) == {"version", "refresh_interval", "raw"}, vector
