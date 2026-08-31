"""Tests for photon.exceptions — the connection error taxonomy."""

import pytest

from photon.exceptions import (
    PhotonConnectionLost,
    PhotonConnectionLostError,
    PhotonConnectionError,
    PhotonError,
    PhotonProtocolError,
    PhotonTimeoutError,
)

_SUBCLASSES = [
    PhotonConnectionError,
    PhotonProtocolError,
    PhotonConnectionLostError,
    PhotonTimeoutError,
]


# --- Taxonomy: every failure mode is catchable as PhotonError ------------------

@pytest.mark.parametrize("cls", _SUBCLASSES)
def test_taxonomy_subclasses_photon_error(cls):
    """Every failure-mode class is a subclass of PhotonError."""
    assert issubclass(cls, PhotonError)


def test_legacy_connection_lost_name_is_an_alias():
    assert PhotonConnectionLost is PhotonConnectionLostError


# --- AC6: public re-export unchanged ------------------------------------------

def test_public_api_surface():
    """The package's public API is client and __version__ only; PhotonError lives in
    photon.exceptions (apps are not expected to handle it: errors fall through)."""
    import photon

    assert photon.__all__ == ["client", "__version__"]
