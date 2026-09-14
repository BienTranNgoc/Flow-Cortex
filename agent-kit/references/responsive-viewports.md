# Responsive viewport set — the standard sweep for UI/UX design, Figma hand-off and QC

Decided by Master 2026-09-03 ("chuẩn để đo responsive… thêm vào các skill"). One list, referenced by `/ui`, `/uiux-design`, `figma-extract`, `taste-skill` and `qc-hunt`, so a plan's acceptance criteria and the browser gate measure the same thing. Mobile-first still applies to the CSS; this list is the MEASUREMENT set.

| Tier | Viewport (CSS px) | Represents | Why it is in the set |
|---|---|---|---|
| min mobile | **360 × 800** | common Android in VN | smallest width we design for; anything narrower is `Not verified` |
| mobile | **390 × 844** | iPhone 14/15 | qc-hunt default |
| tablet | **768 × 1024** | iPad portrait | `md` / the 767–768 media queries |
| small laptop | **1024 × 768** | rail ↔ pinned sidebar switch | the app shell's main breakpoint (`min-width: 1024px`) |
| laptop | **1280 × 800** | 13" laptop | `--page-max` 1280, container query 1249/1280 |
| laptop-short | **1280 × 720** | 1366×768 / 1536×864 Windows laptops after browser chrome (≈650–760 CSS px tall) | the LOWEST real laptop height — the fold check; 800/900 above never catch it (Master 2026-09-10, research-backed) |
| design | **1440 × 900** | the Figma frames | measurement gate reference; sidebar rail AND pinned |
| desktop | **1920 × 1080** | common monitor | `min-width: 1920px` tier |
| max | **2560 × 1440** | wide monitor | `--content-max` reaches 1800; `min-width: 2560px` tier |
| 4K cap check (optional) | **3840 × 2160** | a 4K panel at 100% OS scaling — rare; 4K normally reports 2560×1440 (Windows 150%) or 1920×1080 (macOS 2×) | pass = the column still CAPS (`min(1600px, …)`) and sits centred with sharp 2× assets; a page that "grows to fill" 4K is the anti-pattern (line length, WCAG readability) — never a goal |

Rules:
- **Height is a first-class axis, not a side effect of width.** Laptops in VN are mostly 1366×768 and 1920×1080@125% (CSS 1536×864); after the browser chrome the viewport is 650–760px tall. Every hero/fold/pinned-stage criterion is measured at 1280×720 as well as 1440×900 — a fold that fits at 900 and not at 720 is a defect, not an edge case (research 2026-09-10: StatCounter share via Statista/BrowserStack, WCAG 2.2 SC 1.4.10 reflow also covers 256px-tall horizontal content).
- **Large screens are a cap test, not a scale test.** No mainstream OS hands a 3840×2160 CSS viewport to users; test 2560×1440 as the real "4K" tier and 3840×2160 only to confirm the container cap holds. Fluid type uses `clamp()` with a ceiling; SVG or 2× raster marks for DPR ≥ 2; never `vw`-scale the whole page.
- **Every UI plan** (`/ui`, `/uiux-design` report, `taste-skill`, `redesign-skill`) states behaviour at these tiers — what stacks, what scrolls, what hides — as acceptance criteria, not as an afterthought. A Figma frame carries ONE width; the other tiers are decisions the plan must make (`[AGENT-ASSUMED]` until the designer confirms).
- **The browser gate** (`qc-hunt`, `/browser`, figma-extract measurement gate) screenshots EACH changed section at every tier (element screenshot + full page), records section width vs `html` width, horizontal-scroll test (scroll right 50 px → nothing moves), fully visible items per row, hidden/stacked controls. With an app sidebar, run 1440 and 1920 in BOTH rail and pinned states.
- Add the change's own scenario widths on top (e.g. R-xxx "Laptop width 1180").
- 360 and 390 also get the touch checks: targets ≥ 24 × 24 CSS px (WCAG 2.2 SC 2.5.8; 44 in practice), no hover-only affordances.
- `100vh` heroes → `100dvh` (iOS address bar); `overflow-x: hidden` on the page is never the fix for a section that overflows — find the element.

Project note (xood-frontend): DESIGN.md lists the Tailwind names `sm/md/lg/xl/2xl`; the CSS that actually runs switches at 599 / 767–768 / 1024 (rail↔pinned) / 1249–1280 (container `content`) / 1920 / 2560 / 3200. The set above covers all of them.

## Units & DPR — why a 4K panel can look "shrunk", and what converts to what <!-- CUSTOM:units-dpr 2026-09-10 (Master, research-backed) -->

**Record the DPR with every large-screen finding.** A 3840×2160 panel reports 1920 (macOS 2×), 2560 (Windows 150%, the Windows default) or 3840 CSS px (100% scaling, a minority). CSS `px` is the DPR-normalised reference pixel (CSS Values 4), so at DPR 1 a 16px glyph is physically half the size it is on a 1920 FullHD panel — that is the OS setting, not a site defect. A finding that says "small on 4K" without `devicePixelRatio` and the OS scaling % is `Not verified`; the driver logs `[innerWidth, innerHeight, devicePixelRatio]` per tier.

**Cap, don't scale (Primer 1280/1400, Polaris ~1240, Material 3 extra-large ≥1600dp all agree):** big viewports get more columns or centred whitespace, never enlarged components. Root-font-size or `zoom` upscaling on ≥2560 is rejected (duplicates browser/OS zoom, Safari `getBoundingClientRect` inconsistency, reflow cost); raw `vw` scale-to-fill is rejected (WCAG 1.4.10, `100vw` scrollbar overflow, no precedent).

Convert / don't convert (per property, never blanket):

| Property class | Unit | Reason |
|---|---|---|
| font-size, line-height, padding around text | `rem` / `clamp(rem, rem + vw, rem)` with a ceiling | tracks the user's font-size preference; WCAG 1.4.4. Formula (Utopia): `slope = (max−min)/(vwMax−vwMin)`, `intercept = min − slope·vwMin`, `clamp(min, intercept + slope·100vw, max)` |
| spacing scale (gaps, section padding) | `rem` or fluid clamp; xs/sm hairline gaps may stay px | Tailwind v4 base is `--spacing: 0.25rem` |
| fixed chrome widths (sidebar, panels) | `px`, stepped per tier or driven by `@container`/`cqi` | cap precedent; container queries respond to the cell, not the viewport |
| icons, borders, shadows, radii | `px` | must not compound with text scale |
| content / page caps | `clamp(px, vw, px)` with a hard ceiling | line length (`--measure`), readability |
| media-query breakpoints | `px` | Safari em/rem media-query zoom bug (WebKit 156687) |
| `html { font-size }` | do NOT hardcode px | a px root overrides the user's browser default and defeats every rem token downstream |

Sources: MDN devicePixelRatio; W3C CSS Values 4; Comeau "Surprising truth about pixels"; utopia.fyi/blog/clamp; Tailwind v4 theme docs; Primer layout; Polaris layout; M3 breakpoints; keithjgrant.com px-vs-em media queries; W3C Understanding SC 1.4.4; MDN container queries / zoom.
