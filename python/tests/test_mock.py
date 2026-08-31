"""Unit tests for the contract logic in tests/mock.py.

Only covers what the connection/integration tests don't already exercise by *using* the mock:
``MockCache.handle`` command-shape validation. The reply builders, reply selection,
``MockSocket``, and the TLS handshake are all exercised through the connection tests in
test_connection.py and test_ddb.py.
"""

import io

import pytest

from photon.resp import RespError, decode_resp
from tests.mock import MockCache, _read_command

_REQUEST = [b"photon.request", b"POST", b"https://x/", b"hdrs", b"body", b"service=s&op=o"]


def test_read_command_rejects_missing_crlf_terminator():
    """A bulk string whose payload is not followed by CRLF is a framing violation."""
    frame = io.BytesIO(b"*1\r\n$4\r\nAUTHxx")  # 'xx' where CRLF must be
    with pytest.raises(ValueError, match="not CRLF-terminated"):
        _read_command(frame)


def test_read_command_rejects_non_bulk_arg():
    """A command array whose element is not a bulk string is a framing violation."""
    frame = io.BytesIO(b"*1\r\n:5\r\n")
    with pytest.raises(ValueError, match="Expected bulk string"):
        _read_command(frame)


def test_config_with_wrong_arg_count_is_rejected():
    """photon.config with the wrong number of args is rejected by the mock with a CONFIG_INVALID error."""
    value, _ = decode_resp(MockCache().handle([b"photon.config"]))  # needs exactly 1 arg
    assert isinstance(value, RespError)
    assert value.code == "CONFIG_INVALID"


def test_config_with_invalid_overrides_is_rejected():
    """The overrides arg must be a JSON object; malformed JSON or a non-object is CONFIG_INVALID."""
    for bad in (b"not-json", b'["a-list"]', b"\xff"):
        value, _ = decode_resp(MockCache().handle([b"photon.config", bad]))
        assert isinstance(value, RespError)
        assert value.code == "CONFIG_INVALID"


def test_config_overrides_are_recorded():
    """Each photon.config's decoded overrides are recorded, in order, for assertions."""
    cache = MockCache()
    cache.handle([b"photon.config", b'{"ttl": 300}'])
    cache.handle([b"photon.config", b"{}"])
    assert cache.config_overrides == [{"ttl": 300}, {}]


def test_request_with_wrong_arg_count_is_rejected():
    """photon.request without exactly 5 args (method, url, headers, body, metadata) is rejected."""
    value, _ = decode_resp(MockCache().handle([b"photon.request", b"POST"]))
    assert isinstance(value, RespError)
    assert value.code == "PARSE_ERROR"


def test_request_with_invalid_metadata_is_rejected():
    """The metadata arg must carry both service= and op=; anything else is a parse error."""
    cache = MockCache()
    value, _ = decode_resp(cache.handle(_REQUEST[:-1] + [b"garbage"]))
    assert isinstance(value, RespError)
    assert value.code == "PARSE_ERROR"
    assert cache.requests == []  # an invalid request is never recorded as received


def test_request_metadata_is_decoded_onto_the_record():
    """A valid request's metadata is decoded into a RequestMetadata on the recorded request."""
    cache = MockCache()
    cache.handle(_REQUEST)
    metadata = cache.last_request.metadata
    assert (metadata.service, metadata.operation) == ("s", "o")
