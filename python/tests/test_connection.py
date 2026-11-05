"""Unit tests for aws_elasticache_integration_client.connection, organized by the connector contract:

1. Establishment — connect() yields a usable connection or a ElastiCacheIntegrationsConnectionError
2. Authentication — the AUTH exchange
3. Send path — callers on any thread get their own reply, or a ElastiCacheIntegrations exception
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

from aws_elasticache_integration_client.connection import (
    ElastiCacheIntegrationsConnector,
    _PendingRequests,
    _SocketReader,
)
from aws_elasticache_integration_client.exceptions import (
    ElastiCacheIntegrationsAuthError,
    ElastiCacheIntegrationsConnectionLost,
    ElastiCacheIntegrationsConnectionError,
    ElastiCacheIntegrationsError,
    ElastiCacheIntegrationsProtocolError,
    ElastiCacheIntegrationsTimeoutError,
)
from aws_elasticache_integration_client.protocol import PUSH_KIND_RESPONSE
from aws_elasticache_integration_client.resp import RespError, RespPush, RespValue
from tests.mock import MockCache, Reply


class TestElastiCacheIntegrationsConnector:
    """The connector contract (live MockCache, plaintext and TLS) plus internals."""

    # Defaults for tests that never start a cache / connect; helpers assign
    # instance attributes that shadow these. pytest creates a fresh instance
    # per test, so nothing is shared between tests.
    cache: MockCache | None = None
    conn: ElastiCacheIntegrationsConnector | None = None

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
        self.conn = ElastiCacheIntegrationsConnector(
            self.cache.host, self.cache.port, tls=tls, **kwargs
        )
        self.conn.connect()

    def connect_and_handshake(self, **kwargs) -> None:
        """connect() plus the aws.config handshake — ready to send requests."""
        self.connect(**kwargs)
        self.conn.execute("aws.config", b"{}", timeout_ms=self.conn.connection_timeout_ms)

    def send_request(self, body: bytes = b"body", *, timeout_ms: int | None = None) -> RespValue:
        """Send a canonical aws.request ([method, url, headers, body, metadata]) with the given."""
        args = [b"POST", b"https://x/", b"", body, b"service=s&op=o"]
        return self.conn.execute("aws.request", *args, timeout_ms=timeout_ms)

    def wait_until(self, condition, timeout: float = 2.0) -> bool:
        """Poll ``condition`` until it is true, up to ``timeout``. Returns its final value."""
        deadline = time.monotonic() + timeout
        while not condition() and time.monotonic() < deadline:
            time.sleep(0.01)
        return condition()

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

    def test_request_timeout_defaults_to_five_seconds(self):
        self.conn = ElastiCacheIntegrationsConnector("127.0.0.1", 1, tls=False)

        assert self.conn.request_timeout_ms == 5000

    def test_connect_and_handshake(self):
        """connect() yields a working connection: the aws.config handshake round-trips
        the mock's resolved config (as a raw JSON bulk string — parsing it is
        protocol.py's contract)."""
        self.connect()
        raw = self.conn.execute("aws.config", b'{"k":"v"}')
        cfg = json.loads(raw)
        assert cfg["version"] == "1.0"
        assert cfg["refresh_interval"] == 60

    def test_no_arg_config_round_trips_resp3_null(self):
        """A no-argument aws.config ('overrides unchanged') gets back the literal RESP3
        null byte sequence (`_\\r\\n`) from a real server over a real socket, decoded to None —
        proving the wire-level path end-to-end, not just resp.py's decoder in isolation."""
        self.connect()
        reply = self.conn.execute("aws.config")
        assert reply is None

    def test_connect_to_nothing_raises(self):
        """Connecting to a port with nothing listening raises ElastiCacheIntegrationsConnectionError."""
        # Reserve a port then free it so nothing is listening -> TCP connect fails.
        probe = _socket.socket(_socket.AF_INET, _socket.SOCK_STREAM)
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
        probe.close()
        conn = ElastiCacheIntegrationsConnector("127.0.0.1", port, connection_timeout_ms=1000)
        with pytest.raises(ElastiCacheIntegrationsConnectionError) as exc_info:
            conn.connect()
        assert isinstance(exc_info.value.__cause__, ConnectionRefusedError)

    def test_tls_trusted_cert_connects(self):
        """Over real verifying TLS (trusting the mock's self-signed cert via ca_certs), the
        handshake and request path work. (Response content details are test_request_round_trip's
        job — this test only proves the paths function over TLS.)"""
        self.start_cache(tls=True)
        self.connect_and_handshake(tls=True, ca_certs=self.cache.cert_path)
        assert self.send_request(timeout_ms=self.conn.connection_timeout_ms)[0] == 200

    def test_tls_untrusted_cert_refused(self):
        """With the default CA store (which does not trust the mock's self-signed cert),
        the handshake must fail — never connect insecurely."""
        self.start_cache(tls=True)
        self.conn = ElastiCacheIntegrationsConnector(
            self.cache.host, self.cache.port
        )  # tls=True, no ca_certs
        with pytest.raises(ElastiCacheIntegrationsConnectionError, match="TLS handshake failed"):
            self.conn.connect()

    def test_tls_verify_false_connects_untrusted(self):
        """verify=False connects to a cert the default CA store does not trust, and the
        request path works. The opt-out for self-signed test rigs."""
        self.start_cache(tls=True)
        self.connect_and_handshake(tls=True, verify=False)  # no ca_certs on purpose
        assert self.send_request()[0] == 200

    def test_send_before_connect_raises(self):
        """Sending before connect() raises ElastiCacheIntegrationsError (no connection yet — distinct from
        ElastiCacheIntegrationsConnectionLost, which means a connection existed and died)."""
        self.start_cache()
        conn = ElastiCacheIntegrationsConnector(self.cache.host, self.cache.port, tls=False)
        with pytest.raises(ElastiCacheIntegrationsError, match="used before connect"):
            conn.execute("aws.config", b"{}")

    def test_close_during_connect_aborts_cleanly(self):
        """close() while connect() is still opening the connection: connect() must raise
        ElastiCacheIntegrationsConnectionError and the connector must end fully closed — no half-open
        connection, no leaked I/O loop thread."""
        conn = ElastiCacheIntegrationsConnector("h", 1, tls=False)
        open_entered = threading.Event()

        async def blocked_open(*args, **kwargs):
            open_entered.set()
            # Park on the loop so close()'s task cancellation lands at this await. Not an
            # executor: asyncio.run's teardown joins the default executor, so parking there
            # would hold the loop thread open for reasons unrelated to the connector.
            await asyncio.Event().wait()
            return (mock.Mock(), mock.Mock())

        outcome: list = []

        def run_connect():
            try:
                conn.connect()
            except BaseException as exc:  # noqa: BLE001 — the assertion below names the type
                outcome.append(exc)

        with mock.patch(
            "aws_elasticache_integration_client.connection.asyncio.open_connection", blocked_open
        ):
            t = threading.Thread(target=run_connect)
            t.start()
            assert open_entered.wait(2)
            conn.close()  # races connect(): nothing is installed yet
            t.join(3)

        assert not t.is_alive()
        assert len(outcome) == 1  # connect() raised exactly once
        assert isinstance(outcome[0], ElastiCacheIntegrationsConnectionError), outcome[0]
        assert conn._writer is None
        assert not any(
            th.name == "aws-elasticache-integration-client-io-thread"
            for th in threading.enumerate()
        )

    def test_connect_install_refused_after_close(self):
        """The install-check second line of defense: if close() lands after the connection
        opens but before install, the fresh connection is aborted, never installed.
        Internals: _establish_connection driven directly with _closed pre-set."""
        conn = ElastiCacheIntegrationsConnector("h", 1, tls=False)
        conn._closed = True
        writer = mock.Mock()

        async def fake_open(*args, **kwargs):
            return (mock.Mock(), writer)

        with mock.patch(
            "aws_elasticache_integration_client.connection.asyncio.open_connection", fake_open
        ):
            with pytest.raises(ElastiCacheIntegrationsConnectionError):
                asyncio.run(conn._establish_connection())
        writer.transport.abort.assert_called_once()
        assert conn._writer is None

    # ======================================================================== #
    # 2. Handshake — the HELLO 3 exchange, carrying the credentials
    # ======================================================================== #

    def test_hello_silence_times_out_and_kills_connection(self):
        """A server that goes silent on HELLO trips the request timeout: handshake() raises
        ElastiCacheIntegrationsConnectionError and the connection is closed. The token never appears in
        the message."""
        self.start_cache(failure="hang", failure_command="hello")
        self.connect(
            request_timeout_ms=300,
            auth_provider=lambda: ("test-user", "test-token"),
        )
        with pytest.raises(
            ElastiCacheIntegrationsConnectionError, match="HELLO 3 timed out"
        ) as excinfo:
            self.conn.handshake()
        assert "test-token" not in str(excinfo.value)
        assert self.conn._closed

    def test_hello_accepted(self):
        """A map reporting proto 3 confirms the switch.
        Internals: _verify_hello_reply is pure reply validation, tested directly."""
        conn = ElastiCacheIntegrationsConnector("host", 6379, tls=False)
        conn._verify_hello_reply({b"server": b"valkey", b"proto": 3})  # must not raise
        assert not conn._closed

    @pytest.mark.parametrize(
        "code,error_type",
        [
            ("NOPROTO", ElastiCacheIntegrationsConnectionError),
            ("invalid-iam-credentials", ElastiCacheIntegrationsAuthError),
        ],
    )
    def test_hello_rejected_kills_connection(self, code, error_type):
        """One frame carries both halves of the handshake, so a refused protocol version and a
        refused credential arrive the same way: an error reply. Either closes the connection —
        RESP2 makes deferred responses silently unavailable, and an unauthenticated connection
        has no valid next use — and the code is surfaced.
        Internals: _verify_hello_reply tested directly."""
        conn = ElastiCacheIntegrationsConnector("host", 6379, tls=False)
        with pytest.raises(error_type) as excinfo:
            conn._verify_hello_reply(RespError(code, "nope"))
        assert code in str(excinfo.value)
        assert conn._closed

    def test_hello_rejection_force_refreshes_managed_auth(self):
        """A rejected cached token is replaced before the bounded connect retry."""
        provider = mock.Mock(return_value=("iam-user", "cached-token"))
        provider.refresh_auth = mock.Mock(return_value=("iam-user", "replacement-token"))
        conn = ElastiCacheIntegrationsConnector("host", 6379, tls=False, auth_provider=provider)
        conn.execute = mock.Mock(
            return_value=RespError("invalid-iam-credentials", "credentials rejected")
        )

        with pytest.raises(ElastiCacheIntegrationsAuthError, match="invalid-iam-credentials"):
            conn.handshake()

        provider.refresh_auth.assert_called_once()

    def test_non_auth_hello_rejection_does_not_refresh_managed_auth(self):
        provider = mock.Mock(return_value=("iam-user", "cached-token"))
        provider.refresh_auth = mock.Mock(return_value=("iam-user", "replacement-token"))
        conn = ElastiCacheIntegrationsConnector("host", 6379, tls=False, auth_provider=provider)
        conn.execute = mock.Mock(return_value=RespError("NOPROTO", "unsupported protocol"))

        with pytest.raises(ElastiCacheIntegrationsConnectionError, match="NOPROTO") as excinfo:
            conn.handshake()

        assert not isinstance(excinfo.value, ElastiCacheIntegrationsAuthError)
        provider.refresh_auth.assert_not_called()

    @pytest.mark.parametrize(
        "reply",
        [
            {b"server": b"valkey", b"proto": 2},  # switch refused, reported as RESP2
            {b"server": b"valkey"},  # no proto entry at all
            "OK",  # not a map
        ],
        ids=["proto-2", "no-proto", "not-a-map"],
    )
    def test_hello_unconfirmed_switch_kills_connection(self, reply):
        """Anything that does not positively confirm proto 3 closes the connection: continuing
        would leave head-of-line blocking in place with nothing reporting it."""
        conn = ElastiCacheIntegrationsConnector("host", 6379, tls=False)
        with pytest.raises(ElastiCacheIntegrationsConnectionError):
            conn._verify_hello_reply(reply)
        assert conn._closed

    def test_reauth_rejection_keeps_the_connection_open(self):
        """A background re-AUTH reply arrives in order, so a rejection is not a FIFO desync: the
        connection stays open and the proxy keeps serving on its last valid token. This is the
        opposite of the HELLO bring-up, where a rejection closes. Internals: _verify_auth_reply
        tested directly."""
        conn = ElastiCacheIntegrationsConnector("host", 6379, tls=False)
        with pytest.raises(ElastiCacheIntegrationsAuthError):
            conn._verify_auth_reply(RespError("WRONGPASS", "bad token"))
        assert not conn._closed

    def test_reauth_rejection_force_refreshes_managed_auth(self):
        provider = mock.Mock(return_value=("iam-user", "cached-token"))
        provider.refresh_auth = mock.Mock(return_value=("iam-user", "replacement-token"))
        conn = ElastiCacheIntegrationsConnector("host", 6379, tls=False, auth_provider=provider)
        conn.execute = mock.Mock(return_value=RespError("WRONGPASS", "bad token"))

        with pytest.raises(ElastiCacheIntegrationsAuthError, match="WRONGPASS"):
            conn.authenticate()

        provider.refresh_auth.assert_called_once()

    def test_reauth_unexpected_reply_does_not_refresh_closed_connection(self):
        provider = mock.Mock(return_value=("iam-user", "cached-token"))
        provider.refresh_auth = mock.Mock(return_value=("iam-user", "replacement-token"))
        conn = ElastiCacheIntegrationsConnector("host", 6379, tls=False, auth_provider=provider)
        conn.execute = mock.Mock(return_value=b"response from another request")

        with pytest.raises(ElastiCacheIntegrationsAuthError, match="unexpected reply"):
            conn.authenticate()

        assert not conn.is_alive
        provider.refresh_auth.assert_not_called()

    def test_failed_forced_refresh_logs_and_preserves_rejection(self, caplog):
        provider = mock.Mock(return_value=("iam-user", "cached-token"))
        provider.refresh_auth = mock.Mock(side_effect=RuntimeError("signing failed"))
        conn = ElastiCacheIntegrationsConnector("host", 6379, tls=False, auth_provider=provider)
        conn.execute = mock.Mock(return_value=RespError("WRONGPASS", "bad token"))

        with caplog.at_level("WARNING", logger="aws_elasticache_integration_client.connection"):
            with pytest.raises(ElastiCacheIntegrationsAuthError, match="WRONGPASS"):
                conn.authenticate()

        assert "Failed to force-refresh IAM authentication" in caplog.text

    @pytest.mark.parametrize(
        "reply",
        [
            "WHAT",  # a well-framed but unexpected simple string
            b"cached response body",  # a bulk string from another request
            {b"k": b"v"},  # a map
            7,  # an int
        ],
        ids=["simple", "bulk", "map", "int"],
    )
    def test_reauth_unexpected_reply_closes_the_connection(self, reply):
        """Any non-OK reply other than an error means the FIFO is out of sync, so the connection
        closes and the reply is kept out of the message (it may be another request's body)."""
        conn = ElastiCacheIntegrationsConnector("host", 6379, tls=False)
        with pytest.raises(ElastiCacheIntegrationsAuthError) as excinfo:
            conn._verify_auth_reply(reply)
        assert conn._closed
        assert type(reply).__name__ in str(excinfo.value)
        assert repr(reply) not in str(excinfo.value)

    def test_handshake_carries_the_credentials_inline(self):
        """One frame, `HELLO 3 AUTH <user> <token>`: the proxy derives the caller's FAS from the
        credentials arriving on the HELLO, so no separate AUTH is sent."""
        self.start_cache()
        self.connect(auth_provider=lambda: ("test-user", "test-token"))
        self.conn.handshake()
        assert self.cache.cache_command_log == ["hello"]
        assert self.cache.received_commands[0] == [
            b"HELLO",
            b"3",
            b"AUTH",
            b"test-user",
            b"test-token",
        ]

    # ======================================================================== #
    # 3. Send path — what a caller experiences calling execute
    # ======================================================================== #

    def test_request_round_trip(self):
        """A aws.request round-trips: the caller gets the cache's [status, headers, body]
        array intact (turning it into a ElastiCacheIntegrationsResponse is protocol.py's contract)."""
        self.start_cache(
            response_status=200,
            response_headers="content-type:application/json",
            response_body=b'{"ok":1}',
        )
        self.connect_and_handshake()
        value = self.send_request(body=b'{"ok":1}')
        assert value == [200, b"content-type:application/json", b'{"ok":1}']

    @pytest.mark.parametrize("tls", [False, True], ids=["plaintext", "tls"])
    def test_concurrent_requests_get_own_replies(self, tls):
        """8 threads fire requests simultaneously (barrier-synced); the mock reflects each
        request body; each thread must get its own token back — no response cross-talk over
        the single shared connection. This is the file's central test.

        Parametrized over TLS because the race it guards against belongs to the TLS object:
        concurrent recv and sendall on a plaintext socket are safe, so the plaintext case
        passes against the pre-fix code too."""
        self.start_cache(tls=tls, response_fn=lambda req: Reply.ok(body=req.body))
        tls_kwargs = {"ca_certs": self.cache.cert_path} if tls else {}
        self.connect_and_handshake(tls=tls, **tls_kwargs)

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
        self.connect_and_handshake(request_timeout_ms=300)
        with pytest.raises(ElastiCacheIntegrationsTimeoutError):
            self.send_request()
        assert self.conn._closed is True

    def test_send_on_closed_connection_fails_fast(self):
        """Once closed, a new send fails ElastiCacheIntegrationsConnectionLost immediately — it must not sit out
        the full request timeout."""
        self.start_cache(failure="drop")
        # Generous request_timeout so a fast failure is distinguishable from a timeout wait.
        self.connect_and_handshake(request_timeout_ms=5000)
        with pytest.raises(ElastiCacheIntegrationsConnectionLost):
            self.send_request()  # server drops -> closed
        start = time.monotonic()
        with pytest.raises(ElastiCacheIntegrationsConnectionLost):
            self.send_request()
        assert time.monotonic() - start < 1.0  # closed-check fired, not the 5s timeout

    def test_wedged_loop_does_not_block_callers_forever(self):
        """A wedged I/O loop must not block callers forever: with the send parked behind
        the wedge, the caller's reply timeout still fires. (There is no send-side timeout
        by design — a send only copies bytes into the transport buffer.)"""
        self.connect_and_handshake(request_timeout_ms=200)
        self.conn._io_loop_thread.call(time.sleep, 1)  # wedge the loop
        with pytest.raises(ElastiCacheIntegrationsTimeoutError):
            self.send_request()

    @pytest.mark.parametrize(
        "leak",
        [
            OSError("broken pipe"),
            TypeError("closing transport"),
            AttributeError("closing transport"),
        ],
        ids=["oserror", "typeerror", "attributeerror"],
    )
    def test_write_failure_kills_connection(self, leak):
        """A write that fails — including the wrong-typed exceptions a closing transport can
        leak on 3.12 / early 3.13 (CPython gh-136234) — fails the caller with
        ElastiCacheIntegrationsConnectionLost and kills the connection; the next send fails fast."""
        self.connect_and_handshake(request_timeout_ms=5000)
        writer = self.wrap_writer()
        writer.write = mock.Mock(side_effect=leak)
        with pytest.raises(ElastiCacheIntegrationsConnectionLost):
            self.send_request()
        writer.write.assert_called_once()
        assert self.conn._closed is True
        start = time.monotonic()
        with pytest.raises(ElastiCacheIntegrationsConnectionLost):
            self.send_request()  # closed-check fires, not the 5s timeout
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
        the closed connection."""
        self.start_cache(failure="drop", failure_after_reply=True)
        self.connect_and_handshake()
        value = self.send_request()  # reply arrives despite the FIN right behind it
        assert value[0] == 200
        with pytest.raises(ElastiCacheIntegrationsConnectionLost):
            self.send_request()  # death detected on the next use

    def test_quiet_connection_stays_alive(self):
        """An idle connection several request_timeouts long is normal, not a failure:
        afterwards the connection is alive and a request still round-trips."""
        self.connect_and_handshake(request_timeout_ms=200)
        time.sleep(0.5)  # idle spell
        assert self.conn._closed is False
        self.send_request()  # still works after the quiet spell

    def test_reader_reassembles_split_frames(self):
        """A frame split across reads is reassembled; trailing bytes of a read are
        retained for the next frame. Internals: _SocketReader on an in-memory stream."""

        async def scenario() -> list:
            stream = asyncio.StreamReader()
            reader = _SocketReader(stream)
            stream.feed_data(b"$5\r\nhel")  # first frame, split mid-payload
            stream.feed_data(b"lo\r\n+OK\r\n")  # rest of it + a whole second frame
            return [await reader.read_frame(), await reader.read_frame()]

        assert asyncio.run(scenario()) == [b"hello", "OK"]

    def test_garbage_reply_fails_caller_as_connection_lost(self):
        """A reply that cannot be decoded kills the connection: the waiting caller fails
        with ElastiCacheIntegrationsConnectionLost carrying the ElastiCacheIntegrationsProtocolError as its cause. The
        request was sent and never answered, so it must surface as a connection loss
        (indeterminate), never as a response-level error."""
        self.start_cache(response=Reply(frame=b"X123\r\n"))
        self.connect_and_handshake()
        with pytest.raises(ElastiCacheIntegrationsConnectionLost) as exc_info:
            self.send_request()
        assert isinstance(exc_info.value.__cause__, ElastiCacheIntegrationsProtocolError)
        assert self.conn._closed is True

    def test_deeply_nested_reply_fails_caller_as_connection_lost(self):
        """A reply nested past the RESP depth cap kills the connection like any garbage reply,
        so the waiting caller fails at once instead of waiting out its timeout."""
        self.start_cache(response=Reply(frame=b"*1\r\n" * 5000 + b":1\r\n"))
        self.connect_and_handshake()
        with pytest.raises(ElastiCacheIntegrationsConnectionLost) as exc_info:
            self.send_request()
        assert isinstance(exc_info.value.__cause__, ElastiCacheIntegrationsProtocolError)
        assert str(exc_info.value.__cause__) == "RESP nesting exceeds 100 levels"
        assert self.conn._closed is True

    def test_reader_eof_raises_connection_lost(self):
        """Clean EOF mid-frame surfaces as ElastiCacheIntegrationsConnectionLost, not a decode error.
        Internals: _SocketReader on an in-memory stream."""

        async def scenario() -> None:
            stream = asyncio.StreamReader()
            reader = _SocketReader(stream)
            stream.feed_data(b"$5\r\nhe")  # incomplete frame
            stream.feed_eof()
            await reader.read_frame()

        with pytest.raises(ElastiCacheIntegrationsConnectionLost):
            asyncio.run(scenario())

    def test_reader_rejects_a_frame_over_the_cap(self):
        """A length header may declare any size, so the cap is on bytes held, not bytes promised:
        a body streamed past max_response_size is rejected instead of buffered. Without this the
        buffer has no ceiling at all. Internals: _SocketReader on an in-memory stream."""

        async def scenario() -> None:
            stream = asyncio.StreamReader()
            reader = _SocketReader(stream, max_response_size=1024)
            stream.feed_data(b"$9999999999\r\n")  # declares ~10 GB
            stream.feed_data(b"x" * 4096)  # and starts delivering it
            # EOF so that an uncapped reader ends the read loop instead of waiting forever:
            # without it, losing the cap would hang this test rather than fail it.
            stream.feed_eof()
            await reader.read_frame()

        with pytest.raises(ElastiCacheIntegrationsProtocolError, match="maximum response size"):
            asyncio.run(scenario())

    def test_reader_accepts_a_frame_exactly_at_the_cap(self):
        """The cap counts the whole frame including its header and CRLFs, and a frame of exactly
        that size is legal — so an off-by-one here would reject the largest valid reply.
        Internals: _SocketReader on an in-memory stream."""
        frame = b"$5\r\nhello\r\n"  # 11 bytes all in

        async def scenario(max_response_size: int) -> RespValue:
            stream = asyncio.StreamReader()
            reader = _SocketReader(stream, max_response_size=max_response_size)
            stream.feed_data(frame)
            return await reader.read_frame()

        assert asyncio.run(scenario(len(frame))) == b"hello"
        with pytest.raises(ElastiCacheIntegrationsProtocolError, match="maximum response size"):
            asyncio.run(scenario(len(frame) - 1))

    def test_reader_caps_each_frame_not_the_connection(self):
        """The cap is per reply, not a running total: pipelined frames arrive in one read, so a
        check on the buffer alone would reject a batch of small legal frames. Ten 5-byte frames
        totalling 50 bytes all decode under an 11-byte cap. Internals: _SocketReader."""

        async def scenario() -> list:
            stream = asyncio.StreamReader()
            reader = _SocketReader(stream, max_response_size=11)
            stream.feed_data(b"+OK\r\n" * 10)  # one chunk, ten whole frames
            return [await reader.read_frame() for _ in range(10)]

        assert asyncio.run(scenario()) == ["OK"] * 10

    def test_oversize_reply_fails_caller_as_connection_lost(self):
        """An over-cap reply kills the connection the same way any undecodable one does: the
        caller sees ElastiCacheIntegrationsConnectionLost carrying the ElastiCacheIntegrationsProtocolError as its cause. The
        request was sent and never answered, so it is indeterminate, not a response error."""
        self.start_cache(response=Reply.ok(body=b"x" * 4096))
        self.connect_and_handshake(max_response_size=512)  # roomy for HELLO/config, not the body
        with pytest.raises(ElastiCacheIntegrationsConnectionLost) as exc_info:
            self.send_request()
        assert isinstance(exc_info.value.__cause__, ElastiCacheIntegrationsProtocolError)
        assert "maximum response size" in str(exc_info.value.__cause__)
        assert self.conn._closed is True

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
        """At the cap, registering fails fast with ElastiCacheIntegrationsError and the queue is unchanged;
        resolving a request frees a slot. Internals: _PendingRequests."""
        pending = _PendingRequests(max_pending=2)
        pending.register_new_request(Future())
        pending.register_new_request(Future())
        with pytest.raises(ElastiCacheIntegrationsError):
            pending.register_new_request(Future())
        pending.resolve_oldest_request(b"v1")  # frees one slot
        pending.register_new_request(Future())  # fits again

    def test_max_pending_counts_deferred_requests(self):
        """A deferred request still counts against max_pending."""
        pending = _PendingRequests(max_pending=1)
        pending.register_new_request(Future())
        pending.move_head_to_async_responses("1")  # out of the FIFO, still in flight
        with pytest.raises(ElastiCacheIntegrationsError, match="too many in-flight requests"):
            pending.register_new_request(Future())

    def test_a_deferred_request_lets_the_next_reply_through(self):
        """The reply after a deferral goes to the request behind it; the deferred one keeps
        waiting. Internals: _PendingRequests."""
        pending = _PendingRequests()
        deferred, behind_it = Future(), Future()
        pending.register_new_request(deferred)
        pending.register_new_request(behind_it)
        pending.move_head_to_async_responses("1")
        pending.resolve_oldest_request(b"reply")
        assert behind_it.result(timeout=1) == b"reply"
        assert not deferred.done()

    def test_pending_requests_move_and_resolve_async(self):
        """move_head_to_async_responses moves the oldest future out of the FIFO under the id;
        resolve_async resolves it. Internals: _PendingRequests."""
        pending = _PendingRequests()
        future = Future()
        pending.register_new_request(future)
        pending.move_head_to_async_responses("1")
        assert pending.resolve_async("1", b"reply")
        assert future.result(timeout=1) == b"reply"

    def test_resolve_async_unknown_id(self):
        """resolve_async reports an id nobody waits for, so a push that lost its caller is
        dropped instead of failing the connection. Internals: _PendingRequests."""
        pending = _PendingRequests()
        assert not pending.resolve_async("999", b"v")

    def test_id_reply_with_empty_fifo_is_a_protocol_error(self):
        """A request id reply with nothing pending cannot be matched to a caller, so the stream
        can no longer be trusted. Internals: _PendingRequests."""
        pending = _PendingRequests()
        with pytest.raises(ElastiCacheIntegrationsProtocolError, match="no pending request"):
            pending.move_head_to_async_responses("1")

    def test_duplicate_request_id_is_a_protocol_error(self):
        """Two live requests can never share an id; overwriting the entry would orphan the
        first caller, so a duplicate is a protocol error. Internals: _PendingRequests."""
        pending = _PendingRequests()
        pending.register_new_request(Future())
        pending.move_head_to_async_responses("1")
        pending.register_new_request(Future())  # else the empty-FIFO check fires first
        with pytest.raises(ElastiCacheIntegrationsProtocolError, match="duplicate request id"):
            pending.move_head_to_async_responses("1")

    def test_fail_if_async_fails_a_deferred_future(self):
        """A caller giving up on a deferred request drops its entry and the future carries the
        failure, so a late push finds nothing to resolve. Internals: _PendingRequests."""
        pending = _PendingRequests()
        deferred = Future()
        pending.register_new_request(deferred)
        pending.move_head_to_async_responses("1")
        assert pending.fail_if_async(deferred, ElastiCacheIntegrationsTimeoutError("gave up"))
        with pytest.raises(ElastiCacheIntegrationsTimeoutError):
            deferred.result(timeout=1)
        assert not pending.resolve_async("1", b"late")

    def test_fail_if_async_when_the_reader_settled_first(self):
        """A reply landing as the caller times out leaves nothing to fail: fail_if_async reports
        the connection is fine and the result stands, so a healthy connection is not closed over
        a race."""
        pending = _PendingRequests()
        deferred = Future()
        pending.register_new_request(deferred)
        pending.move_head_to_async_responses("1")
        assert pending.resolve_async("1", b"just in time")  # the reader wins
        assert pending.fail_if_async(deferred, ElastiCacheIntegrationsTimeoutError("too late"))
        assert deferred.result(timeout=1) == b"just in time"

    def test_fail_if_async_leaves_a_fifo_future_alone(self):
        """A future still in the FIFO is not deferred, so it keeps its slot and stays pending —
        that is how the timeout path knows the connection cannot survive.
        Internals: _PendingRequests."""
        pending = _PendingRequests()
        fifo = Future()
        pending.register_new_request(fifo)
        assert not pending.fail_if_async(fifo, ElastiCacheIntegrationsTimeoutError("gave up"))
        assert not fifo.done()

    def test_fail_all_fails_async_futures_too(self):
        """fail_all fails futures in BOTH structures — a deferred caller must not hang until
        its timeout when the connection dies. Internals: _PendingRequests."""
        pending = _PendingRequests()
        deferred = Future()
        pending.register_new_request(deferred)
        pending.move_head_to_async_responses("1")
        fifo = Future()
        pending.register_new_request(fifo)
        pending.fail_all(ElastiCacheIntegrationsConnectionLost("gone"))
        for future in (deferred, fifo):
            with pytest.raises(ElastiCacheIntegrationsConnectionLost):
                future.result(timeout=1)
        assert pending.resolve_async("1", b"late") is False  # map was cleared

    def test_max_pending_propagates_from_connector(self):
        """The connector passes its max_pending through to the pending queue."""
        conn = ElastiCacheIntegrationsConnector("h", 1, tls=False, max_pending=7)
        assert conn._pending._max_pending == 7

    # ======================================================================== #
    # 5. Deferred (out-of-band) responses — HOL blocking removal
    # ======================================================================== #

    def test_reader_routes_an_id_reply_to_the_async_map(self):
        """A simple string other than OK is a request id: the reader moves the oldest future
        out of the FIFO into the async map under that id. Internals: _reader_loop."""
        conn = ElastiCacheIntegrationsConnector("host", 6379, tls=False)
        future = Future()
        conn._pending.register_new_request(future)

        async def read_frame() -> RespValue:
            conn._closed = True
            return "42"

        conn._reader = mock.Mock(read_frame=read_frame)
        asyncio.run(conn._reader_loop())
        assert conn._pending._async_responses["42"] is future
        assert future not in conn._pending._queue

    @pytest.mark.parametrize(
        "reply",
        [[200, b"", b"body"], RespError("ERR", "origin failed")],
        ids=["response", "error"],
    )
    def test_push_consumes_its_id_entry(self, reply):
        """The push for a deferred request resolves its future and removes the id from the
        map, so nothing is left waiting under it. An error travels as a value, like a
        synchronous one. Internals: _handle_push."""
        conn = ElastiCacheIntegrationsConnector("host", 6379, tls=False)
        future = Future()
        conn._pending.register_new_request(future)
        conn._pending.move_head_to_async_responses("42")
        conn._handle_push(RespPush(kind=PUSH_KIND_RESPONSE, items=[b"42", reply]))
        assert future.result(timeout=1) == reply
        assert "42" not in conn._pending._async_responses

    def test_async_request_timeout_keeps_connection_alive(self):
        """A timeout on a deferred request abandons only that request: the connection
        survives, the late push is dropped with a warning, and the next request works."""
        replies = [Reply.request_id(1), Reply.ok(body=b"after")]
        self.start_cache(response_fn=lambda req: replies.pop(0))
        self.connect_and_handshake(request_timeout_ms=300)

        with pytest.raises(ElastiCacheIntegrationsTimeoutError, match="async request timed out"):
            self.send_request()
        assert not self.conn._closed  # the connection survived

        self.cache.send_push(1)  # late push: unknown id, dropped
        value = self.send_request()  # connection still fully usable
        assert value[2] == b"after"
        assert not self.conn._closed

    def test_unknown_push_kind_is_a_protocol_error(self):
        """The proxy only sends kinds this client understands, so an unknown kind means the
        stream cannot be trusted. Internals: _handle_push."""
        conn = ElastiCacheIntegrationsConnector("host", 6379, tls=False)
        with pytest.raises(ElastiCacheIntegrationsProtocolError, match="unknown push kind"):
            conn._handle_push(RespPush(kind=b"invalidate", items=[b"whatever"]))

    def test_malformed_push_kills_connection(self):
        """A push the protocol layer cannot read leaves the whole stream untrustworthy, so the
        reader marks the connection dead. Payload rules live in parse_response_push."""
        self.start_cache()
        self.connect_and_handshake()
        self.cache.send_push(1, items=[Reply.ok().frame])  # a reply, one item
        assert self.wait_until(lambda: self.conn._closed)

    def test_push_for_unknown_id_is_dropped(self):
        """A push whose id maps to nothing (caller timed out earlier) is logged and dropped;
        the connection stays alive. Internals: _handle_push."""
        conn = ElastiCacheIntegrationsConnector("host", 6379, tls=False)
        conn._handle_push(RespPush(kind=PUSH_KIND_RESPONSE, items=[b"7", [200, b"", b""]]))
        assert not conn._closed

    # ======================================================================== #
    # 6. Close — teardown
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
            conn = ElastiCacheIntegrationsConnector(self.cache.host, self.cache.port, tls=False)
            conn.connect()
            conn.execute("aws.config", b"{}")
            conn.close()
        assert not any(
            t.name == "aws-elasticache-integration-client-io-thread" for t in threading.enumerate()
        )

    def test_close_stops_and_forgets_the_io_loop(self):
        """After close(), the I/O loop thread has terminated and all connection state is
        nulled: nothing owned by the connector outlives close()."""
        self.connect()
        thread = self.conn._io_loop_thread.thread
        assert thread.is_alive()
        self.conn.close()
        assert not thread.is_alive()  # join happened, the thread is gone
        assert self.conn._io_loop_thread.thread is None
        assert self.conn._io_loop_thread.loop is None
        assert self.conn._writer is None

    def test_execute_after_close_raises_connection_lost(self):
        """execute() on a closed connector reports ElastiCacheIntegrationsConnectionLost, not the misleading
        "used before connect()" error (close() nulls the loop)."""
        self.connect()
        self.conn.close()
        with pytest.raises(ElastiCacheIntegrationsConnectionLost):
            self.conn.execute("aws.request", b"x")

    def test_close_fails_pending_requests(self):
        """close() while a caller is blocked waiting for a reply: the caller gets
        ElastiCacheIntegrationsConnectionLost promptly — it must not hang until its request timeout."""
        self.start_cache(failure="hang")
        self.connect_and_handshake(request_timeout_ms=10000)

        outcome: list = []

        def blocked_caller():
            try:
                self.send_request()
                outcome.append("returned")
            except ElastiCacheIntegrationsConnectionLost:
                outcome.append("connection-lost")
            except Exception as exc:  # noqa: BLE001
                outcome.append(exc)

        t = threading.Thread(target=blocked_caller)
        t.start()
        deadline = time.monotonic() + 2
        while len(self.conn._pending._queue) == 0:  # wait until the caller is registered + waiting
            assert time.monotonic() < deadline, "caller never registered its request"
            time.sleep(0.01)
        self.conn.close()
        t.join(timeout=2)  # must unblock well before the 10s request timeout

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

        with pytest.raises(ElastiCacheIntegrationsConnectionLost):
            fut.result(timeout=1)  # pending caller still failed
        self.conn.close()  # still idempotent

    # ======================================================================== #
    # 6. Lifetime — one test per way the connection can die
    # ======================================================================== #

    def await_io_thread_exit(self, thread: threading.Thread, timeout: float = 2.0) -> None:
        """Wait for the I/O thread to exit on its own — no close() involved."""
        thread.join(timeout)
        assert not thread.is_alive(), "I/O thread outlived the connection"

    def test_peer_disconnect_releases_the_io_loop(self):
        """A server-side disconnect must end the I/O loop and thread on its own. Nothing
        upstream calls close() on a connector that merely died — hooks logs the error and
        passes the request through — so a connection lost here has to release itself."""
        self.start_cache(failure="drop")
        self.connect_and_handshake()
        thread = self.conn._io_loop_thread.thread

        with pytest.raises(ElastiCacheIntegrationsConnectionLost):
            self.send_request()  # server drops instead of replying

        self.await_io_thread_exit(thread)
        assert self.conn._io_loop_thread.loop is None
        assert self.conn._io_loop_thread.main_task is None
        assert self.conn._writer is None
        assert self.conn._reader is None
        assert self.conn._closed is True

    def test_unexpected_reader_error_releases_the_io_loop(self):
        """Teardown must not depend on having enumerated the right exception types: an error
        the reader never anticipated still releases the connection and still fails the caller,
        rather than leaving a live loop and a caller waiting out its timeout.
        Internals: the FIFO resolve step is made to raise a type no handler catches."""
        self.connect_and_handshake(request_timeout_ms=5000)
        thread = self.conn._io_loop_thread.thread
        self.conn._pending.resolve_oldest_request = mock.Mock(
            side_effect=ValueError("unanticipated")
        )

        start = time.monotonic()
        with pytest.raises(ElastiCacheIntegrationsConnectionLost):
            self.send_request()
        assert time.monotonic() - start < 1.0, "caller waited out the request timeout"

        self.await_io_thread_exit(thread)
        assert self.conn._io_loop_thread.loop is None

    def test_close_from_the_io_thread_raises(self):
        """close() from the I/O thread is a programming error, not a deadlock: it would ask
        that thread to join itself. It raises RuntimeError rather than ElastiCacheIntegrationsError so the
        before-send hook cannot swallow it as a cache miss."""
        self.connect()
        outcome: list = []

        def call_close_on_io_loop_thread() -> None:
            try:
                self.conn.close()
                outcome.append("returned")
            except BaseException as exc:  # noqa: BLE001 — the assertion below names the type
                outcome.append(exc)

        self.conn._io_loop_thread.call(call_close_on_io_loop_thread)
        deadline = time.monotonic() + 2
        while not outcome:
            assert time.monotonic() < deadline, "close() never ran on the I/O thread"
            time.sleep(0.01)

        assert isinstance(outcome[0], RuntimeError), outcome[0]
        assert self.conn._io_loop_thread.thread.is_alive()  # the guard fired before any teardown

    def test_failed_dial_leaves_no_io_thread(self):
        """A connect() that never establishes anything must not leave its loop thread behind:
        the bring-up failure travels back to the caller and the thread ends with it."""
        probe = _socket.socket(_socket.AF_INET, _socket.SOCK_STREAM)
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
        probe.close()  # nothing is listening -> the dial fails

        conn = ElastiCacheIntegrationsConnector(
            "127.0.0.1", port, tls=False, connection_timeout_ms=1000
        )
        with pytest.raises(ElastiCacheIntegrationsConnectionError):
            conn.connect()

        assert not any(
            t.name == "aws-elasticache-integration-client-io-thread" for t in threading.enumerate()
        )
        assert conn._io_loop_thread.loop is None

    def test_second_connect_is_refused(self):
        """ElastiCacheIntegrationsConnector is single-use. A second connect() would overwrite the loop and
        thread handles, orphaning the first pair with nothing left to close them."""
        self.connect()
        with pytest.raises(ElastiCacheIntegrationsConnectionError, match="single-use"):
            self.conn.connect()

    def test_io_thread_declines_to_install_a_loop_after_close(self):
        """close() can land between connect() starting the I/O thread and that thread reaching
        the loop, so close() finds no loop to cancel. The thread must then decline to install
        one and report the closure to the waiting connect().
        Internals: the thread target driven directly with _closed pre-set."""
        conn = ElastiCacheIntegrationsConnector("h", 1, tls=False)
        conn._closed = True
        conn_ready: Future = Future()

        conn._io_thread_main(conn_ready)  # returns as soon as the connection bails

        with pytest.raises(ElastiCacheIntegrationsConnectionError):
            conn_ready.result(timeout=1)
        assert conn._io_loop_thread.loop is None
        assert conn._io_loop_thread.main_task is None
