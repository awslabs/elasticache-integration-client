"""Tests for photon.resp — the standalone RESP3 wire codec."""

import pytest

from photon.exceptions import PhotonError, PhotonProtocolError
from photon.resp import (
    IncompleteFrameError,
    RespError,
    decode_resp,
    encode_resp,
)


# --- AC1: codec API + IncompleteFrameError is a plain Exception --------------------

def test_incomplete_frame_is_not_photon_error():
    """IncompleteFrameError ("need more bytes") must be a plain Exception, NOT a PhotonError, so an incremental reader can distinguish truncation from corruption."""
    assert issubclass(IncompleteFrameError, Exception)
    assert not issubclass(IncompleteFrameError, PhotonError)
# --- AC2: encode/decode_resp round-trip --------------------------------------------

def test_encode_decode_round_trip():
    """Encoding a command then decoding it yields the original elements and consumes the whole buffer."""
    encoded = encode_resp("photon.request", b"POST", b"body")
    value, nxt = decode_resp(encoded)
    assert value == [b"photon.request", b"POST", b"body"]
    assert nxt == len(encoded)


def test_encode_resp_resp3_array_framing():
    """Verifies the exact RESP3 byte framing (*N, $len, CRLFs) produced by the encoder."""
    encoded = encode_resp("photon.request", b"POST", b"https://x/")
    assert encoded == (
        b"*3\r\n"
        b"$14\r\nphoton.request\r\n"
        b"$4\r\nPOST\r\n"
        b"$10\r\nhttps://x/\r\n"
    )


# --- AC3: all RESP3 prefixes decode_resp -------------------------------------------

def test_decode_simple_string():
    """Decodes a + simple string and reports bytes consumed."""
    assert decode_resp(b"+OK\r\n") == (b"OK", 5)


def test_decode_integer():
    """Decodes a : integer."""
    assert decode_resp(b":200\r\n") == (200, 6)


def test_decode_error_frame():
    """Decodes a - error into a RespError with code/message split on the first space."""
    value, nxt = decode_resp(b"-ERR something bad\r\n")
    assert isinstance(value, RespError)
    assert value.code == "ERR"
    assert value.message == "something bad"
    assert nxt == 20


def test_decode_bulk_string():
    """Decodes a $ bulk string of declared length."""
    assert decode_resp(b"$5\r\nhello\r\n") == (b"hello", 11)


def test_decode_null_bulk_string():
    """Decodes a $-1 null bulk string to None."""
    assert decode_resp(b"$-1\r\n") == (None, 5)


def test_decode_array():
    """Decodes a *3 array containing an int and two bulk strings."""
    frame = b"*3\r\n:200\r\n$4\r\nhdrs\r\n$4\r\nbody\r\n"
    value, nxt = decode_resp(frame)
    assert value == [200, b"hdrs", b"body"]
    assert nxt == len(frame)


def test_decode_push_like_array():
    """A > push frame decodes like an array."""
    value, _ = decode_resp(b">2\r\n:1\r\n:2\r\n")
    assert value == [1, 2]


def test_decode_null_array():
    """Decodes a *-1 null array to None."""
    assert decode_resp(b"*-1\r\n") == (None, 5)


# --- AC4: truncation raises IncompleteFrameError -----------------------------------

@pytest.mark.parametrize(
    "partial",
    [
        b"",                       # empty buffer
        b"+missing-crlf",          # header with no terminating CRLF
        b"$5\r\nhell",             # bulk one byte short of declared length (+ trailing CRLF)
        b"$5\r\nhello",            # bulk body present but trailing CRLF missing
        b"*2\r\n:1\r\n",           # array missing its second element
        b">2\r\n:1\r\n",           # push missing its second element
        b":",                      # integer header with no CRLF yet
    ],
)
def test_truncation_raises_incomplete_frame(partial):
    """Each kind of partial buffer (empty, missing CRLF, short bulk, missing bulk trailing CRLF, missing array/push element, bare :) raises IncompleteFrameError."""
    with pytest.raises(IncompleteFrameError):
        decode_resp(partial)


# --- AC5: garbage raises PhotonProtocolError ----------------------------------

@pytest.mark.parametrize(
    "garbage",
    [
        b"!bad\r\n",        # unknown prefix
        b":notanint\r\n",   # integer header unparseable
        b"$notanint\r\n",   # bulk length unparseable
        b"*notanint\r\n",   # array count unparseable
    ],
)
def test_decode_garbage_raises_protocol_error(garbage):
    """Genuinely corrupt input (unknown prefix, unparseable int/bulk/array headers) raises PhotonProtocolError."""
    with pytest.raises(PhotonProtocolError):
        decode_resp(garbage)
