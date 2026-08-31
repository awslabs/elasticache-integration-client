"""Unit tests for photon.connection, organized by the five paths of the connection contract:"""

import json
import socket as _socket
import threading
import time
from unittest import mock

import pytest

from photon.connection import (
    PhotonConnection,
    _PendingRequests,
    _SocketReader,
)
from photon.exceptions import (
    PhotonConnectionLostError,
    PhotonConnectionError,
    PhotonError,
    PhotonProtocolError,
    PhotonTimeoutError,
)
from photon.resp import RespError, RespValue
from tests.mock import MockCache, MockSocket, Reply


class TestPhotonConnection:
    """The connection contract (live MockCache, plaintext and TLS) plus internals."""

    # Defaults for tests that never start a cache / connect; helpers assign
    # instance attributes that shadow these. pytest creates a fresh instance
    # per test, so nothing is shared between tests.
    cache: MockCache | None = None
    conn: PhotonConnection | None = None

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
        self.conn = PhotonConnection(self.cache.host, self.cache.port, tls=tls, **kwargs)
        self.conn.connect()

    def connect_and_handshake(self, **kwargs) -> None:
        """connect() plus the photon.config handshake — ready to send requests."""
        self.connect(**kwargs)
        self.conn.execute("photon.config", b"{}")

    def send_request(self, body: bytes = b"body") -> RespValue:
        """Send a canonical photon.request ([method, url, headers, body, metadata]) with the given."""
        args = [b"POST", b"https://x/", b"", body, b"service=s&op=o"]
        return self.conn.execute("photon.request", *args)

    # ======================================================================== #
    # 1. Establishment — getting a usable connection (TCP, TLS, misuse)
    # ======================================================================== #

    def test_connect_and_handshake(self):
        """connect() yields a tuned, working connection: TCP_NODELAY is set and the
        photon.config handshake round-trips the mock's resolved config (as a raw JSON
        bulk string — parsing it is protocol.py's contract)."""
        self.connect()
        assert self.conn._sock.getsockopt(_socket.IPPROTO_TCP, _socket.TCP_NODELAY) != 0
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
        conn = PhotonConnection("127.0.0.1", port, connect_timeout=1.0)
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
        self.conn = PhotonConnection(self.cache.host, self.cache.port)  # tls=True, no ca_certs
        with pytest.raises(PhotonConnectionError):
            self.conn.connect()

    def test_tls_verify_false_connects_untrusted(self):
        """verify=False connects to a cert the default CA store does not trust, and the
        request path works. The opt-out for self-signed test rigs."""
        self.start_cache(tls=True)
        self.connect_and_handshake(tls=True, verify=False)  # no ca_certs on purpose
        assert self.send_request()[0] == 200

    def test_send_before_connect_raises(self):
        """Sending before connect() raises PhotonError (no socket yet — distinct from
        PhotonConnectionLostError, which means a connection existed and died)."""
        self.start_cache()
        conn = PhotonConnection(self.cache.host, self.cache.port, tls=False)
        with pytest.raises(PhotonError):
            conn.execute("photon.config", b"{}")

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
        conn = PhotonConnection("host", 6379, tls=False)
        conn._verify_auth_reply(b"OK")  # must not raise
        assert conn._dead is False

    def test_auth_rejected_kills_connection(self):
        """An -ERR AUTH reply raises PhotonConnectionError AND marks the connection dead:
        an unauthenticated connection has no valid next use. The token never appears in
        the message. Internals: _verify_auth_reply tested directly."""
        conn = PhotonConnection("host", 6379, tls=False)
        with pytest.raises(PhotonConnectionError) as excinfo:
            conn._verify_auth_reply(RespError("invalid-iam-credentials", "nope"))
        assert "invalid-iam-credentials" in str(excinfo.value)
        assert conn._dead is True

    def test_auth_unexpected_reply_kills_connection(self):
        """A non-error, non-OK AUTH reply is rejected and marks the connection dead.
        Internals: _verify_auth_reply tested directly."""
        conn = PhotonConnection("host", 6379, tls=False)
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
        the single shared socket. This is the file's central test."""
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
        """Once dead, a new send fails PhotonConnectionLostError immediately — it must not sit out
        the full request timeout."""
        self.start_cache(failure="drop")
        # Generous request_timeout so a fast failure is distinguishable from a timeout wait.
        self.connect_and_handshake(request_timeout=5.0)
        with pytest.raises(PhotonConnectionLostError):
            self.send_request()  # server drops -> dead
        start = time.monotonic()
        with pytest.raises(PhotonConnectionLostError):
            self.send_request()
        assert time.monotonic() - start < 1.0  # dead-check fired, not the 5s timeout

    def test_send_lock_acquire_is_bounded(self):
        """A sender that cannot get the send lock within request_timeout raises
        PhotonTimeoutError instead of waiting forever; the pending queue is unchanged."""
        self.connect_and_handshake(request_timeout=0.2)
        depth_before = len(self.conn._pending._queue)
        self.conn._send_lock.acquire()  # simulate a sender stuck holding the lock
        try:
            with pytest.raises(PhotonTimeoutError):
                self.send_request()
            assert len(self.conn._pending._queue) == depth_before
        finally:
            self.conn._send_lock.release()

    def test_send_timeout_is_fatal(self):
        """A sendall that times out (network dead, TCP buffer full) raises
        PhotonTimeoutError and kills the connection: the frame may be half-written."""
        self.connect_and_handshake(request_timeout=0.3)
        # A real socket's sendall is read-only; wrap the socket in a delegating mock instead.
        self.conn._sock = mock.Mock(wraps=self.conn._sock)
        self.conn._sock.sendall.side_effect = TimeoutError
        with pytest.raises(PhotonTimeoutError):
            self.send_request()
        assert self.conn._dead is True

    def test_send_oserror_closes_the_connection(self):
        """A sendall that fails with OSError tears the connection down the same way a send
        timeout does: dead, socket released, reader gone (not just marked dead)."""
        self.connect_and_handshake(request_timeout=0.3)
        self.conn._sock = mock.Mock(wraps=self.conn._sock)
        self.conn._sock.sendall.side_effect = OSError("broken pipe")
        with pytest.raises(PhotonConnectionLostError):
            self.send_request()
        assert self.conn._dead is True
        assert self.conn._closed is True       # full teardown ran, not just _mark_dead
        assert self.conn._sock is None         # fd released
        assert self.conn._reader_thread is None

    def test_quiet_connection_stays_alive(self):
        """recv timeouts on an idle connection are normal: after several reader wake-ups
        the connection is alive and a request still round-trips."""
        self.connect_and_handshake(request_timeout=0.2)
        time.sleep(0.7)  # ~3 reader wake-ups with nothing arriving
        assert self.conn._dead is False
        assert self.conn._reader_thread.is_alive()
        self.send_request()  # still works after the quiet spell

    def test_socket_carries_request_timeout(self):
        """The connected socket has a permanent request_timeout, not blocking mode."""
        self.connect(request_timeout=0.4)
        assert self.conn._sock.gettimeout() == 0.4


    # ======================================================================== #
    # 4. Read path — the reader thread turning bytes into replies
    # ======================================================================== #

    def test_large_reply_reassembled(self):
        """A ~1MB response body — far beyond the 64KB socket read buffer — arrives
        byte-exact through the reader's reassembly loop."""
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
        with pytest.raises(PhotonConnectionLostError):
            self.send_request()             # death detected on the next use

    def test_replies_routed_fifo(self):
        """_PendingRequests resolves futures strictly in registration (FIFO) order, and a
        RespError is delivered as a future's RESULT (a value), not raised — the caller-facing
        parse_* functions raise on it later. Internals: _PendingRequests."""
        pending = _PendingRequests()
        f1 = pending.register()
        f2 = pending.register()
        f3 = pending.register()
        err = RespError("ERR", "boom")
        pending.resolve_next(b"v1")
        pending.resolve_next(err)
        pending.resolve_next(b"v3")
        assert f1.result(timeout=1) == b"v1"
        assert f2.result(timeout=1) is err  # delivered as a value, not raised
        assert f3.result(timeout=1) == b"v3"

    def test_max_pending_rejects_when_full(self):
        """At the cap, registering fails fast with PhotonError and the queue is unchanged;
        resolving a request frees a slot. Internals: _PendingRequests."""
        pending = _PendingRequests(max_pending_requests=2)
        pending.register()
        pending.register()
        with pytest.raises(PhotonError):
            pending.register()
        pending.resolve_next(b"v1")   # frees one slot
        pending.register()          # fits again

    def test_max_pending_requests_propagates_from_connection(self):
        """The connection passes its pending-request limit to the pending queue."""
        conn = PhotonConnection("h", 1, tls=False, max_pending_requests=7)
        assert conn._pending._max_pending_requests == 7

    # ======================================================================== #
    # 5. Close — teardown
    # ======================================================================== #

    def test_close_twice_is_safe(self):
        """Calling close() twice on a connected connection does not raise."""
        self.connect()
        self.conn.close()
        self.conn.close()

    def test_close_during_connect_does_not_leak_the_socket(self):
        """close() racing connect(): connect() must dispose of the fresh socket and raise,
        not install it on the closed connection (where close() could never release it)."""
        conn = PhotonConnection("h", 1, tls=False)
        connect_entered = threading.Event()
        close_done = threading.Event()
        fresh_sock = mock.Mock(name="fresh-socket")

        def blocked_create_connection(addr, timeout):
            connect_entered.set()
            assert close_done.wait(2)
            return fresh_sock

        outcome: list = []

        def run_connect():
            try:
                conn.connect()
            except PhotonConnectionError as exc:
                outcome.append(exc)

        with mock.patch("photon.connection.socket.create_connection", blocked_create_connection):
            t = threading.Thread(target=run_connect)
            t.start()
            assert connect_entered.wait(2)
            conn.close()            # races connect(): _sock is still None here
            close_done.set()
            t.join(2)

        assert len(outcome) == 1    # connect() raised PhotonConnectionError
        assert fresh_sock.close.called
        assert conn._sock is None
        # The rollback also never started a reader: no orphaned thread survives the race.
        assert conn._reader_thread is None
        assert not any(t.name == "photon-reader" for t in threading.enumerate())

    def test_close_stops_and_forgets_the_reader_thread(self):
        """After close(), the reader thread has terminated and all connection state is
        nulled: nothing owned by the connection outlives close()."""
        self.connect()
        thread = self.conn._reader_thread
        assert thread.is_alive()
        self.conn.close()
        assert not thread.is_alive()          # join happened, the thread is gone
        assert self.conn._reader_thread is None
        assert self.conn._sock is None

    def test_execute_after_close_raises_connection_lost(self):
        """execute() on a closed connection reports PhotonConnectionLostError, not the misleading
        "used before connect()" error (close() nulls the socket)."""
        self.connect()
        self.conn.close()
        with pytest.raises(PhotonConnectionLostError):
            self.conn.execute("photon.request", b"x")

    def test_close_fails_pending_requests(self):
        """close() while a caller is blocked waiting for a reply: the caller gets
        PhotonConnectionLostError promptly — it must not hang until its request timeout."""
        self.start_cache(failure="hang")
        self.connect_and_handshake(request_timeout=10.0)

        outcome: list = []

        def blocked_caller():
            try:
                self.send_request()
                outcome.append("returned")
            except PhotonConnectionLostError:
                outcome.append("connection-lost")
            except Exception as exc:  # noqa: BLE001
                outcome.append(exc)

        t = threading.Thread(target=blocked_caller)
        t.start()
        time.sleep(0.2)          # let the caller register + block on its future
        self.conn.close()
        t.join(timeout=2)        # must unblock well before the 10s request timeout

        assert not t.is_alive(), "caller still blocked after close()"
        assert outcome == ["connection-lost"]

    @pytest.mark.parametrize("failing_method", ["shutdown", "close"])
    def test_close_survives_socket_errors(self, failing_method):
        """An OSError from sock.shutdown() or sock.close() (e.g. peer already gone) must not
        escape close(); teardown still completes and pending requests are still failed.
        Internals: a mock socket forces the error (a real socket can't fail mid-close
        deterministically)."""
        conn = PhotonConnection("host", 6379, tls=False)
        sock = mock.Mock()
        getattr(sock, failing_method).side_effect = OSError("already closed")
        conn._sock = sock
        fut = conn._pending.register()

        conn.close()  # must not raise

        sock.shutdown.assert_called_once()
        sock.close.assert_called_once()
        with pytest.raises(PhotonConnectionLostError):
            fut.result(timeout=1)  # pending caller still failed
        conn.close()  # still idempotent
