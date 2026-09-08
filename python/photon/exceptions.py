"""Error taxonomy for the Photon client.

``PhotonError`` is the base for every connector failure; the subclasses name the distinct failure
modes the transport layer raises. The full resilience layer (circuit breaking, pass-through) is
out of scope for the bootstrap.
"""


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
