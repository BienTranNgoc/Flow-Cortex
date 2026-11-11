"""The capabilities this worker runs. An unknown capability is refused, never defaulted."""

from __future__ import annotations

import importlib
from collections.abc import Mapping

from ..errors import PermanentError
from ..roles import RoleError
from . import Handler, HandlerOutcome, OutputFile

__all__ = [
    "HANDLER_MODULES",
    "Handler",
    "HandlerOutcome",
    "OutputFile",
    "Registry",
    "load_handlers",
    "resolve",
]

HANDLER_MODULES: dict[str, str] = {
    "probe": "flowcortex_worker.handlers.probe",
    "extract_audio": "flowcortex_worker.handlers.extract_audio",
    "align_audio": "flowcortex_worker.handlers.align_audio",
    "render_video": "flowcortex_worker.handlers.render_video",
    "caption_burn": "flowcortex_worker.handlers.caption_burn",
    "fetch_video": "flowcortex_worker.handlers.fetch_video",
}


def load_handlers(
    accepted: frozenset[str] | None, modules: Mapping[str, str] = HANDLER_MODULES
) -> dict[str, Handler]:
    """Imports the handler module of every accepted capability (all when `accepted` is None)."""
    handlers: dict[str, Handler] = {}
    for capability, path in modules.items():
        if accepted is not None and capability not in accepted:
            continue
        try:
            handlers[capability] = importlib.import_module(path).handle
        except ImportError as failed:
            raise RoleError(
                f"Handler for capability '{capability}' failed to import: {failed}"
            ) from failed
    return handlers


class Registry:
    def __init__(self, handlers: Mapping[str, Handler], accepted: frozenset[str] | None) -> None:
        self._handlers = handlers
        self._accepted = accepted

    def resolve(self, capability: str) -> Handler:
        if capability in self._handlers:
            return self._handlers[capability]
        if self._accepted is not None and capability in HANDLER_MODULES:
            raise PermanentError(
                "contract_violation",
                f"capability {capability!r} is not accepted by this worker; "
                f"accepted: {', '.join(sorted(self._accepted))}",
            )
        raise PermanentError("contract_violation", f"capability {capability!r} is not registered")


_default: Registry | None = None


def resolve(capability: str) -> Handler:
    """Resolves against a registry holding every handler, loaded on first use."""
    global _default
    if _default is None:
        _default = Registry(load_handlers(None), None)
    return _default.resolve(capability)
