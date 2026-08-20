"""Client-side model and evaluation of the routing predicates returned by ``photon.config``.

The proxy is the authority on what may be cached (see ``design/api-filtering.md``): it merges the
client's overrides with server policy and returns an ordered predicate list. This module holds the
**internal model** — the predicate objects and the routing decision over them — plus the parser for
the format-1 predicate array. It is pure: no I/O, no session state, no service-specific knowledge.

Version dispatch lives one level up, in :mod:`photon.config_format`: it picks a parser for the
document it received and hands back this model, so the evaluation below is independent of the wire
shape and a future format needs no changes here.

Two predicate types exist in format 1, and the proxy emits either one ``service`` predicate or one
``body`` predicate per table location (``photon/config.rs::resolve``). Evaluation is ordered and
first-match-wins regardless, because the wire format is a list.

Anything this module cannot interpret is dropped rather than guessed: an unrouted request is merely
uncached, while a mis-routed one is a correctness bug.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from typing import Protocol, Sequence, runtime_checkable

from photon.exceptions import PhotonError
from photon.models import PhotonRequest

logger = logging.getLogger(__name__)

_TYPE_SERVICE = "service"
_TYPE_BODY = "body"

# How a `bodyMatch` field's value is compared against its allowlist. The proxy may state this
# explicitly (`{"match": ..., "values": [...]}`); when it sends a bare list instead, the mode is
# inferred from the body value's shape — see FieldMatch.
_MATCH_VALUE = "value"   # the body value is one name, and must itself be allowlisted
_MATCH_KEYS = "keys"     # the body value is an object whose keys are names; all must be allowlisted
_MATCH_MODES = frozenset({_MATCH_VALUE, _MATCH_KEYS})

# Mirrors the proxy's photon_max_request_body_bytes (512 KiB). A larger body is rejected
# server-side anyway, so parsing it locally only to pass through burns client CPU.
_MAX_BODY_INSPECT_BYTES = 512 * 1024


@dataclass(slots=True, frozen=True)
class FieldMatch:
    """One `bodyMatch` entry: which body field to read, how to compare it, and what is allowed.

    ``mode`` is ``None`` when the proxy sent a bare list of values rather than an explicit
    ``{"match": ..., "values": [...]}`` object. In that case the comparison is inferred from the
    body value's shape — a string is one name, an object names its keys — which is what the current
    proxy requires. An explicit mode is preferred: it lets the client *detect* a comparison it does
    not implement (and drop that predicate) instead of guessing from shape, and it removes the
    ambiguity where a string-valued field that should have been an object matches as a single name.
    """

    field: str
    mode: str | None
    allowed: frozenset[str]

    def allows(self, value: object) -> bool:
        """Whether ``value`` (the body's value for this field, or None if absent) is allowed."""
        if self.mode == _MATCH_VALUE:
            return isinstance(value, str) and value in self.allowed
        if self.mode == _MATCH_KEYS:
            return self._all_keys_allowed(value)
        # Inferred (no explicit mode): dispatch on the value's shape.
        if isinstance(value, str):
            return value in self.allowed
        return self._all_keys_allowed(value)

    def _all_keys_allowed(self, value: object) -> bool:
        """Every key of an object must be allowlisted, and there must be at least one.

        This is what makes a multi-table batch all-or-nothing: one key outside the allowlist
        disqualifies the whole request. An empty object names nothing, so nothing can be shown to be
        allowlisted.
        """
        if not isinstance(value, dict):
            return False
        return bool(value) and all(key in self.allowed for key in value)


@runtime_checkable
class Predicate(Protocol):
    """A resolved routing rule: does this request belong in the cache?"""

    def matches(self, request: PhotonRequest) -> bool: ...


@dataclass(slots=True, frozen=True)
class ServicePredicate:
    """Match by service + operation, using the metadata derived from the botocore event name.

    API-level granularity: every table is routed for the listed operations.
    """

    service: str                  # lowercased at parse time
    operations: frozenset[str]    # canonical PascalCase op names, e.g. {"GetItem", "Query"}

    def matches(self, request: PhotonRequest) -> bool:
        metadata = request.metadata
        return (metadata.service.lower() == self.service
                and metadata.operation in self.operations)


@dataclass(slots=True, frozen=True)
class BodyPredicate:
    """Service + operation match, plus every ``bodyMatch`` entry must hold allowed value(s).

    Table-level granularity. The field *names* come from the wire (``TableName``, ``RequestItems``),
    never from client code, so the client stays service-agnostic; how each field is compared is
    either stated by the proxy or inferred from the body value's shape — see :class:`FieldMatch`.

    Entries are ANDed. The proxy therefore never puts two table locations in one predicate: it emits
    one predicate per location with disjoint operation sets (``photon/config.rs::resolve``), since a
    `TableName` constraint could never be satisfied by a `BatchGetItem` body.
    """

    service: str
    operations: frozenset[str]
    # A tuple, not a dict, to keep the dataclass frozen/hashable.
    body_match: tuple[FieldMatch, ...]

    def matches(self, request: PhotonRequest) -> bool:
        metadata = request.metadata
        if (metadata.service.lower() != self.service
                or metadata.operation not in self.operations):
            return False           # cheap checks first: never parse a body we can already reject
        body = _parse_body(request.body)
        if body is None:
            return False           # unreadable body ⇒ cannot prove the values are allowed
        return all(entry.allows(body.get(entry.field)) for entry in self.body_match)


def route(predicates: Sequence[Predicate], request: PhotonRequest) -> bool:
    """True when ``request`` should go to the cache. First match wins; an empty set routes nothing.

    ``any`` over a generator short-circuits, so evaluation stops at the first matching predicate.
    """
    return any(predicate.matches(request) for predicate in predicates)


def parse_predicates(raw: object) -> tuple[Predicate, ...]:
    """Parse a **format 1** ``predicates`` array, preserving wire order.

    Called by the format-1 parser in :mod:`photon.config_format`, which owns version dispatch; this
    function only knows one array shape. Returns ``()`` for a missing or empty array — "route
    nothing", so every request passes straight through to the origin service.

    Entries this client cannot evaluate are dropped rather than guessed at: an unknown predicate
    ``type``, a malformed entry, or an unsupported ``bodyMatch`` comparison. Their siblings still
    evaluate, so a proxy using one construct this client does not implement costs only the caching
    that construct would have enabled.

    Raises:
        PhotonError: If ``predicates`` is present but is not an array — a malformed document, as
            opposed to an individual entry this client cannot evaluate.
    """
    if raw is None:
        return ()
    if not isinstance(raw, list):
        raise PhotonError(f"Config 'predicates' must be an array, got {type(raw).__name__}")
    parsed = (_parse_one(entry) for entry in raw)
    return tuple(predicate for predicate in parsed if predicate is not None)


def _parse_one(entry: object) -> Predicate | None:
    """Build one predicate, or None when the client cannot evaluate this entry."""
    if not isinstance(entry, dict):
        logger.debug("Ignoring non-object predicate entry of type %s", type(entry).__name__)
        return None
    kind = entry.get("type")
    if kind not in (_TYPE_SERVICE, _TYPE_BODY):
        logger.debug("Ignoring predicate of unsupported type %r", kind)
        return None
    service, operations = _parse_service_and_operations(entry)
    if service is None:
        return None
    if kind == _TYPE_SERVICE:
        return ServicePredicate(service=service, operations=operations)
    body_match = _parse_body_match(entry)
    if body_match is None:
        return None
    return BodyPredicate(service=service, operations=operations, body_match=body_match)


def _parse_service_and_operations(entry: dict) -> tuple[str | None, frozenset[str]]:
    """Extract the ``service``/``operations`` pair shared by every predicate type.

    An empty ``operations`` list is valid and meaningful: the customer excluded every operation, so
    the predicate exists but can never match.
    """
    service = entry.get("service")
    operations = entry.get("operations")
    if not isinstance(service, str) or not isinstance(operations, list):
        logger.debug("Ignoring malformed predicate: service=%s operations=%s",
                     type(service).__name__, type(operations).__name__)
        return None, frozenset()
    return service.lower(), frozenset(op for op in operations if isinstance(op, str))


def _parse_body_match(entry: dict) -> tuple[FieldMatch, ...] | None:
    """Parse ``bodyMatch`` into ANDed :class:`FieldMatch` entries, or None if malformed.

    Two per-field forms are accepted:

    - ``{"TableName": ["Users"]}`` — a bare list of allowed values. The comparison is inferred from
      the body value's shape. This is what the proxy sends today.
    - ``{"RequestItems": {"match": "keys", "values": ["Users"]}}`` — an explicit comparison. Preferred
      once the proxy emits it, because a mode this client does not implement is *detectable*: the
      predicate is dropped rather than evaluated by a guessed rule.

    An empty ``bodyMatch`` yields ``()``, which makes the ``all()`` in
    :meth:`BodyPredicate.matches` vacuously true — the correct reading of "no body constraint
    stated". A *malformed* entry, or an unrecognized ``match`` mode, drops the whole predicate rather
    than degrading to a service-only match: losing a constraint would cache values the customer never
    allowlisted, which is worse than not caching at all.
    """
    body_match = entry.get("bodyMatch")
    if not isinstance(body_match, dict):
        logger.debug("Ignoring body predicate with bodyMatch of type %s",
                     type(body_match).__name__)
        return None
    parsed: list[FieldMatch] = []
    for field, spec in body_match.items():
        if not isinstance(field, str):
            logger.debug("Ignoring body predicate with a non-string bodyMatch field")
            return None
        field_match = _parse_field_match(field, spec)
        if field_match is None:
            return None
        parsed.append(field_match)
    return tuple(parsed)


def _parse_field_match(field: str, spec: object) -> FieldMatch | None:
    """Parse one ``bodyMatch`` entry into a :class:`FieldMatch`, or None if unusable."""
    if isinstance(spec, list):
        # Implicit form: no mode stated, so the comparison follows the body value's shape.
        return FieldMatch(field=field, mode=None, allowed=_string_set(spec))
    if isinstance(spec, dict):
        mode = spec.get("match")
        values = spec.get("values")
        if mode not in _MATCH_MODES:
            # A comparison this client does not implement. Detectable precisely because the proxy
            # stated it — drop the predicate instead of applying some other rule to it.
            logger.debug("Ignoring body predicate with unsupported match mode %r", mode)
            return None
        if not isinstance(values, list):
            logger.debug("Ignoring body predicate whose 'values' is %s, not a list",
                         type(values).__name__)
            return None
        return FieldMatch(field=field, mode=mode, allowed=_string_set(values))
    logger.debug("Ignoring body predicate with a bodyMatch entry of type %s",
                 type(spec).__name__)
    return None


def _string_set(values: list) -> frozenset[str]:
    """The string members of ``values``. Non-strings cannot name anything, so they are dropped."""
    return frozenset(value for value in values if isinstance(value, str))


def _parse_body(body: object) -> dict | None:
    """Parse a request body into a dict, or None when it cannot be inspected.

    None on: empty, oversized, non-JSON, valid JSON that is not an object, or a body that is not
    bytes/str (botocore may hand us a file-like object for some services). Every case means "do not
    route", so an uninspectable body can never satisfy a body predicate.
    """
    if not body or not isinstance(body, (bytes, str)):
        return None
    if len(body) > _MAX_BODY_INSPECT_BYTES:
        return None
    try:
        parsed = json.loads(body)
    except (ValueError, TypeError):
        return None
    return parsed if isinstance(parsed, dict) else None
