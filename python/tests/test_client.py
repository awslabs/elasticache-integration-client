"""Tests for elasticache_integrations.install: its wiring and resource handling.

install() takes the application's boto3 client and the session it came from, brings up the
ElastiCacheIntegrationsSession (IAM auth + aws.config handshake), and installs the before-send hook. The client
belongs to the caller, so a failed bring-up must leave it open while closing what the library created.
The session and the auth provider are faked so no network or credentials are needed.
"""

import datetime
import gc
import importlib
import weakref
from unittest.mock import MagicMock

import boto3
import botocore
import botocore.session
import pytest
from botocore.client import BaseClient
from botocore.config import Config
from botocore.credentials import DeferredRefreshableCredentials, RefreshableCredentials

from elasticache_integrations import ElastiCacheIntegrationsClientConfiguration
from elasticache_integrations.config_policy import DEFAULT_STALENESS_DURATION
from elasticache_integrations.exceptions import (
    ElastiCacheIntegrationsConfigError,
    ElastiCacheIntegrationsError,
)

# elasticache_integrations/__init__.py re-exports install(), and `elasticache_integrations.client` is also a submodule name —
# so load the module object explicitly to patch its globals.
client_mod = importlib.import_module("elasticache_integrations.client")


def _sdk_client(region_name="us-east-1", credentials="sentinel"):
    """A client the application built, as boto3 would hand one back."""
    sdk_client = MagicMock(spec=BaseClient)
    sdk_client.meta = MagicMock(region_name=region_name)
    if credentials is not None:
        creds = MagicMock() if credentials == "sentinel" else credentials
        sdk_client._request_signer = MagicMock(_credentials=creds)
    return sdk_client


def _patch_common(monkeypatch):
    """Run client() without a network: fake the cache auth provider."""
    auth_provider = MagicMock()
    auth_builder = MagicMock(return_value=auth_provider)
    monkeypatch.setattr(client_mod, "build_cache_auth_provider", auth_builder)
    return auth_builder, auth_provider


def _call(sdk_client, **kwargs):
    kwargs.setdefault("endpoint", "cache:6379")
    kwargs.setdefault("cache_name", "c")
    return client_mod.install(client=sdk_client, **kwargs)


def test_client_wires_up_the_supplied_client(monkeypatch):
    """The caller's client is hooked in place, with nothing rebuilt."""
    _, _ = _patch_common(monkeypatch)
    monkeypatch.setattr(client_mod, "ElastiCacheIntegrationsSession", lambda *a, **k: MagicMock())
    handles: list = []
    monkeypatch.setattr(
        client_mod,
        "ElastiCacheIntegrationsHandle",
        lambda *a, **k: handles.append(a) or MagicMock(),
    )
    sdk_client = _sdk_client()

    _call(sdk_client)

    assert handles[0][0] is sdk_client


def test_client_refuses_a_second_install(monkeypatch):
    """botocore ignores a duplicate handler, so a second session would run unused."""
    _patch_common(monkeypatch)
    sessions: list = []
    monkeypatch.setattr(
        client_mod,
        "ElastiCacheIntegrationsSession",
        lambda *a, **k: sessions.append(k) or MagicMock(),
    )
    sdk_client = _sdk_client()
    _call(sdk_client)

    with pytest.raises(ElastiCacheIntegrationsConfigError, match="already has"):
        _call(sdk_client)

    assert len(sessions) == 1  # the refused install built nothing


def test_client_can_be_installed_again_after_close(monkeypatch):
    """Closing detaches, so the client is installable again, as an uninstalled one is."""
    _patch_common(monkeypatch)
    monkeypatch.setattr(client_mod, "ElastiCacheIntegrationsSession", lambda *a, **k: MagicMock())
    sdk_client = _sdk_client()
    boto3_close = sdk_client.close

    _call(sdk_client)
    sdk_client.close()  # the handle's _close, which detaches

    # boto3's own close is back, so the next install cannot save ours as "the original"
    # and stack another layer onto every close after it.
    assert sdk_client.close is boto3_close
    _call(sdk_client)  # must not raise


def test_client_closes_the_session_when_installing_the_hook_fails(monkeypatch):
    """A session owns a connection and background threads; a failed install must not leak them."""
    _, auth_provider = _patch_common(monkeypatch)
    session = MagicMock()
    monkeypatch.setattr(client_mod, "ElastiCacheIntegrationsSession", lambda *a, **k: session)

    def boom(*args, **kwargs):
        raise RuntimeError("hook install failed")

    monkeypatch.setattr(client_mod, "ElastiCacheIntegrationsHandle", boom)

    with pytest.raises(RuntimeError):
        _call(_sdk_client())

    session.close.assert_called_once()
    auth_provider.close.assert_called()


def test_client_keeps_the_supplied_client_open_when_bringup_fails(monkeypatch):
    """The caller owns the client, so a failed bring-up closes only what the library created."""
    _, auth_provider = _patch_common(monkeypatch)

    def boom(*args, **kwargs):
        raise ElastiCacheIntegrationsError("bring-up failed")

    monkeypatch.setattr(client_mod, "ElastiCacheIntegrationsSession", boom)
    sdk_client = _sdk_client()

    with pytest.raises(ElastiCacheIntegrationsError):
        _call(sdk_client)

    sdk_client.close.assert_not_called()
    auth_provider.close.assert_called_once()


def test_client_signs_the_cache_token_with_the_clients_own_credentials(monkeypatch):
    """No session anywhere: the credentials the client signs with are the ones the cache gets."""
    auth_builder, _ = _patch_common(monkeypatch)
    monkeypatch.setattr(client_mod, "ElastiCacheIntegrationsSession", lambda *a, **k: MagicMock())
    monkeypatch.setattr(client_mod, "ElastiCacheIntegrationsHandle", lambda *a, **k: MagicMock())

    _call(
        _sdk_client(),
        cache_name="My-Cache",
        user_id="IAM-User",
        client_configuration=ElastiCacheIntegrationsClientConfiguration(
            iam_token_refresh_delay=120
        ),
    )

    sdk_client = _sdk_client()
    _call(
        sdk_client,
        cache_name="My-Cache",
        user_id="IAM-User",
        client_configuration=ElastiCacheIntegrationsClientConfiguration(
            iam_token_refresh_delay=120
        ),
    )

    auth_builder.assert_called_with(
        cache_name="My-Cache",
        user_id="IAM-User",
        region="us-east-1",
        credentials=sdk_client._request_signer._credentials,
        token_refresh_delay=120,
    )


def test_client_signs_the_cache_token_for_the_clients_region(monkeypatch):
    """The client's own region is the one its requests are signed for."""
    auth_builder, _ = _patch_common(monkeypatch)
    monkeypatch.setattr(client_mod, "ElastiCacheIntegrationsSession", lambda *a, **k: MagicMock())
    monkeypatch.setattr(client_mod, "ElastiCacheIntegrationsHandle", lambda *a, **k: MagicMock())

    _call(_sdk_client("ap-southeast-2"))

    assert auth_builder.call_args.kwargs["region"] == "ap-southeast-2"


def test_client_rejects_an_unsigned_client(monkeypatch):
    """botocore resolves no credentials for an UNSIGNED client, so the cache cannot authenticate."""
    _patch_common(monkeypatch)
    monkeypatch.setenv("AWS_ACCESS_KEY_ID", "AKIAIOSFODNN7EXAMPLE")
    monkeypatch.setenv("AWS_SECRET_ACCESS_KEY", "secret")
    unsigned = boto3.Session(region_name="us-east-1").client(
        "dynamodb", config=Config(signature_version=botocore.UNSIGNED)
    )

    with pytest.raises(ElastiCacheIntegrationsConfigError, match="UNSIGNED"):
        _call(unsigned)


def test_client_gives_session_ownership_of_managed_auth(monkeypatch):
    _, auth_provider = _patch_common(monkeypatch)
    built: dict = {}
    monkeypatch.setattr(
        client_mod, "ElastiCacheIntegrationsSession", lambda *a, **k: built.update(k) or MagicMock()
    )
    monkeypatch.setattr(client_mod, "ElastiCacheIntegrationsHandle", lambda *a, **k: MagicMock())

    _call(_sdk_client())

    built["close_callback"]()
    auth_provider.close.assert_called_once()


@pytest.mark.parametrize(
    "sdk_client",
    [
        # A service name no longer creates a client.
        "dynamodb",
        object(),
        # A client with no resolvable region cannot have its cache token signed.
        _sdk_client(region_name=None),
        # A client whose credentials cannot be read: the cache would get another principal.
        _sdk_client(credentials=None),
    ],
)
def test_client_rejects_clients_it_cannot_use(monkeypatch, sdk_client):
    _patch_common(monkeypatch)
    monkeypatch.setattr(client_mod, "ElastiCacheIntegrationsSession", lambda *a, **k: MagicMock())
    monkeypatch.setattr(client_mod, "ElastiCacheIntegrationsHandle", lambda *a, **k: MagicMock())

    with pytest.raises(ElastiCacheIntegrationsConfigError):
        client_mod.install(client=sdk_client, endpoint="cache:6379", cache_name="c")


@pytest.mark.parametrize("requested,expected", [(None, 5.0), (1.25, 1.25)])
def test_client_passes_cache_request_timeout_to_connector_factory(monkeypatch, requested, expected):
    _, _ = _patch_common(monkeypatch)
    connector_constructor = MagicMock(return_value=MagicMock())
    monkeypatch.setattr(client_mod, "ElastiCacheIntegrationsConnector", connector_constructor)

    def build_session(connector_factory, *args, **kwargs):
        connector_factory()
        return MagicMock()

    monkeypatch.setattr(client_mod, "ElastiCacheIntegrationsSession", build_session)
    monkeypatch.setattr(client_mod, "ElastiCacheIntegrationsHandle", lambda *a, **k: MagicMock())

    kwargs = (
        {}
        if requested is None
        else {
            "client_configuration": ElastiCacheIntegrationsClientConfiguration(
                cache_request_timeout=requested
            )
        }
    )
    _call(_sdk_client(), **kwargs)

    assert connector_constructor.call_args.kwargs["request_timeout"] == expected


@pytest.mark.parametrize(
    "kwargs,expected",
    [
        ({}, DEFAULT_STALENESS_DURATION),
        (
            {
                "client_configuration": ElastiCacheIntegrationsClientConfiguration(
                    cache_policy_timeout=600
                )
            },
            600,
        ),
    ],
)
def test_client_passes_the_cache_policy_timeout_to_the_session(monkeypatch, kwargs, expected):
    """The public timeout reaches the internal policy, using the default when omitted."""
    _, _ = _patch_common(monkeypatch)
    built: dict = {}
    monkeypatch.setattr(
        client_mod, "ElastiCacheIntegrationsSession", lambda *a, **k: built.update(k) or MagicMock()
    )
    monkeypatch.setattr(client_mod, "ElastiCacheIntegrationsHandle", lambda *a, **k: MagicMock())

    _call(_sdk_client(), **kwargs)

    assert built["config_policy"].staleness_duration == expected


@pytest.mark.parametrize("requested,expected", [(None, 2.0), (0.5, 0.5)])
def test_client_passes_connection_timeout_to_the_session(monkeypatch, requested, expected):
    _patch_common(monkeypatch)
    built: dict = {}
    monkeypatch.setattr(
        client_mod, "ElastiCacheIntegrationsSession", lambda *a, **k: built.update(k) or MagicMock()
    )
    monkeypatch.setattr(client_mod, "ElastiCacheIntegrationsHandle", lambda *a, **k: MagicMock())

    configuration = (
        ElastiCacheIntegrationsClientConfiguration()
        if requested is None
        else ElastiCacheIntegrationsClientConfiguration(connection_timeout=requested)
    )
    _call(_sdk_client(), client_configuration=configuration)

    assert built["policy"].connection_timeout == expected


@pytest.mark.parametrize("bad", [0, True, float("nan"), float("inf"), "2"])
def test_client_configuration_rejects_unusable_connection_timeout(bad):
    with pytest.raises(ElastiCacheIntegrationsConfigError):
        ElastiCacheIntegrationsClientConfiguration(connection_timeout=bad)


@pytest.mark.parametrize(
    "field,bad",
    [
        ("cache_request_timeout", 0),
        ("cache_request_timeout", True),
        ("cache_request_timeout", float("nan")),
        ("cache_request_timeout", float("inf")),
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
    with pytest.raises(ElastiCacheIntegrationsConfigError):
        ElastiCacheIntegrationsClientConfiguration(**{field: bad})


def test_client_passes_the_fallback_duration_to_the_session(monkeypatch):
    _, _ = _patch_common(monkeypatch)
    built: dict = {}
    monkeypatch.setattr(
        client_mod, "ElastiCacheIntegrationsSession", lambda *a, **k: built.update(k) or MagicMock()
    )
    monkeypatch.setattr(client_mod, "ElastiCacheIntegrationsHandle", lambda *a, **k: MagicMock())

    _call(
        _sdk_client(),
        client_configuration=ElastiCacheIntegrationsClientConfiguration(fallback_duration=60),
    )

    assert built["policy"].fallback_duration == 60


def test_client_passes_cache_policy_overrides_to_the_session(monkeypatch):
    _, _ = _patch_common(monkeypatch)
    built: dict = {}
    monkeypatch.setattr(
        client_mod, "ElastiCacheIntegrationsSession", lambda *a, **k: built.update(k) or MagicMock()
    )
    monkeypatch.setattr(client_mod, "ElastiCacheIntegrationsHandle", lambda *a, **k: MagicMock())

    overrides = {"cached_tables": ["t"]}
    _call(
        _sdk_client(),
        client_configuration=ElastiCacheIntegrationsClientConfiguration(
            cache_policy_overrides=overrides
        ),
    )

    assert built["overrides"] == overrides


def test_client_copies_cache_policy_overrides_before_passing_them_to_the_session(monkeypatch):
    _, _ = _patch_common(monkeypatch)
    built: dict = {}
    monkeypatch.setattr(
        client_mod, "ElastiCacheIntegrationsSession", lambda *a, **k: built.update(k) or MagicMock()
    )
    monkeypatch.setattr(client_mod, "ElastiCacheIntegrationsHandle", lambda *a, **k: MagicMock())

    overrides = {"cached_tables": ["t"]}
    _call(
        _sdk_client(),
        client_configuration=ElastiCacheIntegrationsClientConfiguration(
            cache_policy_overrides=overrides
        ),
    )
    overrides["cached_tables"].append("other")

    assert built["overrides"] == {"cached_tables": ["t"]}
    assert built["overrides"] is not overrides
    assert built["overrides"]["cached_tables"] is not overrides["cached_tables"]


class TestClientCredentialsContract:
    """Pins the one private botocore interface this client depends on.

    ``BaseClient`` has no public accessor for its credentials; botocore's own
    ``_get_credentials`` reads the same attribute and is documented as breakable in any
    release. These tests run against a real boto3 client, so a botocore change fails here
    rather than at a customer's first cache handshake.
    """

    def test_credentials_are_reachable_on_a_real_client(self, monkeypatch):
        monkeypatch.setenv("AWS_ACCESS_KEY_ID", "AKIAIOSFODNN7EXAMPLE")
        monkeypatch.setenv("AWS_SECRET_ACCESS_KEY", "secret")
        monkeypatch.setenv("AWS_DEFAULT_REGION", "us-east-1")
        sdk_client = boto3.Session().client("dynamodb")

        credentials = client_mod._client_credentials(sdk_client)

        assert credentials.get_frozen_credentials().access_key == "AKIAIOSFODNN7EXAMPLE"

    def test_the_credentials_object_is_live_so_rotation_is_preserved(self):
        """The library holds the object once; refreshable credentials must refresh through it."""
        calls = {"n": 0}

        def refresh():
            calls["n"] += 1
            return {
                "access_key": f"AKIAEXAMPLEKEY{calls['n']}",
                "secret_key": "secret",
                "token": "token",
                "expiry_time": (
                    datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(minutes=30)
                ).isoformat(),
            }

        botocore_session = botocore.session.Session()
        live = RefreshableCredentials.create_from_metadata(refresh(), refresh, "assume-role")
        botocore_session._credentials = live
        sdk_client = boto3.Session(
            botocore_session=botocore_session, region_name="us-east-1"
        ).client("dynamodb")

        held = client_mod._client_credentials(sdk_client)
        assert held is live
        assert held.get_frozen_credentials().access_key == "AKIAEXAMPLEKEY1"

        live._expiry_time = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(
            minutes=1
        )
        assert held.get_frozen_credentials().access_key == "AKIAEXAMPLEKEY2"
        assert sdk_client._request_signer._credentials is held  # never replaced

    def test_rotation_survives_the_session_being_gone(self):
        """No session is kept: refresh is the credentials object's own business.

        The refresh callable is captured inside the credentials object when the chain resolves
        it (an STS call for assume-role, the SSO cache, the IMDS endpoint), so it needs nothing
        from the session afterwards. This pins that: the session is garbage-collected, and
        credentials still rotate.
        """
        sts_calls = {"n": 0}

        def assume_role_like():
            sts_calls["n"] += 1
            return {
                "access_key": f"ASIAROTATED{sts_calls['n']}",
                "secret_key": "secret",
                "token": f"session-token-{sts_calls['n']}",
                "expiry_time": (
                    datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(minutes=30)
                ).isoformat(),
            }

        botocore_session = botocore.session.Session()
        botocore_session._credentials = DeferredRefreshableCredentials(
            refresh_using=assume_role_like, method="sts-assume-role"
        )
        boto3_session = boto3.Session(botocore_session=botocore_session, region_name="us-east-1")
        sdk_client = boto3_session.client("dynamodb")

        credentials = client_mod._client_credentials(sdk_client)
        session_alive = weakref.ref(botocore_session)
        del botocore_session, boto3_session
        gc.collect()

        assert session_alive() is None  # nothing but the client and the credentials remain
        assert credentials.get_frozen_credentials().access_key == "ASIAROTATED1"

        credentials._expiry_time = datetime.datetime.now(
            datetime.timezone.utc
        ) - datetime.timedelta(minutes=1)
        assert credentials.get_frozen_credentials().access_key == "ASIAROTATED2"
        assert sts_calls["n"] == 2
