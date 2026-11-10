"""Tests for release version normalization and stamping."""

import pytest
from packaging.version import InvalidVersion

from scripts import release


@pytest.mark.parametrize(
    ("tag", "version"),
    [
        ("v0.0.1", "0.0.1"),
        ("v1.2.3", "1.2.3"),
        ("v1.2.3-rc.4", "1.2.3rc4"),
    ],
)
def test_derive_version(tag, version):
    assert release.derive_version(tag) == version


def test_derive_version_rejects_invalid_version():
    with pytest.raises(InvalidVersion):
        release.derive_version("not-a-version")


@pytest.mark.parametrize(
    "version",
    [
        "1.0.0a1",
        "1.0.0.dev1",
        "1.0.0.post1",
        "1.0.0+local",
    ],
)
def test_derive_version_rejects_non_shared_release_forms(version):
    with pytest.raises(InvalidVersion, match="unsupported release version"):
        release.derive_version(f"v{version}")


def test_normalize_version_accepts_semver_release_candidate():
    assert release.normalize_version("1.2.3-rc.4") == "1.2.3rc4"


@pytest.mark.parametrize("version", ["1.0.0a1", "1.0.0.dev1", "1.0.0.post1"])
def test_normalize_version_rejects_non_shared_release_forms(version):
    with pytest.raises(InvalidVersion, match="unsupported release version"):
        release.normalize_version(version)


def test_stamp_project_version(tmp_path):
    pyproject = tmp_path / "pyproject.toml"
    pyproject.write_text(
        '[build-system]\nrequires = ["setuptools"]\n\n'
        '[project]\nname = "example"\nversion = "0.0.1"\n'
    )

    assert release.stamp_project_version("1.2.3-rc.4", pyproject) == "1.2.3rc4"
    assert 'version = "1.2.3rc4"' in pyproject.read_text()


def test_stamp_project_version_requires_project_version(tmp_path):
    pyproject = tmp_path / "pyproject.toml"
    pyproject.write_text('[project]\nname = "example"\n')

    with pytest.raises(ValueError, match="exactly one"):
        release.stamp_project_version("1.2.3", pyproject)
