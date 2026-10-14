"""Aligns the TTS speech track to the source video timeline.

The speech asset from ``generate_speech`` has its own natural duration, which rarely matches
the source video. Rather than stretching the voice audibly, the track is padded or sped up
with ``atempo`` by at most 1.5x, so segments keep their provider timings and only the
silence/overlap at the edges is absorbed.
"""

from __future__ import annotations

import json
from collections.abc import Mapping
from pathlib import Path

from ..contracts.media_job import MediaJob
from ..errors import PermanentError
from ..runtime import ProcessRunner
from . import HandlerOutcome, OutputFile

PROFILE = "vi_voice_v1"
SPEECH_KEY = "speech"
OUTPUT_NAME = "aligned-speech.wav"
OUTPUT_MIME = "audio/wav"
MANIFEST_NAME = "alignment-manifest.json"

# Total speed-up allowed; faster speech is unintelligible, so beyond it the run goes back to
# review. Stays inside a single ffmpeg atempo instance (0.5-2.0).
MAX_SPEEDUP = 1.5

# A short track is padded with silence rather than stretched; the cap is relative to the
# source so a two-second clip is not padded to a fixed absolute length. Beyond it the track
# is too sparse to align, and the run should be sent back to review instead.
MAX_PAD_RATIO = 1.25


def handle(
    job: MediaJob,
    inputs: Mapping[str, Path],
    processes: ProcessRunner,
) -> HandlerOutcome:
    speech = inputs.get(SPEECH_KEY)
    if speech is None:
        raise PermanentError(
            "contract_violation",
            f"align_audio needs an input with key {SPEECH_KEY!r}",
        )
    profile = job.params.get("profile")
    if profile != PROFILE:
        raise PermanentError(
            "contract_violation",
            f"align_audio requires profile {PROFILE!r}",
        )
    keys = [ref.key for ref in job.inputs]
    if len(keys) != len(set(keys)) or set(keys) != {SPEECH_KEY}:
        raise PermanentError(
            "contract_violation",
            f"align_audio takes exactly one {SPEECH_KEY!r} input",
        )
    if set(inputs) != {SPEECH_KEY}:
        raise PermanentError(
            "contract_violation",
            "align_audio resolved inputs do not match the declared inputs",
        )

    speech_seconds = _duration_seconds(speech, processes)
    if speech_seconds <= 0:
        raise PermanentError("contract_violation", "the speech track has no measurable duration")

    target = job.params.get("targetDurationSeconds")
    if target is None:
        raise PermanentError(
            "contract_violation",
            "align_audio requires params.targetDurationSeconds",
        )
    if not isinstance(target, (int, float)) or isinstance(target, bool) or target <= 0:
        raise PermanentError(
            "contract_violation",
            "align_audio requires a positive numeric targetDurationSeconds",
        )
    target_seconds = float(target)

    tempo, pad_seconds = plan_alignment(speech_seconds, target_seconds)

    output = speech.parent / OUTPUT_NAME
    filter_graph = _filter_graph(tempo, pad_seconds)
    completed = processes.run(
        [
            "ffmpeg",
            "-nostdin",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-protocol_whitelist",
            "file",
            "-i",
            str(speech),
            "-af",
            filter_graph,
            "-ac",
            "1",
            "-ar",
            "16000",
            "-c:a",
            "pcm_s16le",
            str(output),
        ]
    )
    if completed.returncode != 0 or not output.is_file() or output.stat().st_size == 0:
        output.unlink(missing_ok=True)
        raise PermanentError(
            "audio_alignment_failed",
            "the speech track could not be aligned to the source timeline",
        )

    aligned_seconds = _duration_seconds(output, processes)
    manifest = {
        "alignedDurationSeconds": round(aligned_seconds, 3),
        "padSeconds": round(pad_seconds, 3),
        "profile": PROFILE,
        "sourceDurationSeconds": round(speech_seconds, 3),
        "tempo": round(tempo, 4),
        "targetDurationSeconds": round(target_seconds, 3),
    }
    encoded = json.dumps(
        manifest,
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    ).encode()
    return HandlerOutcome(
        data=manifest,
        files=[
            OutputFile(OUTPUT_NAME, OUTPUT_MIME, output),
            OutputFile(MANIFEST_NAME, "application/json", encoded),
        ],
    )


def plan_alignment(speech_seconds: float, target_seconds: float) -> tuple[float, float]:
    """Returns the ``atempo`` ratio and trailing pad needed to meet ``target_seconds``.

    Pure and total, so the pad/tempo/overlap rules are decided once here and fixture-tested
    rather than implied by ffmpeg flags. A track longer than the target is sped up, never
    truncated: cutting the tail would drop a spoken segment.
    """
    if speech_seconds <= 0 or target_seconds <= 0:
        raise ValueError("durations must be positive")

    if speech_seconds <= target_seconds:
        pad = target_seconds - speech_seconds
        if pad > speech_seconds * MAX_PAD_RATIO:
            raise PermanentError(
                "audio_alignment_out_of_range",
                f"speech is {speech_seconds:.3f}s against a {target_seconds:.3f}s target: "
                f"padding would need {pad:.3f}s of silence",
            )
        return 1.0, pad

    tempo = speech_seconds / target_seconds
    if tempo > MAX_SPEEDUP:
        raise PermanentError(
            "audio_alignment_out_of_range",
            f"speech is {speech_seconds:.3f}s against a {target_seconds:.3f}s target: "
            f"it would need a {tempo:.2f}x speed-up, above the {MAX_SPEEDUP}x limit",
        )
    return tempo, 0.0


def _filter_graph(tempo: float, pad_seconds: float) -> str:
    parts: list[str] = []
    if abs(tempo - 1.0) > 1e-6:
        parts.append(f"atempo={tempo:.6f}")
    if pad_seconds > 0:
        parts.append(f"apad=pad_dur={pad_seconds:.3f}")
    # apad extends the tail, so the natural end of the graph is already the target length;
    # trimming here would cut the pad back off.
    return ",".join(parts) if parts else "anull"


def _duration_seconds(path: Path, processes: ProcessRunner) -> float:
    completed = processes.run(
        [
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "format=duration",
            "-of",
            "default=noprint_wrappers=1:nokey=1",
            str(path),
        ]
    )
    if completed.returncode != 0:
        raise PermanentError(
            "audio_alignment_failed",
            "ffprobe could not measure the speech duration",
        )
    raw = completed.stdout.decode().strip()
    try:
        return float(raw)
    except ValueError as exc:
        raise PermanentError(
            "audio_alignment_failed",
            "ffprobe returned an unparseable duration",
        ) from exc
