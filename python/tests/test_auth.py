"""Tests for Photon's adapters around the ElastiCache IAM toolkit."""

from __future__ import annotations

import os
from unittest.mock import MagicMock, patch

import pytest
from botocore.credentials import Credentials
from botocore.session import Session as BotocoreSession

from photon.auth import (
    DEFAULT_USER_ID,
    IamAuthTokenProvider,
    ManagedIamAuthTokenProvider,
    build_cache_auth_provider,
    generate_iam_auth_token,
    resolve_auth_params,
)
from photon.exceptions import PhotonError

FIRST_ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE"
ROTATED_ACCESS_KEY = "ASIAROTATEDCREDENTIAL"


def fake_session(*, access_keys=(FIRST_ACCESS_KEY,), region="us-east-1"):
    """Return a botocore session double that can rotate frozen credentials."""
    session = MagicMock(spec=BotocoreSession)
    credentials = [
        MagicMock(
            get_frozen_credentials=MagicMock(
                return_value=Credentials(access_key, f"{access_key}-secret", "session-token")
            )
        )
        for access_key in access_keys
    ]
    session.get_credentials.side_effect = credentials
    session.get_config_variable.side_effect = lambda name: region if name == "region" else None
    return session


class TestResolveAuthParams:
    def test_defaults_user_id(self):
        _, user_id, _ = resolve_auth_params(cache_name="my-cache", region="us-west-2")
        assert user_id == DEFAULT_USER_ID

    @pytest.mark.parametrize("cache_name", [None, ""])
    def test_rejects_missing_cache_name(self, cache_name):
        with pytest.raises(PhotonError, match="cache_name is required"):
            resolve_auth_params(cache_name=cache_name, region="us-east-1")

    @pytest.mark.parametrize("region", [None, ""])
    def test_rejects_missing_region(self, region):
        with pytest.raises(PhotonError, match="region is required"):
            resolve_auth_params(cache_name="my-cache", region=region)


class TestGenerateIamAuthToken:
    def test_uses_toolkit_format_validation_and_normalization(self):
        token = generate_iam_auth_token(
            cache_name="My-Cache",
            user_id="IAM-User",
            region="us-east-1",
            botocore_session=fake_session(),
        )

        assert token.startswith("my-cache/")
        assert "Action=connect" in token
        assert "User=iam-user" in token
        assert "ResourceType=ServerlessCache" in token
        assert "X-Amz-Credential=" in token
        assert "X-Amz-Signature=" in token
        assert "X-Amz-Expires=900" in token
        assert not token.startswith(("http://", "https://"))

        with pytest.raises(PhotonError, match="serverless_cache_name"):
            generate_iam_auth_token(
                cache_name="bad/cache",
                user_id="iam-user",
                region="us-east-1",
                botocore_session=fake_session(),
            )

    def test_raises_on_no_credentials(self):
        session = fake_session(access_keys=())
        session.get_credentials.side_effect = None
        session.get_credentials.return_value = None

        with pytest.raises(PhotonError, match="No AWS credentials"):
            generate_iam_auth_token(
                cache_name="my-cache",
                user_id="my-user",
                region="us-east-1",
                botocore_session=session,
            )

    def test_wraps_unexpected_exceptions_without_details(self):
        session = fake_session()
        session.get_credentials.side_effect = RuntimeError("secret=credential-material")

        error = pytest.raises(
            PhotonError,
            generate_iam_auth_token,
            cache_name="my-cache",
            user_id="my-user",
            region="us-east-1",
            botocore_session=session,
        ).value
        assert str(error) == "Failed to generate IAM auth token"
        assert "credential-material" not in str(error)

    def test_rejects_custom_expiry(self):
        with pytest.raises(PhotonError, match="expires_in must be 900 seconds"):
            generate_iam_auth_token(
                cache_name="my-cache",
                user_id="my-user",
                region="us-east-1",
                botocore_session=fake_session(),
                expires_in=60,
            )


class TestIamAuthTokenProvider:
    def test_returns_normalized_user_and_fresh_tokens(self):
        session = fake_session(access_keys=(FIRST_ACCESS_KEY, ROTATED_ACCESS_KEY))
        provider = IamAuthTokenProvider(
            cache_name="My-Cache",
            user_id="IAM-User",
            region="us-east-1",
            botocore_session=session,
        )

        first_user, first_token = provider()
        second_user, second_token = provider()

        assert first_user == second_user == "iam-user"
        assert FIRST_ACCESS_KEY in first_token
        assert ROTATED_ACCESS_KEY in second_token
        assert session.get_credentials.call_count == 2


class TestManagedIamAuthTokenProvider:
    def test_caches_and_force_refreshes_rotated_credentials(self):
        session = fake_session(access_keys=(FIRST_ACCESS_KEY, ROTATED_ACCESS_KEY))
        provider = build_cache_auth_provider(
            cache_name="My-Cache",
            user_id="IAM-User",
            region="us-east-1",
            botocore_session=session,
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
            assert session.get_credentials.call_count == 2
        finally:
            provider.close()

    def test_close_stops_later_token_requests(self):
        provider = build_cache_auth_provider(
            cache_name="my-cache",
            region="us-east-1",
            botocore_session=fake_session(),
        )
        provider.close()
        with pytest.raises(PhotonError, match="Failed to obtain refreshed IAM auth token"):
            provider()


class TestBuildCacheAuthProvider:
    @patch("photon.auth.ElastiCacheIAMAuthTokenManager")
    def test_passes_configuration_to_toolkit_manager(self, manager_class):
        manager = MagicMock()
        manager_class.return_value = manager
        session = fake_session()

        provider = build_cache_auth_provider(
            cache_name="My-Cache",
            user_id="IAM-User",
            region="eu-west-1",
            botocore_session=session,
            refresh_after=120,
        )

        assert isinstance(provider, ManagedIamAuthTokenProvider)
        manager_class.assert_called_once_with(
            serverless_cache_name="My-Cache",
            user_id="IAM-User",
            region="eu-west-1",
            session=session,
            refresh_after=120,
        )

    @patch.dict(os.environ, {"AWS_DEFAULT_REGION": "us-east-2"}, clear=True)
    def test_region_falls_back_through_toolkit(self):
        provider = build_cache_auth_provider(
            cache_name="my-cache",
            botocore_session=fake_session(region="us-east-2"),
        )
        try:
            _, token = provider()
            assert "us-east-2" in token
        finally:
            provider.close()

    def test_missing_cache_name_raises(self):
        with pytest.raises(PhotonError, match="cache_name is required"):
            build_cache_auth_provider(cache_name=None, region="us-east-1")

    def test_rejects_refresh_outside_toolkit_safety_margin(self):
        with pytest.raises(PhotonError, match="refresh_after"):
            build_cache_auth_provider(
                cache_name="my-cache",
                region="us-east-1",
                botocore_session=fake_session(),
                refresh_after=880,
            )
