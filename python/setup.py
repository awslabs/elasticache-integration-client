from pathlib import Path
from shutil import copyfile

from setuptools import setup
from setuptools.command.build_py import build_py as _build_py
from setuptools.command.sdist import sdist as _sdist


ROOT_CATALOG = Path(__file__).parent.parent / "shared" / "idempotent-operations.json"
PACKAGED_CATALOG = Path(__file__).parent / "photon" / "idempotent-operations.json"


class build_py(_build_py):
    """Copy the repository-wide replay-safety catalog into built packages."""

    def run(self):
        super().run()
        # A repository build reads the shared source of truth. An sdist build uses
        # the copy embedded by ``sdist`` so the source archive is self-contained.
        source = ROOT_CATALOG if ROOT_CATALOG.is_file() else PACKAGED_CATALOG
        target = Path(self.build_lib) / "photon" / "idempotent-operations.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        copyfile(source, target)


class sdist(_sdist):
    """Embed the shared catalog so consumers can build a wheel from the sdist."""

    def make_release_tree(self, base_dir, files):
        super().make_release_tree(base_dir, files)
        target = Path(base_dir) / "photon" / "idempotent-operations.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        copyfile(ROOT_CATALOG, target)


setup(cmdclass={"build_py": build_py, "sdist": sdist})
