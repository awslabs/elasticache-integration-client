"""Error taxonomy for the Photon client.

``PhotonError`` is the base for every connector failure; the subclasses name the distinct failure
modes the transport layer raises.

One error deliberately sits outside that tree: :class:`PhotonCacheUnavailable` subclasses botocore's
``ConnectionClosedError`` instead, because it is the only Photon failure meant to reach the
application.
"""

from botocore.exceptions import ConnectionClosedError


class PhotonError(Exception):
    """Base class for all Photon errors."""


class PhotonConnectionError(PhotonError):
    """Connection bring-up failed (DNS, TCP connect, or TLS handshake)."""


class PhotonProtocolError(PhotonError):
    """A RESP frame was malformed or could not be decoded."""


class PhotonConnectionLost(PhotonError):
    """The socket died mid-flight; pending requests are failed."""


class PhotonTimeoutError(PhotonError):
    """A request exceeded its per-request timeout (named to avoid shadowing the builtin)."""


class PhotonCacheUnavailable(ConnectionClosedError):
    """Photon has no connection to the cache and the fallback window has closed."""

    fmt = (
        'Photon has no connection to the cache, so this request could not be served from it: '
        '"{endpoint_url}".'
    )
