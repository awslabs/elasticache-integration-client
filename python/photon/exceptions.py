"""Error taxonomy for the Photon client.

``PhotonError`` is the base for every connector failure; the subclasses name the distinct failure
modes the transport layer raises.

Two errors deliberately sit outside that tree: :class:`PhotonCacheUnavailable` and
:class:`PhotonOriginError` subclass botocore's retryable ``ConnectionClosedError`` so the SDK
applies the customer's retry configuration.
"""

from botocore.exceptions import ConnectionClosedError


class PhotonError(Exception):
    """Base class for all Photon errors."""


class PhotonConfigError(PhotonError, ValueError):
    """Photon was configured with something it cannot use — a bad endpoint or policy setting.

    Raised while building a client, before any I/O, so it reports a mistake in the calling code
    rather than a runtime failure. Also a ``ValueError``, which is what bad arguments raise in
    Python, so either name catches it.
    """


class PhotonConnectionError(PhotonError):
    """Opening a connection failed (DNS, TCP connect, or TLS handshake)."""


class PhotonProtocolError(PhotonError):
    """A RESP frame was malformed or could not be decoded."""


class PhotonRejectedError(PhotonError):
    """Photon explicitly rejected a request with a RESP error reply."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


class PhotonConnectionLost(PhotonError):
    """The socket died mid-flight; pending requests are failed."""


class PhotonTimeoutError(PhotonError):
    """A request exceeded its per-request timeout (named to avoid shadowing the builtin)."""


class PhotonCacheUnavailable(ConnectionClosedError):
    """Photon has no connection to the cache and the fallback window has closed."""

    #: ``endpoint_url`` is the request's own destination — the origin service — which is what every
    #: botocore transport error carries and what identifies the call that was refused.
    fmt = (
        'Photon has no connection to the cache, so this request to "{endpoint_url}" could not be '
        'served from it.'
    )


class PhotonOriginError(ConnectionClosedError):
    """Photon could not complete an origin request.

    Subclassing botocore's retryable ``ConnectionClosedError`` preserves retry behavior in both
    legacy and standard retry modes, while the overridden message avoids claiming that the
    customer's SDK connection was closed.
    """

    fmt = 'Photon could not complete the origin request to endpoint URL: "{endpoint_url}".'
