"""Tests for photon's client<->cache protocol"""

import json

import pytest

from photon.exceptions import PhotonError, PhotonProtocolError
from photon.models import PhotonRequest, PhotonResponse, RequestMetadata, ResolvedConfig
from photon.protocol import (
    CONFIG_COMMAND,
    PUSH_KIND_RESPONSE,
    REQUEST_COMMAND,
    serialize_config,
    serialize_request,
    normalize_headers,
    parse_config_response,
    parse_request_response,
    parse_response_push,
)
from photon.resp import RespError, RespPush


# --- AC3: config command + parse ----------------------------------------------

def test_serialize_config_empty():
    """Builds the photon.config command from an empty overrides dict and checks the command name and serialized arg."""
    cmd, args = serialize_config({})
    assert cmd == CONFIG_COMMAND == "photon.config"
    assert args == [b"{}"]


def test_serialize_config_compact_json():
    """Confirms overrides are serialized as compact JSON (no spaces) and that key order is not relied upon (asserts on parsed object)."""
    # Serialized as compact JSON (no whitespace). Key order is NOT part of the contract,
    # so assert on the parsed object rather than an exact byte string.
    _, args = serialize_config({"b": 2, "a": 1})
    assert len(args) == 1
    assert b" " not in args[0]  # compact separators, no spaces
    assert json.loads(args[0]) == {"b": 2, "a": 1}


def test_parse_config_response_json_bulk_string():
    """Parses a JSON bulk-string config reply into a ResolvedConfig."""
    cfg = parse_config_response(b'{"version":"v3","refresh_interval":60,"ttl":300,"predicates":[]}')
    assert isinstance(cfg, ResolvedConfig)
    assert cfg.version == "v3"
    assert cfg.refresh_interval == 60
    # Unmodeled fields survive on .raw so nothing from the server is silently dropped.
    assert cfg.raw == {"version": "v3", "refresh_interval": 60, "ttl": 300, "predicates": []}


def test_parse_config_response_missing_fields_default_to_none():
    """A config reply that omits the named fields yields None for them while still capturing the full document on .raw."""
    cfg = parse_config_response(b'{"predicates":[]}')
    assert cfg.version is None
    assert cfg.refresh_interval is None
    assert cfg.raw == {"predicates": []}


def test_parse_config_response_rejects_error_frame():
    """A RespError config reply must be rejected, not parsed."""
    with pytest.raises(PhotonError):
        parse_config_response(RespError("ERR", "nope"))


def test_parse_config_response_rejects_array():
    """A RESP array shape for config is intentionally unsupported."""
    with pytest.raises(PhotonError):
        parse_config_response([b"version", b"v3"])  # RESP-array shape NOT supported


def test_parse_config_response_rejects_malformed_json():
    """Non-JSON bulk string must be rejected."""
    with pytest.raises(PhotonError):
        parse_config_response(b"not json")


def test_parse_config_response_rejects_non_object_json():
    """Valid JSON that is not an object (e.g. an array) must be rejected."""
    with pytest.raises(PhotonError):
        parse_config_response(b"[1,2,3]")


# --- AC4: request command shape ------------------------------------------------

def test_serialize_request_arg_count():
    """The args are exactly the 5 request args and the command name is correct."""
    cmd, args = serialize_request(
        PhotonRequest(method="POST", url="https://x/",
                      metadata=RequestMetadata(service="dynamodb", operation="GetItem"))
    )
    assert cmd == REQUEST_COMMAND == "photon.request"
    assert len(args) == 5


# --- AC5: request response mapping --------------------------------------------

def test_parse_request_response_error_frame_raises():
    """A RespError request reply raises rather than being returned as a response."""
    with pytest.raises(PhotonError):
        parse_request_response(RespError("ERR", "boom"))


def test_parse_request_response_non_array_raises():
    """A non-array request reply (e.g. a bare bulk string) is malformed."""
    with pytest.raises(PhotonError):
        parse_request_response(b"not-an-array")


def test_serialize_request_arg_list():
    """Builds a full PhotonRequest into the ordered 5-element bytes arg list and verifies each element (incl. header lowercasing and metadata formatting)."""
    req = PhotonRequest(
        method="POST",
        url="https://dynamodb.us-east-1.amazonaws.com/",
        headers={"Content-Type": "application/x-amz-json-1.0"},
        body=b"{}",
        metadata=RequestMetadata(service="dynamodb", operation="GetItem"),
    )
    _, args = serialize_request(req)
    assert isinstance(args, list)
    assert len(args) == 5
    assert all(isinstance(a, bytes) for a in args)
    method, url, headers, body, metadata = args
    assert method == b"POST"
    assert url == b"https://dynamodb.us-east-1.amazonaws.com/"
    assert headers == b"content-type:application/x-amz-json-1.0"
    assert body == b"{}"
    assert metadata == b"service=dynamodb&op=GetItem"


def test_serialize_request_passes_body_through():
    """The body bytes go on the wire exactly as stored on the request."""
    req = PhotonRequest(method="POST", url="https://x/", body=b"hello",
                        metadata=RequestMetadata(service="dynamodb", operation="GetItem"))
    assert serialize_request(req)[1][3] == b"hello"


# --- header normalization determinism -----------------------------------------

def test_header_normalization_is_deterministic():
    """Two header dicts that differ only in case, whitespace, and excluded/auth headers normalize to the same deterministic byte string (lowercased, trimmed, sorted, LF-joined, excluded headers dropped)."""
    headers_a = {
        "Content-Type": "application/json",
        "X-Amz-Target": "DynamoDB_20120810.GetItem",
        "Authorization": "AWS4-HMAC-SHA256 ...",
        "X-Amz-Date": "20260624T000000Z",
        "x-amz-security-token": "tok",
        "x-amz-request-id": "rid",
        "amz-sdk-request": "attempt=1",
    }
    headers_b = {
        "x-amz-target": "  DynamoDB_20120810.GetItem  ",
        "CONTENT-TYPE": "application/json",
        "authorization": "different-sig",
    }
    a = normalize_headers(headers_a)
    b = normalize_headers(headers_b)
    assert a == b
    # Excluded headers removed; lowercased; sorted; LF-joined.
    assert a == (
        b"content-type:application/json\n"
        b"x-amz-target:DynamoDB_20120810.GetItem"
    )


def test_header_normalization_all_excluded():
    """When every header is on the excluded list, the result is empty."""
    assert normalize_headers({"Authorization": "x", "X-Amz-Date": "y"}) == b""


def test_header_normalization_handles_empty():
    """An empty dict normalizes to empty bytes."""
    assert normalize_headers({}) == b""


# --- response deserialization -------------------------------------------------

def test_parse_request_response_basic():
    """Builds a PhotonResponse from [status, headers, body], parsing the LF-delimited header blob into a dict."""
    resp = parse_request_response([200, b"content-type:application/json", b"{\"k\":1}"])
    assert isinstance(resp, PhotonResponse)
    assert resp.status_code == 200
    assert resp.headers == {"content-type": "application/json"}
    assert resp.body == b"{\"k\":1}"


def test_parse_request_response_empty():
    """Empty headers and body produce an empty dict and empty bytes."""
    resp = parse_request_response([204, b"", b""])
    assert resp.status_code == 204
    assert resp.headers == {}
    assert resp.body == b""


def test_parse_request_response_skips_malformed_header_line():
    """A header line with no : separator is skipped rather than guessed."""
    resp = parse_request_response([200, b"content-type:application/json\ngarbage", b""])
    assert resp.headers == {"content-type": "application/json"}


# --- status validation --------------------------------------------------------

@pytest.mark.parametrize("bad_status", [99, 600, 0, -1, 1000])
def test_parse_request_response_rejects_out_of_range_status(bad_status):
    """Status codes outside 100-599 are rejected."""
    with pytest.raises(PhotonError):
        parse_request_response([bad_status, b"", b""])


def test_parse_request_response_rejects_non_integer_status():
    """A non-numeric status element is rejected."""
    with pytest.raises(PhotonError):
        parse_request_response([b"not-a-number", b"", b""])


@pytest.mark.parametrize("elements", [None, [], [200], [200, b""], [200, b"", b"", b"x"]])
def test_parse_request_response_rejects_wrong_shape(elements):
    """A response that is not exactly 3 elements (incl. None, empty, too few, too many) is malformed."""
    with pytest.raises(PhotonError):
        parse_request_response(elements)


@pytest.mark.parametrize(
    "elements",
    [
        [200, None, None],   # RESP null ($-1) decodes to None and passes the shape check
        [200, b"", None],
        [200, None, b""],
        [200, 5, b""],
    ],
)
def test_parse_request_response_rejects_wrong_element_types(elements):
    """A well-shaped reply with non-bytes headers/body fails as PhotonError, not a raw crash."""
    with pytest.raises(PhotonError):
        parse_request_response(elements)


# --- out-of-band response push ------------------------------------------------

def test_parse_response_push_splits_id_and_reply():
    """The id comes back as the str form the id reply used; the reply is handed over untouched
    for parse_request_response."""
    push = RespPush(kind=PUSH_KIND_RESPONSE, items=[b"7", [200, b"", b"body"]])
    assert parse_response_push(push) == ("7", [200, b"", b"body"])


def test_parse_response_push_keeps_an_error_reply():
    """A failed request answers with a RespError inside the push, passed through as the value."""
    error = RespError("PHOTON_AUTH_ERROR", "nope")
    assert parse_response_push(RespPush(kind=PUSH_KIND_RESPONSE, items=[b"7", error])) == (
        "7",
        error,
    )


@pytest.mark.parametrize(
    "items, message",
    [
        ([], "takes 2 items"),
        ([b"7"], "takes 2 items"),
        ([b"7", [200], b"extra"], "takes 2 items"),
        ([7, [200]], "Invalid push request id"),
    ],
)
def test_parse_response_push_rejects_wrong_payload(items, message):
    """A push whose payload cannot be read is a protocol error: the stream is untrustworthy,
    unlike a reply that merely carries an error."""
    with pytest.raises(PhotonProtocolError, match=message):
        parse_response_push(RespPush(kind=PUSH_KIND_RESPONSE, items=items))
