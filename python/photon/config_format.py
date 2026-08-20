"""Version-dispatched parsing of the ``photon.config`` reply.

The proxy's ``version`` field identifies the *format* of the resolved config — the shape of the
document and of the predicates inside it — not its contents (``photon/config.rs`` keeps it constant
across override combinations). This module is the one place that knows how to read a given format,
so a future format can change the structure freely:

    wire JSON  ──parser selected by version──▶  ResolvedConfig + Predicate objects
                                                (internal model, version-independent)

Everything downstream — :func:`photon.predicates.route`, :class:`photon.session.PhotonSession`, the
botocore hook — consumes the internal model and never sees the wire shape. Supporting a new format
therefore means writing one function and registering it in :data:`_PARSERS`; no caller changes, and
old formats keep working because their parser stays registered.

Dispatch is on the **major** version, because that is what a structural change bumps. Minor
additions within a major must be self-describing (a new predicate ``type``, a new ``bodyMatch``
``match`` mode) so a parser can skip constructs it does not implement, entry by entry, instead of
failing the whole document — see :func:`photon.predicates.parse_predicates`.

A format with no registered parser yields **no predicates**, so every request passes through to the
origin service: uncached, never mis-routed.
"""

from __future__ import annotations

import logging
from typing import Callable

from photon.models import ResolvedConfig
from photon.predicates import parse_predicates

logger = logging.getLogger(__name__)

#: Parses one resolved-config document into the internal model.
ConfigParser = Callable[[dict], ResolvedConfig]

# Highest minor of format 1 this client implements. Only used to tell an operator that the proxy is
# ahead of the client and some constructs may have been skipped; it never gates parsing.
_V1_SUPPORTED_MINOR = 0


def parse_config(raw: dict) -> ResolvedConfig:
    """Parse a resolved-config document, selecting a parser by its format version.

    An absent ``version`` is read as format 1: the field was introduced with it, so a document
    without one cannot be a later format.
    """
    version = raw.get("version")
    major = _major(version)
    parser = _PARSERS.get(major)
    if parser is None:
        logger.warning(
            "photon.config format %r is not supported by this client (known formats: %s); "
            "no requests will be routed to the cache until the client is upgraded",
            version, ", ".join(sorted(f"{m}.x" for m in _PARSERS)),
        )
        # Keep the raw document: callers may still want refresh_interval-like fields for
        # diagnostics, but nothing is routed on an unparsed format.
        return ResolvedConfig(version=version, raw=raw)
    return parser(raw)


def _major(version: object) -> str:
    """The major component of a version string. ``None`` is treated as format 1."""
    if version is None:
        return "1"
    return str(version).partition(".")[0]


def _parse_v1(raw: dict) -> ResolvedConfig:
    """Parse format 1: a flat document with ``refresh_interval`` and a ``predicates`` array.

    ```json
    {"version": "1.0", "refresh_interval": 60, "ttl": 300, "nttl": 60, "predicates": [...]}
    ```

    ``ttl``/``nttl`` are informational — the proxy resolves the effective TTL per request from its
    own connection state — so they are left on ``raw`` rather than modelled.
    """
    version = raw.get("version")
    _note_newer_minor(version, _V1_SUPPORTED_MINOR)
    return ResolvedConfig(
        version=version,
        refresh_interval=raw.get("refresh_interval"),
        raw=raw,
        predicates=parse_predicates(raw.get("predicates")),
    )


def _note_newer_minor(version: object, supported_minor: int) -> None:
    """Log when the proxy's minor exceeds this client's, which is legal but may skip constructs."""
    minor = str(version).partition(".")[2] if version is not None else ""
    try:
        if int(minor) > supported_minor:
            logger.debug(
                "photon.config format is %s; this client implements minor %s of that format, so "
                "any newer constructs will be skipped",
                version, supported_minor,
            )
    except ValueError:
        pass  # a missing or non-numeric minor tells us nothing; the major already matched


#: Format major → parser. Register a new entry to support a new format; keep the old ones so a
#: client can talk to proxies of either vintage.
_PARSERS: dict[str, ConfigParser] = {"1": _parse_v1}
