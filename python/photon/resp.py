"""Generic RESP3 wire codec for the Photon client.

Owns the RESP value types (:data:`RespValue`, :class:`RespError`), the framing functions
(:func:`encode_resp`, :func:`decode_resp`), and :class:`IncompleteFrame`. ``decode_resp`` distinguishes a
**truncated** frame (more bytes are coming → :class:`IncompleteFrame`) from genuine **garbage**
(unparseable input → :class:`~photon.exceptions.PhotonProtocolError`), so an incremental reader can
tell "need more bytes" apart from "the stream is corrupt".
"""

from __future__ import annotations

from dataclasses import dataclass

from photon.exceptions import PhotonProtocolError

_CRLF = b"\r\n"


@dataclass(slots=True, frozen=True)
class RespError:
    """A normalized RESP error reply (``-CODE message``)."""

    code: str
    message: str

    def __str__(self) -> str:  # pragma: no cover - trivial
        return f"{self.code} {self.message}".strip()


@dataclass(slots=True, frozen=True)
class RespPush:
    """A RESP3 push frame (``>``): an out-of-band message, not the reply to any command.

    The first element is the kind (e.g. ``b"photon.response"``); the rest are its items.
    """

    kind: bytes
    items: list["RespValue"]


# A decoded RESP value: integer, simple string (str), bulk string (bytes), array (list),
# map (dict), error, push, or null (None).
RespValue = (
    int
    | str
    | bytes
    | list["RespValue"]
    | dict["RespValue", "RespValue"]
    | RespError
    | RespPush
    | None
)


class IncompleteFrame(Exception):
    """Raised by :func:`decode_resp` when the buffer holds only a partial frame (need more bytes)."""


def encode_resp(command: str, *args: bytes) -> bytes:
    r"""Encode a command and its binary args as one RESP3 frame, ready for the socket::

    encode_resp("photon.request", b"POST", b"https://x/") ->

    3 values       the command               arg 1            arg 2
    │              │                         │                │
    *3\r\n $14\r\n photon.request\r\n $4\r\n POST\r\n $10\r\n https://x/\r\n
           │                          │               │
           value is 14 bytes long     4 bytes long    10 bytes long
    """
    elements = [command.encode()] + list(args)
    frame = [b"*" + str(len(elements)).encode() + _CRLF]
    for el in elements:
        frame.append(b"$" + str(len(el)).encode() + _CRLF + el + _CRLF)
    return b"".join(frame)


def _parse_int(header: bytes, what: str) -> int:
    """Parse a numeric RESP header, raising PhotonProtocolError on garbage."""
    try:
        return int(header)
    except ValueError as exc:
        raise PhotonProtocolError(f"Malformed RESP {what}: {header!r}") from exc


def _decode_simple_string(data: bytes, header: bytes, after_header: int) -> tuple[RespValue, int]:
    """Decode a ``+`` simple string to ``str``. Bulk strings stay ``bytes``."""
    return header.decode(), after_header


def _decode_integer(data: bytes, header: bytes, after_header: int) -> tuple[RespValue, int]:
    """Decode a ``:`` integer."""
    return _parse_int(header, "integer"), after_header


def _decode_error(data: bytes, header: bytes, after_header: int) -> tuple[RespValue, int]:
    """Decode a ``-`` error into a :class:`RespError` (``-CODE message``)."""
    text = header.decode("utf-8", errors="replace")
    code, _, message = text.partition(" ")
    return RespError(code=code, message=message), after_header


def _decode_null(data: bytes, header: bytes, after_header: int) -> tuple[RespValue, int]:
    """Decode a ``_`` null, the RESP3 form. A valid one carries no body, so a payload is
    rejected rather than ignored: a garbled null would otherwise pass as a real one."""
    if header:
        raise PhotonProtocolError(f"Malformed RESP null: {header!r}")
    return None, after_header


def _decode_bulk_string(data: bytes, header: bytes, after_header: int) -> tuple[RespValue, int]:
    """Decode a ``$`` bulk string, or ``None`` for the ``$-1`` null bulk."""
    length = _parse_int(header, "bulk length")
    if length == -1:
        return None, after_header
    end = after_header + length
    if end + 2 > len(data):  # body + trailing CRLF not all present yet
        raise IncompleteFrame("bulk string body incomplete")
    return data[after_header:end], end + 2


def _decode_array(data: bytes, header: bytes, after_header: int) -> tuple[RespValue, int]:
    """Decode a ``*`` array, or ``None`` for the ``*-1`` null array."""
    count = _parse_int(header, "array length")
    if count == -1:
        return None, after_header
    items: list[RespValue] = []
    pos = after_header
    for _ in range(count):
        item, pos = decode_resp(data, pos)  # inner IncompleteFrame propagates
        items.append(item)
    return items, pos


def _decode_push(data: bytes, header: bytes, after_header: int) -> tuple[RespValue, int]:
    """Decode a ``>`` push: array element semantics, first element is the kind, rest are items."""
    elements, pos = _decode_array(data, header, after_header)
    if not elements:  # ``>0`` or the null ``>-1``
        return RespPush(kind=b"", items=[]), pos
    kind, *items = elements
    if isinstance(kind, str):  # a kind sent as a simple string rather than a bulk string
        kind = kind.encode()
    elif not isinstance(kind, bytes):
        raise PhotonProtocolError(f"Invalid RESP push kind: {kind!r}")
    return RespPush(kind=kind, items=items), pos


def _decode_map(data: bytes, header: bytes, after_header: int) -> tuple[RespValue, int]:
    """Decode a ``%`` map into a ``dict``. Keys and values are each any RESP value."""
    count = _parse_int(header, "map length")
    if count < 0:
        raise PhotonProtocolError(f"Malformed RESP map length: {header!r}")
    items: dict[RespValue, RespValue] = {}
    pos = after_header
    for _ in range(count):
        key, pos = decode_resp(data, pos)  # inner IncompleteFrame propagates
        value, pos = decode_resp(data, pos)
        try:
            items[key] = value
        except TypeError as exc:  # aggregate key: legal RESP3, unusable as a dict key
            raise PhotonProtocolError(f"Unhashable RESP map key: {key!r}") from exc
    return items, pos


# Prefix → decoder dispatch.
_DECODERS = {
    b"+": _decode_simple_string,
    b":": _decode_integer,
    b"-": _decode_error,
    b"_": _decode_null,
    b"$": _decode_bulk_string,
    b"*": _decode_array,
    b">": _decode_push,
    b"%": _decode_map,
}


def decode_resp(data: bytes, start: int = 0) -> tuple[RespValue, int]:
    """Decode a single RESP3 value beginning at ``start``.

    Returns ``(value, next_pos)`` where ``next_pos`` is the index immediately after the
    consumed frame — pass it back as ``start`` to read the next frame::

        decode_resp(b"+OK\\r\\n")               -> ("OK", 5)
        decode_resp(b"$5\\r\\nhello\\r\\n")     -> (b"hello", 11)
        decode_resp(b"*2\\r\\n:1\\r\\n:2\\r\\n") -> ([1, 2], 12)

    Raises:
        IncompleteFrame: not an error — the frame is valid so far but the rest of its
            bytes have not arrived yet. Read more from the socket and call again.
        PhotonProtocolError: the bytes can never form a valid frame; the stream is
            corrupt and the connection should be closed.
    """
    header_end = data.find(_CRLF, start)
    if header_end == -1:
        raise IncompleteFrame("incomplete frame")
    prefix = data[start : start + 1]
    decoder = _DECODERS.get(prefix)
    if decoder is None:
        raise PhotonProtocolError(f"Unsupported RESP prefix: {prefix!r}")
    header = data[start + 1 : header_end]
    return decoder(data, header, header_end + len(_CRLF))
