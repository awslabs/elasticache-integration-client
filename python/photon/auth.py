"""Photon adapters for the Amazon ElastiCache IAM developer toolkit.

The toolkit owns parameter validation, normalization, region and credential
resolution, SigV4 signing, token caching, and background refresh. Photon keeps
the connector-facing ``() -> (user_id, token)`` contract and translates toolkit
failures into its public error taxonomy.
"""

from __future__ import annotations

import botocore.session
from botocore.session import Session as BotocoreSession
from developer_toolkit_elasticache import (
    ConfigurationError,
    ElastiCacheIAMAuthTokenManager,
    ElastiCacheIAMAuthTokenProvider as ToolkitIamAuthTokenProvider,
    ToolkitInputError,
    generate_iam_auth_token as toolkit_generate_iam_auth_token,
)

from photon.exceptions import PhotonError

#: SigV4 presign expiry fixed by the ElastiCache toolkit contract.
TOKEN_EXPIRY_SECONDS = 900

#: Default ElastiCache IAM user for Public Endpoint caches.
DEFAULT_USER_ID = "default.iam-user"

_NO_CREDENTIALS_MESSAGE = "No AWS credentials available for IAM auth token generation"


def _to_photon_error(exc: Exception, message: str) -> PhotonError:
    """Translate toolkit failures without exposing credential-provider details."""
    if isinstance(exc, ConfigurationError) and str(exc).startswith("No AWS credentials"):
        return PhotonError(_NO_CREDENTIALS_MESSAGE)
    if isinstance(exc, ToolkitInputError):
        return PhotonError(str(exc))
    return PhotonError(message)


def generate_iam_auth_token(
    *,
    cache_name: str,
    user_id: str,
    region: str,
    botocore_session: BotocoreSession,
    expires_in: int = TOKEN_EXPIRY_SECONDS,
) -> str:
    """Generate an ElastiCache Serverless IAM token through the toolkit.

    ``expires_in`` remains for compatibility. The toolkit owns the fixed
    15-minute token lifetime, so other values are rejected.
    """
    if expires_in != TOKEN_EXPIRY_SECONDS:
        raise PhotonError(f"expires_in must be {TOKEN_EXPIRY_SECONDS} seconds")
    try:
        return toolkit_generate_iam_auth_token(
            serverless_cache_name=cache_name,
            user_id=user_id,
            region=region,
            session=botocore_session,
        )
    except Exception as exc:
        raise _to_photon_error(exc, "Failed to generate IAM auth token") from exc


def resolve_auth_params(
    *,
    cache_name: str | None,
    user_id: str | None = None,
    region: str | None = None,
) -> tuple[str, str, str]:
    """Check required compatibility parameters and apply the default user id.

    Detailed cache-name and user-id validation is performed by the toolkit when
    a provider is initialized.
    """
    if not cache_name:
        raise PhotonError("cache_name is required for IAM authentication.")
    if not region:
        raise PhotonError("region is required for IAM authentication.")
    return cache_name, user_id or DEFAULT_USER_ID, region


class IamAuthTokenProvider:
    """Compatibility adapter that signs a fresh toolkit token on every call."""

    def __init__(
        self,
        *,
        cache_name: str,
        user_id: str,
        region: str | None,
        botocore_session: BotocoreSession,
    ) -> None:
        try:
            self._provider = ToolkitIamAuthTokenProvider(
                serverless_cache_name=cache_name,
                user_id=user_id,
                region=region,
                session=botocore_session,
            )
        except Exception as exc:
            raise _to_photon_error(exc, "Failed to initialize IAM authentication") from exc
        self._cache_name = cache_name.lower()
        self._user_id = self._provider.user_id
        self._region = region

    def __call__(self) -> tuple[str, str]:
        """Return the normalized user id and a newly signed token."""
        try:
            return self._user_id, self._provider.get_token()
        except Exception as exc:
            raise _to_photon_error(exc, "Failed to generate IAM auth token") from exc

    def __repr__(self) -> str:
        return (
            f"IamAuthTokenProvider(cache_name={self._cache_name!r}, "
            f"user_id={self._user_id!r}, region={self._region!r})"
        )


class ManagedIamAuthTokenProvider:
    """Connector adapter backed by the toolkit's cached token manager."""

    def __init__(self, manager: ElastiCacheIAMAuthTokenManager) -> None:
        self._manager = manager

    def __call__(self) -> tuple[str, str]:
        try:
            return self._manager.get_credentials()
        except Exception as exc:
            raise _to_photon_error(exc, "Failed to obtain refreshed IAM auth token") from exc

    def refresh_auth(self) -> tuple[str, str]:
        """Force a replacement after the cache rejects the current token."""
        try:
            return self._manager.user_id, self._manager.refresh_token()
        except Exception as exc:
            raise _to_photon_error(exc, "Failed to force-refresh IAM auth token") from exc

    def close(self) -> None:
        """Stop toolkit background refresh. Idempotent."""
        self._manager.close()


def build_cache_auth_provider(
    *,
    cache_name: str | None,
    user_id: str | None = None,
    region: str | None = None,
    botocore_session: BotocoreSession | None = None,
    refresh_after: float | None = None,
) -> ManagedIamAuthTokenProvider:
    """Build Photon's managed cache auth provider."""
    if not cache_name:
        raise PhotonError("cache_name is required for IAM authentication.")
    session = botocore_session or botocore.session.Session()
    kwargs = {
        "serverless_cache_name": cache_name,
        "user_id": user_id or DEFAULT_USER_ID,
        "region": region,
        "session": session,
    }
    if refresh_after is not None:
        kwargs["refresh_after"] = refresh_after
    try:
        manager = ElastiCacheIAMAuthTokenManager(**kwargs)
    except Exception as exc:
        raise _to_photon_error(exc, "Failed to initialize IAM token auto-refresh") from exc
    return ManagedIamAuthTokenProvider(manager)
