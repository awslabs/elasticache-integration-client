"""Tests for photon.exceptions — the connector error taxonomy."""

import pytest

from botocore.exceptions import ConnectionClosedError

from photon.exceptions import (
    PhotonCacheUnavailable,
    PhotonConfigError,
    PhotonConnectionLost,
    PhotonConnectionError,
    PhotonError,
    PhotonProtocolError,
    PhotonRejectedError,
    PhotonTimeoutError,
)

_SUBCLASSES = [
    PhotonConfigError,
    PhotonConnectionError,
    PhotonProtocolError,
    PhotonRejectedError,
    PhotonConnectionLost,
    PhotonTimeoutError,
]


# --- Taxonomy: every failure mode is catchable as PhotonError ------------------

@pytest.mark.parametrize("cls", _SUBCLASSES)
def test_taxonomy_subclasses_photon_error(cls):
    """Every failure-mode class is a subclass of PhotonError."""
    assert issubclass(cls, PhotonError)

def test_cache_unavailable_is_a_botocore_error_not_a_photon_error():
    """PhotonCacheUnavailable sits outside the PhotonError tree, on purpose.

    It is the only Photon failure meant to reach the application, so it has to be catchable by the
    ``except botocore...`` blocks an application already has. Being a PhotonError as well would be
    worse than redundant: hooks.py catches PhotonError and passes the request through, so it would be
    swallowed and the escalation ladder would silently stop working.
    """
    assert issubclass(PhotonCacheUnavailable, ConnectionClosedError)
    assert not issubclass(PhotonCacheUnavailable, PhotonError)


def test_cache_unavailable_message_says_the_cache_is_unavailable():
    message = str(PhotonCacheUnavailable(endpoint_url="https://dynamodb.us-east-1.amazonaws.com/"))
    assert "no connection to the cache" in message
    assert "dynamodb.us-east-1.amazonaws.com" in message


# --- Public API surface -------------------------------------------------------

def test_public_api_surface():
    """Verify that only the intended application-facing API is exported.

    Three errors are reachable by an application and so are named here: ``PhotonCacheUnavailable``
    when a request cannot be served and fallback has closed, ``PhotonConfigError`` when
    ``photon.client()`` is given something unusable, and ``PhotonError`` as the base for anything
    else that creating a client can raise. The per-failure-mode classes stay internal in
    ``photon.exceptions``, because the request path converts them to botocore's transport errors
    before an application ever sees them.
    """
    import photon

    assert photon.__all__ == [
        "client",
        "PhotonCacheUnavailable",
        "PhotonConfigError",
        "PhotonError",
        "__version__",
    ]
    assert photon.PhotonCacheUnavailable is PhotonCacheUnavailable
