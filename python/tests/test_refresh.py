"""Tests for photon.refresh.

ConfigRefresher is a generic periodic-callback runner with no dependency on PhotonSession,
PhotonConnector, or any Photon protocol module, so these tests exercise it in isolation with a
plain counting callback. Some tests disable jitter and/or inject a fake ``wait`` for deterministic
timing; others exercise the real defaults (real jitter, real ``threading.Event.wait``) end-to-end.
"""

import time

from photon.refresh import ConfigRefresher


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
    refresher = ConfigRefresher(0.01, refresh_once)
    refresher.start()
    time.sleep(0.1)
    refresher.stop()
    assert get_count() >= 1


def test_refresh_once_called_exactly_stop_after_times_with_fake_wait():
    """Injecting `wait` gives exact, deterministic control over how many cycles run."""
    refresh_once, get_count = _counter()
    wait, _ = _fake_wait(stop_after=3)
    refresher = ConfigRefresher(60, refresh_once, wait=wait)
    refresher.start()
    refresher._thread.join(timeout=5)
    assert get_count() == 3


def test_stop_joins_thread_and_thread_is_no_longer_alive():
    refresher = ConfigRefresher(0.01, lambda: None)
    refresher.start()
    refresher.stop()
    assert refresher._thread.is_alive() is False


def test_stop_before_first_refresh_does_not_block_forever():
    refresher = ConfigRefresher(100, lambda: None)
    refresher.start()
    start = time.monotonic()
    refresher.stop()
    elapsed = time.monotonic() - start
    assert elapsed < 1.0


def test_stop_is_idempotent():
    refresher = ConfigRefresher(0.01, lambda: None)
    refresher.start()
    refresher.stop()
    refresher.stop()  # must not raise


def test_next_refresh_delay_without_jitter_is_exact():
    refresher = ConfigRefresher(10, lambda: None, jitter=0.0)
    for _ in range(5):
        assert refresher._next_refresh_delay() == 10


def test_next_refresh_delay_within_bounds():
    refresher = ConfigRefresher(10, lambda: None)
    for _ in range(50):
        delay = refresher._next_refresh_delay()
        assert 8.0 <= delay <= 12.0


def test_jitter_actually_varies_the_delay():
    """±20% jitter exists to desynchronize a fleet, so identical delays are a failure.

    Bounds alone would pass if jitter were dropped entirely.
    """
    refresher = ConfigRefresher(10, lambda: None)
    assert len({refresher._next_refresh_delay() for _ in range(50)}) > 1


def test_thread_is_daemon():
    refresher = ConfigRefresher(0.01, lambda: None)
    refresher.start()
    assert refresher._thread.daemon is True
    refresher.stop()
