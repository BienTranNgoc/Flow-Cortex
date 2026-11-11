"""The capabilities this worker runs. An unknown capability is refused, never defaulted."""

from __future__ import annotations

from ..errors import PermanentError
from . import (
    Handler,
    HandlerOutcome,
    OutputFile,
    align_audio,
    contact_sheet,
    detect_shots,
    extract_audio,
    inspect,
    probe,
    render_video,
    scene_frames,
)

__all__ = [
    "HANDLERS",
    "Handler",
    "HandlerOutcome",
    "OutputFile",
    "resolve"
    ]

HANDLERS: dict[str, Handler] = {
    "probe": probe.handle,
    "extract_audio": extract_audio.handle,
    "align_audio": align_audio.handle,
    "render_video": render_video.handle,
    "inspect": inspect.handle,
    "contact_sheet": contact_sheet.handle,
    "detect_shots": detect_shots.handle,
    "scene_frames": scene_frames.handle,
    }


def resolve(capability: str) -> Handler:
    try:
        return HANDLERS[capability]
    except KeyError:
        raise PermanentError(
            "contract_violation", f"capability {capability!r} is not registered"
        ) from None
