"""Tests for photon.client: the client() factory's resource handling.

The factory creates the boto3 client first, then brings up the PhotonSession
(IAM auth + photon.config handshake). If bring-up fails, the already-created
boto3 client must be closed rather than leaked. boto3 and the auth provider are
faked so no network or credentials are needed.
"""

import importlib
from unittest.mock import MagicMock

import pytest

from photon.exceptions import PhotonError

# photon/__init__.py re-exports a `client` function, shadowing the `photon.client`
# submodule attribute — so load the module object explicitly to patch its globals.
client_mod = importlib.import_module("photon.client")


def _patch_common(monkeypatch, sdk_client):
    """Run client() without a network: fake boto3 and the cache auth provider."""
    boto3_session = MagicMock()
    boto3_session.client.return_value = sdk_client
    boto3_stub = MagicMock()
    boto3_stub.Session.return_value = boto3_session
    monkeypatch.setattr(client_mod, "boto3", boto3_stub)
    monkeypatch.setattr(client_mod, "build_cache_auth_provider", lambda **kwargs: MagicMock())


def test_client_closes_sdk_client_when_session_bringup_fails(monkeypatch):
    sdk_client = MagicMock()
    _patch_common(monkeypatch, sdk_client)

    def boom(*args, **kwargs):
        raise PhotonError("bring-up failed")

    monkeypatch.setattr(client_mod, "PhotonSession", boom)

    with pytest.raises(PhotonError):
        client_mod.client("dynamodb", endpoint="cache:6379", cache_name="c")

    sdk_client.close.assert_called_once()


def test_client_returns_sdk_client_on_success(monkeypatch):
    sdk_client = MagicMock()
    _patch_common(monkeypatch, sdk_client)
    monkeypatch.setattr(client_mod, "PhotonSession", lambda *a, **k: MagicMock())
    monkeypatch.setattr(client_mod, "PhotonHandle", lambda *a, **k: MagicMock())

    result = client_mod.client("dynamodb", endpoint="cache:6379", cache_name="c")

    assert result is sdk_client
    sdk_client.close.assert_not_called()
