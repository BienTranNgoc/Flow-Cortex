"""Renders a project or plans it: measure the assets, plan once, encode chunk by chunk, join,
mix the audio, normalise, verify.

The document, the plan and the argument lists come from the pure modules next to this one; this
module is the only place that runs a process or touches a file. Anything those modules refuse
(`ValueError`, `KeyError`, `TypeError`) is a bad job no redelivery can fix, so it ends as
`contract_violation`; a process kill is never caught.
"""

from __future__ import annotations

import json
import math
from collections.abc import Mapping
from pathlib import Path
from typing import Any

from ..captions.fonts import audit_libass
from ..captions.line_breaks import load_glyph_advances
from ..contracts.media_job import MediaJob
from ..errors import PermanentError
from ..handlers import HandlerOutcome, OutputFile
from ..runtime import ProcessRunner
from .document import (
    ASSET_PREFIX,
    PROJECT_INPUT,
    Document,
    ImageOverlay,
    parse_document,
    parse_job,
)
from .graph import chunk_command, chunk_path, concat_list, join_command, prints_text
from .mix import (
    final_mux_command,
    loudness_measure_command,
    loudnorm_filter,
    mix_command,
    parse_loudnorm,
)
from .overlays import thin_strip_warnings
from .plan import RENDERER, honours, manifest_hash, plan_document
from .subtitles import CaptionStyles, ass_document, caption_styles
from .timeline import (
    Chunk,
    PlannedTextOverlay,
    TimelinePlan,
    declared_probes,
    plan_timeline,
)

VIDEO_NAME = "video.mp4"
CAPTIONS_NAME = "captions.ass"
PLAN_NAME = "plan.json"
BASE_NAME = "base.mp4"
LIST_NAME = "chunks.txt"

VIDEO_FORMATS = frozenset({"mov,mp4,m4a,3gp,3g2,mj2", "matroska,webm"})
IMAGE_FORMATS = frozenset({"png_pipe", "jpeg_pipe", "webp_pipe"})
AUDIO_FORMATS = frozenset({"mp3", "wav", "mov,mp4,m4a,3gp,3g2,mj2", "matroska,webm"})
OVERLAY_MAX_PIXELS = 4096 * 4096

_USES = {
    "video": ("a video scene", VIDEO_FORMATS),
    "image": ("an image scene or overlay", IMAGE_FORMATS),
    "audio": ("a voice or music clip", AUDIO_FORMATS),
}


def _violation(message: str) -> PermanentError:
    return PermanentError("contract_violation", message)


def _probe_json(argv: list[str], processes: ProcessRunner) -> dict[str, Any] | None:
    completed = processes.run(argv)
    if completed.returncode != 0:
        return None
    try:
        report = json.loads(completed.stdout)
    except ValueError:
        return None
    return report if isinstance(report, dict) else None


def _fps(rate: object) -> float | None:
    if not isinstance(rate, str) or "/" not in rate:
        return None
    numerator, _, denominator = rate.partition("/")
    try:
        top, bottom = float(numerator), float(denominator)
    except ValueError:
        return None
    return round(top / bottom, 3) if bottom > 0 and top > 0 else None


def _rotation(stream: Mapping[str, Any]) -> int:
    for entry in stream.get("side_data_list") or []:
        rotation = entry.get("rotation") if isinstance(entry, dict) else None
        if isinstance(rotation, int | float) and not isinstance(rotation, bool):
            return round(rotation) if math.isfinite(rotation) else 0
    return 0


def _duration_ms(container: Mapping[str, Any], asset_id: str) -> float:
    try:
        seconds = float(container["duration"])
    except (KeyError, TypeError, ValueError):
        seconds = math.nan
    if not math.isfinite(seconds) or seconds <= 0:
        raise PermanentError("probe_failed", f"asset {asset_id!r} has no readable duration")
    return seconds * 1000.0


def probe_asset(path: Path, asset_id: str, kind: str, processes: ProcessRunner) -> dict[str, Any]:
    """Measure one asset and refuse any container or stream its use (`video` or `image` scene or
    overlay, `audio` voice or music clip) does not accept."""
    use, allowed = _USES.get(kind, _USES["video"])
    report = _probe_json(
        [
            "ffprobe", "-v", "error", "-protocol_whitelist", "file",
            "-show_entries",
            "format=format_name,duration:stream=codec_type,width,height,avg_frame_rate,"
            "pix_fmt,color_transfer:stream_disposition=attached_pic:stream_side_data=rotation",
            "-of", "json", str(path),
        ],
        processes,
    )  # fmt: skip
    if report is None:
        raise PermanentError("probe_failed", f"asset {asset_id!r} is not a readable media file")
    container = report.get("format")
    container = container if isinstance(container, dict) else {}
    name = container.get("format_name")
    if name not in allowed:
        raise PermanentError(
            "probe_failed",
            f"asset {asset_id!r} is a {name!r} file; {use} accepts " + ", ".join(sorted(allowed)),
        )
    streams = [s for s in report.get("streams") or [] if isinstance(s, dict)]
    if kind == "audio":
        if not any(s.get("codec_type") == "audio" for s in streams):
            raise PermanentError("probe_failed", f"asset {asset_id!r} has no audio stream")
        return {"hasAudio": True, "durationMs": _duration_ms(container, asset_id)}
    pictures = [
        s
        for s in streams
        if s.get("codec_type") == "video" and (s.get("disposition") or {}).get("attached_pic") != 1
    ]
    if not pictures:
        raise PermanentError("probe_failed", f"asset {asset_id!r} has no video stream")
    picture = pictures[0]
    measured: dict[str, Any] = {
        "width": picture.get("width"),
        "height": picture.get("height"),
        "fps": _fps(picture.get("avg_frame_rate")),
        "hasAudio": any(s.get("codec_type") == "audio" for s in streams),
        "rotation": _rotation(picture),
        "pixFmt": picture.get("pix_fmt"),
        "colorTransfer": picture.get("color_transfer"),
    }
    if kind != "image":
        measured["durationMs"] = _duration_ms(container, asset_id)
    return measured


def _read_project(path: Path) -> Any:
    try:
        return json.loads(path.read_bytes().decode("utf-8"))
    except (OSError, ValueError) as exc:
        raise _violation(f"the project input is not readable UTF-8 JSON: {exc}") from exc


def _run_step(processes: ProcessRunner, argv: list[str], output: Path, step: str) -> bytes:
    """Run one ffmpeg call; a failure or an empty output refuses the job, naming the step."""
    completed = processes.run(argv)
    if completed.returncode != 0 or not output.is_file() or output.stat().st_size == 0:
        output.unlink(missing_ok=True)
        raise _violation(f"{step} failed")
    return completed.stderr


def _video_start(base: Path, processes: ProcessRunner) -> float:
    """Where the joined picture starts on the container clock, in seconds."""
    report = _probe_json(
        [
            "ffprobe", "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=start_time", "-of", "json", str(base),
        ],
        processes,
    )  # fmt: skip
    try:
        if report is None:
            raise ValueError("ffprobe failed")
        start = float(report["streams"][0]["start_time"])
    except (KeyError, IndexError, TypeError, ValueError) as exc:
        raise _violation("the joined video has no readable start time") from exc
    if not math.isfinite(start) or abs(start) > 1.0:
        raise _violation(f"the joined video starts at {start} s")
    return start


def _check_output(video: Path, plan: TimelinePlan, processes: ProcessRunner) -> float:
    """The video's measured duration, after checking streams, size, duration and audio length."""
    what = "the rendered video"
    report = _probe_json(
        [
            "ffprobe", "-v", "error", "-show_entries",
            "stream=codec_type,codec_name,width,height:format=duration",
            "-of", "json", str(video),
        ],
        processes,
    )  # fmt: skip
    if report is None:
        raise _violation(f"{what} failed media verification")
    try:
        streams = [s for s in report["streams"] if isinstance(s, dict)]
        duration = float(report["format"]["duration"])
    except (KeyError, TypeError, ValueError) as exc:
        raise _violation(f"{what} has no readable streams or duration") from exc
    pictures = [s for s in streams if s.get("codec_type") == "video"]
    sounds = [s for s in streams if s.get("codec_type") == "audio"]
    if len(pictures) != 1 or pictures[0].get("codec_name") != "h264":
        raise _violation(f"{what} must hold exactly one H.264 video stream")
    if (pictures[0].get("width"), pictures[0].get("height")) != (plan.out_width, plan.out_height):
        raise _violation(
            f"{what} is {pictures[0].get('width')}x{pictures[0].get('height')}, "
            f"expected {plan.out_width}x{plan.out_height}"
        )
    if len(sounds) != 1 or sounds[0].get("codec_name") != "aac":
        raise _violation(f"{what} must hold exactly one AAC audio stream")
    frame_ms = 1000.0 / plan.canvas.fps
    if not math.isfinite(duration) or abs(duration * 1000.0 - plan.total_ms) > frame_ms:
        raise _violation(
            f"{what} lasts {duration:.3f} s, the timeline is {plan.total_ms / 1000.0:.3f} s"
        )

    frames = _probe_json(
        [
            "ffprobe", "-v", "error", "-select_streams", "a:0",
            "-show_entries", "frame=nb_samples", "-of", "json", str(video),
        ],
        processes,
    )  # fmt: skip
    try:
        if frames is None:
            raise ValueError("ffprobe failed")
        samples = sum(int(entry["nb_samples"]) for entry in frames["frames"])
    except (KeyError, TypeError, ValueError) as exc:
        raise _violation(f"{what} has no countable audio samples") from exc
    expected = plan.total_ms / 1000.0 * plan.canvas.sample_rate
    if abs(samples - expected) > plan.canvas.sample_rate / plan.canvas.fps:
        raise _violation(f"{what} holds {samples} audio samples, the timeline needs {expected:.0f}")
    return duration


def _plan_bytes(plan_json: Mapping[str, Any]) -> bytes:
    """Sorted keys and no spaces, so a plan job and a render of one document write equal bytes."""
    text = json.dumps(plan_json, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return text.encode("utf-8")


def _plan_outcome(document: Document, profile: str) -> HandlerOutcome:
    """A dry run: plan from the values the document declares, start no process."""
    try:
        plan = plan_timeline(document, declared_probes(document), profile, measured=False)
        caption_styles(plan, plan.canvas.width, plan.canvas.height, load_glyph_advances)
        plan_json = plan_document(plan, document)
    except (KeyError, TypeError, ValueError) as exc:
        raise _violation(str(exc)) from exc
    return HandlerOutcome(
        data={
            "renderer": RENDERER,
            "mode": "plan",
            "profile": plan.profile,
            "width": plan.out_width,
            "height": plan.out_height,
            "planHash": plan_json["planHash"],
            "honoredCount": len(honours()),
            "manifestHash": manifest_hash(),
            "durationMs": plan_json["durationMs"],
            "encodeCount": plan_json["encodeCount"],
            "warnings": list(plan.warnings),
            "snapped": list(plan.snapped),
            "handles": list(plan.handles),
        },
        files=[OutputFile(PLAN_NAME, "application/json", _plan_bytes(plan_json))],
    )


def _measure_assets(
    document: Document, files: Mapping[str, str], processes: ProcessRunner
) -> dict[str, dict[str, Any]]:
    """Probe every asset for each way the document uses it, then bound the overlay images.

    The scene probe, when there is one, is the asset's planning probe; an asset used only as an
    overlay or a clip is planned from its overlay or clip probe.
    """
    scene_kind = {s.clip_id: s.asset_kind or "" for s in document.scenes if s.clip_id is not None}
    overlay_images = {o.asset_id for o in document.overlays if isinstance(o, ImageOverlay)}
    clip_audio = {c.asset_id for c in document.voice_clips}
    clip_audio |= {c.asset_id for c in document.music_clips}
    probes: dict[str, dict[str, Any]] = {}
    for asset_id, path in files.items():
        kinds: list[str] = []
        if asset_id in scene_kind:
            kinds.append(scene_kind[asset_id])
        if asset_id in overlay_images:
            kinds.append("image")
        if asset_id in clip_audio:
            kinds.append("audio")
        measured = {
            kind: probe_asset(Path(path), asset_id, kind, processes)
            for kind in dict.fromkeys(kinds)
        }
        probes[asset_id] = measured[kinds[0]]
        if asset_id in overlay_images:
            _check_overlay_image(asset_id, measured["image"], document.assets[asset_id].animation)
    return probes


def _check_overlay_image(
    asset_id: str, probe: Mapping[str, Any], sheet: Mapping[str, int] | None
) -> None:
    """Each overlay input is held decoded as RGBA, so its pixel count is bounded here, not by the
    upload size; a sheet must hold the grid the document declares."""
    width, height = probe.get("width"), probe.get("height")
    if not isinstance(width, int) or not isinstance(height, int) or width < 1 or height < 1:
        raise PermanentError("probe_failed", f"asset {asset_id!r} has no readable image size")
    if width * height > OVERLAY_MAX_PIXELS:
        raise PermanentError(
            "probe_failed",
            f"asset {asset_id!r} is {width}x{height} ({width * height} px); an image overlay "
            f"holds at most {OVERLAY_MAX_PIXELS} px",
        )
    if sheet is None:
        return
    need_w = sheet["cols"] * sheet["frameW"]
    need_h = math.ceil(sheet["frameCount"] / sheet["cols"]) * sheet["frameH"]
    if width < need_w or height < need_h:
        raise PermanentError(
            "probe_failed",
            f"asset {asset_id!r} is {width}x{height}, smaller than the {need_w}x{need_h} its "
            f"animation declares ({sheet['frameCount']} frames in {sheet['cols']} columns of "
            f"{sheet['frameW']}x{sheet['frameH']})",
        )


def _printed_fonts(plan: TimelinePlan, styles: CaptionStyles, chunk: Chunk) -> str:
    """The families the chunk's libass script asks for, for the audit's error label."""
    end = chunk.start_ms + chunk.dur_ms
    fonts: set[str] = set()
    if any(cue.start_ms < end and cue.end_ms > chunk.start_ms for cue in plan.cues):
        fonts |= {style.caption.font for style in styles.all()}
    for overlay in plan.overlays:
        if (
            isinstance(overlay, PlannedTextOverlay)
            and overlay.start_ms < end
            and overlay.end_ms > chunk.start_ms
        ):
            fonts.add(overlay.style.get("font", styles.base.caption.font))
            fonts.update(
                entry["font"] for entry in overlay.style.get("lines") or [] if "font" in entry
            )
    return ", ".join(sorted(fonts))


def _measure_loudness(path: Path, processes: ProcessRunner) -> dict[str, Any]:
    argv = loudness_measure_command(str(path))
    argv.insert(argv.index("-af"), "-vn")
    completed = processes.run(argv)
    if completed.returncode != 0:
        raise _violation(f"the loudness measurement of {path.name} failed")
    try:
        return parse_loudnorm(completed.stderr.decode(errors="replace"))
    except ValueError as exc:
        raise _violation(f"the loudness measurement of {path.name}: {exc}") from exc


def _loudness_report(
    before: Mapping[str, Any], after: Mapping[str, Any]
) -> dict[str, float] | None:
    """The re-measured output loudness and the mix's before value; None when the output measures
    silent or unreadable, since JSON cannot carry an infinite number."""
    try:
        report = {
            "I": float(after["input_i"]),
            "LRA": float(after["input_lra"]),
            "TP": float(after["input_tp"]),
            "beforeI": float(before["input_i"]),
        }
    except (KeyError, TypeError, ValueError) as exc:
        raise _violation(f"the rendered video's loudness is unreadable: {exc}") from exc
    return report if all(math.isfinite(v) for v in report.values()) else None


def render_project(
    job: MediaJob, inputs: Mapping[str, Path], processes: ProcessRunner
) -> HandlerOutcome:
    declared = [ref.key for ref in job.inputs]
    if set(inputs) != set(declared):
        raise _violation("the resolved inputs do not match the declared inputs")
    project_path = inputs.get(PROJECT_INPUT)
    if project_path is None:
        raise _violation(f"a project render needs an input with key {PROJECT_INPUT!r}")

    try:
        document = parse_document(_read_project(project_path))
        spec = parse_job(job.params, declared, document)
    except (KeyError, TypeError, ValueError) as exc:
        raise _violation(str(exc)) from exc

    if spec.mode == "plan":
        return _plan_outcome(document, spec.profile)

    files = {asset_id: str(inputs[f"{ASSET_PREFIX}{asset_id}"]) for asset_id in spec.asset_ids}
    probes = _measure_assets(document, files, processes)

    work = project_path.parent
    produced: list[Path] = []
    video = work / VIDEO_NAME
    succeeded = False
    try:
        try:
            plan = plan_timeline(document, probes, spec.profile)
            styles = caption_styles(
                plan, plan.canvas.width, plan.canvas.height, load_glyph_advances
            )
            plan_json = plan_document(plan, document)
        except (KeyError, TypeError, ValueError) as exc:
            raise _violation(str(exc)) from exc

        def family_of(path: str) -> str:
            queried = processes.run(["fc-query", "-f", "%{family[0]}", path])
            return queried.stdout.decode(errors="replace") if queried.returncode == 0 else ""

        warnings = [*plan.warnings, *thin_strip_warnings(plan, probes)]
        chunk_files: list[str] = []
        total = len(plan.chunks)
        for index, chunk in enumerate(plan.chunks):
            step = f"chunk {index + 1} of {total}"
            script = work / f"chunk_{index}.ass"
            produced.append(script)
            produced.append(Path(chunk_path(str(work), index)))
            try:
                script.write_text(
                    ass_document(plan, styles, chunk.start_ms, chunk.dur_ms), encoding="utf-8"
                )
                argv = chunk_command(plan, index, files, str(work), str(script))
            except (KeyError, TypeError, ValueError) as exc:
                raise _violation(f"{step}: {exc}") from exc
            stderr = _run_step(processes, argv, Path(chunk_path(str(work), index)), step)
            chunk_files.append(chunk_path(str(work), index))
            if prints_text(plan, chunk):
                try:
                    warnings += audit_libass(
                        stderr.decode(errors="replace"),
                        _printed_fonts(plan, styles, chunk),
                        family_of,
                    )
                except ValueError as exc:
                    raise _violation(f"{step}: {exc}") from exc

        base = work / BASE_NAME
        if len(chunk_files) == 1:
            base = Path(chunk_files[0])
        else:
            listing = work / LIST_NAME
            produced += [listing, base]
            listing.write_text(concat_list(chunk_files), encoding="utf-8")
            _run_step(
                processes, join_command(chunk_files, str(listing), str(base)), base, "the join"
            )

        try:
            mix_argv, mix_path = mix_command(plan, str(base), files, str(work))
        except (KeyError, TypeError, ValueError) as exc:
            raise _violation(f"the audio mix: {exc}") from exc
        mix = Path(mix_path)
        produced.append(mix)
        _run_step(processes, mix_argv, mix, "the audio mix")
        before = _measure_loudness(mix, processes)
        try:
            audio_filter = loudnorm_filter(before)
        except ValueError as exc:
            raise _violation(f"the loudness measurement: {exc}") from exc
        if audio_filter is None:
            warnings.append(
                "the mix holds no audible sound (below -70 LUFS): no audio was normalised"
            )
        _run_step(
            processes,
            final_mux_command(
                str(base),
                str(mix),
                str(video),
                plan.total_ms,
                _video_start(base, processes),
                audio_filter,
            ),
            video,
            "the audio mux",
        )
        duration = _check_output(video, plan, processes)
        loudness = None
        if audio_filter is not None:
            after = _measure_loudness(video, processes)
            loudness = _loudness_report(before, after)

        captions = ass_document(plan, styles, 0.0, plan.total_ms).encode("utf-8")
        succeeded = True
    finally:
        for path in produced:
            path.unlink(missing_ok=True)
        if not succeeded:
            video.unlink(missing_ok=True)

    return HandlerOutcome(
        data={
            "renderer": RENDERER,
            "mode": "render",
            "profile": plan.profile,
            "width": plan.out_width,
            "height": plan.out_height,
            "durationSeconds": round(duration, 3),
            "encodeCount": total,
            "planHash": plan_json["planHash"],
            "honoredCount": len(honours()),
            "manifestHash": manifest_hash(),
            "loudness": loudness,
            "warnings": warnings,
            "snapped": list(plan.snapped),
            "handles": list(plan.handles),
        },
        files=[
            OutputFile(VIDEO_NAME, "video/mp4", video),
            OutputFile(CAPTIONS_NAME, "text/x-ssa", captions),
            OutputFile(PLAN_NAME, "application/json", _plan_bytes(plan_json)),
        ],
    )
