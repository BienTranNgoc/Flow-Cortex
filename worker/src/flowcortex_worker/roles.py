"""A media worker's role: the queue it consumes and the capabilities it accepts."""

from __future__ import annotations

from collections.abc import Collection

# Mirrors MediaTopologyConfig.JOBS, RENDER_JOBS and AUDIO_JOBS in the backend.
MEDIA_QUEUES = ("media.jobs", "media.render.jobs", "media.audio.jobs")


class RoleError(Exception):
    """The worker's role is invalid; it exits before it connects to the broker."""


def media_queue(raw: str | None) -> str:
    """The job queue to consume, from MEDIA_QUEUE (unset or blank: media.jobs)."""
    queue = (raw or "").strip() or MEDIA_QUEUES[0]
    if queue not in MEDIA_QUEUES:
        raise RoleError(
            f"Unknown MEDIA_QUEUE '{queue}'; accepted values: {', '.join(MEDIA_QUEUES)}"
        )
    return queue


def accepted_capabilities(raw: str | None, known: Collection[str]) -> frozenset[str] | None:
    """The capabilities WORKER_CAPABILITIES lists; None (unset or empty) accepts every one."""
    listed = [name for entry in (raw or "").split(",") if (name := entry.strip())]
    if not listed:
        return None
    unknown = sorted(set(listed) - set(known))
    if unknown:
        raise RoleError(
            f"Unknown WORKER_CAPABILITIES {', '.join(unknown)}; "
            f"known capabilities: {', '.join(sorted(known))}"
        )
    return frozenset(listed)
