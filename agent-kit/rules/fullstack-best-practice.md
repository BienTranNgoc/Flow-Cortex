---
paths:
  - "**/*.ts"
  - "**/*.tsx"
  - "**/api/**"
  - "**/route.ts"
  - "**/actions.ts"
  - "**/*.server.ts"
  - "**/schema*.ts"
---

# Fullstack best-practice (2026) — encode patterns, not library names

**A pattern outlives the library. Prescribe "parse with a Standard-Schema validator", not "use Zod"; "RFC 9457 error shape", not a hand-rolled JSON; "schema-validated session + RBAC plugin", not a specific auth package.** Named defaults below carry their *reason* so a future reader can re-derive the choice when the library churns. Tags: [STABLE] = settled consensus, [CONTESTED] = credible split, [UNVERIFIED] = time-sensitive / self-reported, check before relying.

1. **TypeScript** — `strict: true` plus `noUncheckedIndexedAccess` + `exactOptionalPropertyTypes`. `satisfies` for config-shaped literals only, not everywhere. [STABLE]
2. **Trust boundaries** — parse every value crossing one (API body, query/params, form, external response, env var) with a Standard-Schema validator (Zod / Valibot / ArkType); derive the type FROM the schema. Types alone are never validation. [STABLE] — validator choice is [CONTESTED] (Zod = ecosystem, Valibot = bundle size, ArkType = speed); the Standard-Schema interop makes it a DX call, not lock-in.
3. **React Server Components by default**; add `"use client"` only for interactivity (state, events, browser APIs), and reason about what serializes across the boundary before adding it. [STABLE as default] — real-world adoption is [CONTESTED] (~45% used it, ~1/3 positive; the failure mode is boundary confusion, not the model). Adopt where RSC solves a real problem (fetch waterfalls, secret-keeping, bundle), not wholesale.
4. **Server Actions for in-tree mutations only** — single-responsibility, consistent `{ ok, data } | { ok: false, error }` shape, log the real error server-side and return a sanitized message. Route Handlers for anything externally callable (webhooks, third-party). [STABLE]
5. **API transport by constraint, not habit** — tRPC only when client and server share one TypeScript codebase/monorepo; REST for public/polyglot/simple CRUD; GraphQL for genuinely variable client queries. [STABLE framework]
6. **Model API errors as RFC 9457 Problem Details** (`application/problem+json`); never leak a raw stack trace or exception to the client. [STABLE] — RFC 9457 (2023) supersedes RFC 7807; cite 9457.
7. **Server-state ≠ client-state** — server-origin data lives in TanStack Query (fetch/cache/retry/invalidate/dedupe); UI-only state in `useState`/Zustand. Never sync one into the other. Forms: React Hook Form + Zod default; plain `useState` for 1–3 fields. [STABLE separation] — client-state lib is [CONTESTED].
8. **Testing** — Vitest + React Testing Library (query by role/label, never by implementation detail) for unit/component; Playwright for E2E (auth, checkout, async RSC); MSW for network mocking. Favor fewer behavioral tests over many state-assertion tests. [STABLE]
9. **Secrets & supply chain** — never place a secret in a `NEXT_PUBLIC_`-prefixed var; commit lockfiles and `.env.example`, never `.env.local`; install `--frozen-lockfile` in CI, verify npm provenance/signatures (`npm audit signatures`). [STABLE] — OWASP Top 10:**2025** is current (A03 Supply-Chain and A10 Exceptional-Conditions are new); there is no "2026" edition.
10. **Observability on every new route/handler** — emit OpenTelemetry with structured JSON logs carrying `traceId` / `spanId` / `requestId` (Next.js: `@vercel/otel` + `instrumentation.ts`). Exclude PII/secrets AT THE CALL SITE — do not rely on redaction middleware alone. [STABLE]
11. **Accessibility** — semantic HTML before ARIA (`<button>`, not `<div role="button">`), correct heading order (no skipped levels), visible focus indicators, interactive targets ≥24×24px (44px in practice). These five classes fix violations on ~95% of sites. No a11y-overlay library as a "fix". [STABLE — WCAG 2.2, SC 2.5.8 AA]
12. **Performance** — treat INP ≤200ms as the Core Web Vital most likely silently failing (not LCP/CLS); enforce a JS/page-weight budget as a CI gate, not an aspiration. Thresholds: LCP ≤2500ms, INP ≤200ms, CLS ≤0.1 at p75. [STABLE]

**Auth** — encode the *pattern* (schema-validated session, RBAC via plugin architecture, no secret in the client bundle), not a package name. [UNVERIFIED] the Auth.js → Better Auth transition is early-2026 and young — verify before hard-coding a library.

Deep checklists (security / perf / a11y / observability / api-design / testing) stay in `agent-kit/references/` — read on demand, don't inline here.
