# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

"""ElastiCacheIntegrations client package — public API.

Read-through / write-through caching for AWS SDK calls, routed over RESP3 to an ElastiCache
Serverless cache.

Customer entry point::

    import boto3
    import aws_elasticache_integration_client

    ddb = boto3.client("dynamodb", region_name="us-east-1")
    aws_elasticache_integration_client.install(client=ddb, endpoint="my-cache:6379", cache_name="my-cache")
    ddb.get_item(TableName="t", Key={"id": {"S": "1"}})  # transparently cached

The client stays an ordinary boto3 client with an identical API; a ``before-send`` hook
routes eligible requests through the cache.
"""

from __future__ import annotations

import logging
from importlib.metadata import version

from aws_elasticache_integration_client.client import (
    ElastiCacheIntegrationsClientConfiguration,
    install,
)
from aws_elasticache_integration_client.exceptions import (
    ElastiCacheIntegrationsAuthError,
    ElastiCacheIntegrationsCacheUnavailable,
    ElastiCacheIntegrationsConfigError,
    ElastiCacheIntegrationsConfigUnevaluable,
    ElastiCacheIntegrationsConnectionError,
    ElastiCacheIntegrationsConnectionLost,
    ElastiCacheIntegrationsError,
    ElastiCacheIntegrationsOriginError,
    ElastiCacheIntegrationsOverridesRejected,
    ElastiCacheIntegrationsProtocolError,
    ElastiCacheIntegrationsRejectedError,
    ElastiCacheIntegrationsRequestNotSent,
    ElastiCacheIntegrationsTimeoutError,
)

__version__ = version("aws-elasticache-integration-client")

# Library best practice: attach a NullHandler to the package logger so ElastiCacheIntegrations never emits
# "No handlers could be found" warnings or configures logging on the application's behalf.
# Applications opt in by configuring the "aws_elasticache_integration_client" logger.
logging.getLogger("aws_elasticache_integration_client").addHandler(logging.NullHandler())

__all__ = [
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
