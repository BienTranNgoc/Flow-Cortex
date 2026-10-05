"""The capabilities this worker runs. An unknown capability is refused, never defaulted."""

from __future__ import annotations

from ..errors import PermanentError
from . import Handler, HandlerOutcome, OutputFile, extract_audio, probe

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
    }


def resolve(capability: str) -> Handler:
    try:
        return HANDLERS[capability]
    except KeyError:
        raise PermanentError(
            "contract_violation", f"capability {capability!r} is not registered"
        ) from None
