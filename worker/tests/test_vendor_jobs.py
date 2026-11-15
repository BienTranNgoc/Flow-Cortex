from __future__ import annotations

from typing import Any, Literal
from uuid import UUID, uuid4

import pytest

from flowcortex_worker.errors import TransientError
from flowcortex_worker.provider.__main__ import build_probe_registry
from flowcortex_worker.provider.adapter import FixtureProviderAdapter, VideoIdentity, VideoRequest
from flowcortex_worker.provider.contracts.provider_job import VendorCandidate
from flowcortex_worker.provider.vendor_jobs import (
    ProbeRegistry,
    VendorJobTimeout,
    canonical_json,
    fingerprint_document,
    select_attach,
    vendor_fingerprint,
    vendor_scope,
)

REVISION_A = UUID("a71ea3ba-d72a-4c2c-985c-3a7fd98853c4")
REVISION_B = UUID("11111111-1111-1111-1111-111111111111")
DASHSCOPE = "https://dashscope-intl.aliyuncs.com/api/v1"
BYTEPLUS = "https://ark.ap-southeast.bytepluses.com/api/v3"
IDENTITY = VideoIdentity("seedance", "seedance-1.5-pro", 5, "9:16", "480p", False)


def scope(base_url: str = DASHSCOPE, key: str = "sk-one") -> dict[str, str]:
    return vendor_scope(base_url, key)


def fingerprint(
    request: VideoRequest | None = None,
    identity: VideoIdentity = IDENTITY,
    vendor: dict[str, str] | None = None,
) -> str:
    return vendor_fingerprint(
        identity, vendor or scope(), request or VideoRequest("a cap", REVISION_A)
    )


def test_same_ask_in_two_runs_has_one_fingerprint() -> None:
    first = VideoRequest("a cap", REVISION_A, reference_image_bytes=b"image-bytes")
    second = VideoRequest(
        "a cap",
        REVISION_B,
        reference_image_url="https://other.example/signed",
        reference_image_bytes=b"image-bytes",
    )

    assert fingerprint(first) == fingerprint(second)
    assert len(fingerprint(first)) == 64


def test_fingerprint_separates_vendor_platforms_and_rotated_keys() -> None:
    base = fingerprint()

    assert fingerprint(vendor=scope(BYTEPLUS)) != base
    assert fingerprint(vendor=scope(key="sk-two")) != base
    document = canonical_json(
        fingerprint_document(IDENTITY, scope(key="sk-secret"), VideoRequest("a", REVISION_A))
    )
    assert "sk-secret" not in document


def test_fingerprint_separates_model_prompt_and_image() -> None:
    base = fingerprint()
    lite = VideoIdentity("seedance", "seedance-1.0-lite", 5, "9:16", "480p", False)

    assert fingerprint(identity=lite) != base
    assert fingerprint(VideoRequest("another prompt", REVISION_A)) != base
    with_image = VideoRequest("a cap", REVISION_A, reference_image_bytes=b"one")
    other_image = VideoRequest("a cap", REVISION_A, reference_image_bytes=b"two")
    assert fingerprint(with_image) != base
    assert fingerprint(with_image) != fingerprint(other_image)


def test_canonical_json_ignores_key_order() -> None:
    assert canonical_json({"b": 1, "a": {"y": 2, "x": 3}}) == canonical_json(
        {"a": {"x": 3, "y": 2}, "b": 1}
    )


def candidate(
    *,
    fp: str,
    vendor: str = "seedance",
    job: str = "cgt-1",
    handle: Any = None,
) -> VendorCandidate:
    return VendorCandidate.model_construct(
        vendorTaskId=uuid4(),
        vendor=vendor,
        model="seedance-1.5-pro",
        fingerprint=fp,
        vendorJobId=job,
        handle={} if handle is None else handle,
    )


class Probes:
    def __init__(self, answers: dict[str, Any]) -> None:
        self.answers = answers
        self.calls: list[str] = []

    def registry(self, vendor: str = "seedance") -> ProbeRegistry:
        def probe(job: str, handle: dict[str, Any]) -> Any:
            self.calls.append(job)
            answer = self.answers[job]
            if isinstance(answer, Exception):
                raise answer
            return answer

        return {vendor: probe}


Verdicts = list[tuple[str, Literal["failed", "expired", "gone"]]]


def attach(candidates: list[VendorCandidate], probes: ProbeRegistry) -> tuple[Any, Verdicts]:
    verdicts: Verdicts = []
    picked = select_attach(
        candidates,
        FP,
        probes,
        lambda c, status: verdicts.append((c.vendorJobId, status)),
    )
    return picked, verdicts


FP = "a" * 64
OTHER = "b" * 64


def test_a_running_candidate_is_attached() -> None:
    probes = Probes({"cgt-1": "running"})
    picked, verdicts = attach([candidate(fp=FP)], probes.registry())

    assert picked is not None and picked.vendorJobId == "cgt-1"
    assert verdicts == []


def test_a_succeeded_candidate_is_reused() -> None:
    picked, _ = attach([candidate(fp=FP)], Probes({"cgt-1": "succeeded"}).registry())

    assert picked is not None


def test_a_mismatched_fingerprint_is_skipped_without_probing() -> None:
    probes = Probes({"cgt-1": "running"})
    picked, verdicts = attach([candidate(fp=OTHER)], probes.registry())

    assert picked is None
    assert probes.calls == []
    assert verdicts == []


@pytest.mark.parametrize("dead", ["failed", "expired", "gone"])
def test_a_dead_candidate_is_reported_and_the_next_is_tried(dead: str) -> None:
    probes = Probes({"cgt-1": dead, "cgt-2": "running"})
    picked, verdicts = attach(
        [candidate(fp=FP, job="cgt-1"), candidate(fp=FP, job="cgt-2")], probes.registry()
    )

    assert picked is not None and picked.vendorJobId == "cgt-2"
    assert verdicts == [("cgt-1", dead)]


@pytest.mark.parametrize("failure", [TransientError("HTTP 503"), TimeoutError("slow")])
def test_a_probe_that_cannot_answer_attaches(failure: Exception) -> None:
    picked, verdicts = attach([candidate(fp=FP)], Probes({"cgt-1": failure}).registry())

    assert picked is not None
    assert verdicts == []


def test_a_handle_that_is_not_an_object_is_reported_gone() -> None:
    probes = Probes({"cgt-1": "running"})
    picked, verdicts = attach([candidate(fp=FP, handle="not-an-object")], probes.registry())

    assert picked is None
    assert verdicts == [("cgt-1", "gone")]
    assert probes.calls == []


def test_a_vendor_without_a_probe_is_skipped() -> None:
    picked, verdicts = attach(
        [candidate(fp=FP, vendor="fal")], Probes({"cgt-1": "running"}).registry("seedance")
    )

    assert picked is None
    assert verdicts == []


def test_no_candidates_means_submit() -> None:
    picked, _ = attach([], Probes({}).registry())

    assert picked is None


def test_fal_probe_is_registered_only_when_enabled_and_keyed() -> None:
    adapter = FixtureProviderAdapter()

    assert "fal" not in build_probe_registry(adapter, {})
    assert "fal" not in build_probe_registry(adapter, {"FAL_KEY": "k"})
    assert "fal" not in build_probe_registry(adapter, {"FAL_QUEUE_PROBE_ENABLED": "true"})
    assert "fal" not in build_probe_registry(
        adapter, {"FAL_QUEUE_PROBE_ENABLED": "false", "FAL_KEY": "k"}
    )
    enabled = build_probe_registry(adapter, {"FAL_QUEUE_PROBE_ENABLED": "true", "FAL_KEY": "k"})
    assert "fal" in enabled
    assert "fixture" in enabled


def test_a_timeout_names_the_vendor_and_the_ledger_row() -> None:
    error = VendorJobTimeout("seedance", "cgt-1", ledger_id="row-1")

    assert "seedance" in str(error) and "row-1" in str(error)
    assert error.exhausted_code == "provider_timeout"
