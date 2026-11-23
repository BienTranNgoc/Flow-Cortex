"""The scene list a role job carries: `scene_frames` output, checked before any process runs."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from ..errors import PermanentError
from ..video_analysis.scenes import FPS, MAX_SEC, MIN_SCENE_FRAMES, middle_frame

# The clip ceiling of `scene_frames` in frames, and the most scenes it can cut from such a clip.
MAX_END_FRAME = int(MAX_SEC) * FPS
MAX_SCENES = MAX_END_FRAME // MIN_SCENE_FRAMES


@dataclass(frozen=True)
class Scene:
    index: int
    start_frame: int
    end_frame: int


def _violation(position: int, detail: str) -> PermanentError:
    return PermanentError("contract_violation", f"scene {position}: {detail}")


def validate_scenes(raw: Any) -> list[Scene]:
    """Contiguous scenes from frame 0, indexed from 0, `endFrame` exclusive, within the ceilings.

    Keys other than `index`, `startFrame` and `endFrame` are ignored.
    """
    if not isinstance(raw, list) or not raw:
        raise PermanentError("contract_violation", "scenes must be a non-empty list")
    if len(raw) > MAX_SCENES:
        raise PermanentError(
            "contract_violation", f"scenes holds {len(raw)} items, at most {MAX_SCENES} allowed"
        )
    scenes: list[Scene] = []
    expect_start = 0
    for position, item in enumerate(raw):
        if not isinstance(item, dict):
            raise _violation(position, "must be an object")
        index, start, end = (item.get(key) for key in ("index", "startFrame", "endFrame"))
        if type(index) is not int or type(start) is not int or type(end) is not int:
            raise _violation(position, "index, startFrame and endFrame must be whole numbers")
        if index != position:
            raise _violation(position, f"index must be {position}, got {index}")
        if start != expect_start:
            raise _violation(position, f"startFrame must be {expect_start}, got {start}")
        if end <= start:
            raise _violation(position, f"endFrame {end} must be after startFrame {start}")
        if end > MAX_END_FRAME:
            raise _violation(position, f"endFrame {end} is past frame {MAX_END_FRAME}")
        scenes.append(Scene(index, start, end))
        expect_start = end
    return scenes


def key_frames(scenes: list[Scene]) -> list[int]:
    """The middle frame of each scene, the one `scene_frames` shows the operator."""
    return [middle_frame(scene.start_frame, scene.end_frame) for scene in scenes]
