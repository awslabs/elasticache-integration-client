"""Tests for the session's background re-AUTH wiring (CR-A1).

A real PhotonSession is built over a mock connector (no network). The runner starts with the
default long interval, so it does not tick during a test; `_reauth_once` is driven directly.
"""

import pytest

from photon.exceptions import PhotonAuthError
from photon.reconnect_policy import ReconnectPolicy
from photon.session import PhotonSession
from tests.mock import mock_connector


def _session(connector) -> PhotonSession:
    return PhotonSession(lambda: connector, policy=ReconnectPolicy())


def test_session_starts_a_reauth_runner():
    conn = mock_connector()
    session = _session(conn)
    try:
        assert session._reauth_runner is not None
    finally:
        session.close()


def test_reauth_once_reauthenticates_the_live_connector():
    conn = mock_connector()
    session = _session(conn)
    try:
        conn.authenticate.reset_mock()  # ignore the bring-up AUTH
        session._reauth_once()
        conn.authenticate.assert_called_once()
    finally:
        session.close()


def test_reauth_once_propagates_a_rejection_so_the_refresher_backs_off():
    # A rejected re-AUTH re-raises out of _reauth_once (rather than being swallowed) so the
    # refresher retries on its backoff instead of waiting the full interval.
    conn = mock_connector()
    session = _session(conn)
    try:
        conn.authenticate.reset_mock()
        conn.authenticate.side_effect = PhotonAuthError("IAM AUTH rejected: WRONGPASS")
        with pytest.raises(PhotonAuthError):
            session._reauth_once()
    finally:
        session.close()


def test_reauth_once_skips_when_reconnecting():
    conn = mock_connector()
    session = _session(conn)
    try:
        session._connection_manager._connector = None  # no live connector (reconnecting)
        conn.authenticate.reset_mock()
        session._reauth_once()  # must not raise
        conn.authenticate.assert_not_called()
    finally:
        session.close()


def test_close_stops_the_reauth_runner():
    conn = mock_connector()
    session = _session(conn)
    runner = session._reauth_runner
    assert runner is not None
    session.close()
    assert runner._stop.is_set()
