"""Tests for aws_elasticache_integration_client.refresh.

Refresher is a generic periodic-callback runner with no dependency on ElastiCacheIntegrationsSession,
ElastiCacheIntegrationsConnector, or any ElastiCacheIntegrations protocol module, so these tests exercise it in isolation with a
plain counting callback. Some tests disable jitter and/or inject a fake ``wait`` for deterministic
timing; others exercise the real defaults (real jitter, real ``threading.Event.wait``) end-to-end.
"""

import logging
import time

from aws_elasticache_integration_client.refresh import Refresher


def _counter():
    """A refresh_once callback that counts its own invocations. Returns (refresh_once, get_count)."""
    count = 0

    def refresh_once():
        nonlocal count
        count += 1

    return refresh_once, lambda: count


def _fake_wait(stop_after: int):
    """A `wait` stand-in: returns False (not stopped) `stop_after` times, then True (stopped).

    Records every (event, timeout) call so a test can assert exactly what delay was requested,
    with no real waiting.
    """
    calls = []

    def wait(event, timeout):
        calls.append(timeout)
        return len(calls) > stop_after

    return wait, calls


def test_start_calls_refresh_once_at_least_once():
    """Exercises the real default `wait` (threading.Event.wait) end-to-end, not the fake."""
    refresh_once, get_count = _counter()
    refresher = Refresher(0.01, refresh_once)
    refresher.start()
    time.sleep(0.1)
    refresher.stop()
    assert get_count() >= 1


def test_refresh_once_called_exactly_stop_after_times_with_fake_wait():
    """Injecting `wait` gives exact, deterministic control over how many cycles run."""
    refresh_once, get_count = _counter()
    wait, _ = _fake_wait(stop_after=3)
    refresher = Refresher(60, refresh_once, wait=wait)
    refresher.start()
    refresher._thread.join(timeout=5)
    assert get_count() == 3


def test_stop_joins_thread_and_thread_is_no_longer_alive():
    refresher = Refresher(0.01, lambda: None)
    refresher.start()
    refresher.stop()
    assert refresher._thread.is_alive() is False


def test_stop_before_first_refresh_does_not_block_forever():
    refresher = Refresher(100, lambda: None)
    refresher.start()
    start = time.monotonic()
    refresher.stop()
    elapsed = time.monotonic() - start
    assert elapsed < 1.0


def test_stop_is_idempotent():
    refresher = Refresher(0.01, lambda: None)
    refresher.start()
    refresher.stop()
    refresher.stop()  # must not raise


def test_stop_without_start_does_not_raise():
    """stop() before start() is a no-op: there is no thread to join. Reachable in production via
    ElastiCacheIntegrationsSession.__init__'s failure path, which closes a session that never started a refresher."""
    Refresher(10, lambda: None).stop()


def test_next_refresh_delay_without_jitter_is_exact():
    refresher = Refresher(10, lambda: None, jitter=0.0)
    for _ in range(5):
        assert refresher._next_refresh_delay() == 10


def test_next_refresh_delay_within_bounds():
    refresher = Refresher(10, lambda: None)
    for _ in range(50):
        delay = refresher._next_refresh_delay()
        assert 8.0 <= delay <= 12.0


def test_jitter_actually_varies_the_delay():
    """±20% jitter exists to desynchronize a fleet, so identical delays are a failure.

    Bounds alone would pass if jitter were dropped entirely.
    """
    refresher = Refresher(10, lambda: None)
    assert len({refresher._next_refresh_delay() for _ in range(50)}) > 1


def test_set_interval_changes_next_computed_delay():
    """set_interval takes effect on the next _next_refresh_delay() call."""
    refresher = Refresher(10, lambda: None, jitter=0.0)
    assert refresher._next_refresh_delay() == 10
    refresher.set_interval(30)
    assert refresher._next_refresh_delay() == 30


def test_set_interval_before_start_applies_to_every_delay():
    """An interval set before the thread exists is in place by the time the loop computes its
    first delay, so no delay ever uses the constructor's interval."""
    refresh_once, get_count = _counter()
    wait, calls = _fake_wait(stop_after=2)
    refresher = Refresher(10, refresh_once, jitter=0.0, wait=wait)
    refresher.set_interval(99)
    refresher.start()
    refresher._thread.join(timeout=5)
    assert calls == [99, 99, 99]


def test_set_interval_from_the_callback_applies_only_to_later_delays():
    """The reschedule path as production drives it: set_interval is called from inside the
    callback, after a wait has returned. The delay already handed to `wait` keeps the old
    interval; every delay computed afterward uses the new one."""
    wait, calls = _fake_wait(stop_after=2)

    def refresh_once():
        refresher.set_interval(99)

    refresher = Refresher(10, refresh_once, jitter=0.0, wait=wait)
    refresher.start()
    refresher._thread.join(timeout=5)
    assert calls == [10, 99, 99]


def test_thread_is_daemon():
    refresher = Refresher(0.01, lambda: None)
    refresher.start()
    assert refresher._thread.daemon is True
    refresher.stop()


def test_a_failing_callback_does_not_stop_the_loop(caplog):
    """A callback that raises is swallowed so the schedule keeps running, and the failure is
    logged with its traceback rather than lost."""
    calls = []

    def refresh_once():
        calls.append(1)
        raise RuntimeError("boom")

    wait, _ = _fake_wait(stop_after=3)
    refresher = Refresher(60, refresh_once, wait=wait)
    with caplog.at_level(logging.WARNING, logger="aws_elasticache_integration_client.refresh"):
        refresher.start()
        refresher._thread.join(timeout=5)
    assert len(calls) == 3  # kept going despite raising every tick
    assert "RuntimeError: boom" in caplog.text  # the traceback was logged
    assert (
        "aws-elasticache-integration-client-refresher" in caplog.text
    )  # the task that failed is named


def test_a_failed_tick_uses_backoff_then_resets_to_interval_on_success():
    """A failed tick retries on failure_delay, not the full interval; a success resets to it."""
    outcomes = iter([RuntimeError("x"), RuntimeError("x"), None])  # fail, fail, ok

    def refresh_once():
        result = next(outcomes)
        if isinstance(result, Exception):
            raise result

    refresher = Refresher(
        100, refresh_once, jitter=0.0, failure_delay=lambda failures: float(failures)
    )
    timeouts = []
    state = {"n": 0}

    def wait(_event, timeout):
        timeouts.append(timeout)
        state["n"] += 1
        if state["n"] > 3:
            refresher._stop.set()
            return True
        return False

    refresher._wait = wait
    refresher._refresh_loop()
    # steady interval, then backoff(1)=1, backoff(2)=2, then success -> back to the interval
    assert timeouts[:4] == [100.0, 1.0, 2.0, 100.0]
