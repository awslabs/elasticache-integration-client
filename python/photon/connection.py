"""Pure-Python connector: the TCP+TLS transport plus its two concurrency primitives.

:class:`PhotonConnector` owns a single TCP+TLS connection, serializes writes with a send-lock, and
runs one daemon reader thread that turns the byte stream into framed ``RespValue``s
(:class:`_SocketReader`) and resolves per-request ``Future``s in FIFO order
(:class:`_PendingRequests`). Serialized writes + strict RESP request/response ⇒ response order
equals request order ⇒ FIFO correlation is correct.
"""

from __future__ import annotations

import socket
import ssl
import threading
from collections import deque
from concurrent.futures import Future
from concurrent.futures import TimeoutError as FuturesTimeout

from photon.exceptions import (
    PhotonConnectionLost,
    PhotonConnectionError,
    PhotonError,
    PhotonProtocolError,
    PhotonTimeoutError,
)
from photon.resp import (
    DEFAULT_MAX_FRAME_SIZE,
    IncompleteFrame,
    RespError,
    RespValue,
    decode_resp,
    encode_resp,
)

_DEFAULT_READ_SIZE = 65536
_DEFAULT_CONNECT_TIMEOUT = 1.0  # TCP connect + TLS handshake
_DEFAULT_REQUEST_TIMEOUT = 0.1  # covers the slowest legitimate cache miss (proxy -> DDB -> proxy)
_DEFAULT_CONFIG_TIMEOUT = 2.0   # init-phase commands: AUTH and photon.config
_AUTH_OK = b"OK"  # the RESP simple string "+OK", decoded
_DEFAULT_MAX_PENDING = 100


class _SocketReader:
    """Accumulates bytes from ``sock`` and yields one framed RespValue per :meth:`read_frame`."""

    def __init__(
        self,
        sock,
        read_size: int = _DEFAULT_READ_SIZE,
        max_response_size: int = DEFAULT_MAX_FRAME_SIZE,
    ) -> None:
        self._sock = sock
        self._read_size = read_size
        self._max_response_size = max_response_size
        self._buf = b""

    def read_frame(self) -> RespValue:
        """Return the next complete RESP value, recv-ing more bytes as needed.

        Raises :class:`~photon.exceptions.PhotonConnectionLost` on clean EOF and lets
        :class:`~photon.exceptions.PhotonProtocolError` (genuine garbage) propagate.
        """
        while True:
            try:
                value, consumed = decode_resp(
                    self._buf,
                    max_frame_size=self._max_response_size,
                )
            except IncompleteFrame:
                if len(self._buf) > self._max_response_size:
                    raise PhotonProtocolError(
                        f"response exceeded maximum size of {self._max_response_size} bytes"
                    )
                remaining = self._max_response_size + 1 - len(self._buf)
                chunk = self._sock.recv(min(self._read_size, remaining))
                if not chunk:
                    raise PhotonConnectionLost("connection closed by peer")
                self._buf += chunk
                continue
            if consumed > self._max_response_size:
                raise PhotonProtocolError(
                    f"response exceeded maximum size of {self._max_response_size} bytes"
                )
            self._buf = self._buf[consumed:]  # retain any trailing bytes for the next frame
            return value


class _PendingRequests:
    """Thread-safe FIFO of pending request futures; the reader resolves them in registration order."""

    def __init__(self, max_pending: int = _DEFAULT_MAX_PENDING) -> None:
        self._queue_lock = threading.Lock()
        self._queue: deque[Future] = deque()
        self._max_pending = max_pending

    def register_new_request(self) -> Future:
        future: Future = Future()
        with self._queue_lock:
            if len(self._queue) >= self._max_pending:
                raise PhotonError(f"too many in-flight requests (max {self._max_pending})")
            self._queue.append(future)
        return future

    def resolve_oldest_request(self, value: RespValue) -> None:
        """Resolve the oldest waiting future with ``value`` (FIFO). No-op if none is waiting.

        A ``RespError`` is a value, not an exception, so it is delivered as the future's result;
        the caller-facing ``parse_*`` functions raise on it.
        """
        with self._queue_lock:
            future = self._queue.popleft() if self._queue else None
        if future is not None and not future.done():
            future.set_result(value)

    def fail_all(self, exc: BaseException) -> None:
        """Fail every waiting future with ``exc`` and clear the queue."""
        with self._queue_lock:
            pending = list(self._queue)
            self._queue.clear()
        for future in pending:
            if not future.done():
                future.set_exception(exc)


class PhotonConnector:
    """Single TCP+TLS connection to the cache with a send-lock, daemon reader, and FIFO futures."""

    def __init__(
        self,
        host: str,
        port: int,
        *,
        tls: bool = True,
        connect_timeout: float = _DEFAULT_CONNECT_TIMEOUT,
        request_timeout: float = _DEFAULT_REQUEST_TIMEOUT,
        config_timeout: float = _DEFAULT_CONFIG_TIMEOUT,
        ca_certs: str | None = None,
        socket_read_size: int = _DEFAULT_READ_SIZE,
        max_response_size: int = DEFAULT_MAX_FRAME_SIZE,
        auth_provider=None,
        max_pending: int = _DEFAULT_MAX_PENDING,
    ) -> None:
        self._host = host
        self._port = port
        self._tls = tls
        self._connect_timeout = connect_timeout
        self._request_timeout = request_timeout
        self.config_timeout = config_timeout  # read by PhotonSession for the config exchange
        self._ca_certs = ca_certs
        self._read_size = socket_read_size
        if max_response_size <= 0:
            raise PhotonError("max_response_size must be positive")
        self._max_response_size = max_response_size
        self._auth_provider = auth_provider

        self._sock: socket.socket | None = None
        self._reader: _SocketReader | None = None
        self._reader_thread: threading.Thread | None = None
        self._pending = _PendingRequests(max_pending)
        self._send_lock = threading.Lock()
        self._state_lock = threading.Lock()
        self._dead = False
        self._closed = False

    # --- lifecycle -----------------------------------------------------------
    def connect(self) -> None:
        """Open the TCP+TLS connection and start the daemon reader thread.

        Raises :class:`~photon.exceptions.PhotonConnectionError` on DNS/TCP/TLS failure.
        """
        # Phase 1: build everything outside the lock (may block on DNS/TCP/TLS).
        try:
            sock = socket.create_connection((self._host, self._port), timeout=self._connect_timeout)
        except OSError as exc:
            raise PhotonConnectionError(f"Failed to connect to {self._host}:{self._port}") from exc
        sock = self._configure_socket(sock)
        reader = _SocketReader(sock, self._read_size, self._max_response_size)
        thread = threading.Thread(target=self._reader_loop, name="photon-reader", daemon=True)

        # Phase 2: install-or-rollback under the lock. Installing the socket AND starting the
        # reader thread in one critical section means close() can never run between them and
        # miss a thread that is about to start.
        with self._state_lock:
            if self._closed:
                # The application closed this connector while we were still setting up the
                # socket; the connector must stay closed, so dispose of the fresh socket
                # instead of installing it.
                sock.close()
                raise PhotonConnectionError("connector closed during connect")
            self._sock = sock
            self._reader = reader
            self._reader_thread = thread
            thread.start()

    def authenticate(self) -> None:
        """Send AUTH using the injected provider. No-op if no provider was set.

        Must be called after connect() and before the config handshake. On timeout, force-closes
        the connection to avoid FIFO desync.

        Raises:
            PhotonConnectionError: If AUTH is rejected or times out.
            PhotonError: If the provider fails to generate a token.
        """
        if self._auth_provider is None:
            return
        user_id, token = self._auth_provider()
        try:
            reply = self.execute("AUTH", user_id.encode(), token.encode(),
                                 timeout=self.config_timeout)
        except PhotonTimeoutError:
            self._mark_dead(PhotonConnectionLost("AUTH timed out"))
            raise PhotonConnectionError("IAM AUTH timed out — connection closed")
        self._verify_auth_reply(reply)

    def _verify_auth_reply(self, reply: RespValue) -> None:
        """Validate an AUTH reply. Raises PhotonConnectionError on rejection or unexpected shape.

        A success reply is the RESP simple string "+OK" (decoded to b"OK"); anything else is
        rejected rather than treated as success. The token is never included in error messages.
        """
        if isinstance(reply, RespError):
            self._mark_dead(PhotonConnectionLost(f"IAM AUTH rejected: {reply.code}"))
            raise PhotonConnectionError(f"IAM AUTH rejected: {reply.code}")
        if reply != _AUTH_OK:
            self._mark_dead(PhotonConnectionLost("unexpected AUTH reply"))
            raise PhotonConnectionError(f"IAM AUTH failed: unexpected reply {reply!r}")

    def _configure_socket(self, sock: socket.socket) -> socket.socket:
        """Wrap the connected socket in TLS (when enabled) and tune it for the reader thread."""
        if self._tls:
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
            ctx.minimum_version = ssl.TLSVersion.TLSv1_2
            ctx.check_hostname = True
            ctx.verify_mode = ssl.CERT_REQUIRED
            if self._ca_certs:
                ctx.load_verify_locations(self._ca_certs)
            else:
                ctx.load_default_certs()
            try:
                sock = ctx.wrap_socket(sock, server_hostname=self._host)
            except (ssl.SSLError, OSError) as exc:
                sock.close()
                raise PhotonConnectionError(f"TLS handshake failed to {self._host}:{self._port}") from exc

        try:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            # Permanent timeout: recv wakes the reader periodically (quiet is normal, it
            # loops); a sendall timeout is fatal (the frame may be half-sent, so the stream
            # toward the server is corrupt). Per-call settimeout would race the reader on
            # the shared socket.
            sock.settimeout(self._request_timeout)
        except OSError as exc:
            sock.close()
            raise PhotonConnectionError(f"Failed to configure socket to {self._host}:{self._port}") from exc
        return sock

    def close(self) -> None:
        """Stop the reader, fail any pending requests, and close the socket. Idempotent."""
        # Phase 1: capture and null the state under the lock (fast, no I/O).
        with self._state_lock:
            if self._closed:
                return
            self._closed = True
            self._dead = True
            sock = self._sock
            self._sock = None
            thread = self._reader_thread
            self._reader_thread = None

        # Phase 2: teardown outside the lock (shutdown/join may block).
        if sock is not None:
            # shutdown() unblocks the reader thread's blocking recv() (close() alone does not
            # reliably interrupt a recv in progress on another thread); then close the fd.
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                sock.close()
            except OSError:
                pass
        self._pending.fail_all(PhotonConnectionLost("connection closed"))
        if thread is not None and thread is not threading.current_thread():
            thread.join(timeout=5)

    # --- send path -----------------------------------------------------------
    def _send(self, command: str, *args: bytes) -> Future:
        """Atomically register a future and send the frame. Returns the future for the reply.

        Register + send happen under one lock: the reader resolves futures in FIFO order,
        so queue order has to match the order frames hit the wire.
        """
        frame = encode_resp(command, *args)
        if not self._send_lock.acquire(timeout=self._request_timeout):
            raise PhotonTimeoutError(f"timed out waiting to send after {self._request_timeout}s")
        try:
            # Capture the socket: close() nulls self._sock, and reading it once avoids an
            # AttributeError if a close races this send. A send on a captured-but-closed
            # socket fails with OSError, handled below.
            sock = self._sock
            if self._dead or sock is None:
                raise PhotonConnectionLost("connection is closed")
            future = self._pending.register_new_request()
            try:
                sock.sendall(frame)
            except TimeoutError as exc:
                # The frame may be half-written: the stream toward the server is corrupt.
                error = PhotonTimeoutError(f"send timed out after {self._request_timeout}s")
                self._mark_dead(error)
                self.close()
                raise error from exc
            except OSError as exc:
                error = PhotonConnectionLost("send failed")
                self._mark_dead(error)
                self.close()  # same teardown as a send timeout: release the fd, join the reader
                raise error from exc
        finally:
            self._send_lock.release()
        return future

    def execute(self, command: str, *args: bytes, timeout: float | None = None) -> RespValue:
        """Send one command and block for its reply, returning the raw :data:`RespValue`.
         A ``RespError`` reply is returned as a value, not raised.

        ``timeout`` overrides the reply wait only (init commands get a larger budget);
        the send path (lock acquire, socket) stays on request_timeout.
        """
        if self._sock is None and not self._closed:
            raise PhotonError("PhotonConnector used before connect()")
        wait = timeout if timeout is not None else self._request_timeout
        future = self._send(command, *args)
        # Wait outside the lock so other threads can send while we block.
        try:
            return future.result(timeout=wait)
        except FuturesTimeout:
            # Intentional: one timeout resets the whole connection. close() fails every other
            # in-flight request too — a late reply would otherwise be FIFO-matched to the
            # wrong caller. The planned out-of-band mode (request-id-tagged replies) removes
            # this cost.
            self.close()
            raise PhotonTimeoutError(f"request timed out after {wait}s")

    # --- reader thread -------------------------------------------------------
    def _reader_loop(self) -> None:
        try:
            while not self._dead:
                try:
                    # Block until one complete RESP frame has arrived off the socket.
                    value = self._reader.read_frame()
                except TimeoutError:
                    # Nothing arrived within request_timeout: a quiet connection is normal.
                    # Re-check _dead and keep waiting. (Must precede OSError: TimeoutError
                    # is an OSError subclass.)
                    continue
                # Hand it to the oldest waiting caller (FIFO), waking their future.result().
                self._pending.resolve_oldest_request(value)
        except (PhotonConnectionLost, PhotonProtocolError) as exc:
            self._mark_dead(exc)
        except OSError as exc:
            error = PhotonConnectionLost("socket read failed")
            error.__cause__ = exc
            self._mark_dead(error)

    def _mark_dead(self, exc: BaseException) -> None:
        self._dead = True
        self._pending.fail_all(exc)
