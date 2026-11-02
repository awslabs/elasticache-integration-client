"""Tests for aws_elasticache_integration_client.exceptions — the connector error taxonomy."""

import pytest

from botocore.exceptions import ConnectionClosedError

from aws_elasticache_integration_client.exceptions import (
    ElastiCacheIntegrationsAuthError,
    ElastiCacheIntegrationsCacheUnavailable,
    ElastiCacheIntegrationsConfigError,
    ElastiCacheIntegrationsOverridesRejected,
    ElastiCacheIntegrationsConfigUnevaluable,
    ElastiCacheIntegrationsConnectionLost,
    ElastiCacheIntegrationsConnectionError,
    ElastiCacheIntegrationsError,
    ElastiCacheIntegrationsOriginError,
    ElastiCacheIntegrationsProtocolError,
    ElastiCacheIntegrationsRejectedError,
    ElastiCacheIntegrationsTimeoutError,
)

_SUBCLASSES = [
    ElastiCacheIntegrationsAuthError,
    ElastiCacheIntegrationsConfigError,
    ElastiCacheIntegrationsOverridesRejected,
    ElastiCacheIntegrationsConfigUnevaluable,
    ElastiCacheIntegrationsConnectionError,
    ElastiCacheIntegrationsProtocolError,
    ElastiCacheIntegrationsRejectedError,
    ElastiCacheIntegrationsConnectionLost,
    ElastiCacheIntegrationsTimeoutError,
]


# --- Taxonomy: every failure mode is catchable as ElastiCacheIntegrationsError ------------------


@pytest.mark.parametrize("cls", _SUBCLASSES)
def test_taxonomy_subclasses_elasticache_integrations_error(cls):
    """Every failure-mode class is a subclass of ElastiCacheIntegrationsError."""
    assert issubclass(cls, ElastiCacheIntegrationsError)


def test_cache_unavailable_is_a_botocore_error_not_a_elasticache_integrations_error():
    """ElastiCacheIntegrationsCacheUnavailable sits outside the ElastiCacheIntegrationsError tree, on purpose.

    It is the only ElastiCacheIntegrations failure meant to reach the application, so it has to be catchable by the
    ``except botocore...`` blocks an application already has. Being a ElastiCacheIntegrationsError as well would be
    worse than redundant: hooks.py catches ElastiCacheIntegrationsError and passes the request through, so it would be
    swallowed and the escalation ladder would silently stop working.
    """
    assert issubclass(ElastiCacheIntegrationsCacheUnavailable, ConnectionClosedError)
    assert not issubclass(ElastiCacheIntegrationsCacheUnavailable, ElastiCacheIntegrationsError)


def test_cache_unavailable_message_says_the_cache_is_unavailable():
    message = str(
        ElastiCacheIntegrationsCacheUnavailable(
            endpoint_url="https://dynamodb.us-east-1.amazonaws.com/"
        )
    )
    assert "no connection to the cache" in message
    assert "dynamodb.us-east-1.amazonaws.com" in message


def test_origin_error_is_retryable_without_claiming_the_sdk_connection_closed():
    assert issubclass(ElastiCacheIntegrationsOriginError, ConnectionClosedError)
    message = str(
        ElastiCacheIntegrationsOriginError(endpoint_url="https://dynamodb.us-east-1.amazonaws.com/")
    )
    assert "could not complete the origin request" in message
    assert "connection was closed" not in message.lower()


# --- Public API surface -------------------------------------------------------


def test_public_api_surface():
    """Verify that only the intended application-facing API is exported.

    Three errors are reachable by an application and so are named here: ``ElastiCacheIntegrationsCacheUnavailable``
    when a request cannot be served and fallback has closed, ``ElastiCacheIntegrationsConfigError`` when
    ``aws_elasticache_integration_client.install()`` is given something unusable, and ``ElastiCacheIntegrationsError`` as the base for anything
    else that installing can raise. The per-failure-mode classes stay internal in
    ``aws_elasticache_integration_client.exceptions``, because the request path converts them to botocore's transport errors
    before an application ever sees them.
    """
    import aws_elasticache_integration_client

    assert aws_elasticache_integration_client.__all__ == [
        "install",
        "ElastiCacheIntegrationsClientConfiguration",
        "ElastiCacheIntegrationsCacheUnavailable",
        "ElastiCacheIntegrationsConfigError",
        "ElastiCacheIntegrationsError",
        "__version__",
    ]
    assert (
        aws_elasticache_integration_client.ElastiCacheIntegrationsCacheUnavailable
        is ElastiCacheIntegrationsCacheUnavailable
    )
