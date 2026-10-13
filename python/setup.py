from pathlib import Path
from shutil import copyfile

from setuptools import setup
from setuptools.command.build_py import build_py as _build_py
from setuptools.command.sdist import sdist as _sdist

CATALOG = "idempotent-operations.json"
SHARED_CATALOG = Path(__file__).parent.parent / "shared" / CATALOG
# Where the sdist carries the catalog. A wheel built from an sdist has no ../shared to read.
SDIST_CATALOG = Path(__file__).parent / "photon" / CATALOG
TOOLKIT_ROOT = Path(__file__).parent / "vendor" / "developer-toolkit-elasticache" / "python"
TOOLKIT_PACKAGE = "developer_toolkit_elasticache"
TOOLKIT_LEGAL_FILES = ("LICENSE", "NOTICE")


class sdist(_sdist):
    """Add the repository-wide replay-safety catalog to the source distribution."""

    def make_release_tree(self, base_dir, files):
        super().make_release_tree(base_dir, files)
        copyfile(SHARED_CATALOG, Path(base_dir) / "photon" / CATALOG)


class build_py(_build_py):
    """Copy the replay-safety catalog into built packages."""

    def run(self):
        super().run()
        # A checkout reads the shared file. An unpacked sdist has only its own copy.
        source = SHARED_CATALOG if SHARED_CATALOG.exists() else SDIST_CATALOG
        target = Path(self.build_lib) / "photon" / CATALOG
        target.parent.mkdir(parents=True, exist_ok=True)
        copyfile(source, target)
        toolkit_target = Path(self.build_lib) / TOOLKIT_PACKAGE
        toolkit_target.mkdir(parents=True, exist_ok=True)
        for filename in TOOLKIT_LEGAL_FILES:
            copyfile(TOOLKIT_ROOT / filename, toolkit_target / filename)


setup(cmdclass={"build_py": build_py, "sdist": sdist})
