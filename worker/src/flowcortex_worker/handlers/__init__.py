"""Capability handlers and the shape every handler shares."""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol

from ..contracts.media_job import MediaJob
from ..runtime import ProcessRunner


@dataclass(frozen=True)
class OutputFile:
    name: str
    mime: str
    body: bytes


@dataclass(frozen=True)
class HandlerOutcome:
    data: dict[str, Any]
    files: list[OutputFile]


class Handler(Protocol):
    # Positional-only: a handler that does not read the job names it `_job`.
    def __call__(
        self, job: MediaJob, inputs: Mapping[str, Path], processes: ProcessRunner, /
    ) -> HandlerOutcome: ...
