"""Deterministic dark-luxury ambient bass beat synthesizer for Showroom BGM ($0, CC0)."""

from __future__ import annotations

import math
import struct
import wave
from pathlib import Path

_SAMPLE_RATE = 24_000
_BPM = 100.0
_BEAT_SECONDS = 60.0 / _BPM  # 0.6s per beat
# Deep minor luxury progression (D1, Bb0, G1, A1) switching every 4 beats (2.4s)
_ROOT_FREQS = (73.42, 58.27, 98.00, 55.00)


def write_luxury_bgm_wav(
    path: Path, duration_seconds: float, *, sample_rate: int = _SAMPLE_RATE
) -> Path:
    """Synthesize a clean mono 16-bit PCM dark-luxury commercial beat WAV to `path`."""
    total_frames = max(1, round(duration_seconds * sample_rate))
    fade_in = max(1, round(0.35 * sample_rate))
    fade_out = max(1, round(0.9 * sample_rate))
    samples = bytearray(total_frames * 2)

    two_pi = 2.0 * math.pi
    for i in range(total_frames):
        t = i / sample_rate
        beat_pos = t / _BEAT_SECONDS
        beat_idx = int(beat_pos)
        beat_frac = beat_pos - beat_idx
        bar_idx = (beat_idx // 4) % len(_ROOT_FREQS)
        root = _ROOT_FREQS[bar_idx]

        # 1. Warm sub-bass pulse synced to each beat
        bass_env = math.exp(-3.2 * beat_frac) * (1.0 - math.exp(-60.0 * beat_frac))
        sub_bass = 0.42 * bass_env * (
            math.sin(two_pi * root * t) + 0.35 * math.sin(two_pi * root * 2.0 * t)
        )

        # 2. Atmospheric minor fifth + octave pad (smooth, non-intrusive under voice)
        pad_lfo = 0.78 + 0.22 * math.sin(two_pi * 0.25 * t)
        pad = (
            0.16
            * pad_lfo
            * (
                math.sin(two_pi * (root * 3.0) * t)
                + 0.6 * math.sin(two_pi * (root * 4.0) * t)
                + 0.35 * math.sin(two_pi * (root * 4.7568) * t)
            )
        )

        # 3. Tight low kick on beats 0 and 2 of each bar
        kick = 0.0
        if beat_idx % 2 == 0 and beat_frac < 0.25:
            kick_env = math.exp(-18.0 * beat_frac)
            kick_freq = 48.0 + 95.0 * math.exp(-35.0 * beat_frac)
            kick = 0.38 * kick_env * math.sin(two_pi * kick_freq * beat_frac * _BEAT_SECONDS)

        # Master fade-in / fade-out envelope
        env = 1.0
        if i < fade_in:
            env *= i / fade_in
        if i >= total_frames - fade_out:
            env *= max(0.0, (total_frames - 1 - i) / fade_out)

        val = max(-0.92, min(0.92, (sub_bass + pad + kick) * env))
        struct.pack_into("<h", samples, i * 2, int(val * 32767))

    with wave.open(str(path), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(sample_rate)
        wav.writeframes(samples)
    return path
