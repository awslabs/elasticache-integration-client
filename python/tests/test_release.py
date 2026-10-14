"""Tests for release tag normalization and validation."""

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


def test_validated_version_checks_tag_against_project(monkeypatch):
    monkeypatch.setattr(release, "project_metadata", lambda: ("elasticache-integrations", "1.2.3"))

    with pytest.raises(ValueError, match="package metadata"):
        release.validated_version("v1.2.4")
