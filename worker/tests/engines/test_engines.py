"""The three engine children against their real models; runs inside the audio image only."""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path
from typing import Any

import pytest

pytest.importorskip("torch")

import numpy as np
import soundfile

from flowcortex_worker.audio.beat_grid import shape_result
from flowcortex_worker.audio.speaker_turns import turns
from flowcortex_worker.audio.windows import window_plan

pytestmark = pytest.mark.engines

WORKER_ROOT = Path(__file__).resolve().parents[2]
GAP_SECONDS = 0.6
DIALOGUE = [
    ("kal", "Good morning everyone and welcome to the weekly meeting about our new product launch"),
    ("slt", "Thank you very much I am happy to be here and I have some numbers to share with you"),
    (
        "kal",
        "Great please go ahead and tell us how the first week of sales went in the north region",
    ),
    ("slt", "The north region sold three thousand units which is much better than we expected"),
    ("kal", "That is wonderful news and I think we should increase the budget for advertising"),
    ("slt", "I agree with you and I will prepare a detailed plan for the marketing team by friday"),
]


def run_engine(module: str, *args: str) -> None:
    subprocess.run(
        [sys.executable, "-m", f"flowcortex_worker.engines.{module}", *args],
        check=True,
        capture_output=True,
        text=True,
        timeout=900,
    )


def flite_line(voice: str, text: str, target: Path) -> Any:
    subprocess.run(
        [
            *("ffmpeg", "-nostdin", "-y", "-f", "lavfi", "-i", f"flite=text={text}:voice={voice}"),
            *("-ac", "1", "-ar", "16000", str(target)),
        ],
        check=True,
        capture_output=True,
    )
    samples, _ = soundfile.read(str(target), dtype="float32")
    return samples


def dialogue_wav(folder: Path) -> tuple[Path, list[tuple[int, int]]]:
    rate = 16000
    gap = np.zeros(int(GAP_SECONDS * rate), dtype="float32")
    pieces: list[Any] = []
    truth: list[tuple[int, int]] = []
    cursor = 0
    for index, (voice, text) in enumerate(DIALOGUE):
        line = flite_line(voice, text, folder / f"line{index}.wav")
        truth.append((round(cursor * 1000 / rate), round((cursor + len(line)) * 1000 / rate)))
        pieces += [line, gap]
        cursor += len(line) + len(gap)
    target = folder / "dialogue.wav"
    soundfile.write(str(target), np.concatenate(pieces), rate)
    return target, truth


def diarize(wav: Path, output: Path) -> list[dict[str, int]]:
    run_engine("speakers", "--input", str(wav), "--output", str(output))
    raw = json.loads(output.read_text(encoding="utf-8"))
    return turns((float(a), float(b), int(label)) for a, b, label in raw)


def test_separation_writes_both_stems_over_two_windows(tmp_path: Path) -> None:
    rate, seconds = 44100, 70
    clock = np.arange(rate * seconds) / rate
    tones = np.stack(
        [np.sin(2 * np.pi * 220 * clock), np.sin(2 * np.pi * 880 * clock)], axis=1
    ).astype("float32")
    source = tmp_path / "tones.wav"
    soundfile.write(str(source), 0.3 * tones, rate)
    assert len(window_plan(rate * seconds, rate)) == 2

    run_engine("separate", "--input", str(source), "--output-dir", str(tmp_path))

    vocals = soundfile.info(str(tmp_path / "vocals.wav"))
    background = soundfile.info(str(tmp_path / "no_vocals.wav"))
    assert vocals.frames == background.frames == rate * seconds


def test_two_voices_taking_turns_are_two_speakers(tmp_path: Path) -> None:
    wav, truth = dialogue_wav(tmp_path)

    found = diarize(wav, tmp_path / "turns.json")

    assert {turn["label"] for turn in found} == {1, 2}
    assert [turn["label"] for turn in found] == [1, 2, 1, 2, 1, 2]
    for turn, (start, end) in zip(found, truth, strict=True):
        assert start <= (turn["fromMs"] + turn["toMs"]) / 2 <= end


def test_the_same_clip_twice_gives_identical_turns(tmp_path: Path) -> None:
    wav, _ = dialogue_wav(tmp_path)

    first = diarize(wav, tmp_path / "first.json")
    second = diarize(wav, tmp_path / "second.json")

    assert first == second


def drum_loop(rate: int, seconds: int, bpm: int) -> Any:
    """A 4/4 loop: kick on beats 1 and 3, snare on 2 and 4, hats every eighth, bass on the bar."""
    rng = np.random.default_rng(7)
    out = np.zeros(rate * seconds, dtype="float64")
    beat = 60.0 / bpm

    def sound_time(length: float) -> Any:
        return np.arange(int(length * rate)) / rate

    def put(at: float, sound: Any) -> None:
        start = int(at * rate)
        chunk = sound[: len(out) - start]
        out[start : start + len(chunk)] += chunk

    tk = sound_time(0.3)
    kick = np.sin(2 * np.pi * (50 * tk + 70 * (1 - np.exp(-tk * 30)) / 30)) * np.exp(-tk * 14)
    ts = sound_time(0.15)
    snare = (
        rng.standard_normal(len(ts)) * np.exp(-ts * 30) * 0.6
        + np.sin(2 * np.pi * 190 * ts) * np.exp(-ts * 25) * 0.4
    )
    th = sound_time(0.04)
    hat = np.diff(rng.standard_normal(len(th) + 1)) * np.exp(-th * 90) * 0.3
    tb = sound_time(beat * 3.8)
    bass = (np.sin(2 * np.pi * 55 * tb) + 0.5 * np.sin(2 * np.pi * 110 * tb)) * np.exp(-tb * 1.2)
    for n in range(int(seconds / beat)):
        at, position = n * beat, n % 4
        if position in (0, 2):
            put(at, kick)
        if position in (1, 3):
            put(at, snare)
        if position == 0:
            put(at, bass * 0.8)
        put(at, hat)
        put(at + beat / 2, hat)
    return (out / max(float(np.max(np.abs(out))), 1e-9) * 0.9).astype("float32")


def test_a_120_bpm_click_track_is_read_as_120(tmp_path: Path) -> None:
    rate, seconds = 22050, 30
    source = tmp_path / "click.wav"
    soundfile.write(str(source), drum_loop(rate, seconds, 120), rate)
    result = tmp_path / "beats.json"

    run_engine("beats", "--input", str(source), "--output", str(result))

    raw = json.loads(result.read_text(encoding="utf-8"))
    shaped = shape_result(
        raw["beats"], raw["downbeats"], raw["rms"], raw["frameMs"], seconds * 1000
    )
    assert abs(float(str(shaped["bpm"])) - 120.0) <= 1


def test_the_beat_evaluation_script_prints_its_help() -> None:
    done = subprocess.run(
        [sys.executable, "eval/beats_eval.py", "--help"],
        cwd=WORKER_ROOT,
        capture_output=True,
        text=True,
        timeout=120,
    )

    assert done.returncode == 0
