"""ffmpeg argument lists for a project render: one filter graph per chunk and the join.

Pure: nothing runs here. Every value that reaches a filter graph was checked by `document`
(closed lists, ranges, `#RRGGBB` fills) and every file name is built from an index, never from a
document id.
"""

from __future__ import annotations

import os
from collections.abc import Mapping

from .overlays import image_overlay_filters, image_windows
from .timeline import (
    PROFILES,
    Chunk,
    PlannedScene,
    PlannedTextOverlay,
    TimelinePlan,
    is_hdr,
)

CONTAIN_BLUR_SIGMA = 24
_ASSET_INPUT = ("-protocol_whitelist", "file")
_HDR_PREFIX = (
    "zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,"
    "tonemap=tonemap=hable:desat=0,zscale=t=bt709:m=bt709:r=tv,"
)


def chunk_path(work_dir: str, index: int) -> str:
    return os.path.join(work_dir, f"chunk_{index}.mp4")


def _overlay_xy(offset_x: float, offset_y: float) -> str:
    """`overlay` position that puts the zoomed layer's centre at the offsets (fractions of the
    frame from its centre), floored to even pixels: yuv420p samples colour per pixel pair, and
    `floor` rounds the same way for negative and positive offsets where `trunc` would not."""
    ox, oy = f"{offset_x:+.6g}", f"{offset_y:+.6g}"
    return f"x=floor(((W-w)/2{ox}*W)/2)*2:y=floor(((H-h)/2{oy}*H)/2)*2"


def _transform_chain(
    fitted: str, bg_filter: str, k: int, zoom: float, offset_x: float, offset_y: float
) -> str:
    fit_label, zoom_label, bg_label = f"[sc{k}fit]", f"[sc{k}z]", f"[sc{k}bg]"
    return (
        f"{fitted}{fit_label};{fit_label}scale=iw*{zoom:g}:ih*{zoom:g}{zoom_label};"
        f"{bg_filter}{bg_label};{bg_label}{zoom_label}overlay={_overlay_xy(offset_x, offset_y)}"
    )


def fit_chain(
    fit: str,
    width: int,
    height: int,
    k: int,
    blur_sigma: float,
    offset_x: float = 0.0,
    offset_y: float = 0.0,
    zoom: float = 1.0,
    fps: int = 30,
) -> str:
    """Bring the source frame to `width`x`height`; `k` is the scene's index in its chunk so labels
    never collide. At identity offsets and zoom the plain fit is returned. Otherwise the fitted
    picture is zoomed and overlaid on black (`cover`, `contain`) or on the blurred copy
    (`contain_blur`, blurred once). The black background needs `r=fps`: `color` defaults to 25 fps
    and `overlay` takes its frame rate from the background."""
    identity = offset_x == 0.0 and offset_y == 0.0 and zoom == 1.0
    if fit in ("cover", "contain"):
        if fit == "cover":
            fitted = (
                f"scale={width}:{height}:force_original_aspect_ratio=increase,crop={width}:{height}"
            )
        else:
            fitted = (
                f"scale={width}:{height}:force_original_aspect_ratio=decrease,"
                f"pad={width}:{height}:(ow-iw)/2:(oh-ih)/2:black"
            )
        if identity:
            return fitted
        return _transform_chain(
            fitted, f"color=c=black:s={width}x{height}:r={fps}", k, zoom, offset_x, offset_y
        )
    if fit == "contain_blur":
        bg, fg, bg_blur, fg_scaled = (f"[cb{k}_{name}]" for name in ("bg", "fg", "bgb", "fgs"))
        split = (
            f"split=2{bg}{fg};{bg}scale={width}:{height}:force_original_aspect_ratio=increase,"
            f"crop={width}:{height},gblur=sigma={blur_sigma:g}{bg_blur};"
            f"{fg}scale={width}:{height}:force_original_aspect_ratio=decrease{fg_scaled}"
        )
        if identity:
            return f"{split};{bg_blur}{fg_scaled}overlay=(W-w)/2:(H-h)/2"
        zoom_label = f"[sc{k}z]"
        return (
            f"{split};{fg_scaled}scale=iw*{zoom:g}:ih*{zoom:g}{zoom_label};"
            f"{bg_blur}{zoom_label}overlay={_overlay_xy(offset_x, offset_y)}"
        )
    raise ValueError(f"fit: unknown value {fit!r}")


def scene_length_ms(scene: PlannedScene) -> float:
    return scene.clamp.dur_ms if scene.clamp is not None else scene.dur_ms


def _scene_audio_on(scene: PlannedScene) -> bool:
    if scene.kind != "video" or not scene.keep_audio:
        return False
    has_audio = scene.probe.get("hasAudio")
    if not isinstance(has_audio, bool):
        raise ValueError(f"scene {scene.id}: the clip's audio stream was not measured")
    return has_audio


def prints_text(plan: TimelinePlan, chunk: Chunk) -> bool:
    """True when libass draws anything in the chunk: a cue or a text overlay overlaps its window."""
    end = chunk.start_ms + chunk.dur_ms
    return any(cue.start_ms < end and cue.end_ms > chunk.start_ms for cue in plan.cues) or any(
        isinstance(overlay, PlannedTextOverlay)
        and overlay.start_ms < end
        and overlay.end_ms > chunk.start_ms
        for overlay in plan.overlays
    )


def _escape_filter_path(path: str) -> str:
    return path.replace("\\", "\\\\").replace(":", "\\:").replace("'", "\\'")


def chunk_command(
    plan: TimelinePlan,
    chunk_index: int,
    files: Mapping[str, str],
    work_dir: str,
    ass_path: str,
    threads: int = 2,
) -> list[str]:
    """One ffmpeg call for one chunk: an input per scene, chained `xfade` / `acrossfade`, the
    chunk's captions drawn by `ass`. The picture is built at the output frame size, so a draft is
    cheap in the fit, the transitions and the caption pass alike. `files` maps an asset id to its
    local path."""
    chunk = plan.chunks[chunk_index]
    fps, rate = plan.canvas.fps, plan.canvas.sample_rate
    width, height = plan.out_width, plan.out_height
    blur_sigma = (
        CONTAIN_BLUR_SIGMA * min(width, height) / min(plan.canvas.width, plan.canvas.height)
    )
    lead = chunk.lead_scene
    scenes = [plan.scenes[i] for i in ([] if lead is None else [lead]) + list(chunk.scene_indexes)]

    # A segment is a scene's picture and sound with the handles its transitions borrow. `seg_off`
    # is how far into the segment this chunk starts (only a lead scene starts late), `seg_dur`
    # how long the chunk plays it and `seg_full` the whole segment, which an image's zoom spans.
    seg_in: list[float] = []
    seg_off: list[float] = []
    seg_dur: list[float] = []
    seg_full: list[float] = []
    position = 0.0
    for k, scene in enumerate(scenes):
        if k > 0:
            before = scenes[k - 1].transition_out
        elif lead is not None and lead > 0:
            before = plan.scenes[lead - 1].transition_out
        else:
            before = None
        last = k == len(scenes) - 1
        after = None if last and not chunk.cut_after else scene.transition_out
        head = before.dur_ms / 2 if before is not None and before.mode == "extend" else 0.0
        tail = after.dur_ms / 2 if after is not None and after.mode == "extend" else 0.0
        full = scene_length_ms(scene) + head + tail
        off = chunk.start_ms - (scene.at_ms - head) if k == 0 and lead is not None else 0.0
        dur = full - off
        if last and chunk.cut_after:
            dur = chunk.dur_ms - position
        seg_in.append(scene.src_in_ms - head)
        seg_off.append(off)
        seg_dur.append(dur)
        seg_full.append(full)
        transition = scene.transition_out
        position += dur - (transition.dur_ms if transition is not None and not last else 0.0)

    verbose = prints_text(plan, chunk)
    cmd = [
        "ffmpeg", "-y", "-hide_banner",
        "-loglevel", "verbose" if verbose else "error",
        *(["-nostats"] if verbose else []),
        "-threads", str(threads),
    ]  # fmt: skip
    inputs = 0
    parts: list[str] = []
    video_labels: list[str] = []
    audio_labels: list[str] = []

    def add_input(*args: str) -> int:
        nonlocal inputs
        cmd.extend(args)
        inputs += 1
        return inputs - 1

    for k, scene in enumerate(scenes):
        dur_s = seg_dur[k] / 1000.0
        # `fps=` ends every picture chain: ffmpeg 7.1 `xfade` wants a constant frame rate on both
        # inputs, and the `trim,setpts` before it would leave the rate undefined.
        off_s = seg_off[k] / 1000.0
        tail_chain = (
            f"setsar=1,format=yuv420p,trim=duration={dur_s:.4f},setpts=PTS-STARTPTS,fps={fps}"
        )
        if scene.clip_id is None:
            color = scene.fill.replace("#", "0x")
            vi = add_input(
                "-f",
                "lavfi",
                "-t",
                f"{dur_s:.4f}",
                "-i",
                f"color=c={color}:s={width}x{height}:r={fps}",
            )
            parts.append(f"[{vi}:v]{tail_chain}[v{k}]")
        elif scene.kind == "image":
            vi = add_input(
                "-loop", "1", "-framerate", str(fps), "-t", f"{dur_s + off_s:.4f}",
                *_ASSET_INPUT, "-i", files[scene.clip_id],
            )  # fmt: skip
            frames = max(1, round(seg_full[k] / 1000.0 * fps))
            motion = ""
            if scene.motion is not None:
                z0, z1 = scene.motion.from_scale, scene.motion.to_scale
                if scene.motion.kind == "zoom_out":
                    z0, z1 = max(z0, z1), min(z0, z1)
                motion = (
                    f"zoompan=z='{z0}+({z1}-{z0})*on/{frames}':x='iw/2-(iw/zoom/2)':"
                    f"y='ih/2-(ih/zoom/2)':d=1:s={width}x{height}:fps={fps},"
                )
            graph = fit_chain(
                scene.fit, width, height, k, blur_sigma,
                scene.offset_x or 0.0, scene.offset_y or 0.0,
                scene.zoom if scene.zoom is not None else 1.0, fps,
            )  # fmt: skip
            # A lead image plays on from where the cut chunk stopped, so its zoom is rendered
            # whole and the part already shown is dropped.
            if off_s > 0:
                tail_chain = tail_chain.replace("trim=duration", f"trim=start={off_s:.4f}:duration")
            parts.append(f"[{vi}:v]{graph},{motion}{tail_chain}[v{k}]")
        else:
            vi = add_input(
                "-ss", f"{(seg_in[k] + seg_off[k]) / 1000.0:.4f}", "-t", f"{dur_s:.4f}",
                *_ASSET_INPUT, "-i", files[scene.clip_id],
            )  # fmt: skip
            prefix = _HDR_PREFIX if is_hdr(scene.probe) else ""
            graph = fit_chain(
                scene.fit, width, height, k, blur_sigma,
                scene.offset_x or 0.0, scene.offset_y or 0.0,
                scene.zoom if scene.zoom is not None else 1.0, fps,
            )  # fmt: skip
            parts.append(f"[{vi}:v]{prefix}{graph},{tail_chain}[v{k}]")
        if _scene_audio_on(scene):
            parts.append(
                f"[{vi}:a]aresample={rate},aformat=sample_fmts=fltp:channel_layouts=stereo,"
                f"volume={scene.gain_db}dB,atrim=duration={dur_s:.4f},asetpts=PTS-STARTPTS,"
                f"apad=whole_dur={dur_s:.4f}[a{k}]"
            )
        else:
            ai = add_input(
                "-f", "lavfi", "-t", f"{dur_s:.4f}", "-i", f"anullsrc=r={rate}:cl=stereo"
            )
            parts.append(f"[{ai}:a]atrim=duration={dur_s:.4f},asetpts=PTS-STARTPTS[a{k}]")
        video_labels.append(f"[v{k}]")
        audio_labels.append(f"[a{k}]")

    video_cur, audio_cur, cur_len = video_labels[0], audio_labels[0], seg_dur[0]
    for k in range(1, len(scenes)):
        transition = scenes[k - 1].transition_out
        if transition is None:
            raise ValueError(f"scene {scenes[k - 1].id}: chained scenes need a transition")
        d = transition.dur_ms / 1000.0
        offset = (cur_len - transition.dur_ms) / 1000.0
        parts.append(
            f"{video_cur}{video_labels[k]}xfade=transition={transition.kind}:"
            f"duration={d:.4f}:offset={offset:.4f}[vx{k}]"
        )
        parts.append(f"{audio_cur}{audio_labels[k]}acrossfade=d={d:.4f}:c1=tri:c2=tri[ax{k}]")
        video_cur, audio_cur = f"[vx{k}]", f"[ax{k}]"
        cur_len = cur_len + seg_dur[k] - transition.dur_ms
    # Image overlays go after the scene chain and before the ASS pass, so text always sits on top.
    for j, (overlay, window) in enumerate(image_windows(plan, chunk)):
        src = add_input(*_ASSET_INPUT, "-i", files[overlay.asset_id])
        filters, video_cur = image_overlay_filters(overlay, window, src, video_cur, j, width, fps)
        parts += filters
    parts.append(f"{video_cur}ass='{_escape_filter_path(ass_path)}'[vout]")
    parts.append(f"{audio_cur}anull[aout]")

    profile = PROFILES[plan.profile]
    cmd += [
        "-filter_complex", ";".join(parts),
        "-map", "[vout]", "-map", "[aout]",
        "-c:v", "libx264", "-preset", profile.preset, "-crf", str(profile.crf),
        "-pix_fmt", "yuv420p", "-r", str(fps),
        "-c:a", "aac", "-b:a", "160k", "-ar", str(rate),
        "-t", f"{cur_len / 1000.0:.4f}", "-movflags", "+faststart",
        chunk_path(work_dir, chunk_index),
    ]  # fmt: skip
    return cmd


def concat_list(chunk_paths: list[str]) -> str:
    """Body of the concat demuxer's list file: the chunk files and nothing else."""
    return "\n".join("file '" + path.replace("'", "'\\''") + "'" for path in chunk_paths) + "\n"


def join_command(chunk_paths: list[str], list_path: str, out_path: str) -> list[str]:
    """Join the chunks by stream copy; `list_path` holds `concat_list(chunk_paths)`."""
    if len(chunk_paths) < 2:
        raise ValueError("join needs at least two chunk files")
    return [
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-f", "concat", "-safe", "0", "-i", list_path,
        "-c", "copy", "-movflags", "+faststart", out_path,
    ]  # fmt: skip
