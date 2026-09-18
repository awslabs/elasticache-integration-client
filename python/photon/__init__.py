"""Photon client package — public API.

Read-through / write-through caching for AWS SDK calls, routed over RESP3 to an ElastiCache
Serverless cache.

Customer entry point::

    import photon
    ddb = photon.client("dynamodb", region_name="us-east-1", endpoint="my-cache:6379")
    ddb.get_item(TableName="t", Key={"id": {"S": "1"}})  # transparently cached

The returned object is an ordinary boto3 client with an identical API; a ``before-send`` hook
routes eligible requests through the cache.
"""

from __future__ import annotations

import logging

from photon.client import client
from photon.exceptions import PhotonCacheUnavailable, PhotonConfigError, PhotonError

__version__ = "0.0.1"

# Library best practice: attach a NullHandler to the package logger so Photon never emits
# "No handlers could be found" warnings or configures logging on the application's behalf.
# Applications opt in by configuring the "photon" logger.
logging.getLogger("photon").addHandler(logging.NullHandler())

__all__ = [
    "client",
    "PhotonCacheUnavailable",
    "PhotonConfigError",
    "PhotonError",
    "__version__",
]
