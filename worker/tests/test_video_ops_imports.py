"""The video ops load on a worker image without OpenCV and NumPy, and the registry serves them.

CI installs the `overlay` extra, so only this guard catches a module-level `import cv2`.
"""

from __future__ import annotations

import pkgutil
import subprocess
import sys
import textwrap

import pytest

from flowcortex_worker import video_ops
from flowcortex_worker.handlers import derive, flip, grade_match, reframe, registry, still

HANDLERS = {
    "still": still,
    "flip": flip,
    "reframe": reframe,
    "derive": derive,
    "grade_match": grade_match,
}
# The only module that imports the vision runtime, and only ever as a child process.
VISION_CHILD = "vision"


def _library_modules() -> list[str]:
    return [m.name for m in pkgutil.iter_modules(video_ops.__path__) if m.name != VISION_CHILD]


def test_the_video_ops_package_has_modules_to_guard() -> None:
    assert {"media", "protocol", "stabilize", "reframe_path", "grade"} <= set(_library_modules())


def test_importing_the_handlers_and_the_rules_needs_no_vision_libraries() -> None:
    modules = [f"flowcortex_worker.handlers.{name}" for name in HANDLERS]
    modules += [f"flowcortex_worker.video_ops.{name}" for name in _library_modules()]
    code = textwrap.dedent(
        f"""
        import importlib
        import sys
        sys.modules["numpy"] = sys.modules["cv2"] = None
        for name in {modules!r}:
            importlib.import_module(name)
        """
    )

    done = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, check=False)

    assert done.returncode == 0, done.stderr


@pytest.mark.parametrize("capability", sorted(HANDLERS))
def test_a_capability_resolves_to_its_handler(capability: str) -> None:
    assert registry.HANDLER_MODULES[capability] == HANDLERS[capability].__name__
    assert (
        registry.load_handlers(frozenset({capability}))[capability] is HANDLERS[capability].handle
    )


@pytest.mark.parametrize("capability", sorted(HANDLERS))
def test_the_default_registry_serves_the_capability(capability: str) -> None:
    default = registry.Registry(registry.load_handlers(None), None)

    assert default.resolve(capability) is HANDLERS[capability].handle
