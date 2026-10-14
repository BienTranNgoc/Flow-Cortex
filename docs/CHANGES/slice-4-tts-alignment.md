# Slice 4 — TTS alignment

The durable change record for **Slice 4** ("TTS and alignment") in `Flow-Cortex`, covering the
work done after the close of the product-video script-revision slice. It stands in for an
OpenSpec change because the `openspec` CLI is not installed in this workspace; its `Requirements`
and `Test gates` sections lift into `openspec/changes/<id>/` when a CLI is available.

## 1. Why

`generate_speech` already ran and produced a WAV whose duration came from the provider, and
`render_video` already produced a fixed 15-second vertical clip — but **nothing in between
reconciled them**. The provider's `durationMs` is whatever the text took to say; the renderer
hard-codes `DURATION_SECONDS = 15`. A 9-second script therefore yields a 9-second voice over a
15-second video: the last six seconds are the renderer's own `anullsrc` silence, and a 20-second
script is **cut off mid-sentence**, because the renderer takes the first 15 seconds and drops
the rest.

Both failure modes are silent. The video publishes, an asset row lands, the run completes — and
the defect is only visible to a human who plays the output. So the slice's first job is a step
that makes the voice track match the video timeline explicitly, with the decision recorded, and
**refuses** rather than quietly truncating when it cannot.

This slice is scoped to `align_audio` only. `generate_speech` was already implemented (worker
adapter, `provider-speech-result.schema.json`, `ProviderSpeechData`), and the
`segment_needs_revision` review loop is explicitly out of scope — see §6.

## 2. Requirements

| ID | Requirement | Status | Evidence |
|---|---|---|---|
| `R-ALIGN-1` | `align_audio` shall be a **closed media capability**, refusable like every other, so an unknown capability is rejected rather than defaulted. | done | `MediaCapability.ALIGN_AUDIO`; `handlers/registry.py` |
| `R-ALIGN-2` | The capability enum shall have **one source of truth**: the JSON Schema, with the Python models regenerated from it rather than hand-edited. | done | `contracts/media-job.schema.json` → `scripts/gen_contracts.sh` → `contracts/media_job.py:38` |
| `R-ALIGN-3` | The pad/tempo decision shall live in **one pure function**, so the rules are decided in code and fixture-tested rather than implied by ffmpeg flags. | done | `align_audio.plan_alignment`; 8 pure test functions in `test_align_audio.py` |
| `R-ALIGN-4` | A speech track **shorter** than the target shall be padded with silence, never time-stretched — stretching a voice audibly damages it. | done | `plan_alignment` returns `tempo == 1.0` for a short track; `test_speech_shorter_than_target_is_padded_not_stretched` |
| `R-ALIGN-5` | A track longer than the target shall be **sped up within a bounded ratio, never truncated**: cutting the tail drops a spoken segment. | done | `plan_alignment` returns `tempo == speech/target`; `test_speech_longer_than_target_is_sped_up_never_truncated` |
| `R-ALIGN-6` | A ratio needing padding beyond `MAX_PAD_RATIO` or a speed-up beyond `MAX_SPEEDUP` (1.5x) shall fail the step as a **permanent error with its own code**, not publish a misaligned video. | done | `audio_alignment_out_of_range` in `errors.py` + `media-result.schema.json`; range tests for pad and speed-up |
| `R-ALIGN-7` | The speed-up shall be capped at **1.5x in total** — faster speech is unintelligible — which keeps it inside a single `atempo` instance (0.5–2.0), so no filter chaining is needed. | done | `align_audio.MAX_SPEEDUP`; `test_a_speed_up_up_to_the_limit_is_planned_as_is`, `test_a_speed_up_beyond_the_limit_is_a_permanent_error` |
| `R-ALIGN-8` | The step shall emit an **alignment manifest** recording source duration, target, tempo and pad, so "why does this video look like this" has an answer after the fact. | done | `alignment-manifest.json`; `test_padded_speech_meets_the_target_duration` asserts manifest equals `data` |
| `R-ALIGN-9` | The step shall refuse a **missing target duration**, a wrong profile, or input keys other than the single `speech` input, at the contract boundary. | done | 3 contract-violation tests |
| `R-ALIGN-10` | A new error code shall be declared at its **source** (`ErrorCode` **and** `media-result.schema.json`) in the same change, so a worker result never fails schema validation for an undeclared code. | done | both files; `pytest tests/test_contracts.py` 17/17 |

## 3. Design choices

### 3.1 `plan_alignment` is pure, and the worker calls it

The pad/tempo rules are the part worth getting right, so they live in a `def` that takes two
floats and returns `(tempo, pad)` — no ffmpeg, no filesystem. `handle` then turns that pair into
a filter graph. This is what lets the tolerance rules be fixture-tested: the decision table is
asserted without spawning a process, and only the handler tests touch real ffmpeg.

The alternative — reading the decision back out of a rendered file — would have made the
interesting rules untestable and slow, which is how a wrong cap survives review.

### 3.2 The pad cap is relative, not absolute

An early version capped padding at `MAX_PAD_SECONDS = 0.5`. That was wrong in a way only a real
test caught: a 4-second track against a 6-second target needs 2 seconds of pad, so the cap
rejected an entirely ordinary short ad and the step failed. A fixed absolute number is also
meaningless across clip lengths — it is fine for a 15-second spot and absurd for a 2-second one.

The cap is now `MAX_PAD_RATIO = 1.25` against the **speech** duration: silence may not exceed
1.25× the voice it follows. Beyond that the track is too sparse to align and the run should go
back to review instead of publishing six seconds of nothing. The 1.25 is the plan's overlap
threshold, so padding and overlap are governed by one number rather than two.

### 3.3 No `atrim` in the filter graph

The first graph was `apad=pad_dur=N,atrim=0:<stretched+pad>`, intending to pin the exact target
length. Measured, `atrim` cut the result to `N` seconds — it discarded the speech the pad had
been appended to, producing a 2-second file where 6 was wanted. `apad` already leaves the graph at
the target length, so the trim was not merely redundant but actively wrong, and it failed
**silently**: ffmpeg exited 0 and wrote a valid, playable, wrong-length WAV.

This is the one defect in the slice that no unit test could have caught. It surfaced only because
the handler tests measure the output with `ffprobe` rather than trusting the exit code.

### 3.4 Error codes live in two places, and both were edited

`ErrorCode` (Python, `Literal`) and `MediaErrorCode` (JSON Schema, `enum`) are two declarations of
one closed set. `mypy` caught the Python side immediately; the Schema side has no such check, so
adding `audio_alignment_failed` to `errors.py` alone would have produced a worker that emits a
result its own contract rejects. Both were changed together, and `gen_contracts.sh` regenerated
the Python models from the Schema so the generated `media_result.py` stays in step.

### 3.5 `ruff format` is not this project's formatter

Running `ruff format --check .` reports 11 committed files as unformatted — `errors.py`,
`storage.py`, `test_roundtrip.py` and others — none of them touched by this slice. The CI gate
(`.github/workflows/worker-ci.yml:38-45`) is `ruff check` + `mypy` + `pytest`; there is no format
step. The style in the tree is therefore the house style, and this slice matches it. Reformatting
would have buried a two-file change in a repository-wide diff, so it was reverted.

## 4. Files touched

```
M  contracts/media-job.schema.json                                 (enum + align_audio)
M  contracts/media-result.schema.json                              (MediaErrorCode + 2 codes)
M  worker/src/flowcortex_worker/contracts/media_job.py             (regenerated)
M  worker/src/flowcortex_worker/contracts/media_result.py          (regenerated)
M  worker/src/flowcortex_worker/errors.py                          (+ 2 ErrorCode literals)
M  worker/src/flowcortex_worker/handlers/registry.py               (+ align_audio)
A  worker/src/flowcortex_worker/handlers/align_audio.py
A  worker/tests/test_align_audio.py
M  backend/modules/media/src/main/java/com/flowcortex/media/api/MediaCapability.java
A  docs/CHANGES/slice-4-tts-alignment.md
```

## 5. Test gates

All run 2026-10-12, Windows + WSL against the committed toolchain.

| Gate | Command | Outcome |
|---|---|---|
| Worker lint | `uv run --frozen --no-build ruff check .` | **pass** — all checks passed |
| Worker types | `uv run --frozen --no-build mypy` | **pass** — 45 source files, 0 issues |
| Worker suite | `uv run --frozen --no-build pytest tests/ --ignore=tests/test_roundtrip.py` | **158 passed**, 0 failed |
| Alignment tests | `uv run --frozen --no-build pytest tests/test_align_audio.py` | **19 passed** |
| Contract tests | `uv run --frozen --no-build pytest tests/test_contracts.py` | **17 passed** |
| Contract drift | `bash scripts/gen_contracts.sh` then `git status` | clean — only the 2 intended generated files changed |
| Backend compile | `.\gradlew.bat :modules:media:compileJava :modules:workflow:compileJava` | **pass** (exit 0) |

`test_roundtrip.py` was excluded from the suite run because it needs a live RabbitMQ and object
store container; it is unaffected by this slice and CI still runs it in full.

The 19 alignment tests split into 8 pure test functions over `plan_alignment` — 14 cases once
`@parametrize` is counted — and 5 functions over the handler against real ffmpeg. The pure side
covers short track, long track, equal durations, pad within cap, pad beyond cap, tempo at
1.0/1.25/1.5, a speed-up beyond 1.5x, and non-positive durations. The handler side
measures the rendered file: padded output is 6.0s, sped-up output is 8.0s, the manifest equals
`data`, and the four contract-violation refusals hold.

## 6. What did not change (intentional)

- **`align_audio` is not in any seeded workflow.** The capability is complete and registered;
  no seeded step uses it yet. The blocker this slice originally recorded is gone: a step whose
  config carries `outputInputs` now gets `InputPolicy.STORED_WITH_OUTPUTS`, so
  `RemoteStepExecutor` passes it configured outputs of earlier completed steps (validated by
  `StoredWithOutputsResolver`), which is how `render_video` already receives the `generate_speech`
  WAV. Wiring `align_audio` the same way — a step config binding the speech output to its
  `speech` input — is a seeding decision that has not been taken.
- **`segment_needs_revision` is not implemented.** The plan lists it as the flow sending a run
  back to review. It needs `align_audio` seeded in a workflow first —
  until a run produces an aligned track there is nothing to inspect — so it is deferred with it.
- **No per-segment speech manifest.** The plan's "speech manifest with per-segment assets" is a
  provider-side change to `generate_speech`, which already ships and already satisfies
  `R-REV-8` (the approved revision's content is what the speech job carries). Changing it would
  alter a contract Slice 3's gates were written against.
- **No `generate_speech` work.** Already implemented and already gated by
  `ScriptRevisionApprovalIT`.
- **No migration, no frontend, no OpenAPI change.** This slice adds no table, column, endpoint or
  response shape; `MediaCapability` is an enum the API never serialises into a response.

## 7. Open follow-ups

- **Seed `align_audio` in a workflow.** The step-to-step output binding exists
  (`outputInputs` + `STORED_WITH_OUTPUTS`); a seeded step config that maps the `generate_speech`
  output to the `speech` input is what remains before `segment_needs_revision` has anything to
  inspect.
- **`render_video` still hard-codes 15 seconds.** `align_audio` produces a track matching the
  target, but the renderer's `DURATION_SECONDS = 15` and `anullsrc` remain. Until the renderer
  takes its duration from the video asset, a run shorter than 15 seconds still ends in silence.
  Aligning the track does not by itself fix the renderer's fixed-length assumption.
- **The overlap threshold is currently unused by code.** `MAX_PAD_RATIO = 1.25` governs padding;
  true segment-level overlap detection does not exist yet, and that needs the per-segment
  manifest above.
- **Backend-side validation of the manifest.** `align_audio`'s `data` is currently untyped on the
  Java side, unlike `ProviderSpeechData`. A `MediaAlignmentData` record at the trust boundary
  would follow that precedent and bounds `tempo`/`pad` where a lying worker could otherwise
  reach the renderer.
- **When an OpenSpec CLI is available:** lift §2 into `proposal.md` + spec deltas.