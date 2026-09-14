# Observability Checklist

Instrument-as-you-build reference for the osf kit. Distilled from the
`observability-and-instrumentation` skill in addyosmani/agent-skills (MIT).
<!-- CUSTOM:observability-lens 2026-07-21 — refreshed to 2026 best-practice from a /research pass (21 sources, OTel spec/W3C/CNCF primaries). Deltas tagged inline. Re-apply from skills-backup/observability-lens-*.tgz if kit reinstalled. -->

> **When osf kit pulls this in:**
> - **`/proposal`** — when a spec ships a *production surface* (service, HTTP
>   endpoint, background job, external integration, or LLM call), observability is
>   a numbered requirement (`R-xxx`), not an afterthought. See §11 (build-lens baseline).
> - **`/apply` + `osf-apply`** — the OBSERVABILITY LENS gate checks the baseline
>   below before a production-surface task is marked `[x]`.
> - `/feat` *Stress-test* and `/perf` cite it when the work ships a production surface.
> - `/review` flags a PR that adds retries/queues/cross-service/LLM calls with zero new telemetry.
>
> NOT for diagnosing a live failure (use `/fix` + gitnexus-debugging) — observability
> is what makes that fast *next* time.

## 1. Define "working" before instrumenting

Telemetry without a question is noise. Write 2–4 questions an on-call engineer will
ask about this feature, then make every signal answer one:

```
FEATURE: checkout payment retry
ON-CALL WILL ASK:
1. What fraction of payments succeed first attempt vs after retry?
2. When a payment fails permanently, why? (provider error? timeout? validation?)
3. Is the payment provider slower than usual?
```

If you can't name the questions, you'll log everything and learn nothing.

## 2. Pick the right signal

| Signal | Answers | Example |
|---|---|---|
| **Structured log / wide event** | "What happened in this specific case?" | `payment_failed` + provider error code |
| **Metric** | "How often / how fast, in aggregate?" | p99 latency of provider calls |
| **Trace** | "Where did time go across services?" | one slow checkout, broken down by hop |

Rule of thumb: metrics tell you **that** something is wrong, traces tell you **where**,
logs tell you **why**. In 2026 all three are consumption views of the same underlying
event data — correlate them by shared `trace_id` (§3, §5), don't treat them as three silos.

## 3. Structured logging

- [ ] Log events, not prose — JSON object with a stable `event` name + machine-readable fields
- [ ] Log levels used consistently: `error` (invariant broken) / `warn` (degraded but handled) / `info` (business event) / `debug` (off in prod)
- [ ] **`trace_id` + `span_id` on every log line = the PRIMARY correlation key** (W3C-standard, 32/16-char lowercase hex; inject via an OTel-aware log appender, log inside the active span's context). A business/request correlation ID stays as a **secondary** field. *(EXTENDS the old "correlation ID mandatory" rule — trace_id/span_id is cross-vendor and lets logs join traces automatically.)*
- [ ] **Prefer one WIDE structured event per request/job** (full context: route, tenant, user-segment, cache hit/miss, downstream latencies, business fields) over many scattered `logger.info()` lines. *(EXTENDS structured logging — this is the uncontested core of the "wide events / canonical log line" pattern; adopt it regardless of backend. The full "Observability 2.0" backend migration is contested/platform-scale — do NOT treat it as mandated.)*
- [ ] **Never log secrets, tokens, passwords, or PII** — and in 2026 "PII" explicitly includes **IP addresses, device IDs, and session identifiers** (CCPA/CPRA-2026 + expanded technical-identifier scope), not just names/emails. See §7.
- [ ] **Every caught error is logged or re-thrown — never swallowed.** An empty `catch {}` (or a catch that silently returns a default) hides query / IO / external-call failures. Log at `error`/`warn` with the exception + context. *(This binds ALL code, not just production surfaces — the single observability rule with no scope gate.)*

```typescript
// BAD: logger.info(`Payment ${id} failed for user ${userId}`)  — unqueryable, leaks userId
logger.warn({ event: 'payment_failed', trace_id, span_id, paymentId: id, provider: 'stripe', errorCode: err.code, attempt: n }, 'payment failed');
```

## 4. Metrics — RED / USE

- [ ] **RED** on every endpoint + external dependency: **R**ate, **E**rrors, **D**uration (latency histogram)
- [ ] **USE** on resources (queues, pools, hosts): **U**tilization, **S**aturation, **E**rrors
- [ ] Percentiles always (p50/p95/p99), averages never (an average hides the 1% having a terrible time)
- [ ] **Cardinality bounded** — labels from small fixed sets only (OTel itself now demotes high-cardinality attributes like `network.peer.address` to opt-in — the discipline is spec-enforced, not just advice):

```
OK as label:    route="/api/tasks/:id"   status_class="5xx"   provider="stripe"
NEVER a label:  user_id, email, request_id, full URL, error message text
```

## 5. Distributed tracing

- [ ] Use OpenTelemetry (graduated CNCF 2026; Traces/Metrics/Logs GA at the spec level). OTLP `http/protobuf` is the default transport (ports 4317 gRPC / 4318 HTTP).
- [ ] HTTP + DB semantic conventions are **stable** — safe to hard-wire. Manual spans only around meaningful internal units of work.
- [ ] Context propagated across every async boundary via **W3C `traceparent`** (inject into HTTP headers / queue metadata at publish, extract at consume) or the trace dies at the gap. *(W3C Trace Context L1 is the adopted Rec; L2 is a Working Draft — track, don't hard-require.)*
- [ ] Head-sample at a low rate by default; keep 100% of errors. For tail decisions use the Collector `tailsamplingprocessor` (policies: `status_code`, `latency`, `rate_limiting`) — note all spans of a trace must reach the same collector instance (needs the load-balancing exporter upstream).

## 6. Sampling & cost control *(NEW section — the old baseline had trace sampling only)*

- [ ] **Log-specific sampling** via the Collector `probabilisticsamplerprocessor` (it now handles LOG records, not just traces): sample keyed to the record's `TraceID` so kept logs stay consistent with kept traces (closes the "orphan logs referencing an unsampled trace" gap).
- [ ] **Always retain `error` / `warn`** via `sampling_priority`; probabilistically sample `debug` / `info`.
- [ ] Align log-sample and trace-sample decisions — sampling them independently produces orphan logs. Add this to the §10 staging check.

## 7. PII & sensitive data — defense in depth

- [ ] **App-level (first line):** never emit secrets/PII into logs/spans/metrics in the first place (telemetry is a classic leak path).
- [ ] **Collector-level (second line, catches what discipline misses):** a redaction pipeline applied ONCE centrally across logs+traces+metrics — `attributesprocessor` (strip/hash known PII keys) → `redactionprocessor` (allow-list + regex value masking) → `transformprocessor` (OTTL scrub of log bodies). Config, not per-service code — so it belongs in the shared collector setup.
- [ ] Treat IP / device / session identifiers as PII-class (2026 regulatory scope: CCPA/CPRA risk-assessment rules effective Jan 2026; EU AI Act Art. 50 transparency Aug 2026).

## 8. GenAI / LLM observability *(NEW section — first-class for AI apps)*

- [ ] **Standardize the output shape on the OTel GenAI semconv namespace** (`gen_ai.*`) — but the conventions are still **experimental** (shape churned mid-2026: flat `gen_ai.prompt.0.role` → JSON `gen_ai.input.messages`). **Pin the semconv version and gate on `OTEL_SEMCONV_STABILITY_OPT_IN=gen_ai_latest_experimental`; do NOT hard-code the exact attribute shape** (same discipline used for HTTP semconv before it stabilized).
- [ ] Instrument via whichever library covers your framework — **OpenLLMetry/Traceloop** (broadest, 60+ frameworks) or **OpenInference/Arize** (agent/RAG span kinds). If running a Collector, add the `gen_ai_normalizer` processor (Alpha) to map both vocabularies onto the canonical `gen_ai.*` shape — don't force one SDK org-wide.
- [ ] Capture per-LLM-call: model, token counts (prompt/completion), cost, latency, tool-calls, and a redacted/summarized prompt reference (§7 — prompts often carry PII).
- [ ] **Evals as telemetry:** attach an eval/quality-score span attribute (hallucination check, relevance, LLM-as-judge verdict) alongside the cost/token attributes — *"a 200 OK with a hallucination is a production incident the request log alone cannot flag."* RED/percentiles do not capture output-quality regressions.
- [ ] *(Forward-looking, not yet a hard requirement)* for user-facing AI features, structured trace logging of prompts/tool-calls/decisions doubles as compliance-evidence under EU AI Act Art. 50 (effective Aug 2026).

## 9. Alerting — symptoms, not causes

```
SYMPTOM (page-worthy):           CAUSE (dashboard, not a page):
error rate > 1% for 5 min        CPU at 85%
p99 latency > 2s                 one pod restarted
queue age > 10 min               disk at 70%
```

- [ ] Every alert is **actionable** (if the response is "ignore, it self-heals" → delete it)
- [ ] Every alert **links to a runbook** (even 3 lines: what it means, first query, escalation)
- [ ] **Derive the two severities from an SLO via multi-window burn-rate** (Google SRE Workbook), not thresholds picked by feel: **page** at burn-rate > 14.4× over 1h (fast burn ≈ 2% budget/1h), **ticket** at > 6× over 6h (slow burn). Rollout order: recording rules → one critical alert → add the warning tier → tune from real incidents.

## 10. Verify the telemetry itself

Instrumentation is code; it can be wrong. Before "done":

- [ ] Force an error in staging → find it in logs by `trace_id`, fields structured (not `[object Object]`)
- [ ] Send test traffic → metric series appear with expected labels and sane values
- [ ] Follow one request end-to-end in the tracing UI → no broken spans; the log line joins the trace by `trace_id`
- [ ] **Confirm log-sample and trace-sample decisions agree** (§6) — no orphan logs
- [ ] Fire each new alert once (lower threshold temporarily) → reaches the right channel, runbook link works

## 11. Build-lens baseline (what `/proposal` requires and `osf-apply` gates on)

The minimum a *production surface* (service / endpoint / job / external integration / LLM call)
must ship — the depth for each lives in the sections above:

1. **One structured wide event** per request/job (stable name, machine-readable fields) — §3
2. **`trace_id` + `span_id`** on every log line (primary correlation key) — §3
3. **RED** on the endpoint + each external dependency — §4
4. **No secrets/PII** (incl. IP / device / session IDs) — §7
5. **LLM call** → `gen_ai.*` attributes (version-pinned) + an eval/quality-score attribute — §8
6. **Frontend surface** (user-facing app/route) → route-level error boundary + client-side error reporting — §12

Plus one rule binding ALL code, not just production surfaces: a **caught error** (query / IO / external) is logged with context or re-thrown, **never swallowed** by an empty catch (§3).

Everything beyond this baseline (log sampling processors, Collector-side redaction, tail
sampling, `gen_ai_normalizer`, burn-rate SLO alerts, eBPF/OBI auto-instrumentation) is
depth/infrastructure — it belongs in the spec's design when relevant, not forced into every task.

## 12. Frontend / client-side observability

The sections above assume server-side signals; a browser/mobile client needs its own — and "logs the error" on the client means reporting it off-device, not a `console.log` that dies in the user's tab.

- [ ] **Global error capture** — a route/app-level error boundary (React `ErrorBoundary`/`componentDidCatch`, Vue `app.config.errorHandler`, Svelte `onError`) PLUS `window.onerror` + `window.onunhandledrejection`, so a crash surfaces instead of blanking the screen silently.
- [ ] **Report client errors off-device** — Sentry / a RUM tool / your own endpoint; attach release + session context and upload **source maps** so stack traces are readable. *(The OTel browser SDK is still Development in 2026 — vendor RUM is the pragmatic default.)*
- [ ] **Failed fetch/API calls are logged with context** (status, endpoint, correlation/trace header) — a swallowed `fetch().catch(() => {})` is the client-side twin of an empty backend catch (§3).
- [ ] **Propagate `traceparent` on outbound API calls** so a client-side error joins the backend trace (§3/§5).
- [ ] **Client-side PII discipline** — scrub tokens/PII from breadcrumbs and error payloads before they leave the browser (§7 applies client-side too; RUM tools ship redaction config).
- [ ] *(Optional, when UX perf matters)* Core Web Vitals (LCP / INP / CLS) via the `web-vitals` lib → your RUM.

## Tooling notes & caveats (2026)

- **OTel Logs SDK maturity is uneven** — spec is GA, but JS/Python/Ruby SDKs are still Development (C++/.NET/Java/PHP Stable; Go/Rust Beta). Check `opentelemetry.io/status/` for your runtime before assuming logs are as battle-tested as tracing.
- **ECS is merging INTO OTel semconv** (Elastic donated it in 2023) — target OTel semconv namespaces for new field naming, not ECS. *(SUPERSEDES any ECS-style field guidance.)*
- **eBPF zero-code instrumentation (OTel OBI, ex-Grafana Beyla; Odigos)** is maturing but **pre-1.0** — a coverage-gap/fast-start tool for legacy/uninstrumented services, NOT a substitute for manual instrumentation in new code.
- **OTel Weaver** (schema governance) and the full "Observability 2.0" backend migration are platform-team-scale — out of scope for a single-service checklist.
