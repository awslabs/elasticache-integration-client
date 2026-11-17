# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

"""Client-side evaluation of the CEL routing predicates returned by ``aws.config``.

The cache is authoritative about what may be cached: it returns an ordered list of predicates, each
a `CEL <https://cel.dev>`_ boolean expression plus the variables it binds. This module compiles those
expressions once and evaluates them against each intercepted request to decide "route to the cache"
or "pass through to the origin service". It is pure: no I/O, no session state, no service-specific
knowledge — ``"dynamodb"``, ``"GetItem"`` and ``"TableName"`` appear only inside the cache-authored
expressions, never in this code.

Each predicate is evaluated against a fixed request context — ``service``, ``operation``, ``method``,
``url``, ``headers``, ``body`` (parsed JSON) — with the predicate's ``vars`` (e.g. ``ops``,
``allowed``) also in scope. A variable may not reuse a context name; the context always wins. A
request routes to the cache when any predicate is ``true``; first-match-wins.

Anything this module cannot evaluate resolves to *don't route*, because an unrouted request is merely
uncached while a mis-routed one is a correctness bug. An expression that errors at evaluation counts
as no-match. An entry that cannot be compiled at config time is different: it taints the whole config,
which is treated as untrustworthy rather than caching by the entries that did compile.
"""

from __future__ import annotations

import json
import logging

import celpy
from celpy import celtypes

from aws_elasticache_integration_client.exceptions import ElastiCacheIntegrationsConfigUnevaluable
from aws_elasticache_integration_client.models import ElastiCacheIntegrationsRequest

logger = logging.getLogger(__name__)

# Names the request context binds. A predicate's ``vars`` may not use them: the context describes
# the request being evaluated, so letting a cache-authored variable shadow it would let an expression
# match a request it does not describe (a mis-route, the one failure mode this module must prevent).
_CONTEXT_NAMES = frozenset({"service", "operation", "method", "url", "headers", "body"})


class CompiledPredicate:
    """A compiled CEL program plus its bound variables, ready to evaluate per request."""

    __slots__ = ("_program", "_vars", "_warned_non_bool")

    def __init__(self, program: celpy.Runner, variables: dict) -> None:
        self._program = program
        self._warned_non_bool = False
        # Convert the bound variables to CEL values once, at construction. A variable colliding with
        # a context name is dropped rather than allowed to shadow the request.
        self._vars = {}
        for name, value in variables.items():
            if name in _CONTEXT_NAMES:
                logger.warning("Ignoring predicate variable %r: reserved context name", name)
                continue
            self._vars[name] = celpy.json_to_cel(value)

    def matches(self, context: dict) -> bool:
        """Whether this predicate is ``true`` for ``context`` (CEL-typed request fields).

        Any evaluation error counts as no-match: the expression is cache-authored, but a body shape
        it did not anticipate must never route rather than raise into the request path. So does a
        result that is not a CEL boolean — an expression that is not a predicate cannot be one by
        coercion, and every non-empty string, list or map is truthy.
        """
        # Redundant with the constructor's filtering (vars cannot hold a context name), kept so the
        # context still wins if that filtering is ever bypassed.
        activation = {**self._vars, **context}
        try:
            result = self._program.evaluate(activation)
        except celpy.CELEvalError:
            return False
        # Some celpy paths return the error rather than raising it; still a no-match, and not a
        # non-boolean result to warn about.
        if isinstance(result, celpy.CELEvalError):
            return False
        if not isinstance(result, celtypes.BoolType):
            if not self._warned_non_bool:
                # Once per compiled predicate: the shape cannot change between requests.
                logger.warning(
                    "Ignoring predicate whose result is %s, not bool", type(result).__name__
                )
                self._warned_non_bool = True
            return False
        return bool(result)


def compile_predicates(raw_config: dict | None) -> list[CompiledPredicate]:
    """Compile the ``predicates`` of a resolved config into evaluatable programs, in wire order.

    Returns ``[]`` when there are no predicates, so every request passes through.

    Raises:
        ElastiCacheIntegrationsConfigUnevaluable: An entry cannot be turned into a working predicate — a non-object
            entry, a missing or non-string ``expr``, or an expression this client's CEL version
            cannot compile. One such entry taints the whole config, which is rejected rather than
            partly applied: caching by the entries that happened to compile would route a different
            set of requests than the cache resolved.
    """
    env = celpy.Environment()
    compiled: list[CompiledPredicate] = []
    for entry in _predicate_entries(raw_config):
        if not isinstance(entry, dict):
            raise ElastiCacheIntegrationsConfigUnevaluable("Predicate entry is not an object")
        expr = entry.get("expr")
        if not isinstance(expr, str):
            raise ElastiCacheIntegrationsConfigUnevaluable("Predicate entry has no string 'expr'")
        try:
            program = env.program(env.compile(expr))
        except celpy.CELParseError as exc:
            raise ElastiCacheIntegrationsConfigUnevaluable(
                f"Predicate expression cannot be compiled: {expr!r}"
            ) from exc
        variables = entry.get("vars")
        compiled.append(
            CompiledPredicate(program, variables if isinstance(variables, dict) else {})
        )
    return compiled


def should_route_to_cache(
    predicates: list[CompiledPredicate], request: ElastiCacheIntegrationsRequest
) -> bool:
    """True when ``request`` should go to the cache. First match wins; empty list routes nothing.

    Builds the request context once and reuses it across predicates.
    """
    if not predicates:
        return False
    context = {name: celpy.json_to_cel(value) for name, value in _build_context(request).items()}
    return any(predicate.matches(context) for predicate in predicates)


def _predicate_entries(raw_config: dict | None) -> list:
    """The ``predicates`` array of a resolved config, or ``[]`` when the key is absent.

    Raises:
        ElastiCacheIntegrationsConfigUnevaluable: The key is present but its value is not a list.
    """
    if not isinstance(raw_config, dict) or "predicates" not in raw_config:
        return []
    entries = raw_config["predicates"]
    if not isinstance(entries, list):
        raise ElastiCacheIntegrationsConfigUnevaluable(
            f"Config 'predicates' must be a list, got {type(entries).__name__}"
        )
    return entries


def _build_context(request: ElastiCacheIntegrationsRequest) -> dict:
    """The CEL evaluation context for one request.

    ``service``/``operation`` come from the metadata the SDK hook derived from the event name; their
    location differs per service, the event name does not. ``body`` is the parsed JSON object, or an
    empty map when the request has no JSON body (e.g. a non-DynamoDB service).

    Every name here is reserved (see ``_CONTEXT_NAMES``).
    """
    return {
        "service": request.metadata.service,
        "operation": request.metadata.operation,
        "method": request.method,
        "url": request.url,
        "headers": dict(request.headers),
        "body": _parse_body(request.body),
    }


def _parse_body(body: object) -> dict:
    """Parse a request body into a JSON object, or ``{}`` when it cannot be inspected.

    ``{}`` on: empty, non-JSON, valid JSON that is not an object, or a non-bytes/str body. An
    expression referencing ``body.<field>`` then simply finds nothing and does not match. Size is not
    a reason: the body is parsed however large it is, and an oversized request is rejected by the
    cache, which passes it through to the origin service.
    """
    if not body or not isinstance(body, (bytes, str)):
        return {}
    try:
        parsed = json.loads(body)
    except (ValueError, TypeError):
        return {}
    return parsed if isinstance(parsed, dict) else {}
