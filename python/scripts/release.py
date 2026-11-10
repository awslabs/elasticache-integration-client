#!/usr/bin/env python3
"""Resolve and validate Python release metadata."""

import argparse
import re
from pathlib import Path

from packaging.version import InvalidVersion, Version


PYTHON_ROOT = Path(__file__).resolve().parents[1]
RELEASE_VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(?:-rc\.[0-9]+)?$")


def project_metadata(pyproject: Path = PYTHON_ROOT / "pyproject.toml") -> tuple[str, str]:
    import tomllib

    with pyproject.open("rb") as file:
        project = tomllib.load(file)["project"]
    return project["name"], project["version"]


def derive_version(tag: str) -> str:
    if not tag.startswith("v"):
        raise InvalidVersion(f"release tag must start with 'v': {tag!r}")
    return normalize_version(tag.removeprefix("v"))


def normalize_version(value: str) -> str:
    if not RELEASE_VERSION.fullmatch(value):
        raise InvalidVersion(f"unsupported release version: {value!r}")
    return str(Version(value))


def stamp_project_version(value: str, pyproject: Path = PYTHON_ROOT / "pyproject.toml") -> str:
    version = normalize_version(value)
    lines = pyproject.read_text().splitlines(keepends=True)
    in_project = False
    replacements = 0

    # Change only project.version so release jobs can stamp their temporary
    # checkout without disturbing other TOML metadata or the development baseline.
    for index, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith("[") and stripped.endswith("]"):
            in_project = stripped == "[project]"
            continue
        if in_project and "=" in stripped and stripped.split("=", 1)[0].strip() == "version":
            prefix = line[: len(line) - len(line.lstrip())]
            newline = "\n" if line.endswith("\n") else ""
            lines[index] = f'{prefix}version = "{version}"{newline}'
            replacements += 1

    if replacements != 1:
        raise ValueError("pyproject.toml must contain exactly one [project] version")

    pyproject.write_text("".join(lines))
    return version


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "command",
        choices=(
            "version",
            "normalize-version",
            "stamp-version",
            "current-version",
            "project-name",
        ),
    )
    parser.add_argument("--tag")
    parser.add_argument("--version")
    args = parser.parse_args()
    name, version = project_metadata()
    if args.command == "version":
        if args.tag is None:
            parser.error("version requires --tag")
        print(derive_version(args.tag))
    elif args.command == "normalize-version":
        if args.version is None:
            parser.error("normalize-version requires --version")
        print(normalize_version(args.version))
    elif args.command == "stamp-version":
        if args.version is None:
            parser.error("stamp-version requires --version")
        print(stamp_project_version(args.version))
    elif args.command == "current-version":
        print(version)
    elif args.command == "project-name":
        print(name)


if __name__ == "__main__":
    main()
