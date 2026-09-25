from pathlib import Path
from shutil import copyfile

from setuptools import setup
from setuptools.command.build_py import build_py as _build_py


class build_py(_build_py):
    """Copy the repository-wide replay-safety catalog into built packages."""

    def run(self):
        super().run()
        source = Path(__file__).parent.parent / "shared" / "idempotent-operations.json"
        target = Path(self.build_lib) / "photon" / "idempotent-operations.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        copyfile(source, target)


setup(cmdclass={"build_py": build_py})
