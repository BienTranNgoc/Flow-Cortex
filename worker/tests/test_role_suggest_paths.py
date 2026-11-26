"""The detector child refuses any path that leaves its work directory, before it loads anything."""

from __future__ import annotations

from pathlib import Path

import pytest

from flowcortex_worker.engines.role_suggest import confine, main


@pytest.fixture
def work(tmp_path: Path) -> Path:
    directory = tmp_path / "work"
    directory.mkdir()
    return directory


@pytest.fixture
def outside(tmp_path: Path) -> Path:
    directory = tmp_path / "outside"
    directory.mkdir()
    (directory / "frame.jpg").write_bytes(b"x")
    return directory


def arguments(work: Path, *, frame: Path, output: Path, model_dir: Path | None = None) -> list[str]:
    model = ["--model-dir", str(model_dir)] if model_dir else []
    return [*model, "--work-dir", str(work), "--output", str(output), str(frame)]


def test_a_frame_with_parent_segments_is_refused(
    work: Path, outside: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    sneaky = work / ".." / "outside" / "frame.jpg"
    argv = arguments(work, frame=sneaky, output=work / "boxes.json")

    with pytest.raises(SystemExit) as refused:
        main(argv)

    assert refused.value.code == 2
    assert "'..'" in capsys.readouterr().err


def test_an_absolute_frame_outside_the_work_directory_is_refused(
    work: Path, outside: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    argv = arguments(work, frame=outside / "frame.jpg", output=work / "boxes.json")

    with pytest.raises(SystemExit) as refused:
        main(argv)

    assert refused.value.code == 2
    assert "outside the allowed directories" in capsys.readouterr().err


def test_a_symlink_that_leaves_the_work_directory_is_refused(
    work: Path, outside: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    link = work / "frame.jpg"
    link.symlink_to(outside / "frame.jpg")
    argv = arguments(work, frame=link, output=work / "boxes.json")

    with pytest.raises(SystemExit) as refused:
        main(argv)

    assert refused.value.code == 2
    assert "outside the allowed directories" in capsys.readouterr().err


def test_an_output_outside_the_work_directory_is_refused(
    work: Path, outside: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    frame = work / "frame.jpg"
    frame.write_bytes(b"x")
    argv = arguments(work, frame=frame, output=outside / "boxes.json")

    with pytest.raises(SystemExit) as refused:
        main(argv)

    assert refused.value.code == 2
    assert "outside the allowed directories" in capsys.readouterr().err


def test_a_model_directory_outside_the_allowed_roots_is_refused(
    work: Path, outside: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    frame = work / "frame.jpg"
    frame.write_bytes(b"x")
    argv = arguments(work, frame=frame, output=work / "boxes.json", model_dir=outside)

    with pytest.raises(SystemExit) as refused:
        main(argv)

    assert refused.value.code == 2
    assert "outside the allowed directories" in capsys.readouterr().err


def test_a_path_inside_the_work_directory_is_accepted(work: Path) -> None:
    frame = work / "frame.jpg"

    assert confine(frame, work) == frame.resolve()
