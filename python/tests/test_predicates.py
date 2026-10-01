"""Tests for photon.predicates — CEL evaluation of resolved-config predicates.

Requires ``cel-python`` (a runtime dependency). The expressions used here are the ones the proxy
actually emits (see ElastiCacheProxyRust/src/libproxy/photon/config.rs), so these tests double as a
cross-check that the client evaluates the proxy's wire format correctly.
"""

import json

import pytest

from photon.exceptions import PhotonConfigUnevaluable
from photon.models import PhotonRequest, RequestMetadata
from photon.predicates import (
    _CONTEXT_NAMES,
    _build_context,
    compile_predicates,
    should_route_to_photon,
)
from tests.mock import DEFAULT_RESOLVED_CONFIG, table_allowlist_config


def _request(operation="GetItem", service="dynamodb", body=b"{}"):
    return PhotonRequest(
        method="POST",
        url="https://dynamodb.us-east-1.amazonaws.com/",
        headers={},
        body=body,
        metadata=RequestMetadata(service=service, operation=operation),
    )


def _get_item(table="Users"):
    return _request(body=json.dumps({"TableName": table, "Key": {"id": {"S": "1"}}}).encode())


# --- compilation --------------------------------------------------------------

def test_compiles_the_all_reads_config_the_proxy_emits():
    predicates = compile_predicates(DEFAULT_RESOLVED_CONFIG)
    assert len(predicates) == 1


def test_missing_or_empty_predicates_compile_to_empty():
    assert compile_predicates(None) == []
    assert compile_predicates({}) == []
    assert compile_predicates({"predicates": []}) == []


@pytest.mark.parametrize("value", ["nope", {"expr": "true"}, None])
def test_predicates_that_are_not_a_list_raise(value):
    with pytest.raises(PhotonConfigUnevaluable):
        compile_predicates({"predicates": value})


def test_predicate_without_string_expr_raises():
    with pytest.raises(PhotonConfigUnevaluable):
        compile_predicates({"predicates": [{"vars": {"ops": ["GetItem"]}}]})


def test_uncompilable_expression_raises():
    config = {"predicates": [
        {"expr": "this is not (valid CEL", "vars": {}},
        DEFAULT_RESOLVED_CONFIG["predicates"][0],
    ]}
    with pytest.raises(PhotonConfigUnevaluable):
        compile_predicates(config)


# --- routing: all-reads (no allowlist) ----------------------------------------

def test_routes_a_listed_operation():
    predicates = compile_predicates(DEFAULT_RESOLVED_CONFIG)
    assert should_route_to_photon(predicates, _request("GetItem")) is True
    assert should_route_to_photon(predicates, _request("Query")) is True
    assert should_route_to_photon(predicates, _request("Scan")) is True


@pytest.mark.parametrize("operation", ["PutItem", "UpdateItem", "DeleteItem", "TransactWriteItems"])
def test_writes_route_under_the_all_ops_predicate(operation):
    """Writes are routed too (write-through invalidation), so they match with no allowlist."""
    predicates = compile_predicates(DEFAULT_RESOLVED_CONFIG)
    assert should_route_to_photon(predicates, _request(operation)) is True


@pytest.mark.parametrize("operation", ["CreateTable", "DescribeTable", "ExecuteStatement"])
def test_does_not_route_unsupported_operations(operation):
    predicates = compile_predicates(DEFAULT_RESOLVED_CONFIG)
    assert should_route_to_photon(predicates, _request(operation)) is False


def test_does_not_route_another_service():
    predicates = compile_predicates(DEFAULT_RESOLVED_CONFIG)
    assert should_route_to_photon(predicates, _request(service="s3")) is False


def test_empty_predicate_set_routes_nothing():
    assert should_route_to_photon([], _request("GetItem")) is False


# --- routing: table allowlist -------------------------------------------------

def test_routes_only_allowlisted_tables():
    predicates = compile_predicates(table_allowlist_config("Users"))
    assert should_route_to_photon(predicates, _get_item("Users")) is True
    assert should_route_to_photon(predicates, _get_item("Orders")) is False


def test_missing_table_name_does_not_route():
    predicates = compile_predicates(table_allowlist_config("Users"))
    assert should_route_to_photon(predicates, _request("GetItem", body=b'{"Key":{}}')) is False


@pytest.mark.parametrize("operation", ["PutItem", "UpdateItem", "DeleteItem"])
def test_single_table_writes_route_by_table_name(operation):
    """Writes share the TableName body shape with the single-item reads."""
    predicates = compile_predicates(table_allowlist_config("Users"))
    assert should_route_to_photon(predicates, _request(operation, body=b'{"TableName":"Users"}')) is True
    assert should_route_to_photon(predicates, _request(operation, body=b'{"TableName":"Orders"}')) is False


@pytest.mark.parametrize("operation", ["GetItem", "Query", "Scan"])
def test_single_table_reads_route_by_table_name(operation):
    """GetItem, Query and Scan all match on body.TableName under an allowlist."""
    predicates = compile_predicates(table_allowlist_config("Users"))
    assert should_route_to_photon(predicates, _request(operation, body=b'{"TableName":"Users"}')) is True
    assert should_route_to_photon(predicates, _request(operation, body=b'{"TableName":"Orders"}')) is False


def test_batch_write_routes_by_request_items():
    predicates = compile_predicates(table_allowlist_config("Users"))
    allowed = json.dumps({"RequestItems": {"Users": []}}).encode()
    outside = json.dumps({"RequestItems": {"Users": [], "Orders": []}}).encode()
    assert should_route_to_photon(predicates, _request("BatchWriteItem", body=allowed)) is True
    assert should_route_to_photon(predicates, _request("BatchWriteItem", body=outside)) is False


def test_transact_write_routes_by_transact_items():
    predicates = compile_predicates(table_allowlist_config("Users"))
    allowed = json.dumps({"TransactItems": [{"Put": {"TableName": "Users"}}]}).encode()
    outside = json.dumps({"TransactItems": [{"Put": {"TableName": "Orders"}}]}).encode()
    assert should_route_to_photon(predicates, _request("TransactWriteItems", body=allowed)) is True
    assert should_route_to_photon(predicates, _request("TransactWriteItems", body=outside)) is False


def test_batch_routes_only_when_every_table_is_allowlisted():
    predicates = compile_predicates(table_allowlist_config("Users", "Sessions"))
    both = json.dumps({"RequestItems": {"Users": {}, "Sessions": {}}}).encode()
    mixed = json.dumps({"RequestItems": {"Users": {}, "Orders": {}}}).encode()
    assert should_route_to_photon(predicates, _request("BatchGetItem", body=both)) is True
    assert should_route_to_photon(predicates, _request("BatchGetItem", body=mixed)) is False


def test_empty_request_items_does_not_route():
    """The size(...) > 0 guard: an empty batch names nothing to allowlist."""
    predicates = compile_predicates(table_allowlist_config("Users"))
    assert should_route_to_photon(predicates, _request("BatchGetItem", body=b'{"RequestItems":{}}')) is False


# --- routing: transactions ----------------------------------------------------

def test_transact_get_routes_only_when_every_table_is_allowlisted():
    predicates = compile_predicates(table_allowlist_config("Users", "Sessions"))
    both = json.dumps({"TransactItems": [
        {"Get": {"TableName": "Users", "Key": {}}},
        {"Get": {"TableName": "Sessions", "Key": {}}},
    ]}).encode()
    mixed = json.dumps({"TransactItems": [
        {"Get": {"TableName": "Users", "Key": {}}},
        {"Get": {"TableName": "Orders", "Key": {}}},
    ]}).encode()
    assert should_route_to_photon(predicates, _request("TransactGetItems", body=both)) is True
    assert should_route_to_photon(predicates, _request("TransactGetItems", body=mixed)) is False


def test_empty_transact_items_does_not_route():
    predicates = compile_predicates(table_allowlist_config("Users"))
    assert should_route_to_photon(predicates, _request("TransactGetItems", body=b'{"TransactItems":[]}')) is False


def test_transact_get_routes_under_the_all_reads_predicate():
    """With no allowlist, TransactGetItems matches the service+operation predicate."""
    predicates = compile_predicates(DEFAULT_RESOLVED_CONFIG)
    assert should_route_to_photon(predicates, _request("TransactGetItems")) is True


# --- body parsing / fail-safe -------------------------------------------------

@pytest.mark.parametrize("body", [b"", b"not json", b'["TableName"]', b"null", object()])
def test_uninspectable_body_does_not_route(body):
    predicates = compile_predicates(table_allowlist_config("Users"))
    assert should_route_to_photon(predicates, _request(body=body)) is False


def test_a_large_body_is_still_inspected():
    # The client imposes no size limit of its own: a big body is parsed and routes on its contents.
    body = json.dumps({"TableName": "Users", "Pad": "x" * (1024 * 1024)}).encode()
    predicates = compile_predicates(table_allowlist_config("Users"))
    assert should_route_to_photon(predicates, _request(body=body)) is True




def test_table_name_matched_exactly_without_normalization():
    predicates = compile_predicates(table_allowlist_config("Users"))
    arn = "arn:aws:dynamodb:us-east-1:123456789012:table/Users"
    assert should_route_to_photon(predicates, _get_item(arn)) is False


# --- context integrity --------------------------------------------------------

def test_non_object_predicate_entries_raise():
    config = {"predicates": ["nope", 42, None, [], DEFAULT_RESOLVED_CONFIG["predicates"][0]]}
    with pytest.raises(PhotonConfigUnevaluable):
        compile_predicates(config)


def test_variable_may_not_shadow_a_context_name(caplog):
    # A variable named `service` must not make a non-DynamoDB request match a dynamodb predicate.
    config = {"predicates": [{
        "expr": "service == 'dynamodb' && operation in ops",
        "vars": {"ops": ["GetItem"], "service": "s3"},
    }]}
    with caplog.at_level("WARNING", logger="photon.predicates"):
        predicates = compile_predicates(config)
    assert "reserved context name" in caplog.text
    # The real service still decides: s3 does not route, dynamodb does.
    assert should_route_to_photon(predicates, _request(service="s3")) is False
    assert should_route_to_photon(predicates, _request(service="dynamodb")) is True


def test_context_names_match_the_reserved_set():
    # Guards against a new context field being added without reserving its name.
    assert set(_build_context(_get_item())) == _CONTEXT_NAMES








@pytest.mark.parametrize("expr", ["'yes'", "1", "['GetItem']", "{'a': 1}"])
def test_non_boolean_result_does_not_route(expr, caplog):
    # A truthy non-boolean would otherwise coerce to True and route everything.
    predicates = compile_predicates({"predicates": [{"expr": expr, "vars": {}}]})
    with caplog.at_level("WARNING", logger="photon.predicates"):
        assert should_route_to_photon(predicates, _get_item()) is False
    assert "not bool" in caplog.text


def test_non_boolean_result_warns_once_per_predicate(caplog):
    predicates = compile_predicates({"predicates": [{"expr": "'yes'", "vars": {}}]})
    with caplog.at_level("WARNING", logger="photon.predicates"):
        for _ in range(5):
            should_route_to_photon(predicates, _get_item())
    assert len([r for r in caplog.records if "not bool" in r.message]) == 1
