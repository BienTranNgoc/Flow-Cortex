# Frontend projects — design docs convention

Read this when working inside a frontend project (a `package.json` depending on
react / vue / svelte / solid / angular, OR using Vite / Next / Nuxt / Tailwind).
Skip for backend / CLI / library repos. (Pointer lives in CLAUDE.md §11.)

- A frontend repo SHOULD have, at its root, both:
  - **`DESIGN.md`** — brand spec for coding agents, following the Google Labs DESIGN.md format (https://github.com/google-labs-code/design.md): YAML front-matter design tokens (colors, typography, spacing, rounded, components) + prose sections (Overview, Colors, Typography, Layout, Do's/Don'ts).
  - **`FRONTEND.md`** — engineering handbook for human devs (token architecture, animation/motion, SVG/icon system, curated resources). This is project-authored, NOT part of the Google Labs spec.
- **Before generating or editing any UI** (`*.tsx`/`*.vue`/`*.css` components, pages, styles): read `DESIGN.md` (tokens + do/don't) and consult `FRONTEND.md`. If `DESIGN.md` (or its token CSS) is missing, CREATE it before writing UI code — announce the scaffold, follow the spec format above, and ground every token in the project's real code/design read (never invent values silently). A missing `FRONTEND.md` is only noted with an offer — it is project-authored.
- **Source of truth for token VALUES** is the project's runtime CSS-variables file (e.g. `tokens-v2.css`), not `DESIGN.md`. On any value/name conflict, the CSS file wins; `DESIGN.md` is the machine-readable mirror + usage rationale and must be reconciled to it.
- Do NOT live-fetch the Google Labs URL every session (it's alpha, costs a fetch). Keep `DESIGN.md` local and spec-conformant; only consult/lint against the URL when explicitly validating structure.
- **`DESIGN.md` is a local-only AI doc → MUST be gitignored**: whenever a skill scaffolds `DESIGN.md`, ensure a `.gitignore` entry exists (add it if missing; same treatment for `FRONTEND.md` when the repo keeps it local-only). `tokens-v2.css` is the opposite — runtime CSS the app imports → stays committed. Editing these docs never implies committing them.
- **Reference (not a dependency):** Shopify Polaris (https://polaris.shopify.com, repo `shopify/polaris`) is a mature design system worth consulting for token architecture (primitive→semantic), WCAG/a11y rigor, and `stylelint-polaris` (lint-enforce "tokens only, no hard-coded hex"). ⚠️ DEPRECATED + archived since 2025-10-01 (superseded by Polaris Web Components) — borrow the *thinking*, never `npm install @shopify/polaris` for new work.
- Typography rules (font ban-list, category menus, extremes), aesthetic-direction vocabulary, numeric a11y/motion thresholds, and the QA-signoff loop live in the `/ui` skill + the project's UI DNA doc — they load on-demand with UI work, not here.
- Accessibility + performance checklists for UI QA: `agent-kit/references/accessibility-checklist.md` and `performance-checklist.md` (pulled by `/ui` QA-Signoff).
