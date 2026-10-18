"""How a failed job is answered: permanently with an error result, or by a broker retry."""

from typing import Literal

ErrorCode = Literal[
    "input_rejected",
    "probe_failed",
    "audio_extraction_failed",
    "contract_violation",
    "run_timeout",
    "duration_exceeded",
    "no_audio_stream",
    "no_speech_detected",
    "separation_failed",
    "compose_failed",
    "voice_alignment_failed",
    ]


class PermanentError(Exception):
    """A failure no redelivery can fix; answered with an error result and acked."""

    def __init__(self, code: ErrorCode, message: str) -> None:
        super().__init__(f"{code}: {message}")
        self.code: ErrorCode = code
        self.message = message


class TransientError(Exception):
    """Store or broker I/O that a later delivery may get past."""


def backoff_seconds(delivery_count: int) -> float:
    """Wait before returning a transient failure: 5 s doubling per earlier delivery, capped."""
    return float(min(5 * 2 ** min(delivery_count, 4), 60))
