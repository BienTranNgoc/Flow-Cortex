"""caption_burn decision rules: rotation swap, input and stream refusals, burn failure."""

from __future__ import annotations

import json
import subprocess
from pathlib import Path
from types import SimpleNamespace
from typing import Any, cast

import pytest

from flowcortex_worker.errors import PermanentError
from flowcortex_worker.handlers import caption_burn
from flowcortex_worker.runtime import ProcessRunner


class FakeRunner:
    def __init__(self, replies: list[subprocess.CompletedProcess[bytes]]) -> None:
        self.replies = replies

    def run(self, argv: list[str]) -> subprocess.CompletedProcess[bytes]:
        return self.replies.pop(0)


def done(code: int = 0, out: bytes = b"") -> subprocess.CompletedProcess[bytes]:
    return subprocess.CompletedProcess([], code, out, b"")


def probe(streams: list[dict[str, Any]]) -> subprocess.CompletedProcess[bytes]:
    return done(out=json.dumps({"streams": streams}).encode())


def stream(rotation: float | None) -> dict[str, Any]:
    entry: dict[str, Any] = {"width": 1920, "height": 1080}
    if rotation is not None:
        entry["side_data_list"] = [{"rotation": rotation}]
    return entry


@pytest.mark.parametrize(
    ("rotation", "expected"),
    [(90, (1080, 1920)), (-90, (1080, 1920)), (0, (1920, 1080)), (None, (1920, 1080))],
)
def test_quarter_turn_rotation_swaps_the_displayed_size(
    tmp_path: Path, rotation: float | None, expected: tuple[int, int]
) -> None:
    runner = cast(ProcessRunner, FakeRunner([probe([stream(rotation)])]))
    assert caption_burn._displayed_size(tmp_path / "in.mp4", runner) == expected


def test_no_video_stream_is_refused(tmp_path: Path) -> None:
    runner = cast(ProcessRunner, FakeRunner([probe([])]))
    with pytest.raises(PermanentError, match="video stream") as excinfo:
        caption_burn._displayed_size(tmp_path / "in.mp4", runner)
    assert excinfo.value.code == "contract_violation"


def test_wrong_input_keys_are_refused(tmp_path: Path) -> None:
    job = cast(Any, SimpleNamespace(params={}))
    runner = cast(ProcessRunner, FakeRunner([]))
    for inputs in (
        {},
        {"video": tmp_path / "a", "words": tmp_path / "b"},
        {"clip": tmp_path / "a"},
    ):
        with pytest.raises(PermanentError, match="'video'") as excinfo:
            caption_burn.handle(job, inputs, runner)
        assert excinfo.value.code == "contract_violation"


def test_ffmpeg_failure_is_a_contract_violation_and_leaves_no_video(tmp_path: Path) -> None:
    source = tmp_path / "in.mp4"
    source.write_bytes(b"x")
    (tmp_path / caption_burn.VIDEO_NAME).write_bytes(b"partial")
    job = cast(
        Any,
        SimpleNamespace(
            params={"words": [{"word": "hi", "start": 0.0, "end": 0.5}], "startOffset": 0}
        ),
    )
    runner = cast(ProcessRunner, FakeRunner([probe([stream(None)]), done(code=1)]))
    with pytest.raises(PermanentError, match="could not be burned") as excinfo:
        caption_burn.handle(job, {"video": source}, runner)
    assert excinfo.value.code == "contract_violation"
    assert not (tmp_path / caption_burn.VIDEO_NAME).exists()
