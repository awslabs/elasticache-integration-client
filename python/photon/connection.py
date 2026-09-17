"""Pure-Python connector: one TCP+TLS connection, driven from a single thread.

Every read and write runs on one thread with its own event loop. To send, a caller hands the
loop a function that registers a future for the reply and writes the frame; a reader on the
same loop turns arriving bytes into RESP values and gives each one to the oldest waiting
future.

Replies are correlated two ways. Serialized writes + strict RESP request/response ⇒ response
order equals request order ⇒ FIFO correlation for synchronous replies. When the cache defers a
request (a cache miss requiring an origin fetch), it replies with a request id and delivers the
real response later as a RESP3 push keyed by that id; the reader moves the request's future from
the FIFO into an id-keyed map, so one slow cache miss no longer blocks the pipelined replies
behind it, and a timeout on such a request no longer kills the connection.

_LoopThread owns the thread and the loop, and lasts exactly as long as the connection.
"""

from __future__ import annotations

import asyncio
import logging
import ssl
import threading
from collections import deque
from collections.abc import Callable
from concurrent.futures import Future
from concurrent.futures import TimeoutError as FuturesTimeout

from photon.exceptions import (
    PhotonConnectionLost,
    PhotonConnectionError,
    PhotonError,
    PhotonProtocolError,
    PhotonTimeoutError,
)
from photon.protocol import PUSH_KIND_RESPONSE, parse_response_push
from photon.resp import IncompleteFrame, RespError, RespPush, RespValue, decode_resp, encode_resp

logger = logging.getLogger(__name__)

_DEFAULT_READ_SIZE = 65536
_DEFAULT_CONNECT_TIMEOUT = 1.0  # TCP connect + TLS handshake
_DEFAULT_REQUEST_TIMEOUT = 0.1  # covers the slowest legitimate cache miss (proxy -> DDB -> proxy)
_DEFAULT_CONFIG_TIMEOUT = 2.0   # init-phase commands: AUTH and photon.config
_CLOSE_TIMEOUT = 0.5            # how long close() waits for the I/O thread to stop
OK = "OK"  # the RESP simple string "+OK", decoded
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
    """Thread-safe home of every in-flight request future, in one of two structures.

    The FIFO deque holds futures whose replies are matched by position (reply order equals
    request order), and ``_async_responses`` holds futures whose replies are matched by request
    id (deferred requests). Both live under one lock so moving a future between them is atomic:
    a future is always in exactly one structure, which the timeout path relies on.
    """

    def __init__(self, max_pending: int = _DEFAULT_MAX_PENDING) -> None:
        self._pending_requests_lock = threading.Lock()
        self._queue: deque[Future] = deque()
        self._async_responses: dict[str, Future] = {}
        self._max_pending = max_pending

    def register_new_request(self, future: Future) -> None:
        with self._pending_requests_lock:
            if len(self._queue) + len(self._async_responses) >= self._max_pending:
                raise PhotonError(f"too many in-flight requests (max {self._max_pending})")
            self._queue.append(future)

    def resolve_oldest_request(self, value: RespValue) -> None:
        """Resolve the oldest waiting future with ``value`` (FIFO). No-op if none is waiting.

        A ``RespError`` is a value, not an exception, so it is delivered as the future's result;
        the caller-facing ``parse_*`` functions raise on it.
        """
        with self._pending_requests_lock:
            future = self._queue.popleft() if self._queue else None
        if future and not future.done():
            future.set_result(value)

    def move_head_to_async_responses(self, request_id: str) -> None:
        """Move the oldest future out of the FIFO into ``_async_responses[request_id]``;
        the push for ``request_id`` resolves it. The caller keeps blocking on the same future,
        but its pipeline (FIFO) slot is freed."""
        with self._pending_requests_lock:
            if not self._queue:
                raise PhotonProtocolError("request id reply with no pending request")
            if request_id in self._async_responses:
                # There can never be two live requests with the same id; a duplicate means
                # the stream cannot be trusted (a silent overwrite would orphan a caller).
                raise PhotonProtocolError(f"duplicate request id {request_id!r}")
            self._async_responses[request_id] = self._queue.popleft()

    def resolve_async(self, request_id: str, outcome: RespValue | BaseException) -> bool:
        """Resolve the future stored under ``request_id``. False: unknown id (removed earlier)."""
        with self._pending_requests_lock:
            future = self._async_responses.pop(request_id, None)
        # gone: the reader resolved this push while we were running this function, or
        # fail_if_async resolved the future with an exception
        if not future:
            return False
        if isinstance(outcome, BaseException):
            future.set_exception(outcome)
        else:
            future.set_result(outcome)
        return True

    def fail_if_async(self, future: Future, exc: BaseException) -> bool:
        """Fail a deferred ``future`` with ``exc``. True: it was deferred, or the reader settled it
        first, so nothing more is needed. False: it still holds a FIFO slot."""
        with self._pending_requests_lock:
            request_id = next(
                (rid for rid, tracked in self._async_responses.items() if tracked is future),
                None,
            )
        if request_id and self.resolve_async(request_id, exc):
            return True
        return future.done()  # True if the future was settled while we were timing out
    def fail_all(self, exc: BaseException) -> None:
        """Fail every waiting future — FIFO and async alike — with ``exc`` and clear both."""
        with self._pending_requests_lock:
            pending = list(self._queue)
            pending.extend(self._async_responses.values())
            self._queue.clear()
            self._async_responses.clear()
        for future in pending:
            if not future.done():
                future.set_exception(exc)


class _LoopThread:
    """Runs an async function on its own event loop, in its own daemon thread.

    Other threads can put callbacks on that loop, end the function, and wait for the thread to exit.
    """

    def __init__(self, name: str = "photon-io-thread") -> None:
        self._name = name
        self._thread: threading.Thread | None = None
        self._loop: asyncio.AbstractEventLoop | None = None
        self._main_task: asyncio.Task | None = None

    @property
    def thread(self) -> threading.Thread | None:
        return self._thread

    @property
    def loop(self) -> asyncio.AbstractEventLoop | None:
        return self._loop

    @property
    def main_task(self) -> asyncio.Task | None:
        return self._main_task

    def owns_current_thread(self) -> bool:
        """True when the caller is the loop thread, which may not join or block on itself."""
        return self._thread is threading.current_thread()

    def start_thread(self, target, *args) -> None:
        """Start the daemon thread on ``target(*args)``, which is expected to call :meth:`run`."""
        self._thread = threading.Thread(target=target, args=args, name=self._name, daemon=True)
        self._thread.start()

    def run(self, main) -> BaseException | None:
        """Run ``main`` on a new event loop on the calling thread."""
        try:
            asyncio.run(main)
        except BaseException as exc:
            return exc
        return None

    def accept_calls(self) -> None:
        """Make the running loop reachable from other threads."""
        self._loop = asyncio.get_running_loop()
        self._main_task = asyncio.current_task()

    def refuse_calls(self) -> None:
        """Put the loop out of reach of other threads, before it closes. """
        self._loop = None
        self._main_task = None

    def call(self, fn, *args) -> None:
        """Schedule a function on the loop."""
        loop = self._loop
        if not loop:
            raise RuntimeError("the loop thread is not running")
        loop.call_soon_threadsafe(fn, *args)

    def cancel(self) -> None:
        """Ask ``main`` to end. Safe from any thread, and never joins. Best effort: a loop
        that has already gone has nothing left to cancel.
        """
        main_task = self._main_task
        if not main_task:
            return
        try:
            self.call(main_task.cancel)
        except RuntimeError:
            pass

    def join(self, timeout: float) -> bool:
        """Wait up to ``timeout`` for the thread to exit, then forget it either way. """
        thread, self._thread = self._thread, None
        if not thread:
            return True
        thread.join(timeout=timeout)
        return not thread.is_alive()


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

        #: Called once when this connection ends, for any reason, including a deliberate close().
        #: Assigned by the owner after construction, because a connector factory takes no arguments.
        #: Must not block or re-enter the connector. It runs on whichever thread noticed the end:
        #: the one owning the I/O loop, or the caller of close() when that loop had already stopped.
        self.on_disconnect: Callable[[], None] | None = None
        self._disconnect_notified = False

        self._reader: _SocketReader | None = None
        self._writer: asyncio.StreamWriter | None = None
        self._io_loop_thread = _LoopThread()
        self._pending = _PendingRequests(max_pending)
        self._state_lock = threading.Lock()
        self._closed = False

    @property
    def is_alive(self) -> bool:
        """Whether this connection is still open.

        A connector is single-use: once closed, it never becomes alive again.
        Reconnecting creates a new connector.
        """
        return not self._closed

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
            if self._io_loop_thread.thread:
                raise PhotonConnectionError("connect() already called; PhotonConnector is single-use")
            self._io_loop_thread.start_thread(self._io_thread_main, conn_ready)

        # The I/O thread always sets conn_ready, on success and on failure.
        # So a thread that stops early cannot leave us waiting here.
        try:
            conn_ready.result(timeout=self._connect_timeout)
        except FuturesTimeout as exc:
            self.close()
            raise PhotonConnectionError(f"Timed out connecting to {self._host}:{self._port}") from exc
        except ssl.SSLError as exc:
            # Ahead of OSError, which ssl.SSLError subclasses: a rejected certificate must not
            # report as an unreachable cache.
            self.close()
            raise PhotonConnectionError(f"TLS handshake failed to {self._host}:{self._port}") from exc
        except OSError as exc:
            # DNS or TCP failure from the dial.
            self.close()
            raise PhotonConnectionError(f"Failed to connect to {self._host}:{self._port}") from exc
        except BaseException:
            self.close()
            raise

    def _io_thread_main(self, conn_ready: Future) -> None:
        """The I/O thread's only job. Runs the connection on its own event loop.

        The loop closes when the connection ends, so the loop and this thread stop with it.
        """
        exc = self._io_loop_thread.run(self._serve_connection(conn_ready))
        if not exc:
            return
        # Nobody above can handle this. Give it to a caller still inside connect().
        if not conn_ready.done():      # never set, so connect() is still waiting
            conn_ready.set_exception(exc)
        elif isinstance(exc, (asyncio.CancelledError, PhotonError)):
            logger.debug("photon connector I/O loop ended", exc_info=exc)
        else:
            logger.error("photon connector I/O loop failed", exc_info=exc)

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
            self._io_loop_thread.accept_calls()

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
                return
            conn_ready.set_result(None)   # no value to pass; connect() just needs the wait to end
            await self._reader_loop()
        finally:
            self._release_conn()

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
        if not self._auth_provider:
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

        A success reply is the RESP simple string "+OK" (decoded to "OK"); anything else is
        rejected rather than treated as success. The token is never included in error messages.
        """
        if isinstance(reply, RespError):
            self.close()
            raise PhotonConnectionError(f"IAM AUTH rejected: {reply.code}")
        if reply != OK:
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
        # Phase 1: mark closed, under the state lock.
        with self._state_lock:
            if self._io_loop_thread.owns_current_thread():
                raise RuntimeError("close() cannot be called from the connector's I/O thread")
            self._closed = True

        # Phase 2: teardown outside the state lock (the join may block).
        self._io_loop_thread.cancel()
        if not self._io_loop_thread.join(_CLOSE_TIMEOUT):
            logger.warning("photon connector I/O thread did not stop within %ss", _CLOSE_TIMEOUT)
        # The I/O thread has already done this, unless the join above expired.
        self._pending.fail_all(PhotonConnectionLost("connection closed"))
        # If the join expired the I/O thread never got to notify, and the owner
        # would go on believing this connection is usable.
        self._notify_disconnect()

    def _release_conn(self) -> None:
        """Abort the transport, fail every waiting caller, and forget the connection.

        Runs on the I/O thread while it is already stopping, so it leaves the loop and the
        thread alone. Idempotent.
        """
        with self._state_lock:
            self._closed = True
            self._io_loop_thread.refuse_calls()
            self._reader = None
            writer, self._writer = self._writer, None
        if writer:
            try:
                writer.transport.abort()
            except Exception:
                logger.warning("photon connector transport abort failed", exc_info=True)
        self._pending.fail_all(PhotonConnectionLost("connection closed"))
        self._notify_disconnect()

    def _notify_disconnect(self) -> None:
        """Notify the owner that this connection has ended, at most once.

        Callback failures are logged and suppressed so they cannot terminate the I/O thread.
        """
        with self._state_lock:
            if self._disconnect_notified:
                return
            self._disconnect_notified = True
        callback = self.on_disconnect
        if not callback:
            return
        try:
            callback()
        except Exception:
            logger.warning("photon connector on_disconnect callback failed", exc_info=True)

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
        if not self._io_loop_thread.loop:
            if self._closed:
                raise PhotonConnectionLost("connection is closed")
            raise PhotonError("PhotonConnector used before connect()")
        frame = encode_resp(command, *args)
        reply_future: Future = Future()
        try:
            self._io_loop_thread.call(self._send, frame, reply_future)
        except RuntimeError as exc:
            # The loop was closed by a concurrent close() before the send could be scheduled.
            raise PhotonConnectionLost("connection closed") from exc
        # Not `timeout or ...`: an explicit timeout=0 means "do not wait for a reply", and `or`
        # would quietly turn it into the default instead.
        if timeout is None:
            timeout = self._request_timeout
        try:
            return reply_future.result(timeout=timeout)
        except FuturesTimeout as exc:
            error = PhotonTimeoutError(f"async request timed out after {timeout}s")
            if self._pending.fail_if_async(reply_future, error):
                # Deferred request: it holds no FIFO slot, so abandoning it is harmless and
                # the connection survives..
                return reply_future.result()
            # Still in the FIFO: a late reply would be FIFO-matched to the wrong caller and
            # desynchronize every reply behind it, so the connection must die. close() fails
            # every other in-flight request too.
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
                if isinstance(value, RespPush):
                    # Out-of-band frame: a deferred request's response, matched by id.
                    self._handle_push(value)
                elif isinstance(value, str) and value != OK:
                    # A request id reply: the cache deferred the oldest request. Move its
                    # future out of the FIFO (freeing the pipeline slot); the push resolves it.
                    self._pending.move_head_to_async_responses(value)
                else:
                    # Hand it to the oldest waiting caller (FIFO), waking their future.result().
                    self._pending.resolve_oldest_request(value)
        except PhotonConnectionLost as exc:
            self._mark_closed(exc)
        except PhotonProtocolError as exc:
            # Mirrors botocore, which raises ConnectionClosedError when the response
            # stream violates the protocol (urllib3 ProtocolError): pending requests
            # fail as a connection loss, with the decode failure as the root cause.
            error = PhotonConnectionLost("reply stream corrupt")
            error.__cause__ = exc
            self._mark_closed(error)
        except OSError as exc:
            error = PhotonConnectionLost("socket read failed")
            error.__cause__ = exc
            self._mark_closed(error)

    def _handle_push(self, push: RespPush) -> None:
        """Route a push by its kind: ``photon.response`` resolves the future mapped to the
        request id it carries. Any other kind, or a structurally broken payload, means the
        stream cannot be trusted, so the error propagates and kills the connection."""
        if push.kind == PUSH_KIND_RESPONSE:
            request_id, response = parse_response_push(push)
            if not self._pending.resolve_async(request_id, response):
                logger.warning("push for unknown request id (timed out earlier); dropped")
            return
        raise PhotonProtocolError(f"unknown push kind {push.kind!r}")

    def _mark_closed(self, exc: BaseException) -> None:
        """Refuse further sends and fail every waiting caller. Releases nothing."""
        self._closed = True
        self._pending.fail_all(exc)
