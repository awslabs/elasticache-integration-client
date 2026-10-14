"""ElastiCacheIntegrations client package — public API.

Read-through / write-through caching for AWS SDK calls, routed over RESP3 to an ElastiCache
Serverless cache.

Customer entry point::

    import elasticache_integrations
    ddb = elasticache_integrations.client("dynamodb", region_name="us-east-1", endpoint="my-cache:6379")
    ddb.get_item(TableName="t", Key={"id": {"S": "1"}})  # transparently cached

The returned object is an ordinary boto3 client with an identical API; a ``before-send`` hook
routes eligible requests through the cache.
"""

from __future__ import annotations

import logging
from importlib.metadata import version

from elasticache_integrations.client import ElastiCacheIntegrationsClientConfiguration, client
from elasticache_integrations.exceptions import (
    ElastiCacheIntegrationsCacheUnavailable,
    ElastiCacheIntegrationsConfigError,
    ElastiCacheIntegrationsError,
)

__version__ = version("elasticache-integrations")

# Library best practice: attach a NullHandler to the package logger so ElastiCacheIntegrations never emits
# "No handlers could be found" warnings or configures logging on the application's behalf.
# Applications opt in by configuring the "elasticache_integrations" logger.
logging.getLogger("elasticache_integrations").addHandler(logging.NullHandler())

__all__ = [
    "client",
    "ElastiCacheIntegrationsClientConfiguration",
    "ElastiCacheIntegrationsCacheUnavailable",
    "ElastiCacheIntegrationsConfigError",
    "ElastiCacheIntegrationsError",
    "__version__",
]
