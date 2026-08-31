"""IAM authentication for the Photon client.

Three layers:
1. AWS credential source — the caller's botocore session (standard chain).
2. Token minter — ``generate_iam_auth_token``: SigV4-presigns an ``elasticache:connect`` request.
3. Provider — ``IamAuthTokenProvider``: a callable ``() -> (user_id, token)`` held by the connection.

The connection calls the provider at connect time (and later for refresh); the provider re-resolves
credentials and mints a fresh token on every invocation.
"""

from __future__ import annotations

import logging

import botocore.session
from botocore.session import Session as BotocoreSession
from botocore.signers import RequestSigner
from botocore.model import ServiceId


from photon.exceptions import PhotonError

logger = logging.getLogger("photon.auth")

# --- constants ----------------------------------------------------------------

#: SigV4 presign expiry — maximum accepted by ElastiCache (15 minutes).
TOKEN_EXPIRY_SECONDS = 900

#: Default ElastiCache IAM user for Public Endpoint caches.
DEFAULT_USER_ID = "default.iam-user"


# --- layer 2: token minting --------------------------------------------------


def generate_iam_auth_token(
    *,
    cache_name: str,
    user_id: str,
    region: str,
    botocore_session: BotocoreSession,
    expires_in: int = TOKEN_EXPIRY_SECONDS,
) -> str:
    """Generate a short-lived IAM auth token for ElastiCache Serverless.

    The token is a SigV4-presigned URL (scheme stripped) that the cache validates as a password.
    Credentials are re-resolved on every call so STS rotation is picked up automatically.

    Returns:
        The signed token string (presigned URL with ``http://`` scheme stripped).

    Raises:
        PhotonError: If credential resolution or signing fails.
    """
    try:
        creds = botocore_session.get_credentials()
        if creds is None:
            raise PhotonError("No AWS credentials available for IAM auth token generation")

        signer = RequestSigner(
            ServiceId("elasticache"),
            region,
            "elasticache",
            "v4",
            creds,
            botocore_session.get_component("event_emitter"),
        )

        url = signer.generate_presigned_url(
            {
                "method": "GET",
                "url": (
                    f"http://{cache_name}/"
                    f"?Action=connect&User={user_id}"
                    f"&ResourceType=ServerlessCache"
                ),
                "body": {},
                "headers": {},
                "context": {},
            },
            operation_name="connect",
            expires_in=expires_in,
            region_name=region,
        )

        token = url[len("http://"):] if url.startswith("http://") else url
        logger.debug("Generated IAM auth token for cache=%s user=%s", cache_name, user_id)
        return token

    except PhotonError:
        raise
    except Exception as exc:
        raise PhotonError("Failed to generate IAM auth token") from exc


# --- layer 1/3: param resolution + provider ----------------------------------


def resolve_auth_params(
    *,
    cache_name: str | None,
    user_id: str | None = None,
    region: str | None = None,
) -> tuple[str, str, str]:
    """Check the required auth parameters are present and apply defaults.

    Cache-name *format* validation is intentionally not performed here: cache names are validated
    upstream, and ElastiCache is the authority on what it accepts (a bad name yields an
    ``invalid-iam-credentials`` rejection at connect time). This function only enforces that the
    required arguments are present and fills in the default user id.

    Returns:
        Tuple of ``(cache_name, user_id, region)``.

    Raises:
        PhotonError: If ``cache_name`` or ``region`` is missing.
    """
    if not cache_name:
        raise PhotonError("cache_name is required for IAM authentication.")

    if not region:
        raise PhotonError("region is required for IAM authentication.")

    resolved_user_id = user_id or DEFAULT_USER_ID

    return cache_name, resolved_user_id, region


def build_cache_auth_provider(
    *,
    cache_name: str | None,
    user_id: str | None = None,
    region: str | None = None,
) -> IamAuthTokenProvider:
    """Assemble the auth provider for a cache connection"""
    botocore_session = botocore.session.Session()
    resolved_region = region or botocore_session.get_config_variable("region")
    cache_name, user_id, resolved_region = resolve_auth_params(
        cache_name=cache_name, user_id=user_id, region=resolved_region
    )
    return IamAuthTokenProvider(
        cache_name=cache_name,
        user_id=user_id,
        region=resolved_region,
        botocore_session=botocore_session,
    )


class IamAuthTokenProvider:
    """Callable credential provider: ``() -> (user_id, token)``.

    Each call re-resolves credentials and mints a fresh token.
    """

    def __init__(
        self,
        *,
        cache_name: str,
        user_id: str,
        region: str,
        botocore_session: BotocoreSession,
    ) -> None:
        self._cache_name = cache_name
        self._user_id = user_id
        self._region = region
        self._botocore_session = botocore_session

    def __call__(self) -> tuple[str, str]:
        """Generate a fresh ``(user_id, token)`` pair."""
        token = generate_iam_auth_token(
            cache_name=self._cache_name,
            user_id=self._user_id,
            region=self._region,
            botocore_session=self._botocore_session,
        )
        return self._user_id, token

    def __repr__(self) -> str:
        return (
            f"IamAuthTokenProvider(cache_name={self._cache_name!r}, "
            f"user_id={self._user_id!r}, region={self._region!r})"
        )
