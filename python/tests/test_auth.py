"""Tests for the managed adapter around the ElastiCache IAM toolkit."""

from __future__ import annotations

import os
from unittest.mock import MagicMock, patch

import pytest
from botocore.credentials import Credentials
from developer_toolkit_elasticache import ConfigurationError, InvalidParameterError

from aws_elasticache_integration_client.auth import (
    DEFAULT_USER_ID,
    ManagedIamAuthTokenProvider,
    build_cache_auth_provider,
)
from aws_elasticache_integration_client.exceptions import ElastiCacheIntegrationsError

FIRST_ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE"
ROTATED_ACCESS_KEY = "ASIAROTATEDCREDENTIAL"


def fake_credentials(*, access_keys=(FIRST_ACCESS_KEY,)):
    """A credentials double that vends rotated keys, as a refreshable object does when read."""
    credentials = MagicMock()
    credentials.get_frozen_credentials.side_effect = [
        Credentials(access_key, f"{access_key}-secret", "session-token")
        for access_key in access_keys
    ]
    return credentials


class TestManagedIamAuthTokenProvider:
    def test_caches_and_force_refreshes_rotated_credentials(self):
        credentials = fake_credentials(access_keys=(FIRST_ACCESS_KEY, ROTATED_ACCESS_KEY))
        provider = build_cache_auth_provider(
            cache_name="My-Cache",
            user_id="IAM-User",
            region="us-east-1",
            credentials=credentials,
        )
        try:
            first = provider()
            cached = provider()
            refreshed = provider.refresh_auth()

            assert isinstance(provider, ManagedIamAuthTokenProvider)
            assert first == cached
            assert first[0] == refreshed[0] == "iam-user"
            assert FIRST_ACCESS_KEY in first[1]
            assert ROTATED_ACCESS_KEY in refreshed[1]
            assert credentials.get_frozen_credentials.call_count == 2
        finally:
            provider.close()

    def test_close_stops_later_token_requests(self):
        provider = build_cache_auth_provider(
            cache_name="my-cache",
            region="us-east-1",
            credentials=fake_credentials(),
        )
        provider.close()

        with pytest.raises(
            ElastiCacheIntegrationsError, match="Failed to obtain refreshed IAM auth token"
        ):
            provider()


class TestBuildCacheAuthProvider:
    @patch("aws_elasticache_integration_client.auth.ElastiCacheIAMAuthTokenManager")
    def test_passes_configuration_to_toolkit_manager(self, manager_class):
        manager_class.return_value = MagicMock()
        credentials = fake_credentials()

        provider = build_cache_auth_provider(
            cache_name="My-Cache",
            user_id="IAM-User",
            region="eu-west-1",
            credentials=credentials,
            token_refresh_delay=120,
        )

        assert isinstance(provider, ManagedIamAuthTokenProvider)
        manager_class.assert_called_once_with(
            serverless_cache_name="My-Cache",
            user_id="IAM-User",
            region="eu-west-1",
            credentials=credentials,
            refresh_after=120,
        )

    @patch.dict(os.environ, {"AWS_REGION": "us-east-2"}, clear=True)
    def test_region_falls_back_to_the_environment(self):
        """install() always passes a region; the toolkit's own fallback still applies if it does not."""
        provider = build_cache_auth_provider(
            cache_name="my-cache",
            credentials=fake_credentials(),
        )
        try:
            user_id, token = provider()
            assert user_id == DEFAULT_USER_ID
            assert "us-east-2" in token
        finally:
            provider.close()

    def test_missing_cache_name_raises(self):
        with pytest.raises(ElastiCacheIntegrationsError, match="cache_name is required"):
            build_cache_auth_provider(
                cache_name=None, region="us-east-1", credentials=fake_credentials()
            )

    def test_rejects_refresh_outside_toolkit_safety_margin(self):
        with pytest.raises(ElastiCacheIntegrationsError, match="refresh_after"):
            build_cache_auth_provider(
                cache_name="my-cache",
                region="us-east-1",
                credentials=fake_credentials(),
                token_refresh_delay=880,
            )

    @patch("aws_elasticache_integration_client.auth.ElastiCacheIAMAuthTokenManager")
    def test_wraps_unexpected_constructor_errors_without_details(self, manager_class):
        manager_class.side_effect = RuntimeError("secret=credential-material")

        error = pytest.raises(
            ElastiCacheIntegrationsError,
            build_cache_auth_provider,
            cache_name="my-cache",
            region="us-east-1",
            credentials=fake_credentials(),
        ).value

        assert str(error) == "Failed to initialize IAM token auto-refresh"
        assert "credential-material" not in str(error)


@pytest.mark.parametrize(
    "toolkit_error",
    [
        ConfigurationError("No AWS credentials found. Configure credentials via the environment."),
        ConfigurationError("No AWS region found. Pass region explicitly, or configure AWS_REGION."),
        InvalidParameterError(
            "Invalid value for parameter 'serverless_cache_name': must match a pattern."
        ),
    ],
)
@patch("aws_elasticache_integration_client.auth.ElastiCacheIAMAuthTokenManager")
def test_toolkit_input_errors_reach_the_caller(manager_class, toolkit_error):
    manager_class.side_effect = toolkit_error

    error = pytest.raises(
        ElastiCacheIntegrationsError,
        build_cache_auth_provider,
        cache_name="my-cache",
        region="us-east-1",
        credentials=fake_credentials(),
    ).value

    assert str(error) == str(toolkit_error)
