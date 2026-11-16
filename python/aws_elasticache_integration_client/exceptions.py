"""Error taxonomy for the ElastiCacheIntegrations client.

``ElastiCacheIntegrationsError`` is the base for everything that can fail on the way to the cache; the subclasses name
the distinct failure modes — the transport's, and the two the config exchange contributes.

Two errors deliberately sit outside that tree: :class:`ElastiCacheIntegrationsCacheUnavailable` and
:class:`ElastiCacheIntegrationsOriginError` subclass botocore's retryable ``ConnectionClosedError`` so the SDK
applies the customer's retry configuration.
"""

from botocore.exceptions import ConnectionClosedError


class ElastiCacheIntegrationsError(Exception):
    """Base class for all ElastiCacheIntegrations errors."""


class ElastiCacheIntegrationsConfigError(ElastiCacheIntegrationsError, ValueError):
    """ElastiCacheIntegrations was configured with something it cannot use — a bad endpoint or policy setting.

    Raised while building a client, before any I/O, so it reports a mistake in the calling code
    rather than a runtime failure. Also a ``ValueError``, which is what bad arguments raise in
    Python, so either name catches it.
    """


class ElastiCacheIntegrationsOverridesRejected(ElastiCacheIntegrationsError):
    """The cache rejected the ``aws.config`` overrides (``CONFIG_INVALID``)."""


class ElastiCacheIntegrationsConfigUnevaluable(ElastiCacheIntegrationsError):
    """The resolved config carried a predicate this client cannot evaluate."""


class ElastiCacheIntegrationsConnectionError(ElastiCacheIntegrationsError):
    """Opening a connection failed (DNS, TCP connect, or TLS handshake)."""


class ElastiCacheIntegrationsProtocolError(ElastiCacheIntegrationsError):
    """A RESP frame was malformed or could not be decoded."""


class ElastiCacheIntegrationsRejectedError(ElastiCacheIntegrationsError):
    """ElastiCacheIntegrations explicitly rejected a request with a RESP error reply."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


class ElastiCacheIntegrationsConnectionLost(ElastiCacheIntegrationsError):
    """The socket died mid-flight; pending requests are failed."""


class ElastiCacheIntegrationsTimeoutError(ElastiCacheIntegrationsError):
    """A request exceeded its per-request timeout (named to avoid shadowing the builtin)."""


class ElastiCacheIntegrationsRequestNotSent(ElastiCacheIntegrationsError):
    """A request exhausted its admission budget before any bytes were sent to the cache."""


class ElastiCacheIntegrationsAuthError(ElastiCacheIntegrationsError):
    """The cache rejected credentials during handshake or background re-AUTH."""


class ElastiCacheIntegrationsCacheUnavailable(ConnectionClosedError):
    """ElastiCacheIntegrations could not serve a request from the cache and origin fallback is not allowed."""

    #: ``endpoint_url`` is kept in ``kwargs``, as on every botocore transport error, but is not in
    #: the message.
    fmt = (
        "ElastiCacheIntegrations could not serve the request from the cache, and origin fallback is "
        "not allowed."
    )


class ElastiCacheIntegrationsOriginError(ConnectionClosedError):
    """ElastiCacheIntegrations could not complete an origin request.

    Subclassing botocore's retryable ``ConnectionClosedError`` preserves retry behavior in both
    legacy and standard retry modes, while the overridden message avoids claiming that the
    customer's SDK connection was closed.
    """

    fmt = 'ElastiCacheIntegrations could not complete the origin request to endpoint URL: "{endpoint_url}".'
