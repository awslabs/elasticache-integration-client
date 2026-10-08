"""Tests for photon.client: the client() factory's resource handling.

The factory creates the boto3 client first, then brings up the PhotonSession
(IAM auth + photon.config handshake). If bring-up fails, the already-created
boto3 client must be closed rather than leaked. boto3 and the auth provider are
faked so no network or credentials are needed.
"""

import importlib
from unittest.mock import MagicMock

import pytest

from photon import PhotonClientConfiguration
from photon.config_policy import DEFAULT_STALENESS_DURATION
from photon.exceptions import PhotonConfigError, PhotonError

# photon/__init__.py re-exports a `client` function, shadowing the `photon.client`
# submodule attribute — so load the module object explicitly to patch its globals.
client_mod = importlib.import_module("photon.client")


def _patch_common(monkeypatch, sdk_client):
    """Run client() without a network: fake boto3 and the cache auth provider."""
    boto3_session = MagicMock()
    boto3_session.client.return_value = sdk_client
    boto3_session.region_name = None
    boto3_stub = MagicMock()
    boto3_stub.Session.return_value = boto3_session
    auth_provider = MagicMock()
    auth_builder = MagicMock(return_value=auth_provider)
    monkeypatch.setattr(client_mod, "boto3", boto3_stub)
    monkeypatch.setattr(client_mod, "build_cache_auth_provider", auth_builder)
    return boto3_session, auth_builder, auth_provider


def test_client_closes_sdk_client_when_session_bringup_fails(monkeypatch):
    sdk_client = MagicMock()
    _, _, auth_provider = _patch_common(monkeypatch, sdk_client)

    def boom(*args, **kwargs):
        raise PhotonError("bring-up failed")

    monkeypatch.setattr(client_mod, "PhotonSession", boom)

    with pytest.raises(PhotonError):
        client_mod.client("dynamodb", endpoint="cache:6379", cache_name="c")

    sdk_client.close.assert_called_once()
    auth_provider.close.assert_called_once()


def test_client_returns_sdk_client_on_success(monkeypatch):
    sdk_client = MagicMock()
    _patch_common(monkeypatch, sdk_client)
    monkeypatch.setattr(client_mod, "PhotonSession", lambda *a, **k: MagicMock())
    monkeypatch.setattr(client_mod, "PhotonHandle", lambda *a, **k: MagicMock())

    result = client_mod.client("dynamodb", endpoint="cache:6379", cache_name="c")

    assert result is sdk_client
    sdk_client.close.assert_not_called()


def test_client_uses_sdk_session_for_managed_auth(monkeypatch):
    sdk_client = MagicMock()
    boto3_session, auth_builder, _ = _patch_common(monkeypatch, sdk_client)
    boto3_session.region_name = "us-east-1"
    monkeypatch.setattr(client_mod, "PhotonSession", lambda *a, **k: MagicMock())
    monkeypatch.setattr(client_mod, "PhotonHandle", lambda *a, **k: MagicMock())

    client_mod.client(
        "dynamodb",
        endpoint="cache:6379",
        cache_name="My-Cache",
        region_name="us-east-1",
        user_id="IAM-User",
        client_configuration=PhotonClientConfiguration(iam_token_refresh_delay=120),
    )

    auth_builder.assert_called_once_with(
        cache_name="My-Cache",
        user_id="IAM-User",
        region="us-east-1",
        botocore_session=boto3_session._session,
        token_refresh_delay=120,
    )


def test_client_uses_the_sdk_resolved_region_for_managed_auth(monkeypatch):
    sdk_client = MagicMock()
    boto3_session, auth_builder, _ = _patch_common(monkeypatch, sdk_client)
    boto3_session.region_name = "ap-southeast-2"
    monkeypatch.setattr(client_mod, "PhotonSession", lambda *a, **k: MagicMock())
    monkeypatch.setattr(client_mod, "PhotonHandle", lambda *a, **k: MagicMock())

    client_mod.client("dynamodb", endpoint="cache:6379", cache_name="my-cache")

    assert auth_builder.call_args.kwargs["region"] == "ap-southeast-2"


def test_client_gives_session_ownership_of_managed_auth(monkeypatch):
    sdk_client = MagicMock()
    _, _, auth_provider = _patch_common(monkeypatch, sdk_client)
    built = {}
    monkeypatch.setattr(client_mod, "PhotonSession", lambda *a, **k: built.update(k) or MagicMock())
    monkeypatch.setattr(client_mod, "PhotonHandle", lambda *a, **k: MagicMock())

    client_mod.client("dynamodb", endpoint="cache:6379", cache_name="c")

    built["close_callback"]()
    auth_provider.close.assert_called_once()


@pytest.mark.parametrize("requested,expected", [(None, 5.0), (1.25, 1.25)])
def test_client_passes_photon_request_timeout_to_connector_factory(
    monkeypatch, requested, expected
):
    sdk_client = MagicMock()
    _patch_common(monkeypatch, sdk_client)
    connector_constructor = MagicMock(return_value=MagicMock())
    monkeypatch.setattr(client_mod, "PhotonConnector", connector_constructor)

    def build_session(connector_factory, *args, **kwargs):
        connector_factory()
        return MagicMock()

    monkeypatch.setattr(client_mod, "PhotonSession", build_session)
    monkeypatch.setattr(client_mod, "PhotonHandle", lambda *a, **k: MagicMock())

    kwargs = (
        {}
        if requested is None
        else {"client_configuration": PhotonClientConfiguration(photon_request_timeout=requested)}
    )
    client_mod.client("dynamodb", endpoint="cache:6379", cache_name="c", **kwargs)

    assert connector_constructor.call_args.kwargs["request_timeout"] == expected


@pytest.mark.parametrize(
    "kwargs,expected",
    [
        ({}, DEFAULT_STALENESS_DURATION),
        ({"client_configuration": PhotonClientConfiguration(cache_policy_timeout=600)}, 600),
    ],
)
def test_client_passes_the_cache_policy_timeout_to_the_session(monkeypatch, kwargs, expected):
    """The public timeout reaches the internal policy, using the default when omitted."""
    _patch_common(monkeypatch, MagicMock())
    built: dict = {}
    monkeypatch.setattr(client_mod, "PhotonSession", lambda *a, **k: built.update(k) or MagicMock())
    monkeypatch.setattr(client_mod, "PhotonHandle", lambda *a, **k: MagicMock())

    client_mod.client("dynamodb", endpoint="cache:6379", cache_name="c", **kwargs)

    assert built["config_policy"].staleness_duration == expected


@pytest.mark.parametrize(
    "field,bad",
    [
        ("photon_request_timeout", 0),
        ("photon_request_timeout", True),
        ("photon_request_timeout", float("nan")),
        ("photon_request_timeout", float("inf")),
        ("fallback_duration", 0.5),
        ("cache_policy_overrides", []),
        ("cache_policy_timeout", 0),
        ("cache_policy_timeout", "600"),
        ("iam_token_refresh_delay", 0),
        ("iam_token_refresh_delay", True),
        ("iam_token_refresh_delay", float("nan")),
        ("iam_token_refresh_delay", float("inf")),
        ("iam_token_refresh_delay", 880),
        ("iam_token_refresh_delay", "300"),
    ],
)
def test_client_configuration_rejects_unusable_values(field, bad):
    with pytest.raises(PhotonConfigError):
        PhotonClientConfiguration(**{field: bad})


def test_client_passes_the_fallback_duration_to_the_session(monkeypatch):
    _patch_common(monkeypatch, MagicMock())
    built: dict = {}
    monkeypatch.setattr(client_mod, "PhotonSession", lambda *a, **k: built.update(k) or MagicMock())
    monkeypatch.setattr(client_mod, "PhotonHandle", lambda *a, **k: MagicMock())

    configuration = PhotonClientConfiguration(fallback_duration=60)
    client_mod.client(
        "dynamodb", endpoint="cache:6379", cache_name="c", client_configuration=configuration
    )

    assert built["policy"].fallback_duration == 60


def test_client_passes_cache_policy_overrides_to_the_session(monkeypatch):
    _patch_common(monkeypatch, MagicMock())
    built: dict = {}
    monkeypatch.setattr(client_mod, "PhotonSession", lambda *a, **k: built.update(k) or MagicMock())
    monkeypatch.setattr(client_mod, "PhotonHandle", lambda *a, **k: MagicMock())

    overrides = {"exclude_tables": ["t"]}
    configuration = PhotonClientConfiguration(cache_policy_overrides=overrides)
    client_mod.client(
        "dynamodb", endpoint="cache:6379", cache_name="c", client_configuration=configuration
    )

    assert built["overrides"] == overrides


def test_client_copies_cache_policy_overrides_before_passing_them_to_the_session(monkeypatch):
    _patch_common(monkeypatch, MagicMock())
    built: dict = {}
    monkeypatch.setattr(client_mod, "PhotonSession", lambda *a, **k: built.update(k) or MagicMock())
    monkeypatch.setattr(client_mod, "PhotonHandle", lambda *a, **k: MagicMock())

    overrides = {"exclude_tables": ["t"]}
    configuration = PhotonClientConfiguration(cache_policy_overrides=overrides)
    client_mod.client(
        "dynamodb", endpoint="cache:6379", cache_name="c", client_configuration=configuration
    )
    overrides["exclude_tables"].append("other")

    assert built["overrides"] == {"exclude_tables": ["t"]}
    assert built["overrides"] is not overrides
    assert built["overrides"]["exclude_tables"] is not overrides["exclude_tables"]
