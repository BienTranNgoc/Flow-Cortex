"""How a failed job is answered: permanently with an error result, or by a broker retry."""

from typing import Literal

ErrorCode = Literal[
    "input_rejected",
    "probe_failed",
    "audio_extraction_failed",
    "audio_alignment_failed",
    "audio_alignment_out_of_range",
    "provider_capability_unavailable",
    "provider_request_failed",
    "provider_response_invalid",
    "contract_violation",
    "run_timeout"
    ]


class PermanentError(Exception):
    """A failure no redelivery can fix; answered with an error result and acked."""

    def __init__(self, code: ErrorCode, message: str) -> None:
        super().__init__(f"{code}: {message}")
        self.code: ErrorCode = code
        self.message = message


class TransientError(Exception):
    """Store or broker I/O that a later delivery may get past."""

    def __init__(self, message: str, *, retry_after_seconds: float | None = None) -> None:
        super().__init__(message)
        self.retry_after_seconds = retry_after_seconds


MAX_BACKOFF_SECONDS = 60.0


def backoff_seconds(delivery_count: int) -> float:
    """Wait before returning a transient failure: 5 s doubling per earlier delivery, capped."""
    return float(min(5 * 2 ** min(delivery_count, 4), MAX_BACKOFF_SECONDS))
