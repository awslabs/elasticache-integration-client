"""Tests for photon.config_format — version-dispatched parsing of the photon.config reply.

These cover the *seam*, not the predicate semantics (those live in test_predicates.py): which parser
a document is routed to, what happens to a format this client does not implement, and that adding a
format is a registration rather than a change to any caller.
"""

import json

import pytest

from photon.config_format import _PARSERS, _parse_v1, parse_config
from photon.exceptions import PhotonError
from photon.models import ResolvedConfig
from photon.predicates import ServicePredicate
from tests.mock import DEFAULT_RESOLVED_CONFIG, resolved_config


# --- format 1 -----------------------------------------------------------------

def test_parses_the_document_the_proxy_sends():
    """The real reply for empty overrides, end to end through the dispatcher."""
    config = parse_config(DEFAULT_RESOLVED_CONFIG)
    assert config.version == "1.0"
    assert config.refresh_interval == 60
    assert len(config.predicates) == 1
    assert isinstance(config.predicates[0], ServicePredicate)
    # The document survives untouched alongside the parsed model, including fields not modelled
    # (ttl/nttl are resolved server-side, so the client only carries them for diagnostics).
    assert config.raw["ttl"] == 300


@pytest.mark.parametrize("version", ["1", "1.0", "1.1", "1.99", "1.x", None])
def test_any_minor_of_a_known_major_is_parsed(version):
    """Dispatch is on the major: within it, additions must be self-describing, so the parser can
    skip constructs it does not implement instead of failing the document."""
    config = parse_config(resolved_config(version=version))
    assert len(config.predicates) == 1


def test_absent_version_is_read_as_format_1():
    """The field was introduced with format 1, so a document without one cannot be later."""
    raw = {k: v for k, v in DEFAULT_RESOLVED_CONFIG.items() if k != "version"}
    assert len(parse_config(raw).predicates) == 1


def test_newer_minor_is_logged(caplog):
    with caplog.at_level("DEBUG", logger="photon.config_format"):
        parse_config(resolved_config(version="1.99"))
    assert "newer constructs will be skipped" in caplog.text


def test_missing_predicates_key_routes_nothing():
    raw = {k: v for k, v in DEFAULT_RESOLVED_CONFIG.items() if k != "predicates"}
    assert parse_config(raw).predicates == ()


def test_malformed_predicates_array_is_an_error():
    """A broken document is an error; an entry this client cannot evaluate is not."""
    with pytest.raises(PhotonError):
        parse_config(resolved_config(predicates={"type": "service"}))


# --- unknown formats ----------------------------------------------------------

@pytest.mark.parametrize("version", ["2.0", "0.9", "3", "v3", "", "nonsense"])
def test_unknown_format_routes_nothing(version, caplog):
    """A format with no parser cannot be read safely — a *known* construct may have changed meaning,
    which per-entry detection cannot catch — so nothing is routed and everything passes through."""
    with caplog.at_level("WARNING", logger="photon.config_format"):
        config = parse_config(resolved_config(version=version))
    assert config.predicates == ()
    assert config.version == version
    assert "not supported by this client" in caplog.text


def test_unknown_format_still_retains_the_raw_document():
    """Nothing from the server is silently discarded, even when it cannot be interpreted."""
    raw = resolved_config(version="2.0")
    assert parse_config(raw).raw == raw


# --- extending to a new format ------------------------------------------------

def test_registering_a_parser_is_all_a_new_format_needs(monkeypatch):
    """Documents the extension point: a format that restructures the document entirely — predicates
    nested under a different key, a different predicate shape — is supported by registering one
    parser. No caller changes, and format 1 keeps working alongside it.
    """
    def _parse_v2(raw: dict) -> ResolvedConfig:
        # A deliberately different structure: everything nested under "routing".
        routing = raw["routing"]
        return ResolvedConfig(
            version=raw.get("version"),
            refresh_interval=routing.get("refresh"),
            raw=raw,
            predicates=(ServicePredicate(service="dynamodb",
                                         operations=frozenset(routing["ops"])),),
        )

    monkeypatch.setitem(_PARSERS, "2", _parse_v2)

    v2 = parse_config({"version": "2.0", "routing": {"refresh": 30, "ops": ["GetItem"]}})
    assert v2.refresh_interval == 30
    assert v2.predicates[0].operations == frozenset({"GetItem"})

    # Format 1 is unaffected: both vintages of proxy remain readable by this client.
    assert parse_config(DEFAULT_RESOLVED_CONFIG).refresh_interval == 60


def test_parsers_are_registered_per_major():
    """The registry keys are bare majors — a minor in a key would never be matched by dispatch."""
    assert all("." not in major for major in _PARSERS)
    assert _PARSERS["1"] is _parse_v1


# --- through the protocol layer ----------------------------------------------

def test_reaches_the_dispatcher_from_a_resp_reply():
    """protocol.parse_config_response owns framing and JSON only; interpretation is dispatched."""
    from photon.protocol import parse_config_response

    reply = json.dumps(resolved_config(version="2.0")).encode()
    assert parse_config_response(reply).predicates == ()

    reply = json.dumps(DEFAULT_RESOLVED_CONFIG).encode()
    assert len(parse_config_response(reply).predicates) == 1
