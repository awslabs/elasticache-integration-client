#!/usr/bin/env python3
"""Validate, inspect, and smoke test Python release artifacts."""

from __future__ import annotations

import argparse
import ast
import hashlib
import re
import tarfile
import zipfile
from email.parser import BytesParser
from pathlib import Path

try:
    import tomllib
except ModuleNotFoundError:  # pragma: no cover - only the version command needs TOML
    tomllib = None


PYTHON_ROOT = Path(__file__).resolve().parents[1]
TAG_PATTERN = re.compile(
    r"^v(?P<major>0|[1-9]\d*)\.(?P<minor>0|[1-9]\d*)\.(?P<patch>0|[1-9]\d*)"
    r"(?:-rc\.(?P<rc>0|[1-9]\d*))?$"
)


def derive_version(tag: str) -> str:
    """Convert the shared release tag format to its PEP 440 version."""
    match = TAG_PATTERN.fullmatch(tag)
    if match is None:
        raise ValueError(
            f"invalid release tag {tag!r}; expected vMAJOR.MINOR.PATCH or "
            "vMAJOR.MINOR.PATCH-rc.N"
        )

    version = f"{match['major']}.{match['minor']}.{match['patch']}"
    if match["rc"] is not None:
        version += f"rc{match['rc']}"
    return version


def project_metadata(pyproject: Path = PYTHON_ROOT / "pyproject.toml") -> tuple[str, str]:
    if tomllib is None:
        raise RuntimeError("Python 3.11 or newer is required to read pyproject.toml")
    with pyproject.open("rb") as file:
        project = tomllib.load(file)["project"]
    return project["name"], project["version"]


def module_version(init_file: Path = PYTHON_ROOT / "photon" / "__init__.py") -> str:
    tree = ast.parse(init_file.read_text(encoding="utf-8"), filename=str(init_file))
    for statement in tree.body:
        if not isinstance(statement, ast.Assign):
            continue
        if any(isinstance(target, ast.Name) and target.id == "__version__"
               for target in statement.targets):
            value = ast.literal_eval(statement.value)
            if isinstance(value, str):
                return value
    raise ValueError(f"{init_file} does not define a string __version__")


def validated_version(tag: str) -> str:
    version = derive_version(tag)
    _, metadata_version = project_metadata()
    runtime_version = module_version()
    if metadata_version != version or runtime_version != version:
        raise ValueError(
            f"tag {tag!r} maps to {version!r}, but pyproject.toml has "
            f"{metadata_version!r} and photon.__version__ has {runtime_version!r}"
        )
    return version


def canonical_name(name: str) -> str:
    return re.sub(r"[-_.]+", "-", name).lower()


def archive_details(path: Path) -> tuple[set[str], bytes]:
    if path.suffix == ".whl":
        with zipfile.ZipFile(path) as archive:
            names = set(archive.namelist())
            metadata_files = [name for name in names if name.endswith(".dist-info/METADATA")]
            if len(metadata_files) != 1:
                raise ValueError(f"{path} must contain exactly one wheel METADATA file")
            return names, archive.read(metadata_files[0])

    if path.name.endswith(".tar.gz"):
        with tarfile.open(path, mode="r:gz") as archive:
            names = set(archive.getnames())
            metadata_files = [
                name
                for name in names
                if name.endswith("/PKG-INFO") and name.count("/") == 1
            ]
            if len(metadata_files) != 1:
                raise ValueError(f"{path} must contain exactly one sdist PKG-INFO file")
            metadata = archive.extractfile(metadata_files[0])
            if metadata is None:
                raise ValueError(f"could not read metadata from {path}")
            return names, metadata.read()

    raise ValueError(f"unsupported distribution file: {path}")


def inspect_distributions(
    dist_dir: Path,
    expected_version: str,
    checksums: Path,
) -> list[Path]:
    wheels = sorted(dist_dir.glob("*.whl"))
    sdists = sorted(dist_dir.glob("*.tar.gz"))
    if len(wheels) != 1 or len(sdists) != 1:
        raise ValueError(
            f"{dist_dir} must contain exactly one wheel and one .tar.gz sdist; "
            f"found {len(wheels)} wheel(s) and {len(sdists)} sdist(s)"
        )

    expected_name, metadata_version = project_metadata()
    if metadata_version != expected_version:
        raise ValueError(
            f"pyproject.toml version {metadata_version!r} does not match "
            f"expected version {expected_version!r}"
        )

    distributions = wheels + sdists
    for distribution in distributions:
        names, metadata_bytes = archive_details(distribution)
        metadata = BytesParser().parsebytes(metadata_bytes)
        if canonical_name(metadata["Name"]) != canonical_name(expected_name):
            raise ValueError(
                f"{distribution} has project name {metadata['Name']!r}, "
                f"expected {expected_name!r}"
            )
        if metadata["Version"] != expected_version:
            raise ValueError(
                f"{distribution} has version {metadata['Version']!r}, "
                f"expected {expected_version!r}"
            )

        required_suffixes = (
            "photon/__init__.py",
            "photon/idempotent-operations.json",
        )
        for suffix in required_suffixes:
            if not any(name.endswith(suffix) for name in names):
                raise ValueError(f"{distribution} does not contain {suffix}")

    checksums.parent.mkdir(parents=True, exist_ok=True)
    checksums.write_text(
        "".join(
            f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n"
            for path in distributions
        ),
        encoding="utf-8",
    )
    return distributions


def smoke_test(expected_version: str) -> None:
    import importlib
    from unittest.mock import MagicMock, patch

    import photon

    if photon.__version__ != expected_version:
        raise ValueError(
            f"installed photon version is {photon.__version__!r}, "
            f"expected {expected_version!r}"
        )
    for public_name in (
        "client",
        "PhotonCacheUnavailable",
        "PhotonConfigError",
        "PhotonError",
    ):
        if not hasattr(photon, public_name):
            raise ValueError(f"installed photon package does not export {public_name}")

    client_module = importlib.import_module("photon.client")
    sdk_client = MagicMock()
    boto3_session = MagicMock()
    boto3_session.client.return_value = sdk_client

    with (
        patch.object(client_module.boto3, "Session", return_value=boto3_session),
        patch.object(client_module, "build_cache_auth_provider", return_value=MagicMock()),
        patch.object(client_module, "PhotonSession", return_value=MagicMock()),
        patch.object(client_module, "PhotonHandle"),
    ):
        constructed = photon.client(
            "dynamodb",
            endpoint="cache.example:6379",
            cache_name="release-smoke",
            region_name="us-east-1",
        )
    if constructed is not sdk_client:
        raise ValueError("photon.client did not return the constructed boto3 client")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)

    version = commands.add_parser("version")
    version.add_argument("--tag", required=True)

    commands.add_parser("project-name")

    inspect = commands.add_parser("inspect")
    inspect.add_argument("--dist-dir", required=True, type=Path)
    inspect.add_argument("--expected-version", required=True)
    inspect.add_argument("--checksums", required=True, type=Path)

    smoke = commands.add_parser("smoke")
    smoke.add_argument("--expected-version", required=True)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.command == "version":
        print(validated_version(args.tag))
    elif args.command == "project-name":
        print(project_metadata()[0])
    elif args.command == "inspect":
        inspect_distributions(args.dist_dir, args.expected_version, args.checksums)
    elif args.command == "smoke":
        smoke_test(args.expected_version)


if __name__ == "__main__":
    main()
