"""Unit tests for photon.auth — parameter validation, token generation, and provider."""

from __future__ import annotations

import os
from unittest.mock import MagicMock, patch

import pytest

from photon.auth import (
    DEFAULT_USER_ID,
    IamAuthTokenProvider,
    build_cache_auth_provider,
    generate_iam_auth_token,
    resolve_auth_params,
)
from photon.exceptions import PhotonError


# --- resolve_auth_params tests ------------------------------------------------


class TestResolveAuthParams:
    """Tests for resolve_auth_params validation and defaults."""

    def test_defaults_user_id(self):
        _, user_id, _ = resolve_auth_params(cache_name="my-cache", region="us-west-2")
        assert user_id == DEFAULT_USER_ID

    def test_rejects_missing_cache_name(self):
        with pytest.raises(PhotonError, match="cache_name is required"):
            resolve_auth_params(cache_name=None, region="us-east-1")

    def test_rejects_empty_cache_name(self):
        with pytest.raises(PhotonError, match="cache_name is required"):
            resolve_auth_params(cache_name="", region="us-east-1")

    def test_rejects_missing_region(self):
        with pytest.raises(PhotonError, match="region is required"):
            resolve_auth_params(cache_name="my-cache", region=None)

    def test_rejects_empty_region(self):
        with pytest.raises(PhotonError, match="region is required"):
            resolve_auth_params(cache_name="my-cache", region="")


# --- generate_iam_auth_token tests -------------------------------------------


class TestGenerateIamAuthToken:
    """Tests for the token minting function."""

    def _make_mock_session(self):
        """Create a mock botocore session with fake credentials."""
        mock_session = MagicMock()
        mock_creds = MagicMock()
        mock_creds.access_key = "AKIAIOSFODNN7EXAMPLE"
        mock_creds.secret_key = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        mock_creds.token = "FwoGZXIvYXdzEBYaDHqa0AP"
        mock_session.get_credentials.return_value = mock_creds
        mock_session.get_component.return_value = MagicMock()
        return mock_session

    @patch.dict(
        os.environ,
        {
            "AWS_ACCESS_KEY_ID": "AKIAIOSFODNN7EXAMPLE",
            "AWS_SECRET_ACCESS_KEY": "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            "AWS_SESSION_TOKEN": "FwoGZXIvYXdzEBYaDHqa0APtoken",
        },
    )
    def test_generates_token_with_correct_format(self):
        """Generate a real token using fake env creds and verify its structure."""
        import botocore.session

        session = botocore.session.Session()
        token = generate_iam_auth_token(
            cache_name="my-cache",
            user_id="my-user",
            region="us-east-1",
            botocore_session=session,
        )

        # Token should have http:// stripped and start with cache name
        assert not token.startswith("http://")
        assert token.startswith("my-cache/")
        # Must contain the signed query params
        assert "Action=connect" in token
        assert "User=my-user" in token
        assert "ResourceType=ServerlessCache" in token
        assert "X-Amz-Credential=" in token
        assert "X-Amz-Signature=" in token
        assert "X-Amz-Expires=900" in token

    def test_raises_on_no_credentials(self):
        mock_session = MagicMock()
        mock_session.get_credentials.return_value = None

        with pytest.raises(PhotonError, match="No AWS credentials"):
            generate_iam_auth_token(
                cache_name="my-cache",
                user_id="my-user",
                region="us-east-1",
                botocore_session=mock_session,
            )

    def test_wraps_unexpected_exceptions(self):
        mock_session = MagicMock()
        mock_session.get_credentials.side_effect = RuntimeError("boom")

        with pytest.raises(PhotonError, match="Failed to generate IAM auth token"):
            generate_iam_auth_token(
                cache_name="my-cache",
                user_id="my-user",
                region="us-east-1",
                botocore_session=mock_session,
            )


# --- IamAuthTokenProvider tests -----------------------------------------------


class TestIamAuthTokenProvider:
    """Tests for the callable provider."""

    @patch("photon.auth.generate_iam_auth_token")
    def test_call_returns_user_id_and_token(self, mock_gen):
        mock_gen.return_value = "my-cache/?signed-stuff"
        mock_session = MagicMock()

        provider = IamAuthTokenProvider(
            cache_name="my-cache",
            user_id="my-user",
            region="us-east-1",
            botocore_session=mock_session,
        )

        user_id, token = provider()

        assert user_id == "my-user"
        assert token == "my-cache/?signed-stuff"
        mock_gen.assert_called_once_with(
            cache_name="my-cache",
            user_id="my-user",
            region="us-east-1",
            botocore_session=mock_session,
        )

    @patch("photon.auth.generate_iam_auth_token")
    def test_each_call_regenerates(self, mock_gen):
        """Each invocation should call generate_iam_auth_token (fresh creds per mint)."""
        mock_gen.return_value = "token"
        mock_session = MagicMock()

        provider = IamAuthTokenProvider(
            cache_name="c", user_id="u", region="r", botocore_session=mock_session
        )

        provider()
        provider()
        provider()

        assert mock_gen.call_count == 3


# --- build_cache_auth_provider tests -------------------------------------------


class TestBuildCacheAuthProvider:
    """Tests for the composition factory: config in, ready provider out."""

    # These assert on the provider's private fields — it exposes no public accessors,
    # and matching repr() strings would be worse. Our own suite poking our own object.

    def test_explicit_region_wins(self):
        """An explicit region is used as-is — the botocore default chain is not consulted."""
        provider = build_cache_auth_provider(cache_name="my-cache", region="eu-west-1")
        assert isinstance(provider, IamAuthTokenProvider)
        assert provider._region == "eu-west-1"
        assert provider._cache_name == "my-cache"
        assert provider._user_id == DEFAULT_USER_ID  # default applied

    @patch.dict(os.environ, {"AWS_DEFAULT_REGION": "us-east-2"})
    def test_region_falls_back_to_botocore_chain(self):
        """Without an explicit region, botocore's default chain (env var here) resolves it."""
        provider = build_cache_auth_provider(cache_name="my-cache")
        assert provider._region == "us-east-2"

    def test_missing_cache_name_raises(self):
        """Param validation still applies through the factory."""
        with pytest.raises(PhotonError, match="cache_name is required"):
            build_cache_auth_provider(cache_name=None, region="us-east-1")
