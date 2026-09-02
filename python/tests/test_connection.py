"""Unit tests for photon.connection, organized by the connector contract:

1. Establishment — connect() yields a usable connection or a PhotonConnectionError
2. Authentication — the AUTH exchange
3. Send path — callers on any thread get their own reply, or a Photon exception
4. Read path — bytes become correctly framed, FIFO-routed replies
5. Close — idempotent, prompt, and nothing the connector owns outlives it

Behavioral tests run against a live MockCache (plaintext and TLS). Fault-injection
tests mock at the asyncio seam (open_connection, the stream writer, the event loop).
Framing and routing internals (_SocketReader, _PendingRequests) are tested directly.
"""

import asyncio
import json
import socket as _socket
import threading
import time
from concurrent.futures import Future
from unittest import mock

import pytest

from photon.connection import PhotonConnector, _PendingRequests, _SocketReader
from photon.exceptions import (
    PhotonConnectionLost,
    PhotonConnectionError,
    PhotonError,
    PhotonTimeoutError,
)
from photon.resp import RespError, RespValue
from tests.mock import MockCache, Reply


class TestPhotonConnector:
    """The connector contract (live MockCache, plaintext and TLS) plus internals."""

    # Defaults for tests that never start a cache / connect; helpers assign
    # instance attributes that shadow these. pytest creates a fresh instance
    # per test, so nothing is shared between tests.
    cache: MockCache | None = None
    conn: PhotonConnector | None = None

    def teardown_method(self):
        # ALWAYS runs (pass or fail): a failing assertion cannot leak the cache
        # thread or a connected socket into subsequent tests.
        if self.conn is not None:
            self.conn.close()
        if self.cache is not None:
            self.cache.stop()

    # --- scenario helpers ------------------------------------------------------

    def start_cache(self, **kwargs) -> MockCache:
        """Start a MockCache with this test's configuration."""
        self.cache = MockCache(**kwargs)
        self.cache.start()
        return self.cache

    def connect(self, *, tls: bool = False, **kwargs) -> None:
        """Create + connect ``self.conn`` (default cache, and plaintext unless ``tls=True``)."""
        if self.cache is None:
            self.start_cache()
        self.conn = PhotonConnector(self.cache.host, self.cache.port, tls=tls, **kwargs)
        self.conn.connect()

    def connect_and_handshake(self, **kwargs) -> None:
        """connect() plus the photon.config handshake — ready to send requests."""
        self.connect(**kwargs)
        self.conn.execute("photon.config", b"{}")

    def send_request(self, body: bytes = b"body") -> RespValue:
        """Send a canonical photon.request ([method, url, headers, body, metadata]) with the given."""
        args = [b"POST", b"https://x/", b"", body, b"service=s&op=o"]
        return self.conn.execute("photon.request", *args)

    def wrap_writer(self) -> mock.Mock:
        """Replace the connector's stream writer with a delegating mock and return it.

        The loop thread reads self._writer per send, so a swap from the test thread
        takes effect on the next execute().
        """
        self.conn._writer = mock.Mock(wraps=self.conn._writer)
        return self.conn._writer

    # ======================================================================== #
    # 1. Establishment — getting a usable connection (TCP, TLS, misuse)
    # ======================================================================== #

    def test_connect_and_handshake(self):
        """connect() yields a working connection: the photon.config handshake round-trips
        the mock's resolved config (as a raw JSON bulk string — parsing it is
        protocol.py's contract)."""
        self.connect()
        raw = self.conn.execute("photon.config", b'{"k":"v"}')
        cfg = json.loads(raw)
        assert cfg["version"] == "v1"
        assert cfg["refresh_interval"] == 60

    def test_connect_to_nothing_raises(self):
        """Connecting to a port with nothing listening raises PhotonConnectionError."""
        # Reserve a port then free it so nothing is listening -> TCP connect fails.
        probe = _socket.socket(_socket.AF_INET, _socket.SOCK_STREAM)
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
        probe.close()
        conn = PhotonConnector("127.0.0.1", port, connect_timeout=1.0)
        with pytest.raises(PhotonConnectionError):
            conn.connect()

    def test_tls_trusted_cert_connects(self):
        """Over real verifying TLS (trusting the mock's self-signed cert via ca_certs), the
        handshake and request path work. (Response content details are test_request_round_trip's
        job — this test only proves the paths function over TLS.)"""
        self.start_cache(tls=True)
        self.connect_and_handshake(tls=True, ca_certs=self.cache.cert_path)
        assert self.send_request()[0] == 200

    def test_tls_untrusted_cert_refused(self):
        """With the default CA store (which does not trust the mock's self-signed cert),
        the handshake must fail — never connect insecurely."""
        self.start_cache(tls=True)
        self.conn = PhotonConnector(self.cache.host, self.cache.port)  # tls=True, no ca_certs
        with pytest.raises(PhotonConnectionError):
            self.conn.connect()

    def test_tls_verify_false_connects_untrusted(self):
        """verify=False connects to a cert the default CA store does not trust, and the
        request path works. The opt-out for self-signed test rigs."""
        self.start_cache(tls=True)
        self.connect_and_handshake(tls=True, verify=False)  # no ca_certs on purpose
        assert self.send_request()[0] == 200

    def test_send_before_connect_raises(self):
        """Sending before connect() raises PhotonError (no connection yet — distinct from
        PhotonConnectionLost, which means a connection existed and died)."""
        self.start_cache()
        conn = PhotonConnector(self.cache.host, self.cache.port, tls=False)
        with pytest.raises(PhotonError):
            conn.execute("photon.config", b"{}")

    def test_close_during_connect_aborts_cleanly(self):
        """close() while connect() is still opening the connection: connect() must raise
        PhotonConnectionError and the connector must end fully closed — no half-open
        connection, no leaked I/O loop thread."""
        conn = PhotonConnector("h", 1, tls=False)
        open_entered = threading.Event()
        release_open = threading.Event()

        async def blocked_open(*args, **kwargs):
            open_entered.set()
            # Block off-loop so close()'s task cancellation can land at this await.
            await asyncio.get_running_loop().run_in_executor(None, release_open.wait, 2)
            return (mock.Mock(), mock.Mock())

        outcome: list = []

        def run_connect():
            try:
                conn.connect()
            except BaseException as exc:  # noqa: BLE001 — the assertion below names the type
                outcome.append(exc)

        with mock.patch("photon.connection.asyncio.open_connection", blocked_open):
            t = threading.Thread(target=run_connect)
            t.start()
            assert open_entered.wait(2)
            conn.close()            # races connect(): nothing is installed yet
            release_open.set()      # free the executor thread promptly
            t.join(3)

        assert not t.is_alive()
        assert len(outcome) == 1    # connect() raised exactly once
        assert isinstance(outcome[0], PhotonConnectionError), outcome[0]
        assert conn._writer is None
        assert not any(th.name == "photon-io-loop" for th in threading.enumerate())

    def test_connect_install_refused_after_close(self):
        """The install-check second line of defense: if close() lands after the connection
        opens but before install, the fresh connection is aborted, never installed.
        Internals: _establish_connection driven directly with _closed pre-set."""
        conn = PhotonConnector("h", 1, tls=False)
        conn._closed = True
        writer = mock.Mock()

        async def fake_open(*args, **kwargs):
            return (mock.Mock(), writer)

        with mock.patch("photon.connection.asyncio.open_connection", fake_open):
            with pytest.raises(PhotonConnectionError):
                asyncio.run(conn._establish_connection())
        writer.transport.abort.assert_called_once()
        assert conn._writer is None

    # ======================================================================== #
    # 2. Authentication — the AUTH exchange
    # ======================================================================== #

    def test_auth_silence_times_out_and_kills_connection(self):
        """A server that goes silent on AUTH trips the request timeout: authenticate()
        raises PhotonConnectionError and the connection is marked dead."""
        self.start_cache(failure="hang", failure_command="auth")
        self.connect(
            request_timeout=0.3,
            auth_provider=lambda: ("test-user", "test-token"),
        )
        with pytest.raises(PhotonConnectionError, match="AUTH timed out"):
            self.conn.authenticate()
        assert self.conn._dead is True

    def test_auth_accepted(self):
        """The RESP +OK reply (decoded to b"OK") is accepted.
        Internals: _verify_auth_reply is pure reply validation, tested directly."""
        conn = PhotonConnector("host", 6379, tls=False)
        conn._verify_auth_reply(b"OK")  # must not raise
        assert conn._dead is False

    def test_auth_rejected_kills_connection(self):
        """An -ERR AUTH reply raises PhotonConnectionError AND marks the connection dead:
        an unauthenticated connection has no valid next use. The token never appears in
        the message. Internals: _verify_auth_reply tested directly."""
        conn = PhotonConnector("host", 6379, tls=False)
        with pytest.raises(PhotonConnectionError) as excinfo:
            conn._verify_auth_reply(RespError("invalid-iam-credentials", "nope"))
        assert "invalid-iam-credentials" in str(excinfo.value)
        assert conn._dead is True

    def test_auth_unexpected_reply_kills_connection(self):
        """A non-error, non-OK AUTH reply is rejected and marks the connection dead.
        Internals: _verify_auth_reply tested directly."""
        conn = PhotonConnector("host", 6379, tls=False)
        with pytest.raises(PhotonConnectionError):
            conn._verify_auth_reply(123)
        assert conn._dead is True

    # ======================================================================== #
    # 3. Send path — what a caller experiences calling execute
    # ======================================================================== #

    def test_request_round_trip(self):
        """A photon.request round-trips: the caller gets the cache's [status, headers, body]
        array intact (turning it into a PhotonResponse is protocol.py's contract)."""
        self.start_cache(
            response_status=200,
            response_headers="content-type:application/json",
            response_body=b'{"ok":1}',
        )
        self.connect_and_handshake()
        value = self.send_request(body=b'{"ok":1}')
        assert value == [200, b"content-type:application/json", b'{"ok":1}']

    def test_concurrent_requests_get_own_replies(self):
        """8 threads fire requests simultaneously (barrier-synced); the mock reflects each
        request body; each thread must get its own token back — no response cross-talk over
        the single shared connection. This is the file's central test."""
        self.start_cache(response_fn=lambda req: Reply.ok(body=req.body))
        self.connect_and_handshake()

        n = 8
        barrier = threading.Barrier(n)
        results: dict[int, bytes] = {}
        errors: list = []

        def worker(i: int) -> None:
            token = f"token-{i}".encode()
            try:
                barrier.wait(timeout=5)
                value = self.send_request(body=token)
                results[i] = value[2]  # body element of [status, headers, body]
            except Exception as exc:  # noqa: BLE001
                errors.append((i, exc))

        threads = [threading.Thread(target=worker, args=(i,)) for i in range(n)]
        for t in threads:
            t.start()
        for t in threads:
            t.join(10)

        assert not errors, errors
        for i in range(n):
            assert results[i] == f"token-{i}".encode()

    def test_error_reply_returned_as_value(self):
        """A -ERR reply surfaces from execute() as a RespError VALUE, not an exception —
        raising on it is the caller-facing parse_* functions' job (protocol.py)."""
        self.start_cache(response=Reply.error("ERR", "mock error reply"))
        self.connect_and_handshake()
        value = self.send_request()
        assert isinstance(value, RespError)
        assert value.code == "ERR"

    def test_no_reply_times_out_and_kills_connection(self):
        """A hung request trips the per-request timeout AND tears the connection down —
        timeout must be fatal, because a late reply on a live connection would be
        FIFO-matched to the WRONG caller."""
        self.start_cache(failure="hang")
        self.connect_and_handshake(request_timeout=0.3)
        with pytest.raises(PhotonTimeoutError):
            self.send_request()
        assert self.conn._dead is True

    def test_send_on_dead_connection_fails_fast(self):
        """Once dead, a new send fails PhotonConnectionLost immediately — it must not sit out
        the full request timeout."""
        self.start_cache(failure="drop")
        # Generous request_timeout so a fast failure is distinguishable from a timeout wait.
        self.connect_and_handshake(request_timeout=5.0)
        with pytest.raises(PhotonConnectionLost):
            self.send_request()  # server drops -> dead
        start = time.monotonic()
        with pytest.raises(PhotonConnectionLost):
            self.send_request()
        assert time.monotonic() - start < 1.0  # dead-check fired, not the 5s timeout

    def test_wedged_loop_does_not_block_callers_forever(self):
        """A wedged I/O loop must not block callers forever: with the send parked behind
        the wedge, the caller's reply timeout still fires. (There is no send-side timeout
        by design — a send only copies bytes into the transport buffer.)"""
        self.connect_and_handshake(request_timeout=0.2)
        self.conn._io_loop.call_soon_threadsafe(time.sleep, 1)  # wedge the loop
        with pytest.raises(PhotonTimeoutError):
            self.send_request()

    @pytest.mark.parametrize(
        "leak",
        [OSError("broken pipe"), TypeError("closing transport"), AttributeError("closing transport")],
        ids=["oserror", "typeerror", "attributeerror"],
    )
    def test_write_failure_kills_connection(self, leak):
        """A write that fails — including the wrong-typed exceptions a closing transport can
        leak on 3.12 / early 3.13 (CPython gh-136234) — fails the caller with
        PhotonConnectionLost and kills the connection; the next send fails fast."""
        self.connect_and_handshake(request_timeout=5.0)
        writer = self.wrap_writer()
        writer.write = mock.Mock(side_effect=leak)
        with pytest.raises(PhotonConnectionLost):
            self.send_request()
        writer.write.assert_called_once()
        assert self.conn._dead is True
        start = time.monotonic()
        with pytest.raises(PhotonConnectionLost):
            self.send_request()  # dead-check fires, not the 5s timeout
        assert time.monotonic() - start < 1.0

    # ======================================================================== #
    # 4. Read path — bytes into framed, FIFO-routed replies
    # ======================================================================== #

    def test_large_reply_reassembled(self):
        """A ~1MB response body — far beyond the 64KB read size — arrives byte-exact
        through the reader's reassembly loop."""
        big_body = b'{"Items":[' + b'"' + b"x" * (1024 * 1024) + b'"]}'
        self.start_cache(response_body=big_body)
        self.connect_and_handshake()
        value = self.send_request()
        assert value[0] == 200
        assert value[2] == big_body

    def test_reply_before_disconnect_is_delivered(self):
        """A cache that replies and immediately closes: the waiting caller must get the
        reply (TCP delivers those bytes before EOF), and only the NEXT request observes
        the dead connection."""
        self.start_cache(failure="drop", failure_after_reply=True)
        self.connect_and_handshake()
        value = self.send_request()         # reply arrives despite the FIN right behind it
        assert value[0] == 200
        with pytest.raises(PhotonConnectionLost):
            self.send_request()             # death detected on the next use

    def test_quiet_connection_stays_alive(self):
        """An idle connection several request_timeouts long is normal, not a failure:
        afterwards the connection is alive and a request still round-trips."""
        self.connect_and_handshake(request_timeout=0.2)
        time.sleep(0.5)  # idle spell
        assert self.conn._dead is False
        self.send_request()  # still works after the quiet spell

    def test_reader_reassembles_split_frames(self):
        """A frame split across reads is reassembled; trailing bytes of a read are
        retained for the next frame. Internals: _SocketReader on an in-memory stream."""

        async def scenario() -> list:
            stream = asyncio.StreamReader()
            reader = _SocketReader(stream)
            stream.feed_data(b"$5\r\nhel")           # first frame, split mid-payload
            stream.feed_data(b"lo\r\n+OK\r\n")       # rest of it + a whole second frame
            return [await reader.read_frame(), await reader.read_frame()]

        assert asyncio.run(scenario()) == [b"hello", b"OK"]

    def test_reader_eof_raises_connection_lost(self):
        """Clean EOF mid-frame surfaces as PhotonConnectionLost, not a decode error.
        Internals: _SocketReader on an in-memory stream."""

        async def scenario() -> None:
            stream = asyncio.StreamReader()
            reader = _SocketReader(stream)
            stream.feed_data(b"$5\r\nhe")  # incomplete frame
            stream.feed_eof()
            await reader.read_frame()

        with pytest.raises(PhotonConnectionLost):
            asyncio.run(scenario())

    def test_replies_routed_fifo(self):
        """_PendingRequests resolves futures strictly in registration (FIFO) order, and a
        RespError is delivered as a future's RESULT (a value), not raised — the caller-facing
        parse_* functions raise on it later. Internals: _PendingRequests."""
        pending = _PendingRequests()
        f1, f2, f3 = Future(), Future(), Future()
        pending.register_new_request(f1)
        pending.register_new_request(f2)
        pending.register_new_request(f3)
        err = RespError("ERR", "boom")
        pending.resolve_oldest_request(b"v1")
        pending.resolve_oldest_request(err)
        pending.resolve_oldest_request(b"v3")
        assert f1.result(timeout=1) == b"v1"
        assert f2.result(timeout=1) is err  # delivered as a value, not raised
        assert f3.result(timeout=1) == b"v3"

    def test_max_pending_rejects_when_full(self):
        """At the cap, registering fails fast with PhotonError and the queue is unchanged;
        resolving a request frees a slot. Internals: _PendingRequests."""
        pending = _PendingRequests(max_pending=2)
        pending.register_new_request(Future())
        pending.register_new_request(Future())
        with pytest.raises(PhotonError):
            pending.register_new_request(Future())
        pending.resolve_oldest_request(b"v1")   # frees one slot
        pending.register_new_request(Future())  # fits again

    def test_max_pending_propagates_from_connector(self):
        """The connector passes its max_pending through to the pending queue."""
        conn = PhotonConnector("h", 1, tls=False, max_pending=7)
        assert conn._pending._max_pending == 7

    # ======================================================================== #
    # 5. Close — teardown
    # ======================================================================== #

    def test_close_twice_is_safe(self):
        """Calling close() twice on a connected connector does not raise."""
        self.connect()
        self.conn.close()
        self.conn.close()

    def test_repeated_connect_close_leaks_nothing(self):
        """Ten connect/use/close cycles leave no I/O loop threads behind: everything a
        connector owns (loop, thread, connection) dies with its close()."""
        self.start_cache()
        for _ in range(10):
            conn = PhotonConnector(self.cache.host, self.cache.port, tls=False)
            conn.connect()
            conn.execute("photon.config", b"{}")
            conn.close()
        assert not any(t.name == "photon-io-loop" for t in threading.enumerate())

    def test_close_stops_and_forgets_the_io_loop(self):
        """After close(), the I/O loop thread has terminated and all connection state is
        nulled: nothing owned by the connector outlives close()."""
        self.connect()
        thread = self.conn._io_thread
        assert thread.is_alive()
        self.conn.close()
        assert not thread.is_alive()          # join happened, the thread is gone
        assert self.conn._io_thread is None
        assert self.conn._io_loop is None
        assert self.conn._writer is None

    def test_execute_after_close_raises_connection_lost(self):
        """execute() on a closed connector reports PhotonConnectionLost, not the misleading
        "used before connect()" error (close() nulls the loop)."""
        self.connect()
        self.conn.close()
        with pytest.raises(PhotonConnectionLost):
            self.conn.execute("photon.request", b"x")

    def test_close_fails_pending_requests(self):
        """close() while a caller is blocked waiting for a reply: the caller gets
        PhotonConnectionLost promptly — it must not hang until its request timeout."""
        self.start_cache(failure="hang")
        self.connect_and_handshake(request_timeout=10.0)

        outcome: list = []

        def blocked_caller():
            try:
                self.send_request()
                outcome.append("returned")
            except PhotonConnectionLost:
                outcome.append("connection-lost")
            except Exception as exc:  # noqa: BLE001
                outcome.append(exc)

        t = threading.Thread(target=blocked_caller)
        t.start()
        deadline = time.monotonic() + 2
        while len(self.conn._pending._queue) == 0:   # wait until the caller is registered + waiting
            assert time.monotonic() < deadline, "caller never registered its request"
            time.sleep(0.01)
        self.conn.close()
        t.join(timeout=2)        # must unblock well before the 10s request timeout

        assert not t.is_alive(), "caller still blocked after close()"
        assert outcome == ["connection-lost"]

    def test_close_survives_teardown_errors(self):
        """An OSError from transport.abort() during teardown (e.g. peer already gone) must
        not escape close(); teardown still completes and pending requests are still failed.
        Internals: a delegating writer mock forces the error."""
        self.connect()
        fut: Future = Future()
        self.conn._pending.register_new_request(fut)
        writer = self.wrap_writer()
        writer.transport.abort.side_effect = OSError("already closed")

        self.conn.close()  # must not raise

        with pytest.raises(PhotonConnectionLost):
            fut.result(timeout=1)  # pending caller still failed
        self.conn.close()  # still idempotent
