"""Project render: refusals before any process, probe allowlist, failures, kills, a real render."""

from __future__ import annotations

import json
import subprocess
from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest
from conftest import UPLOAD_BUCKET, job_body
from test_render_document import (
    cue,
    image_overlay,
    music_clip,
    project,
    scene,
    text_overlay,
    voice_clip,
    with_music,
    with_overlays,
    with_voice,
)

from flowcortex_worker.contracts.media_job import MediaJob
from flowcortex_worker.errors import PermanentError
from flowcortex_worker.handlers import HandlerOutcome, render_video
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
    doc = three_scene_project()
    doc["timeline"]["scenes"][0]["audio"]["keep"] = False
    keys, paths = write_inputs(tmp_path, doc)
    still(paths["asset:a2"])
    runner = Recording()
    outcome = render_video.handle(make_job(keys, profile), paths, runner)

    assert runner.encodes == 4  # three chunks and the audio mix, the filter-graph calls
    assert [f.name for f in outcome.files] == ["video.mp4", "captions.ass", "plan.json"]
    assert [f.mime for f in outcome.files] == ["video/mp4", "text/x-ssa", "application/json"]
    video = outcome.files[0].body
    assert isinstance(video, Path) and video.stat().st_size > 0
    expected = (720, 1280) if profile == "final" else (480, 854)
    assert outcome.data["renderer"] == "flowcortex.video.render/2"
    assert outcome.data["mode"] == "render"
    assert outcome.data["loudness"] is None
    assert any("no audio was normalised" in w for w in outcome.data["warnings"])
    plan_body = outcome.files[2].body
    assert isinstance(plan_body, bytes)
    assert json.loads(plan_body)["planHash"] == outcome.data["planHash"]
    plan_job = render_video.handle(
        make_job(["project"], profile, mode="plan"), {"project": paths["project"]}, NeverRuns()
    )
    assert plan_job.data["planHash"] == outcome.data["planHash"]
    assert plan_job.files[0].body == plan_body
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


PLAN_DATA_KEYS = {
    "renderer", "mode", "profile", "width", "height", "planHash", "honoredCount",
    "manifestHash", "durationMs", "encodeCount", "warnings", "snapped", "handles",
}  # fmt: skip


def write_project_only(root: Path, doc: dict[str, Any]) -> dict[str, Path]:
    directory = root / "project"
    directory.mkdir(parents=True)
    path = directory / "input"
    path.write_text(json.dumps(doc), encoding="utf-8")
    return {"project": path}


def add_asset(root: Path, paths: dict[str, Path], asset_id: str) -> Path:
    directory = root / f"asset_{asset_id}"
    directory.mkdir(parents=True)
    paths[f"asset:{asset_id}"] = directory / "input"
    return paths[f"asset:{asset_id}"]


def png(path: Path, width: int, height: int) -> None:
    ffmpeg(
        "-f", "lavfi", "-i", f"color=c=blue:s={width}x{height}", "-frames:v", "1",
        "-f", "image2", "-c:v", "png", str(path),
    )  # fmt: skip


def wav(path: Path, hertz: int, seconds: int) -> None:
    ffmpeg(
        "-f", "lavfi", "-i", f"sine=f={hertz}:r=44100", "-t", str(seconds), "-f", "wav", str(path)
    )


def mp4_without_sound(path: Path, container: str) -> None:
    ffmpeg(
        "-f", "lavfi", "-i", "testsrc=s=64x64:r=30", "-t", "1", "-c:v", "libx264",
        "-pix_fmt", "yuv420p", "-f", container, str(path),
    )  # fmt: skip


def overlay_project(asset: dict[str, Any]) -> dict[str, Any]:
    doc = three_scene_project()
    doc["assets"]["ov"] = asset
    return with_overlays(doc, image_overlay(assetId="ov"))


def render_with_overlay_asset(
    root: Path, doc: dict[str, Any], writer: Callable[[Path], None], runner: ProcessRunner
) -> HandlerOutcome:
    _, paths = write_inputs(root, doc)
    still(paths["asset:a2"])
    writer(add_asset(root, paths, "ov"))
    return render_project(make_job(list(paths)), paths, runner)


def test_plan_job_starts_no_process_and_returns_plan_json(tmp_path: Path) -> None:
    paths = write_project_only(tmp_path, three_scene_project())
    outcome = render_video.handle(make_job(["project"], mode="plan"), paths, NeverRuns())

    assert [(f.name, f.mime) for f in outcome.files] == [("plan.json", "application/json")]
    assert set(outcome.data) == PLAN_DATA_KEYS
    assert outcome.data["mode"] == "plan"
    assert outcome.data["honoredCount"] == 189
    assert outcome.data["encodeCount"] == 3
    assert outcome.data["durationMs"] == 5000
    body = outcome.files[0].body
    assert isinstance(body, bytes)
    assert json.loads(body)["planHash"] == outcome.data["planHash"]


def test_plan_job_refuses_a_font_the_image_lacks_before_any_process(tmp_path: Path) -> None:
    doc = with_overlays(three_scene_project(), text_overlay(font="Lora"))
    paths = write_project_only(tmp_path, doc)
    with pytest.raises(PermanentError) as refused:
        render_video.handle(make_job(["project"], mode="plan"), paths, NeverRuns())
    assert refused.value.code == "contract_violation"
    assert "Lora" in refused.value.message


def test_plan_job_with_an_asset_input_is_refused(tmp_path: Path) -> None:
    keys, paths = write_inputs(tmp_path, three_scene_project())
    with pytest.raises(PermanentError) as refused:
        render_video.handle(make_job(keys, mode="plan"), paths, NeverRuns())
    assert refused.value.code == "contract_violation"


def test_voice_asset_without_sound_is_probe_failed_and_nothing_encodes(tmp_path: Path) -> None:
    doc = with_voice(three_scene_project(), voice_clip(assetId="v1"))
    _, paths = write_inputs(tmp_path, doc)
    still(paths["asset:a2"])
    mp4_without_sound(add_asset(tmp_path, paths, "v1"), "mp4")
    runner = Recording()
    with pytest.raises(PermanentError) as refused:
        render_project(make_job(list(paths)), paths, runner)
    assert refused.value.code == "probe_failed"
    assert "'v1'" in refused.value.message
    assert runner.encodes == 0


def test_overlay_image_that_is_a_video_is_probe_failed(tmp_path: Path) -> None:
    runner = Recording()
    with pytest.raises(PermanentError) as refused:
        render_with_overlay_asset(
            tmp_path,
            overlay_project({"kind": "image"}),
            lambda p: mp4_without_sound(p, "matroska"),
            runner,
        )
    assert refused.value.code == "probe_failed"
    assert "'ov'" in refused.value.message
    assert runner.encodes == 0


def test_sheet_smaller_than_its_grid_is_probe_failed(tmp_path: Path) -> None:
    sheet = {
        "kind": "image",
        "animation": {"frameCount": 8, "cols": 4, "frameW": 256, "frameH": 256, "frameMs": 100},
    }
    runner = Recording()
    with pytest.raises(PermanentError) as refused:
        render_with_overlay_asset(
            tmp_path, overlay_project(sheet), lambda p: png(p, 512, 512), runner
        )
    assert refused.value.code == "probe_failed"
    assert "'ov'" in refused.value.message
    assert runner.encodes == 0


def test_overlay_image_over_the_pixel_cap_is_probe_failed(tmp_path: Path) -> None:
    runner = Recording()
    with pytest.raises(PermanentError) as refused:
        render_with_overlay_asset(
            tmp_path, overlay_project({"kind": "image"}), lambda p: png(p, 8192, 4096), runner
        )
    assert refused.value.code == "probe_failed"
    assert "'ov'" in refused.value.message
    assert "16777216" in refused.value.message
    assert runner.encodes == 0


def test_overlay_image_at_the_pixel_cap_passes_the_probe_stage(tmp_path: Path) -> None:
    with pytest.raises(PermanentError) as refused:
        render_with_overlay_asset(
            tmp_path,
            overlay_project({"kind": "image"}),
            lambda p: png(p, 4096, 4096),
            Recording(fail_encode=1),
        )
    assert refused.value.code == "contract_violation"
    assert "chunk 1 of 3" in refused.value.message


class FakeLibassLog(Recording):
    """Real ffmpeg, but every chunk reports libass missing a Vietnamese glyph."""

    def run(self, argv: list[str]) -> subprocess.CompletedProcess[bytes]:
        done = super().run(argv)
        if argv[0] == "ffmpeg" and any(a.startswith("-filter_complex") for a in argv):
            return subprocess.CompletedProcess(argv, 0, done.stdout, b"Glyph 0x1EA1 not found\n")
        return done


def test_sticker_only_chunk_names_the_overlay_font_in_the_audit(tmp_path: Path) -> None:
    doc = with_overlays(three_scene_project(), text_overlay(font="Bangers", endMs=1500))
    doc["output"]["lanes"]["captions"] = False
    keys, paths = write_inputs(tmp_path, doc)
    still(paths["asset:a2"])
    with pytest.raises(PermanentError) as refused:
        render_project(make_job(keys), paths, FakeLibassLog())
    assert refused.value.code == "contract_violation"
    assert "Bangers" in refused.value.message
    assert "chunk 1 of 3" in refused.value.message


def test_render_mixes_voice_music_and_overlays_and_normalises(tmp_path: Path) -> None:
    doc = three_scene_project()
    doc["assets"]["ov"] = {"kind": "image"}
    with_overlays(
        doc,
        text_overlay(
            endMs=3000, glow={"color": "#FFCC00", "blurPct": 4, "spreadPct": 2, "strength": 2}
        ),
        image_overlay(assetId="ov", endMs=4000),
    )
    with_voice(doc, voice_clip(assetId="v1", atMs=1000, srcOutMs=3000, durMs=2400, rate=1.25))
    with_music(doc, music_clip(assetId="m1", srcOutMs=1500, durMs=5000, loop=True))
    _, paths = write_inputs(tmp_path, doc)
    still(paths["asset:a2"])
    png(add_asset(tmp_path, paths, "ov"), 200, 100)
    wav(add_asset(tmp_path, paths, "v1"), 330, 3)
    wav(add_asset(tmp_path, paths, "m1"), 220, 2)

    outcome = render_video.handle(make_job(list(paths), "final"), paths, Recording())

    video = outcome.files[0].body
    assert isinstance(video, Path)
    report = json.loads(
        subprocess.run(
            [
                "ffprobe", "-v", "error", "-show_entries", "stream=codec_type,codec_name",
                "-of", "json", str(video),
            ],
            check=True, capture_output=True,
        ).stdout
    )  # fmt: skip
    assert sorted((s["codec_type"], s["codec_name"]) for s in report["streams"]) == [
        ("audio", "aac"),
        ("video", "h264"),
    ]
    decoded = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(video), "-f", "s16le", "-ac", "2", "-ar", "48000", "-"],
        check=True, capture_output=True,
    ).stdout  # fmt: skip
    assert abs(len(decoded) / 4 - 5.0 * 48000) <= 48000 / 30
    loudness = outcome.data["loudness"]
    assert loudness["I"] == pytest.approx(-14, abs=1.5)
    assert "beforeI" in loudness
    plan_job = render_video.handle(
        make_job(["project"], "final", mode="plan"), {"project": paths["project"]}, NeverRuns()
    )
    assert plan_job.data["planHash"] == outcome.data["planHash"]
