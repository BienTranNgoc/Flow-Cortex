"""Duration policies: how long the output is and how the picture is extended to reach it."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

from ..errors import PermanentError

POLICIES = ("match_audio", "match_video", "pad", "warn")
DEFAULT_POLICY = "match_audio"
# About 1.5 frames at 30 fps, enough to absorb ffprobe's jitter.
EPS_SEC = 0.05
WARN_THRESHOLD_SEC = 0.5

PadMode = Literal["clone", "add"]


@dataclass(frozen=True)
class Plan:
    target: float
    pad_mode: PadMode | None
    pad_seconds: float
    mismatch: bool

    @property
    def tpad(self) -> str | None:
        """The filter that extends the picture past its last frame, or None when it is not."""
        if self.pad_mode is None:
            return None
        return f"tpad=stop_mode={self.pad_mode}:stop_duration={self.pad_seconds:.3f}"


def _unknown(raw: object) -> PermanentError:
    return PermanentError(
        "contract_violation",
        f"durationPolicy must be one of {', '.join(POLICIES)}, got {raw!r}",
    )


def resolve_policy(raw: object) -> str:
    """The `durationPolicy` parameter: absent means the default, anything else must be known."""
    if raw is None:
        return DEFAULT_POLICY
    if isinstance(raw, str) and raw in POLICIES:
        return raw
    raise _unknown(raw)


def plan(video_s: float, audio_s: float, policy: str, keep_longer: bool) -> Plan:
    """With picture length V and audio length A: `match_video` and `warn` keep V (`warn` flags a
    difference above 0.5 s); `match_audio` and `pad` extend the picture by A - V when that
    exceeds 50 ms, by cloning the last frame or adding black. The target is A, or max(V, A)
    when `keep_longer`."""
    if policy not in POLICIES:
        raise _unknown(policy)
    delta = audio_s - video_s
    if policy in ("match_video", "warn"):
        return Plan(video_s, None, 0.0, policy == "warn" and abs(delta) > WARN_THRESHOLD_SEC)
    target = max(video_s, audio_s) if keep_longer else audio_s
    if delta > EPS_SEC:
        return Plan(target, "clone" if policy == "match_audio" else "add", delta, False)
    return Plan(target, None, 0.0, False)
