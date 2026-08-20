"""Tests for photon.predicates.

The module is pure, so these tests need no socket, no session and no boto3. The JSON fed to
``parse_predicates`` is the **real** shape ``photon/config.rs::resolve`` emits — the client's
contract is with the proxy's code, not with the (partly stale) design docs.
"""

import json

import pytest

from photon.exceptions import PhotonError
from photon.models import PhotonRequest, RequestMetadata
from photon.predicates import (
    BodyPredicate,
    FieldMatch,
    ServicePredicate,
    _MAX_BODY_INSPECT_BYTES,
    parse_predicates,
    route,
)

# --- the two configs the proxy actually emits ---------------------------------

SERVICE_CONFIG = {
    "version": "1.0",
    "refresh_interval": 60,
    "ttl": 300,
    "nttl": 60,
    "predicates": [
        {"type": "service", "service": "dynamodb",
         "operations": ["GetItem", "BatchGetItem", "Query", "Scan"]}
    ],
}

BODY_CONFIG = {
    "version": "1.0",
    "refresh_interval": 60,
    "ttl": 300,
    "nttl": 60,
    "predicates": [
        {"type": "body", "service": "dynamodb",
         "operations": ["GetItem", "BatchGetItem", "Query", "Scan"],
         "bodyMatch": {"TableName": {"match": "value", "values": ["Sessions", "Users"]}}}
    ],
}

# The batch half of a table allowlist: tables live in the *keys* of `RequestItems`.
BATCH_CONFIG = {
    **BODY_CONFIG,
    "predicates": [
        {"type": "body", "service": "dynamodb", "operations": ["BatchGetItem"],
         "bodyMatch": {"RequestItems": {"match": "keys", "values": ["Sessions", "Users"]}}}
    ],
}

# What the proxy actually emits for `tables={"Users": {...}}`: one predicate per table location,
# with disjoint operation sets (see photon/config.rs::resolve).
ALLOWLIST_CONFIG = {
    **BODY_CONFIG,
    "predicates": [
        {"type": "body", "service": "dynamodb", "operations": ["GetItem", "Query", "Scan"],
         "bodyMatch": {"TableName": {"match": "value", "values": ["Users"]}}},
        {"type": "body", "service": "dynamodb", "operations": ["BatchGetItem"],
         "bodyMatch": {"RequestItems": {"match": "keys", "values": ["Users"]}}},
    ],
}


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


def _parse(config):
    return parse_predicates(config.get("predicates"))


# --- parsing: service predicate -----------------------------------------------

def test_parses_the_service_config_the_proxy_emits():
    """The resolved config for `{}` overrides yields one ServicePredicate over the four reads."""
    predicates = _parse(SERVICE_CONFIG)
    assert predicates == (
        ServicePredicate(service="dynamodb",
                         operations=frozenset({"GetItem", "BatchGetItem", "Query", "Scan"})),
    )


def test_parses_excluded_operations_as_a_shorter_operation_set():
    """`exclude_operations` is resolved server-side; the client just sees fewer operations."""
    config = {**SERVICE_CONFIG, "predicates": [
        {"type": "service", "service": "dynamodb", "operations": ["GetItem", "BatchGetItem"]}
    ]}
    assert _parse(config)[0].operations == frozenset({"GetItem", "BatchGetItem"})


def test_service_name_is_lowercased_at_parse_time():
    """The client's service name comes from the botocore event name, so compare case-insensitively."""
    config = {**SERVICE_CONFIG, "predicates": [
        {"type": "service", "service": "DynamoDB", "operations": ["GetItem"]}
    ]}
    assert _parse(config)[0].service == "dynamodb"


# --- parsing: array-level handling --------------------------------------------

@pytest.mark.parametrize("raw", [None, [], {"predicates": None}.get("predicates")])
def test_missing_or_empty_predicates_route_nothing(raw):
    assert parse_predicates(raw) == ()


@pytest.mark.parametrize("raw", [{}, "service", 7])
def test_non_array_predicates_is_a_malformed_config(raw):
    """A broken config *document* is an error, unlike an individual entry we cannot evaluate."""
    with pytest.raises(PhotonError):
        parse_predicates(raw)


def test_unknown_predicate_type_is_dropped_not_guessed():
    """The `generic` type the docs describe is never emitted by the proxy — and unevaluatable here."""
    config = {**SERVICE_CONFIG, "predicates": [
        {"type": "generic", "method": "POST", "urlPattern": "https://dynamodb.*.amazonaws.com/*"}
    ]}
    assert _parse(config) == ()


def test_unknown_entries_are_dropped_and_siblings_kept():
    """Forward compatibility: an old client still evaluates the entries it does understand."""
    config = {**SERVICE_CONFIG, "predicates": [
        {"type": "future", "whatever": True},
        SERVICE_CONFIG["predicates"][0],
    ]}
    assert len(_parse(config)) == 1


@pytest.mark.parametrize("entry", [
    "not-an-object",
    42,
    None,
    {"type": "service", "operations": ["GetItem"]},                 # no service
    {"type": "service", "service": "dynamodb"},                     # no operations
    {"type": "service", "service": "dynamodb", "operations": "GetItem"},  # not a list
    {"type": "service", "service": 7, "operations": ["GetItem"]},    # wrong type
])
def test_malformed_entries_are_dropped(entry):
    assert parse_predicates([entry]) == ()


def test_non_string_operations_are_filtered_out():
    config = {**SERVICE_CONFIG, "predicates": [
        {"type": "service", "service": "dynamodb", "operations": ["GetItem", 7, None]}
    ]}
    assert _parse(config)[0].operations == frozenset({"GetItem"})


def test_empty_operations_yields_a_predicate_that_never_matches():
    """Excluding every operation is valid: the predicate exists but matches nothing."""
    config = {**SERVICE_CONFIG, "predicates": [
        {"type": "service", "service": "dynamodb", "operations": []}
    ]}
    predicates = _parse(config)
    assert len(predicates) == 1
    assert not route(predicates, _request())


# --- evaluation: service predicate --------------------------------------------

def test_routes_a_listed_operation():
    assert route(_parse(SERVICE_CONFIG), _request("GetItem")) is True


@pytest.mark.parametrize("operation", ["PutItem", "UpdateItem", "DeleteItem", "CreateTable"])
def test_does_not_route_writes_or_unlisted_operations(operation):
    assert route(_parse(SERVICE_CONFIG), _request(operation)) is False


def test_does_not_route_another_service():
    assert route(_parse(SERVICE_CONFIG), _request(service="s3")) is False


def test_service_match_is_case_insensitive():
    assert route(_parse(SERVICE_CONFIG), _request(service="DYNAMODB")) is True


def test_operation_match_is_case_sensitive():
    """Both sides use the canonical PascalCase name; a loose compare would hide a real mismatch."""
    assert route(_parse(SERVICE_CONFIG), _request("getitem")) is False


# --- evaluation: route() semantics --------------------------------------------

def test_route_returns_false_with_no_predicates():
    assert route((), _request()) is False


def test_route_stops_at_the_first_match():
    """First-match-wins: later predicates are not consulted once one matches."""
    calls = []

    class Spy:
        def __init__(self, result):
            self.result = result

        def matches(self, request):
            calls.append(self.result)
            return self.result

    assert route((Spy(False), Spy(True), Spy(False)), _request()) is True
    assert calls == [False, True]  # the third was never evaluated


# --- parsing + evaluation: body predicate ------------------------------------

def test_parses_the_body_config_the_proxy_emits():
    predicates = _parse(BODY_CONFIG)
    assert predicates == (
        BodyPredicate(
            service="dynamodb",
            operations=frozenset({"GetItem", "BatchGetItem", "Query", "Scan"}),
            body_match=(FieldMatch(field="TableName", mode="value",
                                   allowed=frozenset({"Sessions", "Users"})),),
        ),
    )


def test_routes_an_allowlisted_table():
    assert route(_parse(BODY_CONFIG), _get_item("Users")) is True


def test_does_not_route_a_table_outside_the_allowlist():
    assert route(_parse(BODY_CONFIG), _get_item("Orders")) is False


def test_does_not_route_when_the_body_omits_the_field():
    assert route(_parse(BODY_CONFIG), _request(body=b'{"Key":{"id":{"S":"1"}}}')) is False


def test_body_is_not_parsed_when_the_operation_already_fails(monkeypatch):
    """Cheap checks first: a write never pays the JSON parse."""
    import photon.predicates as predicates_module

    def explode(*_args, **_kwargs):
        raise AssertionError("body must not be parsed when service/operation cannot match")

    monkeypatch.setattr(predicates_module.json, "loads", explode)
    assert route(_parse(BODY_CONFIG), _request("PutItem", body=b'{"TableName":"Users"}')) is False


@pytest.mark.parametrize("body", [
    b"",                          # empty
    b"not json",                  # unparseable
    b'["TableName"]',             # valid JSON, not an object
    b"null",                      # valid JSON, not an object
    object(),                     # not bytes/str (botocore may hand us a stream)
])
def test_uninspectable_body_never_matches(body):
    assert route(_parse(BODY_CONFIG), _request(body=body)) is False


def test_oversized_body_is_not_inspected():
    """Mirrors the proxy's body-size gate: it would reject this anyway."""
    padding = "x" * _MAX_BODY_INSPECT_BYTES
    body = json.dumps({"TableName": "Users", "Pad": padding}).encode()
    assert len(body) > _MAX_BODY_INSPECT_BYTES
    assert route(_parse(BODY_CONFIG), _request(body=body)) is False


def test_multi_table_batch_matches_when_every_table_is_allowlisted():
    """`RequestItems` names its tables in the map's keys; all of them must be allowlisted."""
    body = json.dumps({"RequestItems": {"Users": {"Keys": [{"id": {"S": "1"}}]},
                                        "Sessions": {"Keys": [{"id": {"S": "2"}}]}}}).encode()
    assert route(_parse(BATCH_CONFIG), _request("BatchGetItem", body=body)) is True


def test_mixed_batch_passes_through_whole():
    """All-or-nothing: one non-allowlisted table disqualifies the entire batch.

    A predicate yields a single boolean, so it cannot split a batch — and caching only the
    allowlisted part would need per-table handling the proxy does not have.
    """
    body = json.dumps({"RequestItems": {"Users": {}, "Orders": {}}}).encode()
    assert route(_parse(BATCH_CONFIG), _request("BatchGetItem", body=body)) is False


def test_empty_request_items_does_not_match():
    """An empty map names no table, so nothing can be shown to be allowlisted."""
    assert route(_parse(BATCH_CONFIG), _request("BatchGetItem", body=b'{"RequestItems":{}}')) is False


@pytest.mark.parametrize("value", ["7", "[\"Users\"]", "null", "7.5"])
def test_wrong_shaped_field_value_does_not_match(value):
    """Only a string or an object names tables; anything else is uninterpretable."""
    body = ('{"RequestItems":' + value + "}").encode()
    assert route(_parse(BATCH_CONFIG), _request("BatchGetItem", body=body)) is False


def test_single_table_and_batch_predicates_coexist():
    """The two predicates the proxy emits for one allowlist, evaluated as an ordered list.

    Disjoint operation sets, so neither shadows the other under first-match-wins.
    """
    predicates = _parse(ALLOWLIST_CONFIG)
    assert len(predicates) == 2
    get_item = _request("GetItem", body=json.dumps({"TableName": "Users"}).encode())
    batch = _request("BatchGetItem", body=json.dumps({"RequestItems": {"Users": {}}}).encode())
    other_table = _request("GetItem", body=json.dumps({"TableName": "Orders"}).encode())

    assert route(predicates, get_item) is True
    assert route(predicates, batch) is True
    assert route(predicates, other_table) is False


def test_batch_body_shape_does_not_satisfy_a_table_name_predicate():
    """The single-table predicate must not match a batch body, and vice versa.

    This is why the proxy splits them: if `TableName` and `RequestItems` shared one predicate, the
    ANDed fields could never both be satisfied by either body shape.
    """
    batch_body = json.dumps({"RequestItems": {"Users": {}}}).encode()
    # BODY_CONFIG's predicate constrains TableName and covers BatchGetItem, so it is the shape the
    # proxy no longer emits — asserted here to pin why.
    assert route(_parse(BODY_CONFIG), _request("BatchGetItem", body=batch_body)) is False


def test_body_match_fields_are_anded():
    config = {**BODY_CONFIG, "predicates": [
        {"type": "body", "service": "dynamodb", "operations": ["Query"],
         "bodyMatch": {"TableName": {"match": "value", "values": ["Users"]},
                       "IndexName": {"match": "value", "values": ["by-email"]}}}
    ]}
    predicates = _parse(config)
    both = json.dumps({"TableName": "Users", "IndexName": "by-email"}).encode()
    one = json.dumps({"TableName": "Users", "IndexName": "by-name"}).encode()
    assert route(predicates, _request("Query", body=both)) is True
    assert route(predicates, _request("Query", body=one)) is False


def test_empty_body_match_matches_on_service_and_operation_alone():
    """No body constraint stated ⇒ the `all()` is vacuously true."""
    config = {**BODY_CONFIG, "predicates": [
        {"type": "body", "service": "dynamodb", "operations": ["GetItem"], "bodyMatch": {}}
    ]}
    assert route(_parse(config), _request("GetItem", body=b"{}")) is True


def test_empty_allowlist_matches_nothing():
    """Every allowlisted table was also excluded: cache nothing, rather than everything."""
    config = {**BODY_CONFIG, "predicates": [
        {"type": "body", "service": "dynamodb", "operations": ["GetItem"],
         "bodyMatch": {"TableName": {"match": "value", "values": []}}}
    ]}
    assert route(_parse(config), _get_item("Users")) is False


@pytest.mark.parametrize("body_match", [None, "TableName", ["TableName"], {"TableName": "Users"}])
def test_malformed_body_match_drops_the_whole_predicate(body_match):
    """Never degrade to a service-only match: that would cache un-allowlisted tables."""
    config = {**BODY_CONFIG, "predicates": [
        {"type": "body", "service": "dynamodb", "operations": ["GetItem"], "bodyMatch": body_match}
    ]}
    assert _parse(config) == ()


def test_missing_body_match_drops_the_predicate():
    config = {**BODY_CONFIG, "predicates": [
        {"type": "body", "service": "dynamodb", "operations": ["GetItem"]}
    ]}
    assert _parse(config) == ()


def test_non_string_allowlist_values_are_filtered_out():
    config = {**BODY_CONFIG, "predicates": [
        {"type": "body", "service": "dynamodb", "operations": ["GetItem"],
         "bodyMatch": {"TableName": {"match": "value", "values": ["Users", 7, None]}}}
    ]}
    assert _parse(config)[0].body_match == (
        FieldMatch(field="TableName", mode="value", allowed=frozenset({"Users"})),
    )


def test_table_name_is_matched_exactly_without_normalization():
    """An ARN in TableName is a different string; the proxy's cache key treats it the same way."""
    arn = "arn:aws:dynamodb:us-east-1:123456789012:table/Users"
    assert route(_parse(BODY_CONFIG), _get_item(arn)) is False


# --- explicit match modes (the proxy does not emit these yet) ------------------

def _explicit(field, mode, values, operations=("GetItem",)):
    """A config whose single body predicate states its comparison explicitly."""
    return {**BODY_CONFIG, "predicates": [
        {"type": "body", "service": "dynamodb", "operations": list(operations),
         "bodyMatch": {field: {"match": mode, "values": list(values)}}}
    ]}


def test_explicit_value_mode_is_parsed_and_applied():
    predicates = _parse(_explicit("TableName", "value", ["Users"]))
    assert predicates[0].body_match == (
        FieldMatch(field="TableName", mode="value", allowed=frozenset({"Users"})),
    )
    assert route(predicates, _get_item("Users")) is True
    assert route(predicates, _get_item("Orders")) is False


def test_explicit_keys_mode_is_parsed_and_applied():
    predicates = _parse(_explicit("RequestItems", "keys", ["Users", "Sessions"],
                                  operations=("BatchGetItem",)))
    assert predicates[0].body_match == (
        FieldMatch(field="RequestItems", mode="keys",
                   allowed=frozenset({"Users", "Sessions"})),
    )
    both = json.dumps({"RequestItems": {"Users": {}, "Sessions": {}}}).encode()
    mixed = json.dumps({"RequestItems": {"Users": {}, "Orders": {}}}).encode()
    assert route(predicates, _request("BatchGetItem", body=both)) is True
    assert route(predicates, _request("BatchGetItem", body=mixed)) is False


def test_a_stated_mode_is_applied_literally():
    """The comparison is never inferred from the value's shape, which is the point of stating it: a
    string-valued RequestItems does not match a `keys` comparison (a string has no keys), and an
    object-valued TableName does not match a `value` comparison. Under shape inference the first of
    those matched as a single table name.
    """
    keys_mode = _parse(_explicit("RequestItems", "keys", ["Users"],
                                 operations=("BatchGetItem",)))
    string_value = json.dumps({"RequestItems": "Users"}).encode()
    assert route(keys_mode, _request("BatchGetItem", body=string_value)) is False

    value_mode = _parse(_explicit("TableName", "value", ["Users"]))
    object_value = json.dumps({"TableName": {"Users": {}}}).encode()
    assert route(value_mode, _request("GetItem", body=object_value)) is False


def test_unsupported_match_mode_drops_only_that_predicate():
    """A mode this client does not implement is *detectable*, unlike a shape-inferred rule."""
    config = {**BODY_CONFIG, "predicates": [
        {"type": "body", "service": "dynamodb", "operations": ["GetItem"],
         "bodyMatch": {"TableName": {"match": "regex", "values": ["Users.*"]}}},
        SERVICE_CONFIG["predicates"][0],
    ]}
    predicates = _parse(config)
    assert len(predicates) == 1, "the unevaluatable predicate is dropped, its sibling kept"
    assert isinstance(predicates[0], ServicePredicate)


@pytest.mark.parametrize("spec", [
    {"values": ["Users"]},                        # no mode stated
    {"match": "value"},                           # no values
    {"match": "value", "values": "Users"},        # values not a list
    {"match": None, "values": ["Users"]},
    "Users",                                      # neither a list nor an object
    7,
])
def test_malformed_explicit_entry_drops_the_whole_predicate(spec):
    """Never degrade to a service-only match: that would cache un-allowlisted tables."""
    config = {**BODY_CONFIG, "predicates": [
        {"type": "body", "service": "dynamodb", "operations": ["GetItem"],
         "bodyMatch": {"TableName": spec}}
    ]}
    assert _parse(config) == ()


def test_non_string_body_match_field_drops_the_predicate():
    """Not reachable from json.loads (JSON object keys are always strings), but parse_predicates
    accepts arbitrary objects, and a field name that cannot be looked up must not silently become a
    predicate that never matches."""
    assert parse_predicates(
        [{"type": "body", "service": "dynamodb", "operations": ["GetItem"],
          "bodyMatch": {7: {"match": "value", "values": ["Users"]}}}]
    ) == ()
