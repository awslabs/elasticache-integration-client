"""Pure-Python connector: the TCP+TLS transport plus its two concurrency primitives.

:class:`PhotonConnector` owns a single TCP+TLS connection. All I/O runs on a private asyncio
event loop in one daemon thread: callers hand the loop a plain callback that registers a reply
``Future`` and writes the frame, and a reader task turns the byte stream into framed
``RespValue``s (:class:`_SocketReader`) and resolves those futures in FIFO order
(:class:`_PendingRequests`). One callback at a time on one loop thread ⇒ queue order equals
wire order ⇒ FIFO correlation is correct.
"""

from __future__ import annotations

import asyncio
import ssl
import threading
from collections import deque
from concurrent.futures import CancelledError as FuturesCancelledError
from concurrent.futures import Future
from concurrent.futures import TimeoutError as FuturesTimeout

from photon.exceptions import (
    PhotonConnectionLost,
    PhotonConnectionError,
    PhotonError,
    PhotonProtocolError,
    PhotonTimeoutError,
)
from photon.resp import IncompleteFrame, RespError, RespValue, decode_resp, encode_resp

_DEFAULT_READ_SIZE = 65536
_DEFAULT_CONNECT_TIMEOUT = 1.0  # TCP connect + TLS handshake
_DEFAULT_REQUEST_TIMEOUT = 0.1  # covers the slowest legitimate cache miss (proxy -> DDB -> proxy)
_DEFAULT_CONFIG_TIMEOUT = 2.0   # init-phase commands: AUTH and photon.config
_AUTH_OK = b"OK"  # the RESP simple string "+OK", decoded
_DEFAULT_MAX_PENDING = 100


class _SocketReader:
    """Accumulates bytes from ``stream`` and yields one framed RespValue per :meth:`read_frame`."""

    def __init__(self, stream: asyncio.StreamReader, read_size: int = _DEFAULT_READ_SIZE) -> None:
        self._stream = stream
        self._read_size = read_size
        self._buf = b""

    async def read_frame(self) -> RespValue:
        """Return the next complete RESP value, reading more bytes as needed.

        Raises :class:`~photon.exceptions.PhotonConnectionLost` on clean EOF and lets
        :class:`~photon.exceptions.PhotonProtocolError` (genuine garbage) propagate.
        """
        while True:
            try:
                value, consumed = decode_resp(self._buf)
            except IncompleteFrame:
                chunk = await self._stream.read(self._read_size)
                if not chunk:
                    raise PhotonConnectionLost("connection closed by peer")
                self._buf += chunk
                continue
            self._buf = self._buf[consumed:]  # retain any trailing bytes for the next frame
            return value


class _PendingRequests:
    """Thread-safe FIFO of pending request futures; the reader resolves them in registration order."""

    def __init__(self, max_pending: int = _DEFAULT_MAX_PENDING) -> None:
        self._queue_lock = threading.Lock()
        self._queue: deque[Future] = deque()
        self._max_pending = max_pending

    def register_new_request(self, future: Future) -> None:
        with self._queue_lock:
            if len(self._queue) >= self._max_pending:
                raise PhotonError(f"too many in-flight requests (max {self._max_pending})")
            self._queue.append(future)

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
    """Single TCP+TLS connection to the cache owned by an I/O loop, with FIFO futures."""

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
        verify: bool = True,
        socket_read_size: int = _DEFAULT_READ_SIZE,
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
        self._verify = verify
        self._read_size = socket_read_size
        self._auth_provider = auth_provider

        self._writer: asyncio.StreamWriter | None = None
        self._io_loop: asyncio.AbstractEventLoop | None = None
        self._io_thread: threading.Thread | None = None
        self._reader_task: asyncio.Task | None = None
        self._pending = _PendingRequests(max_pending)
        self._state_lock = threading.Lock()
        self._dead = False
        self._closed = False

    # --- lifecycle -----------------------------------------------------------
    def connect(self) -> None:
        """Open the TCP+TLS connection and start the I/O loop thread.
        Raises :class:`~photon.exceptions.PhotonConnectionError` on any failure.
        """
        # Start the I/O thread. One atomic step with the closed-check, so a concurrent
        # close() either refuses us here or finds the thread and dismisses it.
        with self._state_lock:
            if self._closed:
                raise PhotonConnectionError("connector closed during connect")
            io_loop = asyncio.new_event_loop()
            self._io_loop = io_loop
            self._io_thread = threading.Thread(
                target=io_loop.run_forever, name="photon-io-loop", daemon=True
            )
            self._io_thread.start()

        # Hand the loop the bring-up job and wait for the job to finish, giving it
        # at most connect_timeout.
        job = asyncio.run_coroutine_threadsafe(self._establish_connection(), io_loop)
        try:
            job.result(timeout=self._connect_timeout)
        except FuturesTimeout as exc:
            job.cancel()  # the job may still be running
            self.close()
            raise PhotonConnectionError(f"Timed out connecting to {self._host}:{self._port}") from exc
        except FuturesCancelledError as exc:
            # Only a concurrent close() cancels the job, and that close already cleaned up.
            raise PhotonConnectionError("connector closed during connect") from exc
        except OSError as exc:
            # DNS, TCP, or TLS failure from the dial.
            self.close()
            raise PhotonConnectionError(f"Failed to connect to {self._host}:{self._port}") from exc
        except BaseException:
            # The job failed (it raises PhotonConnectionError) or the wait was interrupted.
            job.cancel()  # no-op if the job already finished
            self.close()
            raise

    async def _establish_connection(self) -> None:
        """Open a secure connection, store it, and start the reader.
        Runs on the loop thread, which owns the connection for its whole life.
        """
        stream, writer = await asyncio.open_connection(
            self._host, self._port, ssl=self._build_ssl_context(),
        )

        with self._state_lock:
            if self._closed:
                # The application closed this connector while we were still setting up the
                # connection. It must stay closed, so instead of installing the new connection we
                # kill it: aborting the transport closes the socket in both directions.
                writer.transport.abort()
                raise PhotonConnectionError("connector closed during connect")
            self._reader = _SocketReader(stream, self._read_size)
            self._reader_task = asyncio.get_running_loop().create_task(self._reader_loop())
            self._writer = writer

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

    def _build_ssl_context(self) -> ssl.SSLContext | None:
        """TLS policy for the connection: TLS 1.2+, hostname check, required verification."""
        if not self._tls:
            return None
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        if not self._verify:
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
        elif self._ca_certs:
            ctx.load_verify_locations(self._ca_certs)
        else:
            ctx.load_default_certs()
        return ctx

    def close(self) -> None:
        """Stop the I/O loop, fail any pending requests, and release the connection. Idempotent."""
        # Phase 1: capture and null the state under the state lock (fast, no I/O).
        with self._state_lock:
            if self._closed:
                return
            self._closed = True
            self._dead = True
            io_loop = self._io_loop
            self._io_loop = None
            thread = self._io_thread
            self._io_thread = None

        # Phase 2: teardown outside the state lock (shutdown/join may block).
        if io_loop is not None:
            try:
                asyncio.run_coroutine_threadsafe(self._teardown_connection(), io_loop).result(timeout=5)
            except Exception:  # best effort; failing the pending requests below still runs
                pass
            # The thread's only job is to run the loop, so stopping the loop ends the thread.
            io_loop.call_soon_threadsafe(io_loop.stop)
            if thread is not None:
                thread.join(timeout=5)
                if not thread.is_alive():
                    io_loop.close()
        self._pending.fail_all(PhotonConnectionLost("connection closed"))

    async def _teardown_connection(self) -> None:
        """Cancel every outstanding task and abort the transport. Runs on the loop thread."""
        current = asyncio.current_task() # automatically resolves the loop running on this thread
        tasks = [task for task in asyncio.all_tasks() if task is not current]
        for task in tasks:
            task.cancel()
        # Wait for the cancellations to land, so the loop stops with no task pending.
        await asyncio.gather(*tasks, return_exceptions=True)
        if self._writer is not None:
            self._writer.transport.abort()
            self._writer = None
        self._reader = None
        self._reader_task = None

    # --- send path -----------------------------------------------------------
    def _send(self, frame: bytes, reply_future: Future) -> None:
        """Atomically register a future and send the frame.

        The loop runs one callback at a time, so nothing interleaves between registration
        and write: queue order equals wire order, which FIFO reply matching depends on.
        A plain callback, not a coroutine — no task objects are created per request.
        """
        writer = self._writer
        if self._dead or writer is None or writer.transport.is_closing():
            reply_future.set_exception(PhotonConnectionLost("connection is closed"))
            return
        try:
            self._pending.register_new_request(reply_future)
        except PhotonError as exc:
            reply_future.set_exception(exc)
            return
        try:
            writer.write(frame)
        except (OSError, TypeError, AttributeError) as exc:
            self._on_connection_lost(PhotonConnectionLost("send failed"), cause=exc)

    def execute(self, command: str, *args: bytes, timeout: float | None = None) -> RespValue:
        """Send one command and block for its reply, returning the raw :data:`RespValue`.
         A ``RespError`` reply is returned as a value, not raised.

        ``timeout`` overrides the reply wait (init commands get a larger budget). There is
        no send-side timeout: a send only copies bytes into the transport buffer, so it
        cannot hang. A truly stuck connection is caught by the reply timeout.
        """
        io_loop = self._io_loop
        if io_loop is None:
            if self._closed:
                raise PhotonConnectionLost("connection is closed")
            raise PhotonError("PhotonConnector used before connect()")
        frame = encode_resp(command, *args)
        reply_future: Future = Future()
        try:
            io_loop.call_soon_threadsafe(self._send, frame, reply_future)
        except RuntimeError as exc:
            # The loop was closed by a concurrent close() before the send could be scheduled.
            raise PhotonConnectionLost("connection closed") from exc
        wait = timeout if timeout is not None else self._request_timeout
        try:
            return reply_future.result(timeout=wait)
        except FuturesTimeout as exc:
            # Intentional: one timeout resets the whole connection. close() fails every other
            # in-flight request too — a late reply would otherwise be FIFO-matched to the
            # wrong caller.
            self.close()
            raise PhotonTimeoutError(f"request timed out after {wait}s") from exc

    # --- reader task -------------------------------------------------------
    async def _reader_loop(self) -> None:
        try:
            while not self._dead:
                # Block until one complete RESP frame has arrived off the socket.
                value = await self._reader.read_frame()
                # Hand it to the oldest waiting caller (FIFO), waking their future.result().
                self._pending.resolve_oldest_request(value)
        except asyncio.CancelledError:
            raise
        except (PhotonConnectionLost, PhotonProtocolError) as exc:
            self._on_connection_lost(exc)
        except OSError as exc:
            self._on_connection_lost(PhotonConnectionLost("socket read failed"), cause=exc)

    def _on_connection_lost(self, exc: PhotonConnectionLost, cause: BaseException | None = None) -> None:
        """Fail every waiting caller and abort the transport. Loop thread only."""
        exc.__cause__ = cause
        self._mark_dead(exc)
        self._writer.transport.abort()

    def _mark_dead(self, exc: BaseException) -> None:
        self._dead = True
        self._pending.fail_all(exc)
