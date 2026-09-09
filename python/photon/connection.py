"""Pure-Python connector: the TCP+TLS transport plus its two concurrency primitives.

:class:`PhotonConnector` owns a single TCP+TLS connection. All I/O runs on a private asyncio
event loop in one daemon thread: callers hand the loop a plain callback that registers a reply
``Future`` and writes the frame, and a reader turns the byte stream into framed ``RespValue``s
(:class:`_SocketReader`) and resolves those futures in FIFO order (:class:`_PendingRequests`).
One callback at a time on one loop thread ⇒ queue order equals wire order ⇒ FIFO correlation
is correct.

The loop lives as long as the connection, and no longer. The I/O thread runs one thing:
:meth:`_serve_connection`. That call opens the connection, reads it, then releases it. When
the connection ends, for any reason, the call returns. ``asyncio.run`` then closes the loop
and the thread stops.
"""

from __future__ import annotations

import asyncio
import logging
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
from photon.resp import IncompleteFrame, RespError, RespValue, decode_resp, encode_resp

_DEFAULT_READ_SIZE = 65536
_DEFAULT_CONNECT_TIMEOUT = 1.0  # TCP connect + TLS handshake
_DEFAULT_REQUEST_TIMEOUT = 0.1  # covers the slowest legitimate cache miss (proxy -> DDB -> proxy)
_DEFAULT_CONFIG_TIMEOUT = 2.0   # init-phase commands: AUTH and photon.config
_CLOSE_TIMEOUT = 0.5            # how long close() waits for the I/O thread to stop
_AUTH_OK = b"OK"  # the RESP simple string "+OK", decoded
_DEFAULT_MAX_PENDING = 100

logger = logging.getLogger(__name__)


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

        self._reader: _SocketReader | None = None
        self._writer: asyncio.StreamWriter | None = None
        self._io_loop: asyncio.AbstractEventLoop | None = None
        self._io_thread: threading.Thread | None = None
        self._io_task: asyncio.Task | None = None   # close() needs this to end the connection
        self._pending = _PendingRequests(max_pending)
        self._state_lock = threading.Lock()
        self._closed = False

    # --- lifecycle -----------------------------------------------------------
    def connect(self) -> None:
        """Open the TCP+TLS connection and start the I/O loop thread.
        Raises :class:`~photon.exceptions.PhotonConnectionError` on any failure.
        """
        # The I/O thread opens the socket, so it has to report the outcome back here.
        # Success sets it to None. Failure sets it to the error that stopped it.
        conn_ready: Future = Future()
        # Start the I/O thread. One atomic step with the closed-check, so a concurrent
        # close() either refuses us here or finds the thread and dismisses it.
        with self._state_lock:
            if self._closed:
                raise PhotonConnectionError("connector closed during connect")
            if self._io_thread is not None:
                raise PhotonConnectionError("connect() already called; PhotonConnector is single-use")
            self._io_thread = threading.Thread(
                target=self._io_thread_main, args=(conn_ready,), name="photon-io-thread", daemon=True
            )
            self._io_thread.start()

        # The I/O thread always sets conn_ready, on success and on failure.
        # So a thread that stops early cannot leave us waiting here.
        try:
            conn_ready.result(timeout=self._connect_timeout)
        except FuturesTimeout as exc:
            self.close()
            raise PhotonConnectionError(f"Timed out connecting to {self._host}:{self._port}") from exc
        except OSError as exc:
            # DNS, TCP, or TLS failure from the dial.
            self.close()
            raise PhotonConnectionError(f"Failed to connect to {self._host}:{self._port}") from exc
        except BaseException:
            self.close()
            raise

    def _io_thread_main(self, conn_ready: Future) -> None:
        """The I/O thread's only job. Runs the connection on its own event loop.

        ``asyncio.run`` closes that loop when the call returns, so the loop and this
        thread stop when the connection does.
        """
        try:
            asyncio.run(self._serve_connection(conn_ready))
        except BaseException as exc:
            # Nobody above can handle this. Give it to a caller still inside connect().
            if not conn_ready.done():      # never set, so connect() is still waiting
                conn_ready.set_exception(exc)
            elif isinstance(exc, (asyncio.CancelledError, PhotonError)):
                logger.debug("photon connector I/O loop ended", exc_info=True)
            else:
                # Our own bug: callers were told the connection closed, which says nothing
                # about the real cause. This log is the only record of it.
                logger.error("photon connector I/O loop failed", exc_info=True)

    async def _serve_connection(self, conn_ready: Future) -> None:
        """Open the connection, read replies until it ends, then release it. Runs on the I/O thread.

        The ``finally`` below is the only place the connector cleans up. It runs when the
        connection drops, when close() cancels us, and when something unexpected breaks.
        """
        with self._state_lock:
            # close() got here first and found no loop to cancel, so do not start one.
            if self._closed:
                conn_ready.set_exception(PhotonConnectionError("connector closed during connect"))
                return
            self._io_loop = asyncio.get_running_loop()
            self._io_task = asyncio.current_task()

        try:
            try:
                await self._establish_connection()
            except asyncio.CancelledError:
                # close() is the only thing that cancels us. Report it as a closed
                # connector: asyncio raises a different CancelledError class than
                # concurrent.futures, so connect() would not recognise the original.
                conn_ready.set_exception(
                    PhotonConnectionError("connector closed during connect")
                )
                raise
            except BaseException as exc:
                # connect() turns the type into a message, so pass it on as it is.
                conn_ready.set_exception(exc)
                raise
            conn_ready.set_result(None)   # no value to pass; connect() just needs the wait to end
            await self._reader_loop()
        finally:
            self._release()

    async def _establish_connection(self) -> None:
        """Open a secure connection and install it. Runs on the loop thread, which owns
        the connection for its whole life.
        """
        stream_reader, writer = await asyncio.open_connection(
            self._host, self._port, ssl=self._build_ssl_context(),
        )

        with self._state_lock:
            if self._closed:
                # The application closed this connector while we were still setting up the
                # connection. It must stay closed, so instead of installing the new connection we
                # kill it: aborting the transport closes the socket in both directions.
                writer.transport.abort()
                raise PhotonConnectionError("connector closed during connect")
            self._reader = _SocketReader(stream_reader, self._read_size)
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
            self.close()
            raise PhotonConnectionError("IAM AUTH timed out — connection closed")
        self._verify_auth_reply(reply)

    def _verify_auth_reply(self, reply: RespValue) -> None:
        """Validate an AUTH reply. Raises PhotonConnectionError on rejection or unexpected shape.

        A success reply is the RESP simple string "+OK" (decoded to b"OK"); anything else is
        rejected rather than treated as success. The token is never included in error messages.
        """
        if isinstance(reply, RespError):
            self.close()
            raise PhotonConnectionError(f"IAM AUTH rejected: {reply.code}")
        if reply != _AUTH_OK:
            self.close()
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
        """Release the connection, stop the I/O loop, and fail pending requests. Idempotent.

        Any thread may call this except the I/O thread, which cleans up on its own.
        """
        # Phase 1: mark closed and capture the handles, under the state lock.
        with self._state_lock:
            if self._io_thread is threading.current_thread():
                raise RuntimeError("close() cannot be called from the connector's I/O thread")
            self._closed = True
            io_loop = self._io_loop
            task = self._io_task
            thread = self._io_thread
            self._io_thread = None

        # Phase 2: teardown outside the state lock (shutdown/join may block).
        if io_loop is not None and task is not None:
            try:
                io_loop.call_soon_threadsafe(task.cancel)
            except RuntimeError:
                pass  # the loop is already closed: teardown has run or is running
        if thread is not None:
            thread.join(timeout=_CLOSE_TIMEOUT)
            if thread.is_alive():
                logger.debug("photon connector I/O thread did not stop within %ss", _CLOSE_TIMEOUT)
        self._pending.fail_all(PhotonConnectionLost("connection closed"))

    def _release(self) -> None:
        """Abort the transport, fail every waiting caller, and forget the connection.

        Runs on the I/O thread while it is already stopping, so it leaves the loop and the
        thread alone. Idempotent.
        """
        with self._state_lock:
            self._closed = True
            self._io_loop = None
            self._io_task = None
            self._reader = None
            writer, self._writer = self._writer, None
        if writer is not None:
            try:
                writer.transport.abort()
            except Exception:
                # abort() can fail if the peer is already gone. Fail the callers anyway.
                logger.debug("photon connector transport abort failed", exc_info=True)
        self._pending.fail_all(PhotonConnectionLost("connection closed"))

    # --- send path -----------------------------------------------------------
    def _send(self, frame: bytes, reply_future: Future) -> None:
        """Atomically register a future and send the frame.

        The loop runs one callback at a time, so nothing interleaves between registration
        and write: queue order equals wire order, which FIFO reply matching depends on.
        A plain callback, not an async function: no task is created per request.
        """
        if self._closed or self._writer is None or self._writer.transport.is_closing():
            reply_future.set_exception(PhotonConnectionLost("connection is closed"))
            return
        try:
            self._pending.register_new_request(reply_future)
        except PhotonError as exc:
            reply_future.set_exception(exc)
            return
        try:
            self._writer.write(frame)
        except (OSError, TypeError, AttributeError) as exc:
            error = PhotonConnectionLost("send failed")
            error.__cause__ = exc
            self._mark_closed(error)
            # The abort gives the reader an EOF, which ends the connection as usual.
            self._writer.transport.abort()

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
        timeout = timeout if timeout is not None else self._request_timeout
        try:
            return reply_future.result(timeout=timeout)
        except FuturesTimeout as exc:
            # Intentional: one timeout resets the whole connection. close() fails every other
            # in-flight request too — a late reply would otherwise be FIFO-matched to the
            # wrong caller.
            self.close()
            raise PhotonTimeoutError(f"request timed out after {timeout}s") from exc

    # --- reader ---------------------------------------------------------------
    async def _reader_loop(self) -> None:
        """Frame replies and resolve callers in FIFO order until the connection ends.

        Fails the waiting callers, then returns. :meth:`_serve_connection` releases the
        connection, so anything this misses still gets cleaned up.
        """
        try:
            while not self._closed:
                # Block until one complete RESP frame has arrived off the socket.
                value = await self._reader.read_frame()
                # Hand it to the oldest waiting caller (FIFO), waking their future.result().
                self._pending.resolve_oldest_request(value)
        except (PhotonConnectionLost, PhotonProtocolError) as exc:
            self._mark_closed(exc)
        except OSError as exc:
            error = PhotonConnectionLost("socket read failed")
            error.__cause__ = exc
            self._mark_closed(error)

    def _mark_closed(self, exc: BaseException) -> None:
        """Refuse further sends and fail every waiting caller. Releases nothing."""
        self._closed = True
        self._pending.fail_all(exc)
