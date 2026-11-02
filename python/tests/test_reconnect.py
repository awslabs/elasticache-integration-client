"""Tests for reconnect, fallback, and client-initiated connection replacement.

Behavioral tests use a real :class:`ElastiCacheIntegrationsConnector` and :class:`MockCache`
through a :class:`MockTcpProxy`, which can break connections with no request in
flight. Tests that only exercise the session state machine use an autospec
connector double.
"""

from __future__ import annotations

import socket
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import pytest

from aws_elasticache_integration_client import connection_manager
from aws_elasticache_integration_client import session as session_module
from aws_elasticache_integration_client.config_policy import ConfigPolicy
from aws_elasticache_integration_client.connection import ElastiCacheIntegrationsConnector
from aws_elasticache_integration_client.connection_manager import ConnectionState
from aws_elasticache_integration_client.exceptions import (
    ElastiCacheIntegrationsCacheUnavailable,
    ElastiCacheIntegrationsConfigError,
    ElastiCacheIntegrationsConnectionError,
    ElastiCacheIntegrationsConnectionLost,
    ElastiCacheIntegrationsError,
    ElastiCacheIntegrationsOverridesRejected,
)
from aws_elasticache_integration_client.models import (
    ElastiCacheIntegrationsRequest,
    ElastiCacheIntegrationsResponse,
    RequestMetadata,
)
from aws_elasticache_integration_client.reconnect_policy import ReconnectPolicy
from aws_elasticache_integration_client.session import ElastiCacheIntegrationsSession
from tests.mock import (
    DEFAULT_RESOLVED_CONFIG,
    MockCache,
    MockTcpProxy,
    mock_connector,
)

# Fast enough that a test does not wait on backoff, slow enough that ordering stays observable.
FAST = ReconnectPolicy(base_delay=0.001, max_delay=0.01, jitter=0.0)

_STATE_TIMEOUT = 5.0  # generous: these waits only run long when something is actually broken
_POLL_INTERVAL = 0.002  # how often a wait re-checks


def test_invalidate_connection_enters_reconnecting_before_close():
    connector = mock_connector()
    manager = connection_manager.ConnectionManager(lambda: connector, lambda value: None, FAST)
    manager._connector = connector
    manager._state = ConnectionState.ALIVE

    manager.invalidate_connection(connector)

    assert manager.state is ConnectionState.RECONNECTING
    assert manager.connector is None
    assert manager._reconnect_needed.is_set()
    connector.close.assert_called_once_with()


def get_item_request(body: bytes = b'{"TableName": "widgets"}') -> ElastiCacheIntegrationsRequest:
    """A DynamoDB GetItem — the canonical request every config in this file routes to the cache."""
    return ElastiCacheIntegrationsRequest(
        method="POST",
        url="https://dynamodb.us-east-1.amazonaws.com/",
        headers={"content-type": "application/x-amz-json-1.0"},
        body=body,
        metadata=RequestMetadata(service="dynamodb", operation="GetItem"),
    )


class _FakeClock:
    """A monotonic clock the test moves by hand, to reach a deadline without waiting for it.

    Substituted for the ``time`` module inside :mod:`aws_elasticache_integration_client.connection_manager`, which reads
    ``monotonic()`` to open the fallback window and to check it. ``sleep`` stays real: the reconnect
    loop still has to back off.
    """

    def __init__(self) -> None:
        self.now = time.monotonic()

    def monotonic(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds

    @staticmethod
    def sleep(seconds: float) -> None:
        time.sleep(seconds)


class _RealConnectors:
    """A connector factory pointed at one address, remembering every connector it built.

    ``built`` is what lets a test assert on connections the session owns privately — how many were
    attempted, and whether they were closed. Extra keyword arguments go to each
    :class:`ElastiCacheIntegrationsConnector`, e.g. a long ``config_timeout`` to keep a handshake from timing out on
    its own.
    """

    def __init__(self, host: str, port: int, **connector_kwargs) -> None:
        self._host = host
        self._port = port
        self._connector_kwargs = connector_kwargs
        self.built: list[ElastiCacheIntegrationsConnector] = []

    def __call__(self) -> ElastiCacheIntegrationsConnector:
        connector = ElastiCacheIntegrationsConnector(
            self._host,
            self._port,
            tls=False,
            auth_provider=lambda: ("test-user", "token"),
            **self._connector_kwargs,
        )
        self.built.append(connector)
        return connector


def wait_for(predicate, timeout: float = _STATE_TIMEOUT, describe=None) -> None:
    """Wait until ``predicate()`` holds, failing the test if it has not within ``timeout``.

    Polls, rather than waiting on an event: what these tests watch — the connection state, the
    number of connections built — lives inside the client, which publishes no change notification.

    ``describe`` is called only on failure, so a message can report what was actually observed. Every
    ``wait_for*`` in this file fails on its own rather than returning a verdict a caller could forget
    to check.
    """
    deadline = time.monotonic() + timeout
    while not predicate():
        if time.monotonic() >= deadline:
            raise AssertionError(describe() if describe else "condition not reached in time")
        time.sleep(_POLL_INTERVAL)


def wait_for_state(
    session: ElastiCacheIntegrationsSession, state: ConnectionState, timeout: float = _STATE_TIMEOUT
) -> None:
    """Wait until the session reaches ``state``.

    Only use this for states the session will remain in. ``RECONNECTING`` is
    transient during normal recovery, so use :func:`wait_for_reconnect` instead.
    """
    wait_for(
        lambda: session.state is state, timeout, lambda: f"expected {state}, still {session.state}"
    )


def wait_for_reconnect(
    r: "_ReconnectRig", connection_id: int, timeout: float = _STATE_TIMEOUT
) -> None:
    """Wait until connection number ``connection_id`` is built and serving.

    Connection 1 is the one the session opened; a reconnect makes 2, and so on. The count is
    monotonic, so unlike the transient ``RECONNECTING`` state it cannot be missed while polling.
    """
    wait_for(
        lambda: r.connector_count >= connection_id and r.session.state is ConnectionState.ALIVE,
        timeout,
        lambda: (
            f"expected connection {connection_id} and ALIVE; "
            f"have {r.connector_count} and {r.session.state}"
        ),
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


class _ReconnectRig:
    """A live session whose cache sits behind a proxy the test can cut.

    Bundles the three things a reconnect test drives — the cache, the proxy in front of it, and the
    session — plus counts of the connectors and auth tokens the session has asked for, which is how
    these tests observe that a reconnect happened at all.
    """

    def __init__(
        self,
        *,
        policy: ReconnectPolicy = FAST,
        config: dict | None = None,
        overrides: dict | None = None,
        config_policy: ConfigPolicy | None = None,
        **cache_kwargs,
    ) -> None:
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

        def factory() -> ElastiCacheIntegrationsConnector:
            self.connector_count += 1
            return ElastiCacheIntegrationsConnector(
                self.proxy.host,
                self.proxy.port,
                tls=False,
                auth_provider=auth_provider,
                request_timeout=0.5,
            )

        self.factory = factory
        self.session = ElastiCacheIntegrationsSession(
            factory,
            overrides=overrides,
            policy=policy,
            config_policy=config_policy or ConfigPolicy(),
        )

    def close(self) -> None:
        self.session.close()
        self.proxy.stop()
        self.cache.stop()


@pytest.fixture
def rig():
    """Build :class:`_ReconnectRig`s and close each one when the test ends.

    Yields a factory rather than a rig so a test can pass its own policy or cache behaviour, and so
    teardown still covers a rig built halfway through a test.
    """
    rigs: list[_ReconnectRig] = []

    def build(**kwargs) -> _ReconnectRig:
        built = _ReconnectRig(**kwargs)
        rigs.append(built)
        return built

    yield build
    for built in rigs:
        built.close()


def test_reconnect_after_drop_serves_next_request(rig):
    """A dropped connection is replaced in the background, and requests work again."""
    r = rig()
    assert r.session.request(get_item_request())

    r.proxy.disconnect_clients()
    wait_for_reconnect(r, connection_id=2)

    response = r.session.request(get_item_request())
    assert isinstance(response, ElastiCacheIntegrationsResponse)
    assert response.status_code == 200


def test_reconnect_resolves_fresh_credentials_and_repeats_the_handshake(rig):
    """A reconnect creates a new connector with fresh credentials and configuration."""
    r = rig()
    assert (r.connector_count, r.auth_count) == (1, 1)
    assert r.cache.cache_command_log.count("aws.config") == 1

    r.proxy.disconnect_clients()
    wait_for_reconnect(r, connection_id=2)

    assert r.auth_count == 2
    assert r.cache.cache_command_log.count("hello") == 2
    assert r.cache.cache_command_log.count("aws.config") == 2


def test_reconnect_is_single_flight_under_concurrent_failures(rig):
    """Concurrent failures trigger one reconnect, not one reconnect per caller.

    The test verifies the single-flight behavior without depending on its locking
    implementation.
    """
    r = rig()
    assert r.connector_count == 1, "the session starts on connection 1"
    r.proxy.disconnect_clients()

    with ThreadPoolExecutor(max_workers=8) as pool:
        # Some of these raise (they found the dead connector) and some return None (they arrived
        # after it was retired). Both are correct; the assertion is about connection count.
        futures = [
            pool.submit(_swallow_cache_error, r.session, get_item_request()) for _ in range(8)
        ]
        for future in futures:
            future.result(timeout=_STATE_TIMEOUT)

    wait_for_reconnect(r, connection_id=2)
    assert r.connector_count == 2, "concurrent failures must share one reconnect"
    assert r.proxy.accepted_connections == 2


def _swallow_cache_error(
    session: ElastiCacheIntegrationsSession, request: ElastiCacheIntegrationsRequest
):
    try:
        return session.request(request)
    except (ElastiCacheIntegrationsError, ElastiCacheIntegrationsCacheUnavailable):
        pass


def test_non_elasticache_integrations_error_during_reconnect_is_retried(rig):
    """A failed reconnect attempt is retried even when setup raises a non-ElastiCacheIntegrations exception.

    Reconnect must not strand the session because of an unexpected setup error.
    """
    r = rig()
    config_before, predicates_before = r.session._config, r.session._predicates
    # From here the cache answers with a config ElastiCacheIntegrations cannot use: 2**200 overflows what CEL can
    # hold, so compile_predicates raises ValueError — a non-ElastiCacheIntegrations error, mid-connection.
    r.cache.set_config({"version": "1.0", "predicates": [{"expr": "true", "vars": {"n": 2**200}}]})
    r.proxy.disconnect_clients()

    # Attempts keep coming, rather than stopping at the first ValueError.
    wait_for_attempts(r, at_least=4)
    assert r.session.state is ConnectionState.RECONNECTING
    # A configure that fails after resolving the config publishes nothing, so the session never
    # routes by a config paired with the predicates of a different one.
    assert r.session._config is config_before
    assert r.session._predicates is predicates_before

    r.cache.set_config(DEFAULT_RESOLVED_CONFIG)  # a config that compiles
    wait_for_state(r.session, ConnectionState.ALIVE)
    assert r.session.request(get_item_request())


def test_reconnect_recompiles_predicates_from_the_new_config(rig):
    """Requests use predicates from the newly resolved configuration."""
    r = rig(config=config_routing("GetItem"))
    assert r.session.request(get_item_request())

    # The proxy now answers with a config that no longer routes GetItem.
    r.cache.set_config(config_routing("PutItem"))
    r.proxy.disconnect_clients()
    wait_for_reconnect(r, connection_id=2)

    assert not r.session.request(get_item_request())


def test_a_connection_that_dies_as_it_is_published_is_still_replaced(rig):
    """A disconnect that lands while the replacement is being published is not lost.

    The reconnect signal is cleared before the connection is examined rather than after it has been
    rebuilt, so a connector that dies in that window leaves the flag standing and the loop finds work
    to do. Clearing it afterwards would swallow the signal and strand the session on a dead
    connection, because a connector notifies its owner once and never again.

    Reaches into ``_finish_reconnect`` because that window is a few instructions wide; killing the
    connection from outside would land on either side of it at random.
    """
    r = rig()
    manager = r.session._connection_manager
    publish = manager._finish_reconnect
    killed_once = threading.Event()

    def kill_as_it_is_published(candidate, attempt):
        published = publish(candidate, attempt)
        if published and not killed_once.is_set():
            killed_once.set()
            candidate.close()  # fires the disconnect before the loop clears the flag
        return published

    manager._finish_reconnect = kill_as_it_is_published
    r.proxy.disconnect_clients()

    # The second connection died on arrival, so a third one has to follow it.
    wait_for_reconnect(r, connection_id=3)
    assert r.session.request(get_item_request())


def test_disconnect_notification_starts_a_reconnect_without_any_request(rig):
    """An idle connection that dies is rebuilt without any request to discover it.

    The rebuild is driven by the connector's disconnect notification, not by a request failing, so
    a cache that drops an idle connection is recovered before the next request arrives.
    """
    r = rig()
    r.proxy.disconnect_clients()

    wait_for_reconnect(r, connection_id=2)
    assert r.cache.requests == [], "nothing was sent, so only the notification could have driven it"


def test_a_stale_config_is_replaced_by_a_reconnect(rig, monkeypatch):
    """A config left unconfirmed past its staleness window is fixed by replacing the connection.

    The session drops a connection whose config it no longer trusts, which the manager cannot tell
    from any other lost connection, so the ordinary reconnect rebuilds it — and bringing one up
    resolves a config, which is what makes the drop a repair rather than only an interruption. The
    clock is moved rather than waited out, so the window is reached instantly.
    """
    clock = _FakeClock()
    monkeypatch.setattr(session_module, "time", clock)
    r = rig(config_policy=ConfigPolicy(staleness_duration=1))
    r.cache.no_arg_config_reply = {"version": "1.0"}  # no refresh_interval: the refresh fails

    clock.advance(1)
    assert r.session.config_stale

    r.session._refresh_once()  # the refresh fails past the window, so the connection is dropped

    wait_for_reconnect(r, connection_id=2)
    assert not r.session.config_stale, "the replacement connection resolved a config as it came up"
    assert r.session.request(get_item_request())


def test_an_in_flight_request_raises_and_is_never_replayed(rig):
    """A request whose connection dies under it raises, and is not retried on the replacement.

    The connection is healthy when the request goes out — the cache drops it instead of replying — so
    the request was sent and its fate is unknown. ElastiCacheIntegrations neither falls back to the origin (which
    could apply it twice) nor replays it onto the new connection, even though the fallback window is
    wide open; it raises and lets the SDK decide.

    That the send itself raises is the connector's own contract, covered in ``test_connection.py``.
    What only this layer can show is the marker never reaching the cache a second time: the connector
    cannot reconnect, so nothing below the manager can replay anything.
    """
    r = rig(failure="drop")  # the cache drops the connection instead of replying

    with pytest.raises(ElastiCacheIntegrationsConnectionLost):
        r.session.request(get_item_request(b'{"TableName": "widgets", "marker": 1}'))

    wait_for_reconnect(r, connection_id=2)
    markers = [req.body for req in r.cache.requests if b"marker" in req.body]
    assert len(markers) == 1, "the failed request must not be replayed onto the new connection"


def test_unbounded_window_passes_through_indefinitely(rig):
    """The default unbounded window passes disconnected requests through."""
    r = rig(
        policy=ReconnectPolicy(
            fallback_duration=None,
            base_delay=0.001,
            max_delay=0.01,
            jitter=0.0,
            connection_timeout=0.01,
        )
    )
    set_as_reconnecting(r)

    for _ in range(5):
        assert not r.session.request(get_item_request())


def test_finite_window_passes_through_then_escalates(rig, monkeypatch):
    """A finite fallback window passes requests through until it expires, then raises.

    The window is configured in whole seconds, so the expiry is reached by moving the clock the
    manager reads rather than by sleeping through it: the test is instant, and it lands exactly on
    the boundary instead of somewhere after it.
    """
    clock = _FakeClock()
    monkeypatch.setattr(connection_manager, "time", clock)
    r = rig(
        policy=ReconnectPolicy(
            fallback_duration=1,
            base_delay=0.001,
            max_delay=0.01,
            jitter=0.0,
            connection_timeout=0.01,
        )
    )
    set_as_reconnecting(r)

    assert not r.session.request(get_item_request())  # inside the window

    # Exactly onto the deadline, which is already outside the window. The window starts when the
    # wait for a reconnect ends, so both are added the way the manager adds them.
    clock.advance(0.01)
    clock.advance(1)
    with pytest.raises(ElastiCacheIntegrationsCacheUnavailable):
        r.session.request(get_item_request())


def test_zero_window_escalates_until_reconnected(rig):
    """A zero-length fallback window raises on the first request, and stops once reconnected.

    ``fallback_duration=0`` is the strictest setting: no request may be served by the origin behind
    the application's back, so every routed request raises while the cache is unreachable — and only
    while it is unreachable.
    """
    r = rig(
        policy=ReconnectPolicy(
            fallback_duration=0,
            base_delay=0.001,
            max_delay=0.01,
            jitter=0.0,
            connection_timeout=0.01,
        )
    )
    set_as_reconnecting(r)

    with pytest.raises(ElastiCacheIntegrationsCacheUnavailable):
        r.session.request(get_item_request())

    r.proxy.allow_connections()
    wait_for_state(r.session, ConnectionState.ALIVE)
    assert r.session.request(get_item_request())


def test_reconnect_probes_indefinitely(rig):
    """Reconnect attempts continue until the connection is restored."""
    r = rig()
    set_as_reconnecting(r)

    # Far more than any plausible attempt cap, and far more than the 3 that bound startup.
    wait_for_attempts(r, at_least=10)
    assert r.session.state is ConnectionState.RECONNECTING

    r.proxy.allow_connections()
    wait_for_state(r.session, ConnectionState.ALIVE)
    assert r.session.request(get_item_request())


def test_a_declined_request_does_not_escalate_during_an_outage(rig):
    """A request no predicate matches passes through even while the cache is unreachable.

    Escalation is for requests ElastiCacheIntegrations would have served. One it declines is not ElastiCacheIntegrations's to fail, so
    the strictest possible window — ``fallback_duration=0`` — still lets it through to the origin.
    """
    r = rig(
        policy=ReconnectPolicy(fallback_duration=0, base_delay=0.001, max_delay=0.01, jitter=0.0),
        config=config_routing("PutItem"),
    )
    set_as_reconnecting(r)

    assert not r.session.request(get_item_request())  # GetItem is not routed by this config


def wait_for_attempts(r: _ReconnectRig, at_least: int) -> None:
    """Wait until the session has built ``at_least`` connections.

    A floor rather than an exact count: while the proxy refuses connections the reconnect loop keeps
    trying, so by the time a test looks the count has usually moved on again.
    """
    wait_for(
        lambda: r.connector_count >= at_least,
        describe=lambda: f"expected at least {at_least} connection(s); have {r.connector_count}",
    )


def set_as_reconnecting(r: _ReconnectRig) -> None:
    """Cut the connection and leave the session reconnecting.

    The proxy refuses new connections as well, so every reconnect attempt fails and the state stays
    put — which is why waiting for ``RECONNECTING`` here cannot miss it, unlike during a recovery.
    """
    r.proxy.disconnect_and_block()
    wait_for_state(r.session, ConnectionState.RECONNECTING)


def test_backoff_doubles_to_the_ceiling_and_stays_there():
    """Backoff doubles until ``max_delay``, then remains at the ceiling."""
    policy = ReconnectPolicy(base_delay=0.1, max_delay=3.2, jitter=0.0)
    delays = [policy.delay_for(n) for n in range(1, 10)]
    assert delays == pytest.approx([0.1, 0.2, 0.4, 0.8, 1.6, 3.2, 3.2, 3.2, 3.2])


def test_backoff_stays_at_the_ceiling_after_many_failures():
    """A very long run of failures keeps the ceiling instead of overflowing the float."""
    policy = ReconnectPolicy(base_delay=0.1, max_delay=3.2, jitter=0.0)
    assert policy.delay_for(1025) == pytest.approx(3.2)
    assert policy.delay_for(1_000_000) == pytest.approx(3.2)


def test_every_delay_is_jittered_including_the_repeated_tail():
    """Jitter is applied to every delay, including attempts after the cap."""
    policy = ReconnectPolicy(base_delay=0.1, max_delay=3.2, jitter=0.2)
    tail = [policy.delay_for(50) for _ in range(20)]

    assert len(set(tail)) > 1, "the tail must be jittered, not a fixed pulse"
    assert all(3.2 * 0.8 <= delay <= 3.2 * 1.2 for delay in tail)


def test_policy_rejects_incoherent_settings():
    """Invalid policy settings are rejected at construction."""
    with pytest.raises(ElastiCacheIntegrationsConfigError):
        ReconnectPolicy(base_delay=0)
    with pytest.raises(ElastiCacheIntegrationsConfigError):
        ReconnectPolicy(base_delay=1.0, max_delay=0.5)
    with pytest.raises(ElastiCacheIntegrationsConfigError):
        ReconnectPolicy(jitter=1.0)
    with pytest.raises(ElastiCacheIntegrationsConfigError):
        ReconnectPolicy(connection_timeout=0)
    with pytest.raises(ElastiCacheIntegrationsConfigError):
        ReconnectPolicy(fallback_duration="60")
    with pytest.raises(ElastiCacheIntegrationsConfigError):
        ReconnectPolicy(fallback_duration=True)
    with pytest.raises(ElastiCacheIntegrationsConfigError):
        ReconnectPolicy(fallback_duration=0.5)


def test_a_misconfigured_policy_is_catchable_either_way():
    """A configuration mistake answers to Python's name for it and to ElastiCacheIntegrations's."""
    with pytest.raises(ValueError):
        ReconnectPolicy(jitter=1.0)
    with pytest.raises(ElastiCacheIntegrationsError):
        ReconnectPolicy(jitter=1.0)


@pytest.mark.parametrize(
    "duration,expected", [(None, float("inf")), (-1, float("inf")), (0, 0.0), (2, 2.0)]
)
def test_fallback_window_normalizes_both_unbounded_spellings(duration, expected):
    """``None`` and negative values normalize to infinity; ``0`` remains zero."""
    assert ReconnectPolicy(fallback_duration=duration).fallback_window == expected


def test_close_stops_reconnecting(rig):
    """Closing the session stops further reconnect attempts."""
    r = rig()
    set_as_reconnecting(r)
    wait_for_attempts(r, at_least=2)

    r.session.close()
    settled = r.connector_count
    time.sleep(0.1)

    assert r.session.state is ConnectionState.CLOSED
    assert r.connector_count == settled, "reconnecting must stop when the session closes"


def test_close_is_prompt_during_a_long_backoff(rig):
    """close() interrupts a backoff wait instead of waiting it out.

    Asserted through the thread rather than the clock: the backoff here is 30s and ``close()`` joins
    the reconnect thread for 2s before giving up with a warning, so a wait it could not interrupt
    would leave that thread running once ``close()`` returned.
    """
    r = rig(policy=ReconnectPolicy(base_delay=30.0, max_delay=30.0, jitter=0.0))
    set_as_reconnecting(r)
    wait_for_attempts(r, at_least=2)

    r.session.close()
    assert not r.session._connection_manager._reconnect_thread.is_alive(), (
        "close() gave up on the reconnect thread instead of interrupting its backoff"
    )


def test_close_interrupts_a_connection_being_opened():
    """close() ends a reconnect that is blocked mid-handshake instead of waiting it out.

    The cache accepts the reconnect and then never answers AUTH, and the connector is given a 30s
    reply timeout so that step cannot end on its own. Closing the connection under the handshake is
    what frees the thread; the join inside close() is only a backstop for a thread that ignores it.
    """
    cache = MockCache()
    cache.start()
    proxy = MockTcpProxy(cache.host, cache.port)
    try:
        session = ElastiCacheIntegrationsSession(
            _RealConnectors(proxy.host, proxy.port, config_timeout=30.0), policy=_fast()
        )
        cache.failure, cache.failure_command = "hang", "hello"
        proxy.disconnect_clients()
        # The reconnect is now inside the handshake the cache will never answer.
        wait_for(lambda: cache.cache_command_log.count("hello") >= 2)

        started = time.monotonic()
        session.close()
        elapsed = time.monotonic() - started
    finally:
        proxy.stop()
        cache.stop()

    assert not [
        t for t in threading.enumerate() if t.name == "aws-elasticache-integration-client-reconnect"
    ], "close() returned with the reconnect thread still in the handshake"
    assert elapsed < 1.0, f"close() waited out the stalled handshake: {elapsed:.2f}s"


def test_init_still_fails_loudly():
    """An initial connection failure is raised instead of silently falling back.

    Unlike reconnects, construction does not enter an indefinite recovery loop.
    """
    # A double, so the failure is a rejected handshake specifically, and so `close` can be counted.
    connector = mock_connector()
    connector.handshake.side_effect = ElastiCacheIntegrationsConnectionError(
        "HELLO 3 rejected: WRONGPASS"
    )

    with pytest.raises(ElastiCacheIntegrationsConnectionError):
        ElastiCacheIntegrationsSession(
            lambda: connector, policy=ReconnectPolicy(connection_timeout=0.05)
        )
    connector.close.assert_called_once_with()


def test_creating_a_session_retries_before_giving_up():
    """An initial connection failure is retried until ``connection_timeout`` has passed.

    Unlike reconnects, session creation must either establish a connection or raise, so the caller
    can handle a startup failure.
    """
    # A double, not a real connector: this needs the first two attempts to fail and the third to
    # succeed, which no cache can be scripted to do, and it asserts each failed candidate was closed
    # exactly once — which `is_alive` cannot tell apart from being closed twice.
    built = []

    def factory():
        conn = mock_connector()
        built.append(conn)
        if len(built) < 3:
            conn.handshake.side_effect = ElastiCacheIntegrationsConnectionError("cache unreachable")
        return conn

    session = ElastiCacheIntegrationsSession(factory, policy=_fast())
    try:
        assert len(built) == 3
        assert session.state is ConnectionState.ALIVE
        # Each failed candidate was closed, so no half-open connection was left behind.
        assert built[0].close.called and built[1].close.called
    finally:
        session.close()


def test_creating_a_session_gives_up_after_connection_timeout():
    """Session creation raises once ``connection_timeout`` has passed, after retrying inside it.

    Real connectors against a port nobody is listening on, so the failure is a real refused
    connection rather than a scripted one — the way a wrong endpoint or a cache that is simply not
    there actually fails.
    """
    connectors = _RealConnectors(*_unused_port())

    started = time.monotonic()
    with pytest.raises(ElastiCacheIntegrationsConnectionError):
        ElastiCacheIntegrationsSession(connectors, policy=_fast(connection_timeout=0.2))
    elapsed = time.monotonic() - started

    assert len(connectors.built) >= 2, "a refused connection is retried inside the budget"
    assert elapsed < 1.0, f"gave up well after the budget: {elapsed:.2f}s"
    assert not any(connector.is_alive for connector in connectors.built)


def test_creating_a_session_uses_full_connection_timeout(monkeypatch):
    clock = _FakeClock()
    monkeypatch.setattr(clock, "sleep", clock.advance)
    monkeypatch.setattr(connection_manager, "time", clock)
    built = []

    def factory():
        connector = mock_connector()
        built.append(connector)
        return connector

    def reject(_connector):
        raise ElastiCacheIntegrationsConnectionError("cache unreachable")

    timeout = 2.0
    manager = connection_manager.ConnectionManager(
        factory,
        reject,
        ReconnectPolicy(
            connection_timeout=timeout,
            base_delay=0.1,
            max_delay=3.2,
            jitter=0.0,
        ),
    )
    started = clock.monotonic()

    with pytest.raises(ElastiCacheIntegrationsConnectionError) as exc_info:
        manager.init_connection()

    assert str(exc_info.value) == f"opening a connection timed out after {timeout}s"
    assert clock.monotonic() - started == pytest.approx(timeout)
    assert len(built) == 5
    assert all(connector.close.call_count == 1 for connector in built)


def test_creating_a_session_preserves_non_connection_setup_error(monkeypatch):
    clock = _FakeClock()
    monkeypatch.setattr(clock, "sleep", clock.advance)
    monkeypatch.setattr(connection_manager, "time", clock)
    built = []

    def factory():
        connector = mock_connector()
        built.append(connector)
        return connector

    def reject(_connector):
        raise ElastiCacheIntegrationsOverridesRejected("aws.config rejected: CONFIG_INVALID")

    timeout = 2.0
    manager = connection_manager.ConnectionManager(
        factory,
        reject,
        ReconnectPolicy(
            connection_timeout=timeout,
            base_delay=0.1,
            max_delay=3.2,
            jitter=0.0,
        ),
    )

    started = clock.monotonic()
    with pytest.raises(
        ElastiCacheIntegrationsOverridesRejected,
        match="aws.config rejected: CONFIG_INVALID",
    ):
        manager.init_connection()

    assert clock.monotonic() - started == pytest.approx(timeout)
    assert len(built) == 5
    assert all(connector.close.call_count == 1 for connector in built)


def _unused_port() -> tuple[str, int]:
    """An address with nothing listening on it, so connecting to it is refused."""
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()


def test_connection_timeout_bounds_connect_and_configure():
    """``connection_timeout`` bounds connect plus configure, not just the decision to retry again.

    The cache accepts the connection and then never answers the HELLO, so the timeout has to cover
    the configure step and not only the connect. Bounding attempts alone would wait the connector's
    own ``config_timeout`` (2.0s) instead.
    """
    cache = MockCache(failure="hang", failure_command="hello")
    cache.start()
    connectors = _RealConnectors(cache.host, cache.port)
    try:
        timeout = 0.3
        started = time.monotonic()
        with pytest.raises(ElastiCacheIntegrationsConnectionError) as exc_info:
            ElastiCacheIntegrationsSession(connectors, policy=_fast(connection_timeout=timeout))
        elapsed = time.monotonic() - started

        assert str(exc_info.value) == f"opening a connection timed out after {timeout}s"

        # The hung attempt uses up the whole budget, so there is no time left for another.
        assert len(connectors.built) == 1
        assert elapsed >= timeout, f"gave up before the timeout elapsed: {elapsed:.2f}s"
        assert elapsed < 1.5, f"the timeout did not cover the AUTH: {elapsed:.2f}s"
        # A timed-out attempt leaks neither a connection nor the thread that was bringing it up:
        # the connector is closed, which releases the thread waiting on it.
        #
        # Asserted here, inside the try: once the cache stops, the connectors would see EOF and the
        # abandoned AUTH would hit the connector's own 2.0s timeout and self-close, so an assertion
        # after the teardown — or one waiting longer than that — passes without a closer behind it.
        assert not any(connector.is_alive for connector in connectors.built)
        wait_for(
            lambda: (
                not [
                    t
                    for t in threading.enumerate()
                    if t.name == "aws-elasticache-integration-client-connect"
                ]
            ),
            timeout=1.0,
        )
    finally:
        cache.stop()


def _fast(**kwargs) -> ReconnectPolicy:
    """A policy whose delays are too short to slow a test down."""
    return ReconnectPolicy(base_delay=0.001, max_delay=0.002, jitter=0.0, **kwargs)
