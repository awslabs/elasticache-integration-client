"""Tests for photon.resp — the standalone RESP3 wire codec."""

import pytest

from photon.exceptions import PhotonError, PhotonProtocolError
from photon.resp import (
    IncompleteFrame,
    RespError,
    RespPush,
    decode_resp,
    encode_resp,
)


# --- AC1: codec API + IncompleteFrame is a plain Exception --------------------


def test_incomplete_frame_is_not_photon_error():
    """IncompleteFrame ("need more bytes") must be a plain Exception, NOT a PhotonError, so an incremental reader can distinguish truncation from corruption."""
    assert issubclass(IncompleteFrame, Exception)
    assert not issubclass(IncompleteFrame, PhotonError)


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
    assert encoded == (b"*3\r\n$14\r\nphoton.request\r\n$4\r\nPOST\r\n$10\r\nhttps://x/\r\n")


# --- AC3: all RESP3 prefixes decode_resp -------------------------------------------


def test_decode_simple_string():
    """Decodes a + simple string to str (bulk strings stay bytes) and reports bytes consumed."""
    assert decode_resp(b"+OK\r\n") == ("OK", 5)


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


def test_decode_resp3_null():
    """Decodes the RESP3 null `_` to None. Once the connection is RESP3 the server encodes
    nulls this way instead of as $-1, so both forms must reach the caller as None."""
    assert decode_resp(b"_\r\n") == (None, 3)


def test_decode_array():
    """Decodes a *3 array containing an int and two bulk strings."""
    frame = b"*3\r\n:200\r\n$4\r\nhdrs\r\n$4\r\nbody\r\n"
    value, nxt = decode_resp(frame)
    assert value == [200, b"hdrs", b"body"]
    assert nxt == len(frame)


def test_decode_push():
    """A > push frame decodes its elements like an array but is wrapped in RespPush, so the
    reader can tell an out-of-band message from a positional reply. The first element is the
    kind."""
    frame = b">2\r\n$10\r\ninvalidate\r\n*2\r\n$3\r\nfoo\r\n$3\r\nbar\r\n"
    value, nxt = decode_resp(frame)
    assert isinstance(value, RespPush)
    assert value.kind == b"invalidate"
    assert value.items == [[b"foo", b"bar"]]
    assert nxt == len(frame)


def test_decode_push_with_only_a_kind():
    """A push may carry nothing but its kind; items is then empty."""
    frame = b">1\r\n$10\r\ninvalidate\r\n"
    assert decode_resp(frame) == (RespPush(kind=b"invalidate", items=[]), len(frame))


def test_decode_frame_after_a_push():
    """The position a push reports lands on the next frame, so a reply pipelined behind a push
    is decoded from there."""
    frame = b">1\r\n$10\r\ninvalidate\r\n+OK\r\n"
    _, nxt = decode_resp(frame)
    assert decode_resp(frame, nxt) == ("OK", len(frame))


@pytest.mark.parametrize("frame", [b">0\r\n", b">-1\r\n"])
def test_decode_empty_push(frame):
    """An empty push (``>0``, or the null ``>-1``) carries no kind; it decodes rather than
    failing, and a reader that knows no such kind ignores it."""
    assert decode_resp(frame) == (RespPush(kind=b"", items=[]), len(frame))


def test_decode_push_kind_as_simple_string():
    """A kind sent as a simple string ends up as the same bytes as a bulk string kind."""
    frame = b">1\r\n+invalidate\r\n"
    assert decode_resp(frame) == (RespPush(kind=b"invalidate", items=[]), len(frame))


def test_decode_null_array():
    """Decodes a *-1 null array to None."""
    assert decode_resp(b"*-1\r\n") == (None, 5)


def test_decode_map():
    """A % map decodes to a dict. Keys arrive as bulk strings (bytes), values keep their own
    types — this is the shape of a HELLO reply, which the client reads to confirm RESP3."""
    frame = b"%2\r\n$5\r\nproto\r\n:3\r\n$6\r\nserver\r\n$6\r\nvalkey\r\n"
    value, nxt = decode_resp(frame)
    assert value == {b"proto": 3, b"server": b"valkey"}
    assert nxt == len(frame)


def test_decode_empty_map():
    """A zero-length map decodes to an empty dict."""
    assert decode_resp(b"%0\r\n") == ({}, 4)


def test_decode_map_with_aggregate_key_raises_protocol_error():
    """An array key is legal RESP3 but cannot key a dict. Rejected as a protocol error rather
    than raising an unhandled TypeError out of the codec."""
    with pytest.raises(PhotonProtocolError, match="Unhashable RESP map key"):
        decode_resp(b"%1\r\n*1\r\n:1\r\n:2\r\n")


def test_decode_negative_map_length_raises_protocol_error():
    """There is no null map in RESP3, so a negative length is garbage, not a null."""
    with pytest.raises(PhotonProtocolError, match="Malformed RESP map length"):
        decode_resp(b"%-1\r\n")


# --- AC4: truncation raises IncompleteFrame -----------------------------------


@pytest.mark.parametrize(
    "partial, message",
    [
        (b"", "incomplete frame"),
        (b"+missing-crlf", "incomplete frame"),
        (b"$5\r\nhell", "bulk string body incomplete"),
        (b"$5\r\nhello", "bulk string body incomplete"),
        (b"*2\r\n:1\r\n", "incomplete frame"),
        (b"%1\r\n$5\r\nproto\r\n", "incomplete frame"),
        (b">2\r\n:1\r\n", "incomplete frame"),
        (b":", "incomplete frame"),
        (b"_", "incomplete frame"),
    ],
)
def test_truncation_raises_incomplete_frame(partial, message):
    """A partial buffer raises IncompleteFrame, saying how far it got."""
    with pytest.raises(IncompleteFrame, match=message):
        decode_resp(partial)


# --- AC5: garbage raises PhotonProtocolError ----------------------------------


@pytest.mark.parametrize(
    "garbage, message",
    [
        (b"!bad\r\n", "Unsupported RESP prefix"),
        (b":notanint\r\n", "Malformed RESP integer"),
        (b"$notanint\r\n", "Malformed RESP bulk length"),
        (b"*notanint\r\n", "Malformed RESP array length"),
        (b">2\r\n:1\r\n:2\r\n", "Invalid RESP push kind"),
        (b"_bogus\r\n", "Malformed RESP null"),
    ],
)
def test_decode_garbage_raises_protocol_error(garbage, message):
    """Genuinely corrupt input raises PhotonProtocolError, naming what it could not read."""
    with pytest.raises(PhotonProtocolError, match=message):
        decode_resp(garbage)


# --- bytearray input (incremental reader buffer) -------------------------------


def test_decode_from_bytearray_returns_bytes():
    """A bytearray buffer decodes like bytes: bulk strings come back as bytes and simple
    strings as str, exactly as from a bytes buffer — never as bytearray slices."""
    frame = bytearray(b"*3\r\n:200\r\n+OK\r\n$5\r\nhello\r\n")
    value, nxt = decode_resp(frame)
    assert value == [200, "OK", b"hello"]
    assert type(value[1]) is str and type(value[2]) is bytes
    assert nxt == len(frame)


def test_decode_from_bytearray_incremental_consume():
    """The reader's loop shape: append chunks to a bytearray, decode, delete the consumed
    prefix in place; the trailing bytes stay available for the next frame."""
    buf = bytearray()
    with pytest.raises(IncompleteFrame):
        decode_resp(buf)
    buf += b"$5\r\nhel"
    with pytest.raises(IncompleteFrame):
        decode_resp(buf)
    buf += b"lo\r\n+OK\r\n"
    value, consumed = decode_resp(buf)
    assert value == b"hello"
    assert type(value) is bytes
    del buf[:consumed]
    assert decode_resp(buf) == ("OK", 5)


def test_decode_bytearray_garbage_raises_protocol_error():
    """Unknown prefixes are rejected from a bytearray buffer too (hashable prefix lookup)."""
    with pytest.raises(PhotonProtocolError):
        decode_resp(bytearray(b"!bad\r\n"))


def test_decode_map_from_bytearray_keeps_keys_hashable():
    """A HELLO reply is a ``%`` map and dict keys must be hashable, so an unnormalized
    bytearray key raises PhotonProtocolError on the connect path. Added with the ``%``/``>``
    decoders, which landed after the bytearray change was written."""
    value, _ = decode_resp(bytearray(b"%1\r\n$5\r\nproto\r\n:3\r\n"))
    assert value == {b"proto": 3}
    assert all(type(key) is bytes for key in value)


def test_decode_push_from_bytearray_keeps_the_kind_bytes():
    """``_decode_push`` isinstance-checks the kind against bytes, so an unnormalized bytearray
    would be rejected as an invalid push kind."""
    value, _ = decode_resp(bytearray(b">2\r\n$15\r\nphoton.response\r\n:1\r\n"))
    assert value.kind == b"photon.response"
    assert type(value.kind) is bytes
