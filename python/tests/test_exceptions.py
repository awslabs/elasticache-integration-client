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
    ElastiCacheIntegrationsRequestNotSent,
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
    ElastiCacheIntegrationsRequestNotSent,
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


def test_cache_unavailable_message_says_the_request_was_not_served():
    message = str(
        ElastiCacheIntegrationsCacheUnavailable(
            endpoint_url="https://dynamodb.us-east-1.amazonaws.com/"
        )
    )
    assert message == (
        "ElastiCacheIntegrations could not serve the request from the cache, and origin fallback is not allowed."
    )
    with_reason = ElastiCacheIntegrationsCacheUnavailable(endpoint_url="x", reason="no capacity")
    assert str(with_reason) == (
        "ElastiCacheIntegrations could not serve the request from the cache, and origin fallback is not "
        "allowed: no capacity"
    )


def test_origin_error_is_retryable_without_claiming_the_sdk_connection_closed():
    assert issubclass(ElastiCacheIntegrationsOriginError, ConnectionClosedError)
    message = str(
        ElastiCacheIntegrationsOriginError(endpoint_url="https://dynamodb.us-east-1.amazonaws.com/")
    )
    assert "could not complete the origin request" in message
    assert "connection was closed" not in message.lower()


# --- Public API surface -------------------------------------------------------


def test_public_api_surface():
    """Every error an application can receive is exported, so it can be caught by class."""
    import aws_elasticache_integration_client
    from aws_elasticache_integration_client import exceptions

    assert aws_elasticache_integration_client.__all__ == [
        "install",
        "ElastiCacheIntegrationsClientConfiguration",
        "ElastiCacheIntegrationsAuthError",
        "ElastiCacheIntegrationsCacheUnavailable",
        "ElastiCacheIntegrationsConfigError",
        "ElastiCacheIntegrationsConfigUnevaluable",
        "ElastiCacheIntegrationsConnectionError",
        "ElastiCacheIntegrationsConnectionLost",
        "ElastiCacheIntegrationsError",
        "ElastiCacheIntegrationsOriginError",
        "ElastiCacheIntegrationsOverridesRejected",
        "ElastiCacheIntegrationsProtocolError",
        "ElastiCacheIntegrationsRejectedError",
        "ElastiCacheIntegrationsRequestNotSent",
        "ElastiCacheIntegrationsTimeoutError",
        "__version__",
    ]
    for name in aws_elasticache_integration_client.__all__[2:-1]:
        assert getattr(aws_elasticache_integration_client, name) is getattr(exceptions, name)
