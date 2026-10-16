# Slice 2 — Provider OpenAI / GitHub Models + runtime-validate result

This document is the durable change record for the portion of **Slice 2** ("Product input and script
generation") that landed in the `Flow-Cortex` repo between baseline and the close of the slice. It
stands in for an OpenSpec change because the `openspec` CLI is not installed in this workspace
(`0_context §2`); when a CLI is available, this file's `Requirements & scenarios` and `Tasks`
sections can be lifted into `openspec/changes/<id>/proposal.md`, `tasks.md`, and the spec deltas.

## 1. Why

Slice 1 closed with backend orchestration working against the **fixture** provider. Slice 2's
last-mile gap is the real provider, plus the missing rule that the consumer must **trust but
verify** the response coming back from the broker (the fixture does not exercise the failure
modes the real OpenAI API can produce — empty content, JSON-mode rejection, size drift, etc.).

Two product invariants in `0_context §6` depend on the change:

- Provider script generation must use only the confirmed product inputs (`name`, `description`,
  `CTA`, optional `price`, optional `promotion`); no fabricated claims.
- OpenAI secrets must only reside in env/secret manager, never in DB, message, log or frontend
  bundle.

`AGENTS.md §3` plus the project rule "parse every value crossing a trust boundary" also asks for
runtime validation at the producer/consumer boundary — the worker already validates on the way in
(see `MediaContractSchemaTest`); the broker's "outbound" boundary was unchecked.

## 2. Requirements

| ID | Requirement | Status | Evidence |
|---|---|---|---|
| `R-PROVIDER-1` | The provider worker shall call OpenAI's Chat Completions API for `generate_script` with a JSON-Schema `response_format` constraining the model to return a single `script` field, in Vietnamese. | done | `worker/src/flowcortex_worker/provider/openai_adapter.py::_SCRIPT_RESPONSE_FORMAT`, `tests/test_openai_adapter.py::test_generate_script_uses_response_format_json_schema` |
| `R-PROVIDER-2` | The worker shall support the same OpenAI-compatible endpoint over a configurable `base_url`, so Azure OpenAI and an internal proxy are reachable without code change. | done | `openai_adapter.openai_client`, `_base_url` parameter |
| `R-PROVIDER-3` | Adapter selection shall be runtime-driven: `PROVIDER_ADAPTER ∈ {openai, fixture}`; missing secrets fall back to the fixture so a fresh checkout still boots. | done | `provider/__main__.py::_build_adapter`, `tests/test_openai_adapter.py::test_build_adapter_*` |
| `R-PROVIDER-4` | API key, model name, prompt content, and the returned script shall not appear in any log. The adapter only logs the *error class* of a failed call. | done | `openai_adapter.py` lines emitting `log.warning(..., errorType=...)`; the existing `telemetry.Redacting` redaction wrapper in the worker covers accidental inclusion. |
| `R-PROVIDER-5` | The script body the model produces shall be checked at three layers: (a) `response_format.json_schema` (b) the worker's `generate_script` (c) the backend `RunAdvancer.applyResult` parsing the broker response against the typed records. | done | adapter enforces `len(script) <= 8000`; backend parses with `objectMapper.convertValue(data, ProviderScriptData.class)`; `RunAdvancerTest::aProviderResultThatBreaksTheJsonSchemaFails...` |
| `R-VALIDATE-1` | A provider step's `MediaResult.data` that does not match the typed schema (`provider-script-result.schema.json` or `provider-speech-result.schema.json`) shall fail the run step with `error_code=contract_violation` rather than land in `run_step.output` as compliant data. | done | `RunAdvancer.applyResult` (added 2026-10-11), tests `aProviderResultThatBreaksTheJsonSchemaFailsTheStepAtTheTrustBoundaryWithContractViolation` + `aProviderResultThatConformsToTheJsonSchemaIsAcceptedWithoutRejection` |
| `R-CAPS-1` | The SPA shall hide the Product Video entry from the Workflows list when `flowcortex.product-video.enabled=false`. The backend remains the source of truth — the per-endpoint 503 is unchanged. | done | `GET /api/v1/capabilities` + `WorkflowsListPage` filter, `WorkflowsListPage.test.tsx` |

## 3. Design choices

### 3.1 OpenAI over a hand-rolled HTTP client

The official `openai>=1.50,<2` Python SDK is the chosen client. Reasoning:

- Single dependency covers both the OpenAI-hosted API and any OpenAI-compatible endpoint
  (Azure, internal proxy) via the SDK's `base_url` parameter.
- The SDK ships `openai.types.chat.ChatCompletion`, `client.chat.completions.create`, retries
  with `max_retries`, and the structured-output `response_format=json_schema` syntax
  (the model is forced to return JSON matching our schema — no second parse pass).
- Per `0_context §9`, stdlib or already-installed deps are preferred. The SDK brings
  `httpx` transitively; we have not introduced a new direct dependency in `pyproject.toml`
  beyond the SDK itself.

### 3.1.a GitHub Models removed (2026-10-11)

GitHub Models sunset its public Chat Completions access; the `github_models_client` helper and
the `PROVIDER_ADAPTER=github` branch of `select_adapter` were removed together with `GITHUB_TOKEN`
in `.env.example` and `docker-compose.yml`. The `openai_client` helper still accepts `base_url`
for Azure / internal proxies.

The worker process keeps the **same image** for both the media handler and the provider handler
— only the entrypoint differs. The provider import graph is a strict subset of the worker's.

### 3.2 Fail-closed at the backend boundary

`RunAdvancer.applyResult` runs the response through `objectMapper.convertValue(data, ProviderScriptData.class)`
*before* the CAS update that marks the step `COMPLETED`. If the conversion throws (Jackson
produces an `IllegalArgumentException` with the underlying `MismatchedInputException`), the step
is failed with `error_code=contract_violation` and the message bubbles up. The
`media.late_result_discarded` meter is **not** incremented in that path (a real failure is
not a late message). This mirrors the pre-existing pattern for media steps that
violate `MediaJob`/`MediaResult`/`MediaProgress` schemas — provider steps are simply the new
arrival under the same rule.

### 3.3 Capabilities as a thin endpoint

A new `GET /api/v1/capabilities` (composition-root controller, not a module one) returns the
two flags the SPA actually needs. We do **not** join the user's role/scope in there: this is
not a permissions surface, it is a feature-flag mirror. The Modulith module boundary rule
(`backend/boot/app/.../capabilities/CapabilitiesController.java` lives outside `:modules:*`)
keeps the read of `MediaProperties` and `flowcortex.product-video.enabled` in a place that
can legally import both.

## 4. Files touched (against the slice-2 baseline)

```
A  backend/boot/app/src/main/java/com/flowcortex/app/capabilities/CapabilitiesController.java
A  backend/boot/app/src/test/java/com/flowcortex/app/capabilities/CapabilitiesControllerTest.java
A  backend/boot/app/src/test/java/com/flowcortex/app/capabilities/ProductVideoEnabledTest.java
A  backend/modules/media/src/main/java/com/flowcortex/media/api/ProviderScriptData.java
A  backend/modules/media/src/main/java/com/flowcortex/media/api/ProviderSpeechData.java
M  backend/modules/workflow/src/main/java/com/flowcortex/workflow/application/RunAdvancer.java
M  backend/modules/workflow/src/test/java/com/flowcortex/workflow/application/RunAdvancerTest.java
A  docs/openapi.yaml
A  docs/CHANGES/slice-2-provider-openai.md
A  frontend/src/features/workflow/useCapabilities.ts
M  frontend/src/features/workflow/WorkflowsListPage.tsx
A  frontend/src/features/workflow/WorkflowsListPage.test.tsx
M  packages/api-client/src/generated/flowCortexApi.ts   (orval output)
M  worker/src/flowcortex_worker/provider/__main__.py
A  worker/src/flowcortex_worker/provider/openai_adapter.py
M  worker/pyproject.toml                                (openai>=1.50,<2)
A  worker/tests/test_openai_adapter.py
M  private_docs/ad-video/0_context_video_quang_cao_san_pham.md
```

## 5. Test gates

| Gate | Command | Outcome (recorded 2026-10-11) |
|---|---|---|
| Workflow backend | `./gradlew :modules:workflow:test` | **149/149** (24 → 26 RunAdvancerTest; +2 provider-validation cases) |
| Media backend | `./gradlew :modules:media:test` | **83/83** |
| Capabilities controller | `./gradlew :boot:app:test --tests 'com.flowcortex.app.capabilities.*'` | **2/2** (default + flag-on) |
| Worker provider + openai | `uv run pytest tests/test_provider_capabilities.py tests/test_provider_consumer.py tests/test_openai_adapter.py` | **18/18** |
| Worker lint | `uv run ruff check src/flowcortex_worker/provider tests/test_openai_adapter.py` | **All checks passed** |
| Worker type | `uv run mypy src/flowcortex_worker/provider tests/test_openai_adapter.py` | **Success: no issues found in 3 source files** |
| Frontend tests | `npm test` | **100/100** (10 files) |
| Frontend type | `npm run typecheck` | clean |
| Frontend lint | `npm run lint` | clean |
| API client regen | `npm run gen:api` | clean; `getCapabilities` + `CapabilitiesResponse` produced |

The Boot OpenAPI snapshot and integration tests (e.g. `WorkflowRunEngineIT`, `MediaKillSwitchIT`)
are not exercised in this session — they require a live Postgres + RabbitMQ + Silo stack the
sandbox cannot reach (`0_context §13`). They are still in CI and must remain green on a
real run.

## 6. What did not change (intentional)

- **No new Spring Modulith module boundary.** The capabilities controller is in
  `backend/boot/app`, not in `:modules:workflow` or `:modules:media`, so we do not introduce a
  new cross-module dependency. Workflow continues to own the product-video flag value; media
  continues to own the `MediaProperties.enabled` value; the composition root reads both.
- **No backend change to the per-endpoint 503.** `ProductVideosController` still returns
  503 when the flag is off; the SPA simply does not show the entry.
- **No TTS implementation.** `OpenAIProviderAdapter.generate_speech` returns a placeholder
  `SpeechArtifact` to keep the worker aligned with the `ProviderAdapter` protocol; the real
  `audio.speech.create` call is the entry point of **Slice 4**.
- **No provider-side test that hits the network.** All OpenAI adapter tests mock the SDK
  client (a `MagicMock` is enough — the schema, the JSON parsing, the boundary, and the
  selection logic are what we own).

## 7. Open follow-ups (Slice 3+)

- Slice 3 will rework the approval checkpoint to operate on a `script_revision` document with
  optimistic concurrency; the typed `ProviderScriptData` already lives in
  `com.flowcortex.media.api` so the revision table can reference it without further moves.
- Slice 4 will replace the `OpenAIProviderAdapter.generate_speech` placeholder with a call to
  `client.audio.speech.create(model='gpt-4o-mini-tts', voice='alloy', input=script)`, returning
  the same `SpeechArtifact` shape — the seam is already in place.
- When an OpenSpec CLI is available: convert the `Requirements` table above into
  `proposal.md`, the `Test gates` row into `tasks.md`, and add the `ProviderScriptData` /
  `ProviderSpeechData` records to `specs/media/...` and `specs/workflow/...` so the contract
  drift-checker picks them up.
