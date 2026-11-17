# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

"""Tests for aws_elasticache_integration_client.config_policy."""

import pytest

from aws_elasticache_integration_client.config_policy import (
    DEFAULT_STALENESS_DURATION,
    ConfigPolicy,
)
from aws_elasticache_integration_client.exceptions import ElastiCacheIntegrationsConfigError


def test_the_default_window_is_one_hour():
    """An unspecified staleness duration is one hour."""
    assert ConfigPolicy().staleness_duration == DEFAULT_STALENESS_DURATION == 3600


@pytest.mark.parametrize("bad", ["3600", 3600.0, True, None, 0, -1])
def test_policy_rejects_unusable_durations(bad):
    """Only a positive whole number of seconds is a window: a string, a float, a bool, ``None``,
    zero and negatives are all rejected rather than reinterpreted."""
    with pytest.raises(ElastiCacheIntegrationsConfigError):
        ConfigPolicy(staleness_duration=bad)
