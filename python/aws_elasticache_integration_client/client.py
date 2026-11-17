# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

"""Public entry point: the :func:`aws_elasticache_integration_client.install` function."""

from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass

from botocore.client import BaseClient
from botocore.credentials import Credentials

from aws_elasticache_integration_client.auth import build_cache_auth_provider
from aws_elasticache_integration_client.config_policy import (
    DEFAULT_STALENESS_DURATION,
    ConfigPolicy,
)
from aws_elasticache_integration_client.connection import (
    DEFAULT_REQUEST_TIMEOUT_MS,
    ElastiCacheIntegrationsConnector,
)
from aws_elasticache_integration_client.exceptions import ElastiCacheIntegrationsConfigError
from aws_elasticache_integration_client.hooks import ElastiCacheIntegrationsHandle, installed_handle
from aws_elasticache_integration_client.reconnect_policy import (
    DEFAULT_CONNECTION_TIMEOUT_MS,
    ReconnectPolicy,
)
from aws_elasticache_integration_client.session import (
    ElastiCacheIntegrationsSession,
    parse_endpoint,
)

#: Upper bound for iam_token_refresh_delay, mirroring the toolkit's 20-second safety margin
#: on the 15-minute token. The toolkit re-validates; this is the user-facing check.
MAX_IAM_TOKEN_REFRESH_DELAY = 880


@dataclass(frozen=True, kw_only=True)
class ElastiCacheIntegrationsClientConfiguration:
    """Configuration for a ElastiCacheIntegrations client. All fields are optional and use defaults when omitted.

    Attributes:
        cache_request_timeout: Maximum time, in milliseconds, to wait for each ElastiCacheIntegrations request
            response. Defaults to 5000.
        connection_timeout: How long, in milliseconds, ElastiCacheIntegrations tries to connect at startup, and how
            long a request waits for reconnection after a connection is lost. Defaults to 5000.
        fallback_duration: How long, in seconds, routed requests may use the origin service
            after an outage's reconnect wait ends without a connection. The window is shared
            by requests during one outage. A later outage gets a new window after recovery.

            * ``None`` (the default) or any negative value: allow fallback for the rest of
              the outage.
            * ``N > 0``: allow fallback for N seconds. Later requests raise
              :class:`~aws_elasticache_integration_client.exceptions.ElastiCacheIntegrationsCacheUnavailable`.
            * ``0``: disable fallback. Requests raise as soon as the reconnect wait ends.
        cache_policy_overrides: Optional settings that change what ElastiCacheIntegrations caches:
            which tables and operations to cache, and the cache entry lifetimes. ``cached_tables``
            and ``cached_operations`` are allowlists — each empty or absent means everything, so
            keeping a table out of the cache means naming the tables that should be cached rather
            than naming the one that should not. Sent through the internal ``aws.config`` command.
        cache_policy_timeout: How long, in seconds, ElastiCacheIntegrations may use a cache policy without
            confirmation from the cache. After this time, ElastiCacheIntegrations reconnects to get a current policy.
            Defaults to 3600 seconds and must be a positive whole number.
        iam_token_refresh_delay: How long, in seconds, after a token is issued before the IAM
            toolkit signs a replacement in the background, while callers keep using the current
            token. Must be a whole number greater than 0 and less than 880. When omitted, the
            toolkit default of 300 seconds is used.

    Building the configuration raises :class:`~aws_elasticache_integration_client.exceptions.ElastiCacheIntegrationsConfigError` for a value
    that is not usable.
    """

    cache_request_timeout: int = DEFAULT_REQUEST_TIMEOUT_MS
    connection_timeout: int = DEFAULT_CONNECTION_TIMEOUT_MS
    fallback_duration: int | None = None
    cache_policy_overrides: dict | None = None
    cache_policy_timeout: int = DEFAULT_STALENESS_DURATION
    iam_token_refresh_delay: int | None = None

    def __post_init__(self) -> None:
        if type(self.cache_request_timeout) is not int or self.cache_request_timeout <= 0:
            raise ElastiCacheIntegrationsConfigError(
                "cache_request_timeout must be a positive whole number of milliseconds"
            )
        if type(self.connection_timeout) is not int or self.connection_timeout <= 0:
            raise ElastiCacheIntegrationsConfigError(
                "connection_timeout must be a positive whole number of milliseconds"
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
            type(self.iam_token_refresh_delay) is not int
            or not 0 < self.iam_token_refresh_delay < MAX_IAM_TOKEN_REFRESH_DELAY
        ):
            raise ElastiCacheIntegrationsConfigError(
                "iam_token_refresh_delay must be a whole number of seconds greater than 0 and less "
                f"than {MAX_IAM_TOKEN_REFRESH_DELAY}, or None to use the toolkit default"
            )


def _client_credentials(sdk_client: BaseClient) -> Credentials:
    """Return the live credentials object the client signs its own requests with.

    The object refreshes itself, so holding it keeps rotated credentials current.
    """
    credentials = getattr(getattr(sdk_client, "_request_signer", None), "_credentials", None)
    # get_frozen_credentials is what the IAM toolkit calls for every token it signs, so an
    # object without it could not be used even though it is not None.
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
        ca_certs: CA bundle for the cache's certificate, instead of the system trust store.
        client_configuration: How the cache behaves and its optional cache policy overrides.
            See :class:`~aws_elasticache_integration_client.ElastiCacheIntegrationsClientConfiguration`;
            omitted means all defaults.

    Examples:

        The simplest form, with a client built however the application already builds one::

            ddb = boto3.client("dynamodb", region_name="us-east-1")
            aws_elasticache_integration_client.install(
                client=ddb, endpoint="my-cache:6379", cache_name="my-cache"
            )

            ddb.get_item(TableName="my-table", Key={"id": {"S": "1"}})  # unchanged

        A client with its own profile and configuration, which carry over untouched, and the
        cache tuned away from its defaults::

            session = boto3.Session(profile_name="prod", region_name="us-east-1")
            ddb = session.client("dynamodb", config=Config(connect_timeout=3))

            aws_elasticache_integration_client.install(
                client=ddb,
                endpoint="my-cache:6379",
                cache_name="my-cache",
                client_configuration=ElastiCacheIntegrationsClientConfiguration(
                    cache_request_timeout=2000,
                    fallback_duration=60,
                    cache_policy_overrides={"cached_tables": ["Users", "Sessions"]},
                ),
            )
    """
    if not isinstance(client, BaseClient):
        raise ElastiCacheIntegrationsConfigError(
            f"client must be a boto3 client, got {type(client).__name__}. Create one with "
            "boto3.client(service) or boto3.Session(...).client(service)"
        )

    if installed_handle(client):
        raise ElastiCacheIntegrationsConfigError(
            "This boto3 client already has ElastiCache Integrations installed; install once "
            "per client."
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
    session = None

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
                connection_timeout_ms=configuration.connection_timeout,
                request_timeout_ms=configuration.cache_request_timeout,
            )

        # The session connects (HELLO 3 handshake + aws.config exchange) before the hook is
        # installed, so the first intercepted request already has a ready session.
        session = ElastiCacheIntegrationsSession(
            connector_factory,
            overrides=deepcopy(configuration.cache_policy_overrides),
            policy=ReconnectPolicy(
                fallback_duration=configuration.fallback_duration,
                connection_timeout_ms=configuration.connection_timeout,
            ),
            config_policy=ConfigPolicy(staleness_duration=configuration.cache_policy_timeout),
            close_callback=auth_provider.close,
        )
        ElastiCacheIntegrationsHandle(client, session)
    except BaseException:
        # Bring-up failed (auth, the config handshake, or wiring). Close what we created — a
        # session owns a connection and background threads — and re-raise the original error.
        # The client is the application's: it stays open, and stays usable without the cache.
        if session:
            try:
                session.close()
            except Exception:
                pass
        if auth_provider:
            try:
                auth_provider.close()
            except Exception:
                pass
        raise
