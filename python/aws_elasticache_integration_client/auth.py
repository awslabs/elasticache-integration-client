"""ElastiCache Integrations adapters for the Amazon ElastiCache IAM developer toolkit.

The toolkit owns parameter validation, normalization, region and credential
resolution, SigV4 signing, token caching, and background refresh. The client keeps
the connector-facing ``() -> (user_id, token)`` contract and translates toolkit
failures into its public error taxonomy.
"""

from __future__ import annotations

from botocore.credentials import Credentials
from developer_toolkit_elasticache import (
    ElastiCacheIAMAuthTokenManager,
    ToolkitInputError,
)

from aws_elasticache_integration_client.exceptions import ElastiCacheIntegrationsError

#: Default ElastiCache IAM user for Public Endpoint caches.
DEFAULT_USER_ID = "default.iam-user"


def _to_elasticache_integrations_error(
    exc: Exception, message: str
) -> ElastiCacheIntegrationsError:
    """Translate toolkit failures into the client's error taxonomy.

    Toolkit input errors carry static, actionable guidance and are safe to pass through.
    Unexpected failures get a stable generic message; callers chain the original as ``__cause__``.
    """
    if isinstance(exc, ToolkitInputError):
        return ElastiCacheIntegrationsError(str(exc))
    return ElastiCacheIntegrationsError(message)


class ManagedIamAuthTokenProvider:
    """Connector adapter backed by the toolkit's cached token manager."""

    def __init__(self, manager: ElastiCacheIAMAuthTokenManager) -> None:
        self._manager = manager

    def __call__(self) -> tuple[str, str]:
        try:
            return self._manager.get_credentials()
        except Exception as exc:
            raise _to_elasticache_integrations_error(
                exc, "Failed to obtain refreshed IAM auth token"
            ) from exc

    def refresh_auth(self) -> tuple[str, str]:
        """Force a replacement after the cache rejects the current token."""
        try:
            return self._manager.user_id, self._manager.refresh_token()
        except Exception as exc:
            raise _to_elasticache_integrations_error(
                exc, "Failed to force-refresh IAM auth token"
            ) from exc

    def close(self) -> None:
        """Stop toolkit background refresh. Idempotent."""
        self._manager.close()


def build_cache_auth_provider(
    *,
    cache_name: str | None,
    credentials: Credentials,
    user_id: str | None = None,
    region: str | None = None,
    token_refresh_delay: float | None = None,
) -> ManagedIamAuthTokenProvider:
    """Build the managed cache auth provider.

    Signs with ``credentials``, the live object behind the application's own client, so the
    cache is authenticated as the same principal and rotation is preserved: the toolkit
    re-reads them for every token it signs.
    """
    if not cache_name:
        raise ElastiCacheIntegrationsError("cache_name is required for IAM authentication.")
    kwargs = {
        "serverless_cache_name": cache_name,
        "user_id": user_id or DEFAULT_USER_ID,
        "region": region,
        "credentials": credentials,
    }
    if token_refresh_delay is not None:
        kwargs["refresh_after"] = token_refresh_delay
    try:
        manager = ElastiCacheIAMAuthTokenManager(**kwargs)
    except Exception as exc:
        raise _to_elasticache_integrations_error(
            exc, "Failed to initialize IAM token auto-refresh"
        ) from exc
    return ManagedIamAuthTokenProvider(manager)
