"""The quick effects of the derive `effect` op, each one ffmpeg filter graph."""

from __future__ import annotations

from ..errors import PermanentError

EFFECTS = ("flash", "zoom", "shake", "glitch", "slowmo")

FLASH_SECONDS = 0.3
ZOOM_END = 1.2
SHAKE_FRACTION = 0.02
GLITCH_FRACTION = 0.015
GLITCH_BURST = 3
GLITCH_PERIOD = 10
# Constants of the sine hash behind the shake offsets; changing them changes every offset.
_SHAKE_X = (12.9898, 78.233, 43758.5453)
_SHAKE_Y = (39.3468, 11.135, 24634.6345)
# yuv420p needs even sides; the effects that do not size their own output trim an odd source first.
_EVEN_SIDES = "scale=trunc(iw/2)*2:trunc(ih/2)*2"


def check_effect(name: object) -> str:
    """`name` when it is one of `EFFECTS`; anything else is refused naming the `effect` param."""
    if not isinstance(name, str) or name not in EFFECTS:
        raise PermanentError(
            "contract_violation",
            f"param 'effect' of opParams must be one of {', '.join(EFFECTS)} (got {name!r})",
        )
    return name


def _hash01(frame: str, constants: tuple[float, float, float]) -> str:
    """A stateless pseudo-random value in [0, 1) of the frame number, as an ffmpeg expression."""
    a, b, c = constants
    return f"(sin({frame}*{a}+{b})*{c}-floor(sin({frame}*{a}+{b})*{c}))"


def effect_filter(
    effect: str, width: int, height: int, rate: str, fps: float, seconds: float
) -> str:
    """The filter graph of `effect` for a clip shown at `width`x`height`, `rate` (an ffmpeg
    fraction such as `25/1`) = `fps` frames a second, whose window lasts `seconds`."""
    check_effect(effect)
    even_w, even_h = width // 2 * 2, height // 2 * 2
    if effect == "flash":
        return f"{_EVEN_SIDES},fade=t=in:st=0:d={FLASH_SECONDS}:color=white"
    if effect == "zoom":
        # Constant rate first, so the frame-driven ramp lasts the window on a variable-rate clip.
        frames = max(round(seconds * fps), 1)
        step = (ZOOM_END - 1) / max(frames - 1, 1)
        return (
            f"fps={rate},zoompan=z='min(1+{step:.8f}*on,{ZOOM_END})'"
            f":x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=1:s={even_w}x{even_h}:fps={rate}"
        )
    if effect == "shake":
        # Scale up by the shake margin, then crop a moving window of the original size inside it.
        margin_x, margin_y = int(even_w * SHAKE_FRACTION), int(even_h * SHAKE_FRACTION)
        offset_x = f"({margin_x}*(2*{_hash01('n', _SHAKE_X)}-1))"
        offset_y = f"({margin_y}*(2*{_hash01('n', _SHAKE_Y)}-1))"
        return (
            f"scale={even_w + 2 * margin_x}:{even_h + 2 * margin_y},"
            f"crop={even_w}:{even_h}:x='{margin_x}+{offset_x}':y='{margin_y}+{offset_y}'"
        )
    if effect == "glitch":
        shift = int(width * GLITCH_FRACTION)
        return (
            f"{_EVEN_SIDES},rgbashift=rh={shift}:bh=-{shift}"
            f":enable='lt(mod(n,{GLITCH_PERIOD}),{GLITCH_BURST})'"
        )
    # slowmo: stretch the first half over the window, repeat frames up to the source rate and pad
    # the tail so that the output length, not the stretched stream, decides where it ends.
    return (
        f"{_EVEN_SIDES},trim=end={seconds / 2:.6f},setpts=2*(PTS-STARTPTS),fps={rate},"
        "tpad=stop_mode=clone:stop_duration=1"
    )
