"""Tests for reconnect, fallback, and client-initiated connection replacement.

Behavioral tests use a real :class:`PhotonConnector` and :class:`MockCache`
through a :class:`MockTcpProxy`, which can break connections with no request in
flight. Tests that only exercise the session state machine use an autospec
connector double.
"""

from __future__ import annotations

import time
from concurrent.futures import ThreadPoolExecutor

import pytest

from photon.connection import PhotonConnector
from photon.exceptions import (
    PhotonCacheUnavailable,
    PhotonConnectionError,
    PhotonConnectionLost,
    PhotonError,
)
from photon.models import PhotonRequest, PhotonResponse, RequestMetadata
from photon.policy import ReconnectPolicy
from photon.session import PhotonSession, SessionState
from tests.mock import (
    DEFAULT_RESOLVED_CONFIG,
    MockCache,
    MockTcpProxy,
    Reply,
    mock_connector,
)

# Fast enough that a test does not wait on backoff, slow enough that ordering stays observable.
FAST = ReconnectPolicy(base_delay=0.001, max_delay=0.01, jitter=0.0)

_STATE_TIMEOUT = 5.0  # generous: these waits only run long when something is actually broken


def a_request(body: bytes = b'{"TableName": "widgets"}') -> PhotonRequest:
    """Return a canonical cacheable DynamoDB GetItem request."""
    return PhotonRequest(
        method="POST",
        url="https://dynamodb.us-east-1.amazonaws.com/",
        headers={"content-type": "application/x-amz-json-1.0"},
        body=body,
        metadata=RequestMetadata(service="dynamodb", operation="GetItem"),
    )


def wait_for_state(session: PhotonSession, state: SessionState,
                   timeout: float = _STATE_TIMEOUT) -> None:
    """Wait until the session reaches ``state``.

    Only use this for states the session will remain in. ``RECONNECTING`` is
    transient during normal recovery, so use :func:`wait_for_reconnect` instead.
    """
    deadline = time.monotonic() + timeout
    while session.state is not state and time.monotonic() < deadline:
        time.sleep(0.002)
    assert session.state is state, f"expected {state}, still {session.state}"


def wait_for_reconnect(r: "_Rig", connections: int, timeout: float = _STATE_TIMEOUT) -> None:
    """Wait until ``connections`` have been created and the session is alive.

    Connection count is monotonic, so unlike ``RECONNECTING`` it cannot be
    missed while polling.
    """
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if r.connector_count >= connections and r.session.state is SessionState.ALIVE:
            return
        time.sleep(0.002)
    raise AssertionError(
        f"expected {connections} connection(s) and ALIVE; "
        f"have {r.connector_count} and {r.session.state}"
    )


def config_routing(*operations: str) -> dict:
    """Return a config that routes the given DynamoDB operations."""
    return {
        "version": "1.0",
        "refresh_interval": 60,
        "predicates": [
            {
                "expr": "service == 'dynamodb' && operation in ops",
                "vars": {"ops": list(operations)},
            }
        ],
    }


class _Rig:
    """A cache, proxy, and session wired together for reconnect tests."""

    def __init__(self, *, policy: ReconnectPolicy = FAST, config: dict | None = None,
                 overrides: dict | None = None, **cache_kwargs) -> None:
        self.cache = MockCache(config=config, **cache_kwargs)
        self.cache.start()
        self.proxy = MockTcpProxy(self.cache.host, self.cache.port)
        self.connector_count = 0
        self.auth_count = 0

        def auth_provider() -> tuple[str, str]:
            # Counts as a proxy for "did the reconnect resolve fresh credentials?", which is the
            # property that matters: a stale token is how a new connection fails 12 hours later.
            self.auth_count += 1
            return "test-user", f"token-{self.auth_count}"

        def factory() -> PhotonConnector:
            self.connector_count += 1
            return PhotonConnector(
                self.proxy.host, self.proxy.port, tls=False,
                auth_provider=auth_provider, request_timeout=0.5,
            )

        self.factory = factory
        self.session = PhotonSession(factory, overrides=overrides, policy=policy)

    def close(self) -> None:
        self.session.close()
        self.proxy.stop()
        self.cache.stop()


@pytest.fixture
def rig():
    rigs: list[_Rig] = []

    def build(**kwargs) -> _Rig:
        built = _Rig(**kwargs)
        rigs.append(built)
        return built

    yield build
    for built in rigs:
        built.close()


def test_reconnect_after_drop_serves_next_request(rig):
    """A dropped connection is replaced in the background, and requests work again."""
    r = rig()
    assert r.session.request(a_request())

    r.proxy.disconnect_clients()
    wait_for_reconnect(r, connections=2)

    response = r.session.request(a_request())
    assert isinstance(response, PhotonResponse)
    assert response.status_code == 200


def test_reconnect_resolves_fresh_credentials_and_repeats_the_handshake(rig):
    """A reconnect creates a new connector with fresh credentials and configuration."""
    r = rig()
    assert (r.connector_count, r.auth_count) == (1, 1)
    assert r.cache.photon_command_log.count("photon.config") == 1

    r.proxy.disconnect_clients()
    wait_for_reconnect(r, connections=2)

    assert r.auth_count == 2
    assert r.cache.photon_command_log.count("auth") == 2
    assert r.cache.photon_command_log.count("photon.config") == 2


def test_reconnect_is_single_flight_under_concurrent_failures(rig):
    """Concurrent failures trigger one reconnect, not one reconnect per caller.

    The test verifies the single-flight behavior without depending on its locking
    implementation.
    """
    r = rig()
    r.proxy.disconnect_clients()

    with ThreadPoolExecutor(max_workers=8) as pool:
        # Some of these raise (they found the dead connector) and some return None (they arrived
        # after it was retired). Both are correct; the assertion is about connection count.
        futures = [pool.submit(_swallow_photon_error, r.session, a_request()) for _ in range(8)]
        for future in futures:
            future.result(timeout=_STATE_TIMEOUT)

    wait_for_reconnect(r, connections=2)
    assert r.connector_count == 2, "concurrent failures must share one reconnect"
    assert r.proxy.accepted_connections == 2


def _swallow_photon_error(session: PhotonSession, request: PhotonRequest):
    try:
        return session.request(request)
    except (PhotonError, PhotonCacheUnavailable):
        pass


def test_non_photon_error_during_reconnect_is_retried(rig):
    """A failed reconnect attempt is retried even when setup raises a non-Photon exception.

    Reconnect must not strand the session because of an unexpected setup error.
    """
    r = rig()
    # The proxy now answers with a config that makes predicate compilation raise.
    r.cache._config = {"version": "1.0", "predicates": [{"expr": "true", "vars": {"n": 2 ** 200}}]}
    r.proxy.disconnect_clients()

    # Attempts keep coming, rather than stopping at the first ValueError.
    _wait_until(lambda: r.connector_count >= 4)
    assert r.session.state is SessionState.RECONNECTING

    r.cache._config = DEFAULT_RESOLVED_CONFIG      # a config that compiles
    wait_for_state(r.session, SessionState.ALIVE)
    assert r.session.request(a_request())


def test_reconnect_recompiles_predicates_from_the_new_config(rig):
    """Requests use predicates from the newly resolved configuration."""
    r = rig(config=config_routing("GetItem"))
    assert r.session.request(a_request())

    # The proxy now answers with a config that no longer routes GetItem.
    r.cache._config = config_routing("PutItem")
    r.proxy.disconnect_clients()
    wait_for_reconnect(r, connections=2)

    assert not r.session.request(a_request())


def test_disconnect_notification_starts_a_reconnect_without_any_request(rig):
    """A disconnected idle connection is rebuilt without waiting for a request."""
    r = rig()
    r.proxy.disconnect_clients()

    wait_for_reconnect(r, connections=2)


def test_in_flight_request_is_not_replayed(rig):
    """A request that fails in flight is not replayed on the replacement connection.

    Photon cannot know whether the request reached the cache, so replaying it
    could execute it twice.
    """
    r = rig(failure="drop")   # the cache drops the connection instead of replying

    with pytest.raises(PhotonConnectionLost):
        r.session.request(a_request(b'{"TableName": "widgets", "marker": 1}'))

    wait_for_reconnect(r, connections=2)
    markers = [req.body for req in r.cache.requests if b"marker" in req.body]
    assert len(markers) == 1, "the failed request must not be replayed onto the new connection"


def test_request_that_discovers_the_failure_raises_rather_than_passing_through(rig):
    """A request that reaches a dead connection raises instead of falling back.

    Pass-through is only safe when Photon has not sent the request.
    """
    r = rig(policy=ReconnectPolicy(fallback_duration=None, base_delay=0.001, max_delay=0.01,
                                   jitter=0.0), failure="drop")
    with pytest.raises(PhotonConnectionLost):
        r.session.request(a_request())


def test_positional_correlation_survives_a_reconnect(rig):
    """Concurrent requests remain correlated with their replies across a reconnect.

    The wire protocol correlates replies by position, so a connection swap must not
    mix replies between callers.
    """
    r = rig(response_fn=lambda received: Reply.ok(body=received.body))

    def call(marker: int):
        body = b'{"TableName": "widgets", "marker": %d}' % marker
        response = _swallow_photon_error(r.session, a_request(body))
        return marker, response

    with ThreadPoolExecutor(max_workers=8) as pool:
        first = [pool.submit(call, i) for i in range(4)]
        r.proxy.disconnect_clients()
        wait_for_reconnect(r, connections=2)
        second = [pool.submit(call, i) for i in range(4, 8)]
        results = [f.result(timeout=_STATE_TIMEOUT) for f in first + second]

    for marker, response in results:
        if response is None:
            continue  # passed through or declined while reconnecting; nothing was correlated
        assert b'"marker": %d' % marker in response.body, "reply landed on the wrong caller"


def test_unbounded_window_passes_through_indefinitely(rig):
    """The default unbounded window passes disconnected requests through."""
    r = rig(policy=ReconnectPolicy(fallback_duration=None, base_delay=0.001, max_delay=0.01,
                                   jitter=0.0))
    r.proxy.disable_forwarding()      # every reconnect attempt now fails
    _wait_for_reconnecting(r)

    for _ in range(5):
        assert not r.session.request(a_request())


def test_finite_window_passes_through_then_escalates(rig):
    """A finite fallback window passes through until expiry, then raises."""
    # A whole second, because the window is configured in integer seconds. Slower than the rest of
    # this file, and the only honest way to exercise a real expiry.
    r = rig(policy=ReconnectPolicy(fallback_duration=1, base_delay=0.001, max_delay=0.01,
                                   jitter=0.0))
    r.proxy.disable_forwarding()
    _wait_for_reconnecting(r)

    assert not r.session.request(a_request())    # inside the window

    time.sleep(1.05)
    with pytest.raises(PhotonCacheUnavailable):
        r.session.request(a_request())


def test_zero_window_escalates_on_the_first_request(rig):
    """A zero-length fallback window raises on the first request."""
    r = rig(policy=ReconnectPolicy(fallback_duration=0, base_delay=0.001, max_delay=0.01,
                                   jitter=0.0))
    r.proxy.disable_forwarding()
    _wait_for_reconnecting(r)

    with pytest.raises(PhotonCacheUnavailable):
        r.session.request(a_request())


def test_escalation_stops_once_reconnected(rig):
    """Once reconnected, requests are served normally again."""
    r = rig(policy=ReconnectPolicy(fallback_duration=0, base_delay=0.001, max_delay=0.01,
                                   jitter=0.0))
    r.proxy.disable_forwarding()
    _wait_for_reconnecting(r)
    with pytest.raises(PhotonCacheUnavailable):
        r.session.request(a_request())

    r.proxy.enable_forwarding()
    wait_for_state(r.session, SessionState.ALIVE)
    assert r.session.request(a_request())


def test_reconnect_finishing_mid_request_does_not_escalate(rig):
    """A request does not escalate if reconnect completes before fallback is checked.

    The request should observe the newly restored connection rather than treating
    the reconnect window as an outage.
    """
    r = rig(policy=ReconnectPolicy(fallback_duration=None, base_delay=0.001, max_delay=0.01,
                                   jitter=0.0))
    r.proxy.disable_forwarding()
    _wait_for_reconnecting(r)

    check_window = r.session._ensure_fallback_allowed

    def reconnect_first(request: PhotonRequest) -> None:
        # Stand in for the reconnect thread publishing between the two reads.
        r.proxy.enable_forwarding()
        wait_for_state(r.session, SessionState.ALIVE)
        check_window(request)

    r.session._ensure_fallback_allowed = reconnect_first
    assert r.session.request(a_request()) is None


def test_fallback_window_opens_before_the_connector_is_cleared(rig):
    """The fallback deadline is set before the connector is cleared.

    Otherwise, a request could observe no connector with the previous outage's
    deadline and incorrectly escalate during a new, unbounded fallback window.
    """
    r = rig()
    connector_when_window_read: list[object] = []

    class _WatchedPolicy:
        """Records the connector state when the fallback window is read."""

        def __init__(self, policy: ReconnectPolicy) -> None:
            self._policy = policy

        def __getattr__(self, name: str):
            return getattr(self._policy, name)

        @property
        def fallback_window(self) -> float:
            connector_when_window_read.append(r.session._connector)
            return self._policy.fallback_window

    r.session._policy = _WatchedPolicy(r.session._policy)
    r.proxy.disconnect_clients()
    # Wait for the observation, not for RECONNECTING: with this rig's fast policy the reconnect can
    # finish before the state is ever sampled, which made this test flake under load.
    _wait_until(lambda: connector_when_window_read)
    assert connector_when_window_read[0] is not None


def test_reconnect_probes_indefinitely(rig):
    """Reconnect attempts continue until the connection is restored."""
    r = rig()
    r.proxy.disable_forwarding()
    _wait_for_reconnecting(r)

    # Far more than any plausible attempt cap, including PR #20's default of 3.
    _wait_until(lambda: r.connector_count >= 10)
    assert r.session.state is SessionState.RECONNECTING

    r.proxy.enable_forwarding()
    wait_for_state(r.session, SessionState.ALIVE)
    assert r.session.request(a_request())


def test_declined_by_predicate_is_not_an_escalation(rig):
    """A request declined by its predicate remains a normal pass-through."""
    r = rig(policy=ReconnectPolicy(fallback_duration=0, base_delay=0.001, max_delay=0.01,
                                   jitter=0.0), config=config_routing("PutItem"))
    r.proxy.disable_forwarding()
    _wait_for_reconnecting(r)

    assert not r.session.request(a_request())   # GetItem is not routed by this config


def _wait_for_reconnecting(r: _Rig) -> None:
    """Disconnect the current connection and wait for reconnecting to begin."""
    r.proxy.disconnect_clients()
    wait_for_state(r.session, SessionState.RECONNECTING)


def test_backoff_doubles_to_the_ceiling_and_stays_there():
    """Backoff doubles until ``max_delay``, then remains at the ceiling."""
    policy = ReconnectPolicy(base_delay=0.1, max_delay=3.2, jitter=0.0)
    delays = [policy.delay_for(n) for n in range(1, 10)]
    assert delays == pytest.approx([0.1, 0.2, 0.4, 0.8, 1.6, 3.2, 3.2, 3.2, 3.2])


def test_every_delay_is_jittered_including_the_repeated_tail():
    """Jitter is applied to every delay, including attempts after the cap."""
    policy = ReconnectPolicy(base_delay=0.1, max_delay=3.2, jitter=0.2)
    tail = [policy.delay_for(50) for _ in range(20)]

    assert len(set(tail)) > 1, "the tail must be jittered, not a fixed pulse"
    assert all(3.2 * 0.8 <= delay <= 3.2 * 1.2 for delay in tail)


def test_policy_rejects_incoherent_settings():
    """Invalid policy settings are rejected at construction."""
    with pytest.raises(PhotonError):
        ReconnectPolicy(base_delay=0)
    with pytest.raises(PhotonError):
        ReconnectPolicy(base_delay=1.0, max_delay=0.5)
    with pytest.raises(PhotonError):
        ReconnectPolicy(jitter=1.0)
    with pytest.raises(PhotonError):
        ReconnectPolicy(fallback_duration="60")
    with pytest.raises(PhotonError):
        ReconnectPolicy(fallback_duration=True)
    with pytest.raises(PhotonError):
        ReconnectPolicy(fallback_duration=0.5)


@pytest.mark.parametrize("duration,expected", [(None, float("inf")), (-1, float("inf")),
                                               (0, 0.0), (2, 2.0)])
def test_fallback_window_normalizes_both_unbounded_spellings(duration, expected):
    """``None`` and negative values normalize to infinity; ``0`` remains zero."""
    assert ReconnectPolicy(fallback_duration=duration).fallback_window == expected


def test_close_stops_reconnecting(rig):
    """Closing the session stops further reconnect attempts."""
    r = rig()
    r.proxy.disable_forwarding()
    _wait_for_reconnecting(r)
    _wait_until(lambda: r.connector_count >= 2)

    r.session.close()
    settled = r.connector_count
    time.sleep(0.1)

    assert r.session.state is SessionState.CLOSED
    assert r.connector_count == settled, "reconnecting must stop when the session closes"


def test_close_is_prompt_during_a_long_backoff(rig):
    """Closing the session interrupts a backoff wait."""
    r = rig(policy=ReconnectPolicy(base_delay=30.0, max_delay=30.0, jitter=0.0))
    r.proxy.disable_forwarding()
    _wait_for_reconnecting(r)
    _wait_until(lambda: r.connector_count >= 2)

    started = time.monotonic()
    r.session.close()
    assert time.monotonic() - started < 2.0


def test_construction_still_fails_loudly():
    """An initial connection failure is raised instead of silently falling back.

    Unlike reconnects, construction does not enter an indefinite recovery loop.
    """
    connector = mock_connector()
    connector.authenticate.side_effect = PhotonConnectionError("IAM AUTH rejected: WRONGPASS")

    with pytest.raises(PhotonConnectionError):
        PhotonSession(lambda: connector, policy=ReconnectPolicy(connect_attempts=1))
    connector.close.assert_called_once_with()


def test_creating_a_session_retries_before_giving_up():
    """An initial connection failure is retried up to the configured attempt limit.

    Unlike reconnects, session creation must either establish a connection or raise, so the caller
    can handle a startup failure.
    """
    built = []

    def factory():
        conn = mock_connector()
        built.append(conn)
        if len(built) < 3:
            conn.authenticate.side_effect = PhotonConnectionError("cache unreachable")
        return conn

    session = PhotonSession(factory, policy=_fast(connect_attempts=3))
    try:
        assert len(built) == 3
        assert session.state is SessionState.ALIVE
        # Each failed candidate was closed, so no half-open connection was left behind.
        assert built[0].close.called and built[1].close.called
    finally:
        session.close()


def test_creating_a_session_gives_up_after_connect_attempts():
    """Session creation raises after exhausting the configured connection attempts."""
    built = []

    def factory():
        conn = mock_connector()
        conn.authenticate.side_effect = PhotonConnectionError("cache unreachable")
        built.append(conn)
        return conn

    with pytest.raises(PhotonConnectionError):
        PhotonSession(factory, policy=_fast(connect_attempts=2))
    assert len(built) == 2


def test_creating_a_session_stops_retrying_at_the_deadline():
    """The connection deadline stops retries even when attempts remain.

    Session creation runs in the caller's startup path, so its total retry time must be bounded
    independently of the attempt limit.
    """
    built = []

    def factory():
        conn = mock_connector()
        conn.authenticate.side_effect = PhotonConnectionError("cache unreachable")
        built.append(conn)
        return conn

    # Five attempts allowed, but the first backoff alone would overrun the budget.
    policy = ReconnectPolicy(connect_attempts=5, connection_timeout=0.01,
                             base_delay=0.05, max_delay=0.05, jitter=0.0)
    with pytest.raises(PhotonConnectionError):
        PhotonSession(factory, policy=policy)

    assert len(built) == 1, "the deadline must stop retrying before connect_attempts does"


def test_policy_rejects_a_non_positive_connection_timeout():
    with pytest.raises(PhotonError):
        ReconnectPolicy(connection_timeout=0)


def _fast(**kwargs) -> ReconnectPolicy:
    """A policy whose delays are too short to slow a test down."""
    return ReconnectPolicy(base_delay=0.001, max_delay=0.002, jitter=0.0, **kwargs)


def _wait_until(predicate, timeout: float = _STATE_TIMEOUT) -> None:
    deadline = time.monotonic() + timeout
    while not predicate() and time.monotonic() < deadline:
        time.sleep(0.002)
    assert predicate(), "condition not reached within timeout"
