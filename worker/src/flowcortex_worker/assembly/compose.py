"""The compose audio graph: voice, ducked background and unducked effects into one limited mix."""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from typing import Literal

BG_DUCK = "threshold=0.05:ratio=4:attack=20:release=400"

Role = Literal["video", "background", "voice", "effects"]
BackgroundSource = Literal["file", "video"]


@dataclass(frozen=True)
class AudioGraph:
    """`inputs` lists the roles in `-i` order, the picture first; `graph` is the filter_complex,
    producing `[v]` and `[aout]`."""

    inputs: tuple[Role, ...]
    graph: str


def audio_graph(
    video_chain: str,
    background: BackgroundSource | None,
    effects: bool,
    gains: Mapping[str, float],
    target: float,
) -> AudioGraph | None:
    """None when only the voice plays (the caller maps the voice input as it is).

    `gains` carries `voice`, `background` and `effects` in dB. A `video` background reads the
    picture's own audio, so it adds no input. Only the background goes through the sidechain.
    """
    if background is None and not effects:
        return None
    roles: list[Role] = ["video"]
    if background == "file":
        roles.append("background")
    roles.append("voice")
    if effects:
        roles.append("effects")

    def label(role: Role) -> str:
        return f"[{roles.index(role)}:a]"

    length = f"{target:.3f}"
    chains = [f"[0:v]{video_chain}[v]"]
    mix: list[str] = []
    voice = f"{label('voice')}volume={gains['voice']:.2f}dB,aformat=channel_layouts=stereo"
    if background is None:
        chains.append(f"{voice}[v1]")
    else:
        source = "[0:a]" if background == "video" else label("background")
        # The background must cover the whole output: a source shorter than the picture leaves a
        # truncated audio stream that some players handle badly.
        chains.append(
            f"{source}volume={gains['background']:.2f}dB,aformat=channel_layouts=stereo,"
            f"apad=whole_dur={length}[bg]"
        )
        chains.append(f"{voice},asplit=2[v1][vsc]")
        # The key must be padded too: sidechaincompress ends with its shortest input, so a voice
        # shorter than the music would cut the music and leave silence from where the voice ends
        # (a 30.88 s background under a 4.75 s voice came out 4.72 s). Padded to the target, not
        # endless, because amix duration=longest never ends on an infinite input.
        chains.append(f"[vsc]apad=whole_dur={length}[vscpad]")
        chains.append(f"[bg][vscpad]sidechaincompress={BG_DUCK}[bgduck]")
        mix.append("[bgduck]")
    mix.append("[v1]")
    if effects:
        # atrim before apad: effects longer than the output must not stretch amix.
        chains.append(
            f"{label('effects')}volume={gains['effects']:.2f}dB,aformat=channel_layouts=stereo,"
            f"atrim=0:{length},apad=whole_dur={length}[sfx]"
        )
        mix.append("[sfx]")
    chains.append(
        f"{''.join(mix)}amix=inputs={len(mix)}:duration=longest:normalize=0,alimiter=limit=0.97[aout]"
    )
    return AudioGraph(tuple(roles), ";".join(chains))
