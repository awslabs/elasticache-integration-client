"""Client-side model and evaluation of the routing predicates returned by ``photon.config``.

The proxy is the authority on what may be cached (see ``design/api-filtering.md``): it merges the
client's overrides with server policy and returns an ordered predicate list. This module only
*interprets* that list — it decides, per intercepted request, "route to the cache" or "pass through
to the origin service". It is pure: no I/O, no session state, no service-specific knowledge.

Two predicate types exist on the wire, and the proxy emits exactly one of them per response
(``photon/config.rs::resolve``): a ``service`` predicate when the customer set no table allowlist,
otherwise a ``body`` predicate carrying that allowlist. Evaluation is ordered and first-match-wins
anyway, because the wire format is a list and a future proxy may send several.

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

# Predicate *schema* version the client understands. The proxy's PREDICATE_FORMAT_VERSION is
# "1.0" and is constant per format — it is NOT a content hash, so it can never be used to detect
# that a config changed. It is used here for one thing only: refusing a format whose known types
# may have changed meaning (see _version_supported).
_SUPPORTED_MAJOR = "1"

_TYPE_SERVICE = "service"
_TYPE_BODY = "body"

# Mirrors the proxy's photon_max_request_body_bytes (512 KiB). A larger body is rejected
# server-side anyway, so parsing it locally only to pass through burns client CPU.
_MAX_BODY_INSPECT_BYTES = 512 * 1024


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
    """Service + operation match, plus every ``bodyMatch`` field must hold allowed value(s).

    Table-level granularity. The field *names* come from the wire (``TableName``, ``RequestItems``),
    never from client code, so the client stays service-agnostic.

    A field's shape decides how it is matched, mirroring DynamoDB's own request format:

    - **string** (``TableName`` on single-table reads) — the value must be allowlisted.
    - **object** (``RequestItems`` on batch reads, keyed by table name) — **every** key must be
      allowlisted, and there must be at least one.

    The object rule makes batch reads all-or-nothing: a batch spanning an allowlisted and a
    non-allowlisted table does not match and passes through whole. A single predicate cannot split a
    batch, and caching only part of one would need per-table handling the proxy does not have.

    Fields are ANDed. The proxy therefore never puts two table locations in one predicate — it emits
    one predicate per location with disjoint operation sets (``photon/config.rs::resolve``).
    """

    service: str
    operations: frozenset[str]
    # ((field, allowed_values), ...) — a tuple, not a dict, to keep the dataclass frozen/hashable.
    body_match: tuple[tuple[str, frozenset[str]], ...]

    def matches(self, request: PhotonRequest) -> bool:
        metadata = request.metadata
        if (metadata.service.lower() != self.service
                or metadata.operation not in self.operations):
            return False           # cheap checks first: never parse a body we can already reject
        body = _parse_body(request.body)
        if body is None:
            return False           # unreadable body ⇒ cannot prove the values are allowed
        return all(_field_allowed(body.get(field), allowed)
                   for field, allowed in self.body_match)


def route(predicates: Sequence[Predicate], request: PhotonRequest) -> bool:
    """True when ``request`` should go to the cache. First match wins; an empty set routes nothing.

    ``any`` over a generator short-circuits, so evaluation stops at the first matching predicate.
    """
    return any(predicate.matches(request) for predicate in predicates)


def parse_predicates(raw: object, version: object = None) -> tuple[Predicate, ...]:
    """Parse the ``predicates`` array of a resolved config, preserving wire order.

    Returns ``()`` for a missing/empty array or an unsupported format major — both mean "route
    nothing", so every request passes straight through to the origin service.

    Raises:
        PhotonError: If ``predicates`` is present but is not an array (a malformed config document,
            as opposed to an individual entry this client cannot evaluate).
    """
    if not _version_supported(version):
        logger.warning(
            "photon.config predicate format %r is unsupported (this client supports %s.x); "
            "no requests will be routed to the cache until the client is upgraded",
            version, _SUPPORTED_MAJOR,
        )
        return ()
    if raw is None:
        return ()
    if not isinstance(raw, list):
        raise PhotonError(f"Config 'predicates' must be an array, got {type(raw).__name__}")
    parsed = (_parse_one(entry) for entry in raw)
    return tuple(predicate for predicate in parsed if predicate is not None)


def _version_supported(version: object) -> bool:
    """Whether this client can evaluate the given predicate format version.

    A missing version is tolerated (treated as supported); a differing major is not. Dropping
    unknown *entries* already covers new predicate types safely, but it cannot cover a **known**
    type whose meaning changed under a new major — the client would parse it happily and cache the
    wrong things. So a major bump stops routing entirely instead.
    """
    if version is None:
        return True
    return str(version).split(".", 1)[0] == _SUPPORTED_MAJOR


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


def _parse_body_match(entry: dict) -> tuple[tuple[str, frozenset[str]], ...] | None:
    """Parse ``bodyMatch`` into ANDed ``(field, allowed_values)`` pairs, or None if malformed.

    An empty ``bodyMatch`` yields ``()``, which makes the ``all()`` in
    :meth:`BodyPredicate.matches` vacuously true — the correct reading of "no body constraint
    stated". A *malformed* one drops the whole predicate instead of degrading to a service-only
    match: losing the constraint would cache values the customer never allowlisted, which is worse
    than not caching at all.
    """
    body_match = entry.get("bodyMatch")
    if not isinstance(body_match, dict):
        logger.debug("Ignoring body predicate with bodyMatch of type %s",
                     type(body_match).__name__)
        return None
    pairs = tuple(
        (field, frozenset(value for value in values if isinstance(value, str)))
        for field, values in body_match.items()
        if isinstance(field, str) and isinstance(values, list)
    )
    if len(pairs) != len(body_match):
        logger.debug("Ignoring body predicate with malformed bodyMatch entries")
        return None
    return pairs


def _field_allowed(value: object, allowed: frozenset[str]) -> bool:
    """Whether a body field's value satisfies its allowlist, dispatching on the value's shape.

    A string is one name and must itself be allowed. An object names several (its keys) and **all**
    of them must be allowed — that is what makes a multi-table batch all-or-nothing. An empty object
    names none, which cannot be shown to be allowlisted, so it does not match. Any other shape
    (number, list, null, missing field) does not match: the client cannot tell what it names.
    """
    if isinstance(value, str):
        return value in allowed
    if isinstance(value, dict):
        return bool(value) and all(key in allowed for key in value)
    return False


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
