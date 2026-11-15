"""Project render: refusals before any process, probe allowlist, failures, kills, a real render."""

from __future__ import annotations

import json
import subprocess
from pathlib import Path
from typing import Any

import pytest
from conftest import UPLOAD_BUCKET, job_body
from test_render_document import cue, project, scene

from flowcortex_worker.contracts.media_job import MediaJob
from flowcortex_worker.errors import PermanentError
from flowcortex_worker.handlers import render_video
from flowcortex_worker.render.execute import render_project
from flowcortex_worker.runtime import ProcessKilled, ProcessRunner


class Recording(ProcessRunner):
    """Runs everything for real, records the executables, and fails or kills on request."""

    def __init__(self, *, fail_encode: int | None = None, kill_encode: bool = False) -> None:
        super().__init__()
        self.calls: list[str] = []
        self.encodes = 0
        self.fail_encode = fail_encode
        self.kill_encode = kill_encode

    def run(self, argv: list[str]) -> subprocess.CompletedProcess[bytes]:
        self.calls.append(Path(argv[0]).name)
        if argv[0] == "ffmpeg" and any(a.startswith("-filter_complex") for a in argv):
            self.encodes += 1
            if self.kill_encode:
                raise ProcessKilled("watchdog")
            if self.fail_encode == self.encodes:
                return subprocess.CompletedProcess(argv, 1, b"", b"boom")
        return super().run(argv)


class NeverRuns(ProcessRunner):
    def run(self, argv: list[str]) -> subprocess.CompletedProcess[bytes]:
        raise AssertionError(f"no process may start, got {argv[0]}")


def make_job(keys: list[str], profile: str = "draft", **params: Any) -> MediaJob:
    return MediaJob.model_validate(
        job_body(
            capability="render_video",
            inputs=[
                {"key": key, "bucket": UPLOAD_BUCKET, "objectKey": f"u-1/{i}"}
                for i, key in enumerate(keys)
            ],
            params={"profile": profile, "mode": "render", **params},
        )
    )


def ffmpeg(*args: str) -> None:
    subprocess.run(
        ["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y", *args], check=True
    )


def stream_starts(path: Path) -> dict[str, float]:
    probed = subprocess.run(
        [
            "ffprobe", "-v", "error", "-show_entries", "stream=codec_type,start_time",
            "-of", "json", str(path),
        ],
        check=True, capture_output=True,
    )  # fmt: skip
    return {s["codec_type"]: float(s["start_time"]) for s in json.loads(probed.stdout)["streams"]}


def write_inputs(root: Path, doc: dict[str, Any]) -> tuple[list[str], dict[str, Path]]:
    """Each input sits in its own directory as `input`, as the store fetches it."""
    paths: dict[str, Path] = {}
    for key in ("project", "asset:a1", "asset:a2"):
        directory = root / key.replace(":", "_")
        directory.mkdir(parents=True)
        paths[key] = directory / "input"
    paths["project"].write_text(json.dumps(doc), encoding="utf-8")
    ffmpeg(
        "-f", "lavfi", "-i", "testsrc=s=480x270:r=30", "-f", "lavfi", "-i", "sine=f=440:r=44100",
        "-t", "4", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-f", "mp4",
        str(paths["asset:a1"]),
    )  # fmt: skip
    return list(paths), paths


def three_scene_project() -> dict[str, Any]:
    doc = project()
    doc["canvas"] = {"width": 720, "height": 1280, "fps": 30, "sampleRate": 48000}
    doc["timeline"]["scenes"] = [
        scene(0, durMs=2000, srcOutMs=2000),
        scene(1, clipId="a2", durMs=2000, srcOutMs=2000, atMs=2000),
        scene(2, clipId=None, durMs=1000, srcOutMs=1000, atMs=4000, fill="#336699"),
    ]
    return doc


def still(path: Path) -> None:
    ffmpeg(
        "-f", "lavfi", "-i", "color=c=red:s=200x200", "-frames:v", "1", "-f", "image2", str(path)
    )  # fmt: skip


def test_cue_without_start_is_refused_before_any_process(tmp_path: Path) -> None:
    doc = three_scene_project()
    del cue(doc)["startMs"]
    keys, paths = write_inputs(tmp_path, doc)
    with pytest.raises(PermanentError) as refused:
        render_project(make_job(keys), paths, NeverRuns())
    assert refused.value.code == "contract_violation"
    assert "startMs" in refused.value.message


def test_unreadable_project_is_a_contract_violation(tmp_path: Path) -> None:
    keys, paths = write_inputs(tmp_path, three_scene_project())
    paths["project"].write_bytes(b"\xff\xfe not json")
    with pytest.raises(PermanentError) as refused:
        render_project(make_job(keys), paths, NeverRuns())
    assert refused.value.code == "contract_violation"


def test_playlist_asset_is_refused_as_probe_failed_and_nothing_encodes(tmp_path: Path) -> None:
    keys, paths = write_inputs(tmp_path, three_scene_project())
    still(paths["asset:a2"])
    paths["asset:a1"].write_text("#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:2,\nseg.ts\n#EXT-X-ENDLIST\n")
    runner = Recording()
    with pytest.raises(PermanentError) as refused:
        render_project(make_job(keys), paths, runner)
    assert refused.value.code == "probe_failed"
    assert "'a1'" in refused.value.message
    assert runner.encodes == 0


def test_image_asset_on_a_video_scene_is_refused(tmp_path: Path) -> None:
    keys, paths = write_inputs(tmp_path, three_scene_project())
    still(paths["asset:a1"])
    still(paths["asset:a2"])
    with pytest.raises(PermanentError) as refused:
        render_project(make_job(keys), paths, Recording())
    assert refused.value.code == "probe_failed"


def test_failed_chunk_names_its_position_and_leaves_no_output(tmp_path: Path) -> None:
    keys, paths = write_inputs(tmp_path, three_scene_project())
    still(paths["asset:a2"])
    runner = Recording(fail_encode=2)
    with pytest.raises(PermanentError) as refused:
        render_project(make_job(keys), paths, runner)
    assert refused.value.code == "contract_violation"
    assert "chunk 2 of 3" in refused.value.message
    assert sorted(p.name for p in paths["project"].parent.iterdir()) == ["input"]


def test_a_kill_during_an_encode_propagates_and_cleans_up(tmp_path: Path) -> None:
    keys, paths = write_inputs(tmp_path, three_scene_project())
    still(paths["asset:a2"])
    with pytest.raises(ProcessKilled):
        render_project(make_job(keys), paths, Recording(kill_encode=True))
    assert sorted(p.name for p in paths["project"].parent.iterdir()) == ["input"]


@pytest.mark.parametrize("profile", ["draft", "final"])
def test_three_scenes_render_in_three_chunks(tmp_path: Path, profile: str) -> None:
    keys, paths = write_inputs(tmp_path, three_scene_project())
    still(paths["asset:a2"])
    runner = Recording()
    outcome = render_video.handle(make_job(keys, profile), paths, runner)

    assert runner.encodes == 3
    assert [f.name for f in outcome.files] == ["video.mp4", "captions.ass"]
    assert [f.mime for f in outcome.files] == ["video/mp4", "text/x-ssa"]
    video = outcome.files[0].body
    assert isinstance(video, Path) and video.stat().st_size > 0
    expected = (720, 1280) if profile == "final" else (480, 854)
    assert outcome.data["renderer"] == "flowcortex.video.render/1"
    assert (outcome.data["width"], outcome.data["height"]) == expected
    assert outcome.data["durationSeconds"] == pytest.approx(5.0, abs=0.001)
    starts = stream_starts(video)
    assert starts["video"] == 0
    assert starts["audio"] == 0
    assert outcome.data["encodeCount"] == 3
    assert outcome.data["profile"] == profile
    assert isinstance(outcome.data["warnings"], list)
    assert isinstance(outcome.data["snapped"], list)
    assert isinstance(outcome.data["handles"], list)
    captions = outcome.files[1].body
    assert isinstance(captions, bytes) and b"Dialogue:" in captions
    assert {p.name for p in video.parent.iterdir()} == {"input", "video.mp4"}


def test_unknown_profile_lists_the_three_allowed(tmp_path: Path) -> None:
    keys, paths = write_inputs(tmp_path, three_scene_project())
    with pytest.raises(PermanentError) as refused:
        render_video.handle(make_job(keys, "vertical_ad_v2"), paths, NeverRuns())
    assert refused.value.code == "contract_violation"
    for name in ("vertical_ad_v1", "draft", "final"):
        assert name in refused.value.message
