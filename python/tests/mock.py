"""Test doubles for the Photon client suite — all in one place.

This module holds the three authored mock classes the suite uses:

* :class:`MockCache` — stands in for the ElastiCache Serverless cache. It models the **client↔cache
  wire contract**: the two commands ``photon.config`` and ``photon.request``, the synchronous
  success reply, and the error reply. A pure :meth:`MockCache.handle` method *is* the contract
  engine (validates command shape, picks a reply) and does no I/O, so it
  is unit-testable without a socket. A small synchronous, threaded TLS server wraps it so the real
  :class:`~photon.connection.PhotonConnector` can talk to it over verifying TLS.
* :class:`MockSocket` — an in-memory, scriptable socket for ``_SocketReader`` unit tests.
* :class:`MockClient` — a botocore client stand-in exposing the ``.meta.events`` emitter.

For the layers above the cache it also provides two ``create_autospec`` factory functions,
:func:`mock_connector` and :func:`mock_session`, used by the session and hooks tests.
"""

from __future__ import annotations

import datetime
import ipaddress
import json
import os
import socket
import ssl
import tempfile
import threading
import types
from dataclasses import dataclass
from collections.abc import Callable, Sequence
from unittest.mock import create_autospec

from botocore.hooks import HierarchicalEmitter

from photon.connection import PhotonConnector
from photon.models import PhotonResponse, RequestMetadata
from photon.session import PhotonSession

_CRLF = b"\r\n"

# The resolved config the real proxy returns for empty overrides — see
# ElastiCacheProxyRust/src/libproxy/photon/config.rs::resolve. One CEL predicate caching every
# supported read regardless of table; `version` is the predicate *format* version ("1.0", constant).
DEFAULT_RESOLVED_CONFIG = {
    "version": "1.0",
    "refresh_interval": 60,
    "ttl": 300,
    "nttl": 60,
    "predicates": [
        {
            "expr": "service == 'dynamodb' && operation in ops",
            "vars": {"ops": ["GetItem", "BatchGetItem", "Query", "Scan", "PutItem", "UpdateItem",
                             "DeleteItem", "BatchWriteItem", "TransactGetItems",
                             "TransactWriteItems"]},
        }
    ],
}


def table_allowlist_config(*tables: str) -> dict:
    """A resolved config restricting caching to ``tables`` — the two CEL predicates the proxy emits
    for a ``tables`` override (single-table reads on ``body.TableName``, ``BatchGetItem`` on the keys
    of ``body.RequestItems``)."""
    allowed = sorted(tables)
    return {
        "version": "1.0",
        "refresh_interval": 60,
        "ttl": 300,
        "nttl": 60,
        "predicates": [
            {
                "expr": "service == 'dynamodb' && operation in ops "
                        "&& has(body.TableName) && body.TableName in allowed",
                "vars": {"ops": ["GetItem", "Query", "Scan", "PutItem", "UpdateItem", "DeleteItem"],
                         "allowed": allowed},
            },
            {
                "expr": "service == 'dynamodb' && operation in ops "
                        "&& has(body.RequestItems) && size(body.RequestItems) > 0 "
                        "&& body.RequestItems.all(t, t in allowed)",
                "vars": {"ops": ["BatchGetItem", "BatchWriteItem"], "allowed": allowed},
            },
            {
                "expr": "service == 'dynamodb' && operation in ops "
                        "&& has(body.TransactItems) && size(body.TransactItems) > 0 "
                        "&& body.TransactItems.all(i, i.all(k, i[k].TableName in allowed))",
                "vars": {"ops": ["TransactGetItems", "TransactWriteItems"], "allowed": allowed},
            },
        ],
    }

# Error codes the real cache uses in its `-<CODE> <message>` replies. They are
# client-visible protocol, so the mock replies with the same names.
CODE_PARSE_ERROR = "PARSE_ERROR"
CODE_UNSUPPORTED = "UNSUPPORTED"
CODE_UNIMPLEMENTED = "UNIMPLEMENTED"
CODE_CONFIG_INVALID = "CONFIG_INVALID"


# --------------------------------------------------------------------------- #
# RESP encoding helpers
# --------------------------------------------------------------------------- #
def _bulk(data: bytes) -> bytes:
    return b"$" + str(len(data)).encode() + _CRLF + data + _CRLF


def _int(value: int) -> bytes:
    return b":" + str(value).encode() + _CRLF


def _array(items: list[bytes]) -> bytes:
    return b"*" + str(len(items)).encode() + _CRLF + b"".join(items)


def _error(code: str, message: str) -> bytes:
    text = f"{code} {message}".strip()
    # An error line is CRLF-framed; a newline inside it would silently truncate the
    # frame and desynchronize the stream.
    if "\r" in text or "\n" in text:
        raise ValueError(f"error text must be newline-free: {text!r}")
    return b"-" + text.encode() + _CRLF


# --------------------------------------------------------------------------- #
# Replies — the in-scope cache response types
# --------------------------------------------------------------------------- #
@dataclass
class Reply:
    """A RESP response to ``photon.request``."""

    frame: bytes

    def to_bytes(self) -> bytes:
        return self.frame

    @classmethod
    def ok(
        cls,
        status: int = 200,
        headers: str = "content-type:application/x-amz-json-1.0",
        body: bytes = b'{"Item": {}}',
    ) -> "Reply":
        """A synchronous success reply: ``*3`` array of ``[:status, $headers, $body]``."""
        return cls(_array([_int(status), _bulk(headers.encode("utf-8")), _bulk(body)]))

    @classmethod
    def error(cls, code: str = "ERR", message: str = "mock error reply") -> "Reply":
        """A RESP error reply ``-<CODE> <message>``."""
        return cls(_error(code, message))


# --------------------------------------------------------------------------- #
# Parsed request record (what the cache received and decoded)
# --------------------------------------------------------------------------- #
@dataclass
class ReceivedRequest:
    """The decoded view of one ``photon.request`` the cache received."""

    method: bytes
    url: bytes
    headers: bytes
    body: bytes
    metadata: RequestMetadata
    raw_args: list[bytes]


# --------------------------------------------------------------------------- #
# Self-signed cert (so the client can perform verifying TLS against the mock)
# --------------------------------------------------------------------------- #
def _generate_self_signed_cert() -> tuple[str, str]:
    """Generate a self-signed cert/key for 127.0.0.1; return ``(cert_path, key_path)``."""
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import NameOID

    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "127.0.0.1")])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(minutes=5))
        .not_valid_after(now + datetime.timedelta(days=1))
        .add_extension(
            x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]),
            critical=False,
        )
        .sign(key, hashes.SHA256())
    )
    cert_file = tempfile.NamedTemporaryFile(suffix=".crt", delete=False)
    key_file = tempfile.NamedTemporaryFile(suffix=".key", delete=False)
    cert_file.write(cert.public_bytes(serialization.Encoding.PEM))
    key_file.write(
        key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.TraditionalOpenSSL,
            encryption_algorithm=serialization.NoEncryption(),
        )
    )
    cert_file.close()
    key_file.close()
    return cert_file.name, key_file.name


# --------------------------------------------------------------------------- #
# RESP command reader (client -> cache is always an array of bulk strings)
# --------------------------------------------------------------------------- #
def _read_command(rfile) -> list[bytes] | None:
    """Read one RESP command (array of bulk strings) from a blocking file object.

    Returns ``None`` on clean EOF, ``[]`` on an unexpected/inline line (skipped by the caller).
    """
    line = rfile.readline()
    if not line:
        return None
    if not line.startswith(b"*"):
        return []
    count = int(line[1:].strip())
    args: list[bytes] = []
    for _ in range(count):
        header = rfile.readline()
        if not header.startswith(b"$"):
            raise ValueError(f"Expected bulk string, got {header!r}")
        length = int(header[1:].strip())
        data = rfile.read(length)
        terminator = rfile.read(2)
        if terminator != _CRLF:
            raise ValueError(f"bulk string not CRLF-terminated: {terminator!r}")
        args.append(data)
    return args


class MockCache:
    """A fake Photon cache server.

    Accepts any AUTH, serves a canned config, and answers each ``photon.request`` with
    ``response_fn(request)``, a fixed ``response``, or the default built from
    ``response_status``/``response_headers``/``response_body``
    Records everything received (``received_commands``, ``photon_command_log``, ``requests``).

    ``failure="hang"`` never replies; ``failure="drop"`` closes the connection instead of
    replying. ``failure_after_reply=True`` sends the normal reply first and *then* misbehaves —
    e.g. with ``failure="drop"`` the reply and the connection close arrive back-to-back.
    ``failure_command`` picks the command that triggers it (default
    ``photon.request``; use ``"auth"`` to break the handshake).

    Plaintext by default; ``tls=True`` serves verifying TLS — trust it via ``ca_certs=cache.cert_path``.

    Threading model: each accepted connection is served by its own thread, which appends to
    the recording lists while the test thread reads them (safe for single appends; read them
    after the reply that produced them has arrived). The mock models **one client connection
    at a time**.
    """

    def __init__(
        self,
        config: dict | None = None,
        *,
        tls: bool = False,
        response_status: int = 200,
        response_headers: str = "content-type:application/x-amz-json-1.0",
        response_body: bytes = b'{"Item": {}}',
        response: Reply | None = None,
        response_fn: Callable[[ReceivedRequest], Reply] | None = None,
        failure: str | None = None,
        failure_command: str = "photon.request",  # which command triggers `failure` (e.g. "auth")
        failure_after_reply: bool = False,  # reply normally first, then apply `failure`
    ) -> None:
        self._config = config or DEFAULT_RESOLVED_CONFIG
        self.tls = tls
        self.response_status = response_status
        self.response_headers = response_headers
        self.response_body = response_body
        self._response = response
        self._response_fn = response_fn
        if failure not in (None, "hang", "drop"):
            raise ValueError(f"Unsupported failure mode: {failure!r}")
        self.failure = failure
        self.failure_command = failure_command.lower()
        self.failure_after_reply = failure_after_reply

        # Recording, for test assertions.
        self.received_commands: list[list[bytes]] = []  # every command, full arg list incl. name
        self.photon_command_log: list[str] = []  # names of auth/photon.* commands, in order
        self.requests: list[ReceivedRequest] = []  # decoded photon.request records, in order
        self.config_overrides: list[dict] = []  # decoded photon.config overrides, in order
        self.protocol_errors: list[str] = []  # RESP framing violations by the client, in order

        # Server state.
        self._listen_sock: socket.socket | None = None
        self._ssl_ctx: ssl.SSLContext | None = None
        self._accept_thread: threading.Thread | None = None
        self._stop = threading.Event()
        self.host: str | None = None
        self.port: int | None = None
        self.cert_path: str | None = None
        self._key_path: str | None = None

    # --- introspection --------------------------------------------------------
    @property
    def endpoint(self) -> str:
        return f"{self.host}:{self.port}"

    @property
    def last_request_args(self) -> list[bytes] | None:
        """Raw arg list (command name excluded) of the most recent ``photon.request``.

        Returns ``[method, url, headers, body, metadata]`` or ``None`` if no
        ``photon.request`` has been received. Lets a test assert exactly what the client shipped.
        """
        return self.requests[-1].raw_args if self.requests else None

    @property
    def last_request(self) -> ReceivedRequest | None:
        """The most recent decoded ``photon.request`` record, or ``None``."""
        return self.requests[-1] if self.requests else None

    # --- pure contract engine (no I/O) ---------------------------------------
    def handle(self, command: list[bytes]) -> bytes:
        """Turn one decoded RESP command into the RESP reply frame. Pure: no socket, no threads.

        Validates command shape, records the command, parses a ``photon.request``, and selects
        the reply. Returns RESP bytes ready to send.
        """
        self.received_commands.append(command)
        name = command[0].decode("utf-8", errors="replace").lower()
        args = command[1:]

        if name == "auth":
            self.photon_command_log.append(name)
            # Accept any credentials — this is a test mock.
            return b"+OK\r\n"

        if name == "photon.config":
            self.photon_command_log.append(name)
            if len(args) != 1:
                return _error(CODE_CONFIG_INVALID, f"expected 1 arg, got {len(args)}")
            try:
                overrides = json.loads(args[0])
            except (ValueError, UnicodeDecodeError):
                return _error(CODE_CONFIG_INVALID, "overrides is not valid JSON")
            if not isinstance(overrides, dict):
                return _error(CODE_CONFIG_INVALID, "overrides must be a JSON object")
            self.config_overrides.append(overrides)
            return _bulk(json.dumps(self._config).encode("utf-8"))

        if name == "photon.request":
            self.photon_command_log.append(name)
            # Exactly 5 args: method, url, headers, body, metadata. The real cache
            # rejects any other count with -PARSE_ERROR.
            if len(args) != 5:
                return _error(CODE_PARSE_ERROR, "failed to parse photon.request")
            metadata = self._parse_metadata(args[4])
            if metadata is None:
                return _error(CODE_PARSE_ERROR, "failed to parse photon.request")
            parsed = self._parse_request(args, metadata)
            self.requests.append(parsed)
            return self._reply_for(parsed).to_bytes()

        return _error("ERR", "unknown command")

    @staticmethod
    def _parse_metadata(raw: bytes) -> RequestMetadata | None:
        """Parse the metadata arg the way the real cache does: ``service=<name>&op=<operation>``,
        both keys required, unknown keys ignored for forward compatibility. ``None`` = invalid."""
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError:
            return None
        service = operation = None
        for pair in text.split("&"):
            key, sep, value = pair.partition("=")
            if not sep:
                continue
            if key == "service":
                service = value
            elif key == "op":
                operation = value
        if service is None or operation is None:
            return None
        return RequestMetadata(service=service, operation=operation)

    @staticmethod
    def _parse_request(args: list[bytes], metadata: RequestMetadata) -> ReceivedRequest:
        """Decode the 5 mandatory args of a ``photon.request``."""
        method, url, headers, body, _raw_metadata = args
        return ReceivedRequest(
            method=method,
            url=url,
            headers=headers,
            body=body,
            metadata=metadata,
            raw_args=list(args),
        )

    def _reply_for(self, parsed: ReceivedRequest) -> Reply:
        """Pick the reply for a parsed request: callable, then override, then default."""
        if self._response_fn is not None:
            return self._response_fn(parsed)
        if self._response is not None:
            return self._response
        return Reply.ok(self.response_status, self.response_headers, self.response_body)

    # --- synchronous threaded TLS server -------------------------------------
    def start(self) -> None:
        """Start the threaded server on an ephemeral 127.0.0.1 port (plaintext unless ``tls``)."""
        if self.tls:
            self.cert_path, self._key_path = _generate_self_signed_cert()
            self._ssl_ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            self._ssl_ctx.load_cert_chain(self.cert_path, self._key_path)

        self._listen_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._listen_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._listen_sock.bind(("127.0.0.1", 0))
        self._listen_sock.listen(16)
        self.host, self.port = self._listen_sock.getsockname()[:2]

        self._accept_thread = threading.Thread(
            target=self._accept_loop, name="mock-cache-accept", daemon=True
        )
        self._accept_thread.start()

    def stop(self) -> None:
        """Stop accepting, unblock any hung handler, and close the listening socket. Idempotent."""
        self._stop.set()
        if self._listen_sock is not None:
            try:
                self._listen_sock.close()  # unblocks accept()
            except OSError:
                pass
        if self._accept_thread is not None and self._accept_thread is not threading.current_thread():
            self._accept_thread.join(5)
        # The self-signed cert/key were written with delete=False; remove them so a
        # test run doesn't accumulate temp files (one of them is a private key).
        for attr in ("cert_path", "_key_path"):
            path = getattr(self, attr)
            if path is not None:
                try:
                    os.unlink(path)
                except OSError:
                    pass
                setattr(self, attr, None)

    def _accept_loop(self) -> None:
        assert self._listen_sock is not None
        self._listen_sock.settimeout(0.5)  # wake periodically to observe the stop flag
        while not self._stop.is_set():
            try:
                conn, _ = self._listen_sock.accept()
            except socket.timeout:
                continue
            except OSError:
                break  # listening socket closed by stop()
            threading.Thread(
                target=self._handle_connection, args=(conn,), daemon=True
            ).start()

    def _handle_connection(self, conn: socket.socket) -> None:
        if self.tls:
            assert self._ssl_ctx is not None
            try:
                sock = self._ssl_ctx.wrap_socket(conn, server_side=True)
            except (ssl.SSLError, OSError):
                conn.close()
                return
        else:
            sock = conn
        try:
            rfile = sock.makefile("rb")
            while not self._stop.is_set():
                command = _read_command(rfile)
                if command is None:
                    break  # clean EOF
                if not command:
                    continue  # inline/unexpected line, skip
                name = command[0].decode("utf-8", errors="replace").lower()
                if name == self.failure_command and self.failure in ("hang", "drop"):
                    if self.failure_after_reply:
                        # Reply normally first (handle() records the command), THEN misbehave.
                        sock.sendall(self.handle(command))
                    else:
                        # Record the command (other commands still succeed normally), then
                        # misbehave. A well-formed photon.request is also parsed into
                        # ``requests`` — a failure test asserting on last_request must see
                        # the request that triggered the failure, not the previous one.
                        self.received_commands.append(command)
                        self.photon_command_log.append(name)
                        if name == "photon.request" and len(command) == 6:
                            metadata = self._parse_metadata(command[5])
                            if metadata is not None:
                                self.requests.append(self._parse_request(command[1:], metadata))
                    if self.failure == "drop":
                        break  # close the connection (without replying unless failure_after_reply)
                    self._stop.wait()  # hang: never reply again, hold the connection until shutdown
                    break
                sock.sendall(self.handle(command))
        except (OSError, ssl.SSLError):
            pass
        except ValueError as exc:
            # The client broke RESP framing. Record it so tests can assert the
            # suite never produced a malformed frame (a silent close here would be
            # indistinguishable from failure="drop").
            self.protocol_errors.append(str(exc))
        finally:
            try:
                sock.close()
            except Exception:  # noqa: BLE001 - best-effort socket teardown
                pass


# --------------------------------------------------------------------------- #
# MockSocket — in-memory socket for _SocketReader unit tests
# --------------------------------------------------------------------------- #
class MockSocket:
    """Returns each preset chunk from :meth:`recv` (regardless of requested size); ``b""`` = EOF."""

    def __init__(self, chunks: Sequence[bytes]) -> None:
        self._chunks = list(chunks)
        self.recv_calls = 0

    def recv(self, _n: int) -> bytes:
        self.recv_calls += 1
        if not self._chunks:
            return b""
        return self._chunks.pop(0)


# --------------------------------------------------------------------------- #
# MockClient — botocore client stand-in
# --------------------------------------------------------------------------- #
class MockClient:
    """Client stand-in exposing the ``.meta.events`` emitter the hooks register on.

    Backed by a real botocore ``HierarchicalEmitter`` so register/unregister/emit behave as on a
    real client, without binding the tests to any particular AWS service.
    """

    def __init__(self) -> None:
        self.meta = types.SimpleNamespace(events=HierarchicalEmitter())
        self.closed = False

    def close(self) -> None:
        self.closed = True


# --------------------------------------------------------------------------- #
# Autospec doubles for the layers above the cache
# --------------------------------------------------------------------------- #
def mock_connector() -> PhotonConnector:
    """A ``create_autospec(PhotonConnector)`` double with sensible default replies.

    Bound to the real class, so the session is verified against the real connector signatures
    without a network. ``execute`` returns wire-shaped ``RespValue``s (the session does the
    parsing): a JSON bulk string for ``photon.config``, a ``[status, headers, body]`` array
    otherwise. Override ``execute.side_effect``/``return_value`` in the test as needed.
    """
    conn = create_autospec(PhotonConnector, instance=True)
    # Instance attribute (set in __init__), invisible to autospec; the session reads it.
    conn.config_timeout = 2.0

    def _execute(command, *args, timeout=None):
        if command == "photon.config":
            return json.dumps(DEFAULT_RESOLVED_CONFIG).encode()
        return [200, b"content-type:application/json", b'{"ok":true}']

    conn.execute.side_effect = _execute
    return conn


def mock_session() -> PhotonSession:
    """A ``create_autospec(PhotonSession)`` double that returns a 200 PhotonResponse by default."""
    session = create_autospec(PhotonSession, instance=True)
    session.request.return_value = PhotonResponse(status_code=200, headers={}, body=b"{}")
    return session
