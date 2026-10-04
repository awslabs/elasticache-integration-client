#!/usr/bin/env python3
"""Resolve and validate Python release metadata."""

import argparse
from pathlib import Path

from packaging.version import Version


PYTHON_ROOT = Path(__file__).resolve().parents[1]


def project_metadata(pyproject: Path = PYTHON_ROOT / "pyproject.toml") -> tuple[str, str]:
    import tomllib

    with pyproject.open("rb") as file:
        project = tomllib.load(file)["project"]
    return project["name"], project["version"]


def derive_version(tag: str) -> str:
    return str(Version(tag.removeprefix("v")))


def validated_version(tag: str) -> str:
    version = derive_version(tag)
    _, expected_version = project_metadata()
    if version != expected_version:
        raise ValueError(
            f"tag {tag!r} maps to {version!r}, but package metadata has {expected_version!r}"
        )
    return version


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("version", "current-version", "project-name"))
    parser.add_argument("--tag")
    args = parser.parse_args()
    name, version = project_metadata()
    if args.command == "version":
        if args.tag is None:
            parser.error("version requires --tag")
        print(validated_version(args.tag))
    elif args.command == "current-version":
        print(version)
    elif args.command == "project-name":
        print(name)


if __name__ == "__main__":
    main()
