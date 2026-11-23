"""Applies one editing op to a window of a video: a cut, a quick baked-in effect, a stabilisation
or a 9:16 reframe that follows the face and the product."""

from __future__ import annotations

import math
import shutil
import sys
from collections.abc import Callable, Iterator, Mapping
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from ..contracts.media_job import MediaJob
from ..errors import PermanentError
from ..runtime import ProcessRunner
from ..video_analysis.streams import VideoStream, last_line, tool_failure
from ..video_ops import protocol, reframe_path, stabilize
from ..video_ops.effects import check_effect, effect_filter
from ..video_ops.media import Clip, describe_output, probe_clip, read_window
from . import HandlerOutcome, OutputFile

OUTPUT_NAME = "derive.mp4"
OUTPUT_MIME = "video/mp4"
WORK_DIR = "vision"
CROP_COMMANDS = "crop.cmd"
TRANSFORM_FILE = "stab.trf"
_FFMPEG = ["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y"]
_FFMPEG_VERBOSE = ["ffmpeg", "-nostdin", "-hide_banner", "-v", "verbose", "-y"]
_FILE_ONLY = ["-protocol_whitelist", "file"]


@dataclass(frozen=True)
class Request:
    """One derive job after its parameters were checked and its input probed."""

    video: Path
    clip: Clip
    start_ms: int
    end_ms: int
    bounded: bool
    keep_audio: bool
    processes: ProcessRunner

    @property
    def output(self) -> Path:
        return self.video.parent / OUTPUT_NAME

    @property
    def window_seconds(self) -> float:
        return (self.end_ms - self.start_ms) / 1000


# An op's `Prepare` checks its `opParams` before any tool runs and returns the `Render` that
# makes the file and answers the `extras` of the job's data.
Render = Callable[[Request], dict[str, Any]]
Prepare = Callable[[Mapping[str, Any]], Render]


def _encode_argv(request: Request, video_filter: str | None, length: float | None) -> list[str]:
    seek = ["-ss", f"{request.start_ms / 1000:.3f}"] if request.start_ms > 0 else []
    limit = ["-t", f"{length:.3f}"] if length is not None else []
    audio = ["-map", "0:a:0", "-c:a", "aac", "-b:a", "128k"] if request.keep_audio else ["-an"]
    return [
        *_FFMPEG, "-protocol_whitelist", "file",
        *seek, "-i", str(request.video), *limit,
        "-map", f"0:{request.clip.video.index}",
        *(["-vf", video_filter] if video_filter else []),
        "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p",
        # The input's rotation is applied to the pixels while decoding; the tag is cleared so a
        # player does not turn the picture a second time.
        "-metadata:s:v:0", "rotate=0",
        *audio,
        "-movflags", "+faststart",
        str(request.output),
    ]  # fmt: skip


def _encode(request: Request, video_filter: str | None, length: float | None, what: str) -> None:
    encoded = request.processes.run(_encode_argv(request, video_filter, length))
    if encoded.returncode != 0:
        raise tool_failure(
            "ffmpeg",
            f"could not {what} the input (exit {encoded.returncode}): {last_line(encoded.stderr)}",
        )


def _prepare_cut(_op_params: Mapping[str, Any]) -> Render:
    def render(request: Request) -> dict[str, Any]:
        # Without srcOutMs the encode runs to the end of the input, so a measured end never
        # cuts its last frame.
        _encode(request, None, request.window_seconds if request.bounded else None, "cut")
        return {}

    return render


def _rate_pair(stream: VideoStream) -> tuple[int, int]:
    if stream.rate is None:
        raise ValueError("the frame rate was not read; probe with require_rate=True")
    return stream.rate


def _frame_rate(stream: VideoStream) -> str:
    num, den = _rate_pair(stream)
    return f"{num}/{den}"


def _prepare_effect(op_params: Mapping[str, Any]) -> Render:
    name = check_effect(op_params.get("effect"))

    def render(request: Request) -> dict[str, Any]:
        stream = request.clip.video
        seconds = request.window_seconds
        graph = effect_filter(
            name, stream.width, stream.height, _frame_rate(stream), stream.fps, seconds
        )
        _encode(request, graph, seconds, f"apply the {name} effect to")
        return {}

    return render


def _seek(request: Request) -> list[str]:
    return ["-ss", f"{request.start_ms / 1000:.3f}"] if request.start_ms > 0 else []


def _limit(request: Request) -> list[str]:
    return ["-t", f"{request.window_seconds:.3f}"] if request.bounded else []


def _audio_codec(request: Request) -> list[str]:
    return ["-c:a", "aac", "-b:a", "128k"] if request.keep_audio else ["-an"]


def _run(request: Request, argv: list[str], what: str) -> bytes:
    """Run one ffmpeg step; its error output, or a permanent failure naming its last line."""
    done = request.processes.run(argv)
    if done.returncode != 0:
        raise tool_failure(
            "ffmpeg", f"could not {what} (exit {done.returncode}): {last_line(done.stderr)}"
        )
    return done.stderr


@contextmanager
def _vision_dir(request: Request) -> Iterator[Path]:
    """A cleared work directory for the vision child and its ffmpeg files, removed afterwards."""
    work = request.video.parent / WORK_DIR
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir(parents=True)
    try:
        yield work
    finally:
        shutil.rmtree(work, ignore_errors=True)


def _motion(result: Mapping[str, Any], key: str) -> float:
    value = result.get(key)
    if (
        isinstance(value, bool)
        or not isinstance(value, int | float)
        or not math.isfinite(value)
        or value < 0
    ):
        raise PermanentError(
            "contract_violation", f"the vision child's result has no usable {key!r} (got {value!r})"
        )
    return float(value)


def _prepare_stabilize(op_params: Mapping[str, Any]) -> Render:
    smoothing, optzoom = stabilize.read_params(op_params)

    def render(request: Request) -> dict[str, Any]:
        stream = request.clip.video
        stabilize.check_disk_room(
            stream.width, stream.height, stream.fps, request.window_seconds, request.video.parent
        )
        with _vision_dir(request) as work:
            window, transform = work / protocol.WINDOW_FILE, work / TRANSFORM_FILE
            output = work / protocol.OUTPUT_FILE
            # Both vidstab passes read this all-intra copy: vidstabtransform fed from a seeked
            # decode of the source's H.264 comes out corrupt, over an intra copy it is clean.
            _run(
                request,
                [
                    *_FFMPEG, *_FILE_ONLY, *_seek(request), "-i", str(request.video),
                    *_limit(request), "-map", f"0:{stream.index}", "-an",
                    "-c:v", "libx264", "-preset", "ultrafast", "-g", "1", "-crf", "10",
                    "-pix_fmt", "yuv420p", str(window),
                ],
                "cut the window of",
            )  # fmt: skip
            _run(
                request,
                [
                    *_FFMPEG, *_FILE_ONLY, "-i", str(window),
                    "-vf", f"vidstabdetect=result={transform}", "-an", "-f", "null", "-",
                ],
                "analyse the shake of",
            )  # fmt: skip
            log = _run(
                request,
                [
                    *_FFMPEG_VERBOSE, *_FILE_ONLY, "-i", str(window),
                    *(
                        [*_FILE_ONLY, *_seek(request), "-i", str(request.video)]
                        if request.keep_audio
                        else []
                    ),
                    "-map", "0:v:0", *(["-map", "1:a:0"] if request.keep_audio else []),
                    "-vf",
                    f"vidstabtransform=input={transform}:smoothing={smoothing}:optzoom={optzoom}",
                    "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p",
                    "-metadata:s:v:0", "rotate=0", *_limit(request),
                    *_audio_codec(request), "-movflags", "+faststart", str(output),
                ],
                "stabilise",
            )  # fmt: skip
            # vidstab's verbose log carries a raw 0xff byte, so it is decoded leniently.
            zoom = stabilize.parse_zoom_pct(log.decode("utf-8", errors="replace"))
            measured = protocol.run_child("motion", work, {}, request.processes)
            steadier = stabilize.not_steadier(
                _motion(measured, "windowMotion"), _motion(measured, "outputMotion")
            )
            output.replace(request.output)
        return {"zoomPct": zoom, "notSteadier": steadier}

    return render


def _read_track(result: Mapping[str, Any]) -> tuple[list[int], list[reframe_path.Box | None]]:
    """The cut frames and the per-frame face boxes of the child's `track` result."""
    frames, cuts, faces = result.get("frames"), result.get("cuts"), result.get("faces")
    if (
        isinstance(frames, bool)
        or not isinstance(frames, int)
        or not isinstance(cuts, list)
        or not all(isinstance(c, int) and not isinstance(c, bool) for c in cuts)
        or not isinstance(faces, list)
        or len(faces) != frames
    ):
        raise PermanentError("contract_violation", "the vision child's 'track' result is malformed")
    boxes: list[reframe_path.Box | None] = []
    for face in faces:
        if face is None:
            boxes.append(None)
        elif (
            isinstance(face, list)
            and len(face) == 4
            and all(isinstance(v, int | float) and not isinstance(v, bool) for v in face)
        ):
            boxes.append((float(face[0]), float(face[1]), float(face[2]), float(face[3])))
        else:
            raise PermanentError(
                "contract_violation", "the vision child's 'track' result is malformed"
            )
    return cuts, boxes


def _prepare_reframe(op_params: Mapping[str, Any]) -> Render:
    # The boxes are a vision model's reply: one that cannot be read means "faces only".
    boxes = op_params.get("boxes")
    sheet = op_params.get("sheet", {})
    if not isinstance(sheet, Mapping):
        raise PermanentError(
            "contract_violation", f"param 'sheet' must be an object (got {sheet!r})"
        )

    def render(request: Request) -> dict[str, Any]:
        stream = request.clip.video
        win = reframe_path.window_width(stream.height)
        if win >= stream.width:
            raise PermanentError(
                "input_rejected",
                f"the {stream.width}x{stream.height} source is not wider than the {win} px "
                f"window of a 9:16 crop",
            )
        rate = _frame_rate(stream)
        analysis_width = min(reframe_path.ANALYSIS_WIDTH, stream.width)
        analysis_height = max(2, round(stream.height * analysis_width / stream.width / 2) * 2)
        timing = f"fps={rate},setpts=N/({rate}*TB)"
        with _vision_dir(request) as work:
            _run(
                request,
                [
                    *_FFMPEG, *_FILE_ONLY, *_seek(request), "-i", str(request.video),
                    *_limit(request), "-map", f"0:{stream.index}", "-an",
                    "-vf", f"{timing},scale={analysis_width}:{analysis_height}",
                    "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
                    "-pix_fmt", "yuv420p", str(work / protocol.ANALYSIS_FILE),
                ],
                "sample the window of",
            )  # fmt: skip
            tracked = protocol.run_child(
                "track",
                work,
                {
                    "stride": max(1, round(stream.fps / reframe_path.FACE_FPS)),
                    "scale": stream.width / analysis_width,
                },
                request.processes,
            )
        cuts, faces = _read_track(tracked)
        if not faces:
            raise tool_failure("ffmpeg", "decoded no frame of the window")

        usable = reframe_path.sheet_boxes_to_frames(boxes, sheet)
        answers: dict[int, list[int] | None] = dict.fromkeys(
            reframe_path.sheet_null_frames(boxes, sheet)
        )
        answers.update(usable)
        products = reframe_path.product_per_frame(
            answers, cuts, len(faces), stream.fps, stream.width
        )
        path = reframe_path.crop_path(len(faces), faces, products, cuts, win, stream.width)

        commands = request.video.parent / CROP_COMMANDS
        commands.write_text(reframe_path.crop_commands(path, _rate_pair(stream)), encoding="utf-8")
        try:
            _run(
                request,
                [
                    *_FFMPEG, *_FILE_ONLY, *_seek(request), "-i", str(request.video),
                    *_limit(request), "-map", f"0:{stream.index}",
                    *(["-map", "0:a:0"] if request.keep_audio else []),
                    "-vf",
                    f"{timing},sendcmd=f={commands},crop={win}:{stream.height}:{path[0]}:0",
                    "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p",
                    "-metadata:s:v:0", "rotate=0", *_audio_codec(request),
                    "-movflags", "+faststart", str(request.output),
                ],
                "reframe the window of",
            )  # fmt: skip
        finally:
            commands.unlink(missing_ok=True)
        return {"productMissing": not usable}

    return render


OPS: dict[str, Prepare] = {
    "cut": _prepare_cut,
    "effect": _prepare_effect,
    "stabilize": _prepare_stabilize,
    "reframe": _prepare_reframe,
}


def _check_params(params: Mapping[str, Any]) -> tuple[Render, bool]:
    """The op's `Render` and `keepAudio`; a window shape is checked here too, against no length,
    because the clip's length is only known after the probe."""
    op = params.get("op")
    if not isinstance(op, str) or op not in OPS:
        raise PermanentError(
            "contract_violation", f"param 'op' must be one of {', '.join(OPS)} (got {op!r})"
        )
    keep_audio = params.get("keepAudio", True)
    if not isinstance(keep_audio, bool):
        raise PermanentError(
            "contract_violation", f"param 'keepAudio' must be true or false (got {keep_audio!r})"
        )
    op_params = params.get("opParams", {})
    if not isinstance(op_params, Mapping):
        raise PermanentError(
            "contract_violation", f"param 'opParams' must be an object (got {op_params!r})"
        )
    read_window(params, "srcInMs", "srcOutMs", sys.maxsize, "ms")
    return OPS[op](op_params), keep_audio


def handle(
    job: MediaJob,
    inputs: Mapping[str, Path],
    processes: ProcessRunner,
) -> HandlerOutcome:
    if set(inputs) != {"video"}:
        raise PermanentError(
            "contract_violation",
            f"derive needs exactly one input with key 'video' (got {sorted(inputs)})",
        )
    render, keep_audio = _check_params(job.params)
    video = inputs["video"]
    clip = probe_clip(video, processes)
    start_ms, end_ms = read_window(job.params, "srcInMs", "srcOutMs", clip.duration_ms, "ms")
    request = Request(
        video=video,
        clip=clip,
        start_ms=start_ms,
        end_ms=end_ms,
        bounded="srcOutMs" in job.params,
        keep_audio=keep_audio and clip.has_audio,
        processes=processes,
    )

    extras = render(request)

    described = describe_output(request.output, processes)
    return HandlerOutcome(
        data={**described, "extras": extras},
        files=[OutputFile(OUTPUT_NAME, OUTPUT_MIME, request.output)],
    )
