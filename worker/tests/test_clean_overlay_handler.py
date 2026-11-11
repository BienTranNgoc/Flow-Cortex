"""clean_overlay handler: parameter and input checks, the child protocol and its failures."""

from __future__ import annotations

import json
import subprocess
import sys
import textwrap
from collections.abc import Callable
from pathlib import Path
from types import SimpleNamespace
from typing import Any, cast

import pytest

from flowcortex_worker.errors import PermanentError
from flowcortex_worker.handlers import HandlerOutcome, OutputFile, clean_overlay
from flowcortex_worker.overlay import protocol
from flowcortex_worker.runtime import ProcessKilled, ProcessRunner

RESULT = {"regionsRemoved": 2, "facesProtected": 0}


class FakeRunner:
    """Stands in for ProcessRunner: `on_run` plays the child (writes its files)."""

    def __init__(
        self,
        code: int = 0,
        stderr: bytes = b"",
        on_run: Callable[[Path], None] | None = None,
        raises: Exception | None = None,
    ) -> None:
        self.code = code
        self.stderr = stderr
        self.on_run = on_run
        self.raises = raises
        self.calls: list[tuple[list[str], Path | None]] = []
        self.params_seen: dict[str, Any] | None = None
        self.input_seen: bytes | None = None

    def run(self, argv: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess[bytes]:
        self.calls.append((argv, cwd))
        if self.raises is not None:
            raise self.raises
        assert cwd is not None
        self.params_seen = json.loads((cwd / protocol.PARAMS_FILE).read_text(encoding="utf-8"))
        self.input_seen = (cwd / protocol.INPUT_FILE).read_bytes()
        if self.on_run is not None:
            self.on_run(cwd)
        return subprocess.CompletedProcess(argv, self.code, b"", self.stderr)


def writes_outputs(work: Path) -> None:
    (work / protocol.CLEAN_FILE).write_bytes(b"\x89PNG-clean")
    (work / protocol.RESULT_FILE).write_text(json.dumps(RESULT), encoding="utf-8")


def writes_partial(work: Path) -> None:
    (work / protocol.CLEAN_FILE).write_bytes(b"partial")


def job(params: dict[str, Any]) -> Any:
    return SimpleNamespace(params=params)


def image(tmp_path: Path) -> Path:
    source = tmp_path / "in.png"
    source.write_bytes(b"\x89PNG")
    return source


def run(tmp_path: Path, runner: FakeRunner, params: dict[str, Any] | None = None) -> HandlerOutcome:
    return clean_overlay.handle(
        job(params or {}), {"image": image(tmp_path)}, cast(ProcessRunner, runner)
    )


def leftovers(tmp_path: Path) -> list[str]:
    return sorted(p.name for p in tmp_path.rglob(protocol.CLEAN_FILE))


@pytest.mark.parametrize(
    "params",
    [
        {"strength": 6},
        {"region": "corners"},
        {"region": "manual"},
        {"extractText": "true"},
        {"surprise": 1},
    ],
)
def test_bad_params_are_refused_before_the_child_runs(
    tmp_path: Path, params: dict[str, Any]
) -> None:
    runner = FakeRunner(on_run=writes_outputs)
    with pytest.raises(PermanentError) as refused:
        run(tmp_path, runner, params)
    assert refused.value.code == "contract_violation"
    assert runner.calls == []


def test_missing_or_extra_inputs_are_refused(tmp_path: Path) -> None:
    runner = FakeRunner(on_run=writes_outputs)
    source = image(tmp_path)
    for inputs in ({}, {"video": source}, {"image": source, "mask": source}):
        with pytest.raises(PermanentError, match="'image'") as refused:
            clean_overlay.handle(job({}), inputs, cast(ProcessRunner, runner))
        assert refused.value.code == "contract_violation"
    assert runner.calls == []


def test_a_finished_child_yields_the_clean_png_and_its_result(tmp_path: Path) -> None:
    runner = FakeRunner(on_run=writes_outputs)
    params = {
        "region": "manual",
        "customRegions": [{"x": 0.5, "y": 0.0, "w": 0.5, "h": 0.1}],
        "strength": 5,
        "extractText": True,
    }

    outcome = run(tmp_path, runner, params)

    [(argv, work)] = runner.calls
    assert argv == [sys.executable, "-m", "flowcortex_worker.overlay"]
    assert work is not None
    assert work.parent == tmp_path
    assert runner.input_seen == (tmp_path / "in.png").read_bytes()
    assert sorted(p.name for p in work.iterdir()) == [protocol.CLEAN_FILE, protocol.RESULT_FILE]
    assert runner.params_seen == {
        "region": "manual",
        "boxes": [[0.5, 0.0, 0.5, 0.1]],
        "strength": 5,
        "dilatePx": 7,
        "extractText": True,
    }
    assert outcome.data == RESULT
    [file] = outcome.files
    assert file == OutputFile("clean.png", "image/png", work / "clean.png")
    assert cast(Path, file.body).read_bytes() == b"\x89PNG-clean"


def test_exit_3_is_input_rejected(tmp_path: Path) -> None:
    with pytest.raises(PermanentError) as refused:
        run(tmp_path, FakeRunner(code=protocol.INPUT_REJECTED, on_run=writes_partial))
    assert refused.value.code == "input_rejected"
    assert leftovers(tmp_path) == []


def test_exit_4_names_the_face_model(tmp_path: Path) -> None:
    with pytest.raises(PermanentError, match="face") as refused:
        run(tmp_path, FakeRunner(code=protocol.FACE_MODEL_MISSING))
    assert refused.value.code == "contract_violation"


def test_exit_5_names_the_overlay_runtime(tmp_path: Path) -> None:
    with pytest.raises(PermanentError, match="overlay runtime") as refused:
        run(tmp_path, FakeRunner(code=protocol.RUNTIME_MISSING))
    assert refused.value.code == "contract_violation"


@pytest.mark.parametrize("code", [137, -9, 1])
def test_a_crashed_child_is_a_contract_violation_with_the_stderr_tail(
    tmp_path: Path, code: int
) -> None:
    runner = FakeRunner(code=code, stderr=b"warming up\nout of memory\n", on_run=writes_partial)
    with pytest.raises(PermanentError, match="out of memory") as refused:
        run(tmp_path, runner)
    assert refused.value.code == "contract_violation"
    assert leftovers(tmp_path) == []


def test_exit_0_without_the_expected_files_is_a_contract_violation(tmp_path: Path) -> None:
    with pytest.raises(PermanentError) as refused:
        run(tmp_path, FakeRunner(on_run=writes_partial))
    assert refused.value.code == "contract_violation"
    assert leftovers(tmp_path) == []


def test_an_unreadable_result_is_a_contract_violation(tmp_path: Path) -> None:
    def garbage(work: Path) -> None:
        writes_outputs(work)
        (work / protocol.RESULT_FILE).write_text("[1]", encoding="utf-8")

    with pytest.raises(PermanentError) as refused:
        run(tmp_path, FakeRunner(on_run=garbage))
    assert refused.value.code == "contract_violation"
    assert leftovers(tmp_path) == []


def test_a_kill_propagates_as_a_kill(tmp_path: Path) -> None:
    runner = FakeRunner(raises=ProcessKilled("run_timeout"))
    with pytest.raises(ProcessKilled):
        run(tmp_path, runner)


def test_importing_the_handler_needs_no_overlay_libraries() -> None:
    code = textwrap.dedent(
        """
        import sys
        sys.modules["numpy"] = sys.modules["cv2"] = sys.modules["torch"] = None
        import flowcortex_worker.handlers.clean_overlay
        """
    )
    done = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True)
    assert done.returncode == 0, done.stderr
