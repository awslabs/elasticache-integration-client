"""Tests for the tag and artifact checks used by Python CD."""

from __future__ import annotations

import io
import tarfile
import zipfile
from pathlib import Path

import pytest

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


@pytest.mark.parametrize(
    "tag",
    [
        "1.2.3",
        "v1.2",
        "v01.2.3",
        "v1.2.3-rc4",
        "v1.2.3-beta.1",
    ],
)
def test_derive_version_rejects_unsupported_tags(tag):
    with pytest.raises(ValueError, match="invalid release tag"):
        release.derive_version(tag)


def _metadata(name="photon", version="0.0.1"):
    return f"Metadata-Version: 2.1\nName: {name}\nVersion: {version}\n\n".encode()


def _write_wheel(path: Path, *, include_catalog=True):
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("photon/__init__.py", "")
        if include_catalog:
            archive.writestr("photon/idempotent-operations.json", "{}")
        archive.writestr("photon-0.0.1.dist-info/METADATA", _metadata())


def _write_sdist(path: Path):
    with tarfile.open(path, "w:gz") as archive:
        files = {
            "photon-0.0.1/PKG-INFO": _metadata(),
            "photon-0.0.1/photon.egg-info/PKG-INFO": _metadata(),
            "photon-0.0.1/photon/__init__.py": b"",
            "photon-0.0.1/photon/idempotent-operations.json": b"{}",
        }
        for name, content in files.items():
            info = tarfile.TarInfo(name)
            info.size = len(content)
            archive.addfile(info, io.BytesIO(content))


def test_inspect_distributions_checks_contents_and_writes_hashes(tmp_path, monkeypatch):
    dist_dir = tmp_path / "dist"
    dist_dir.mkdir()
    wheel = dist_dir / "photon-0.0.1-py3-none-any.whl"
    sdist = dist_dir / "photon-0.0.1.tar.gz"
    _write_wheel(wheel)
    _write_sdist(sdist)
    monkeypatch.setattr(release, "project_metadata", lambda: ("photon", "0.0.1"))

    checksums = tmp_path / "python-SHA256SUMS"
    assert release.inspect_distributions(dist_dir, "0.0.1", checksums) == [wheel, sdist]
    checksum_text = checksums.read_text()
    assert wheel.name in checksum_text
    assert sdist.name in checksum_text


def test_inspect_distributions_requires_packaged_catalog(tmp_path, monkeypatch):
    dist_dir = tmp_path / "dist"
    dist_dir.mkdir()
    _write_wheel(dist_dir / "photon-0.0.1-py3-none-any.whl", include_catalog=False)
    _write_sdist(dist_dir / "photon-0.0.1.tar.gz")
    monkeypatch.setattr(release, "project_metadata", lambda: ("photon", "0.0.1"))

    with pytest.raises(ValueError, match="idempotent-operations.json"):
        release.inspect_distributions(dist_dir, "0.0.1", tmp_path / "SHA256SUMS")
