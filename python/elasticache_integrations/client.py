"""Public entry point: the :func:`elasticache_integrations.install` function."""

from __future__ import annotations

import math
from copy import deepcopy
from dataclasses import dataclass

from botocore.client import BaseClient
from botocore.credentials import Credentials

from elasticache_integrations.auth import build_cache_auth_provider
from elasticache_integrations.config_policy import DEFAULT_STALENESS_DURATION, ConfigPolicy
from elasticache_integrations.connection import ElastiCacheIntegrationsConnector
from elasticache_integrations.exceptions import ElastiCacheIntegrationsConfigError
from elasticache_integrations.hooks import ElastiCacheIntegrationsHandle
from elasticache_integrations.reconnect_policy import ReconnectPolicy
from elasticache_integrations.session import ElastiCacheIntegrationsSession, parse_endpoint

#: Upper bound for iam_token_refresh_delay, mirroring the toolkit's 20-second safety margin
#: on the 15-minute token. The toolkit re-validates; this is the user-facing check.
MAX_IAM_TOKEN_REFRESH_DELAY = 880


@dataclass(frozen=True, kw_only=True)
class ElastiCacheIntegrationsClientConfiguration:
    """Configuration for a ElastiCacheIntegrations client. All fields are optional and use defaults when omitted.

    Attributes:
        cache_request_timeout: Maximum time, in seconds, to wait for each ElastiCacheIntegrations request response.
            Defaults to 5 seconds.
        fallback_duration: How long, in seconds, requests may be served directly by the origin
            service while ElastiCacheIntegrations has no connection to the cache. ``None`` (the default) or any
            negative value means indefinitely, so a cache outage never becomes an application
            outage. ``0`` means never — the first request after a connection is lost raises
            :class:`~elasticache_integrations.exceptions.ElastiCacheIntegrationsCacheUnavailable`.
        cache_policy_overrides: Optional settings that change what ElastiCacheIntegrations caches, such as tables to
            exclude and cache entry lifetimes. Sent through the internal ``aws.config`` command.
        cache_policy_timeout: How long, in seconds, ElastiCacheIntegrations may use a cache policy without
            confirmation from the cache. After this time, ElastiCacheIntegrations reconnects to get a current policy.
            Defaults to 3600 seconds and must be a positive whole number.
        iam_token_refresh_delay: How long, in seconds, after a token is issued before the IAM
            toolkit signs a replacement in the background, while callers keep using the current
            token. Must be greater than 0 and less than 880. When omitted, the toolkit default of
            300 seconds is used.

    Building the configuration raises :class:`~elasticache_integrations.exceptions.ElastiCacheIntegrationsConfigError` for a value
    that is not usable.
    """

    cache_request_timeout: float = 5.0
    fallback_duration: int | None = None
    cache_policy_overrides: dict | None = None
    cache_policy_timeout: int = DEFAULT_STALENESS_DURATION
    iam_token_refresh_delay: float | None = None

    def __post_init__(self) -> None:
        if (
            isinstance(self.cache_request_timeout, bool)
            or not isinstance(self.cache_request_timeout, (int, float))
            or not math.isfinite(self.cache_request_timeout)
            or self.cache_request_timeout <= 0
        ):
            raise ElastiCacheIntegrationsConfigError(
                "cache_request_timeout must be a positive number of seconds"
            )
        if self.fallback_duration is not None and (
            isinstance(self.fallback_duration, bool) or not isinstance(self.fallback_duration, int)
        ):
            raise ElastiCacheIntegrationsConfigError(
                "fallback_duration must be a whole number of seconds or None"
            )
        if self.cache_policy_overrides is not None and not isinstance(
            self.cache_policy_overrides, dict
        ):
            raise ElastiCacheIntegrationsConfigError(
                "cache_policy_overrides must be a dict or None"
            )
        if type(self.cache_policy_timeout) is not int or self.cache_policy_timeout <= 0:
            raise ElastiCacheIntegrationsConfigError(
                "cache_policy_timeout must be a positive whole number of seconds"
            )
        if self.iam_token_refresh_delay is not None and (
            isinstance(self.iam_token_refresh_delay, bool)
            or not isinstance(self.iam_token_refresh_delay, (int, float))
            or not math.isfinite(self.iam_token_refresh_delay)
            or not 0 < self.iam_token_refresh_delay < MAX_IAM_TOKEN_REFRESH_DELAY
        ):
            raise ElastiCacheIntegrationsConfigError(
                "iam_token_refresh_delay must be a number of seconds greater than 0 and less "
                f"than {MAX_IAM_TOKEN_REFRESH_DELAY}, or None to use the toolkit default"
            )


def _client_credentials(sdk_client: BaseClient) -> Credentials:
    """Return the live credentials object the client signs its own requests with.

    The object refreshes itself, so holding it keeps rotated credentials current.
    """
    credentials = getattr(getattr(sdk_client, "_request_signer", None), "_credentials", None)
    if not credentials or not hasattr(credentials, "get_frozen_credentials"):
        raise ElastiCacheIntegrationsConfigError(
            "This boto3 client carries no credentials, so the cache cannot be authenticated. "
            "A client built with signature_version=UNSIGNED is not supported."
        )
    return credentials


def install(
    *,
    client: BaseClient,
    endpoint: str,
    cache_name: str,
    user_id: str | None = None,
    verify: bool = True,
    ca_certs: str | None = None,
    client_configuration: ElastiCacheIntegrationsClientConfiguration | None = None,
):
    """Install ElastiCacheIntegrations into an application's boto3 client.

    The client keeps its identical API and starts serving eligible requests from the cache;
    calling ``close()`` on it disconnects the cache before closing the client as usual.

    Args:
        client: The boto3 client to route through the cache. It keeps all of its own
            configuration, and its region and credentials are the ones the cache is
            authenticated with.
        endpoint: Cache endpoint as ``host:port``.
        cache_name: Required. The ElastiCache Serverless cache name.
        user_id: Optional ElastiCache IAM user id (defaults to ``default.iam-user``).
        verify: Verify the cache's TLS certificate. False is for self-signed test rigs only.
        ca_certs: CA bundle to verify against instead of the system trust store.
        client_configuration: How the cache behaves and its optional cache policy overrides.
            See :class:`~elasticache_integrations.ElastiCacheIntegrationsClientConfiguration`;
            omitted means all defaults.

    Examples:

        The simplest form, with a client built however the application already builds one::

            ddb = boto3.client("dynamodb", region_name="us-east-1")
            elasticache_integrations.install(
                client=ddb, endpoint="my-cache:6379", cache_name="my-cache"
            )

            ddb.get_item(TableName="my-table", Key={"id": {"S": "1"}})  # unchanged

        A client with its own profile and configuration, which carry over untouched, and the
        cache tuned away from its defaults::

            session = boto3.Session(profile_name="prod", region_name="us-east-1")
            ddb = session.client("dynamodb", config=Config(connect_timeout=3))

            elasticache_integrations.install(
                client=ddb,
                endpoint="my-cache:6379",
                cache_name="my-cache",
                client_configuration=ElastiCacheIntegrationsClientConfiguration(
                    cache_request_timeout=2.0,
                    fallback_duration=60,
                    cache_policy_overrides={"tables": ["Users", "Sessions"]},
                ),
            )
    """
    if not isinstance(client, BaseClient):
        raise ElastiCacheIntegrationsConfigError(
            f"client must be a boto3 client, got {type(client).__name__}. Create one with "
            "boto3.client(service) or boto3.Session(...).client(service)"
        )

    configuration = client_configuration or ElastiCacheIntegrationsClientConfiguration()
    region = client.meta.region_name
    if not region:
        raise ElastiCacheIntegrationsConfigError(
            "The boto3 client has no region, so the cache's IAM token cannot be signed. "
            "Create the client with a region."
        )
    credentials = _client_credentials(client)
    auth_provider = None

    try:
        host, port = parse_endpoint(endpoint)
        auth_provider = build_cache_auth_provider(
            cache_name=cache_name,
            user_id=user_id,
            region=region,
            credentials=credentials,
            token_refresh_delay=configuration.iam_token_refresh_delay,
        )

        def connector_factory() -> ElastiCacheIntegrationsConnector:
            return ElastiCacheIntegrationsConnector(
                host,
                port,
                verify=verify,
                ca_certs=ca_certs,
                auth_provider=auth_provider,
                request_timeout=configuration.cache_request_timeout,
            )

        # The session connects (HELLO 3 handshake + aws.config exchange) before the hook is
        # installed, so the first intercepted request already has a ready session.
        session = ElastiCacheIntegrationsSession(
            connector_factory,
            overrides=deepcopy(configuration.cache_policy_overrides),
            policy=ReconnectPolicy(fallback_duration=configuration.fallback_duration),
            config_policy=ConfigPolicy(staleness_duration=configuration.cache_policy_timeout),
            close_callback=auth_provider.close,
        )
        ElastiCacheIntegrationsHandle(client, session)
    except BaseException:
        # Bring-up failed (auth, the config handshake, or wiring). Close what we created and
        # re-raise the original error. The client is the application's: it stays open, and
        # stays usable without the cache.
        if auth_provider is not None:
            try:
                auth_provider.close()
            except Exception:
                pass
        raise
