# Performance Checklist

Measure-first performance reference for the osf kit. Adapted from
addyosmani/agent-skills (MIT).

> **When osf kit pulls this in (load on-demand):**
> - `/perf` cites it as the optimization baseline.
> - `/ui` *QA-Signoff* Performance group reads the Core Web Vitals targets.
> - `/review` *Performance & Memory* dimension uses the anti-patterns table.
>
> **Metric honesty (hard rule):** an LLM reading static code CANNOT measure LCP/INP/CLS,
> bundle size, or p95. Tag every figure with its source (measured tool output vs
> estimate); if not measured, write `not measured` — never invent a number.
>
> **Structural safety (CLAUDE.md §5):** before changing a hot-path exported symbol,
> run GitNexus `impact` to find every caller that inherits the change.

## Core Web Vitals Targets

| Metric | Good | Needs Work | Poor |
|--------|------|------------|------|
| LCP (Largest Contentful Paint) | ≤ 2.5s | ≤ 4.0s | > 4.0s |
| INP (Interaction to Next Paint) | ≤ 200ms | ≤ 500ms | > 500ms |
| CLS (Cumulative Layout Shift) | ≤ 0.1 | ≤ 0.25 | > 0.25 |

## TTFB Diagnosis

When TTFB is slow (> 800ms), check each component in the DevTools Network waterfall:

- [ ] **DNS resolution** slow → add `<link rel="dns-prefetch">` / `preconnect` for known origins
- [ ] **TCP/TLS handshake** slow → enable HTTP/2, consider edge deployment, verify keep-alive
- [ ] **Server processing** slow → profile backend, check slow queries, add caching

## Frontend Checklist

### Images
- [ ] Modern formats (WebP, AVIF)
- [ ] Responsively sized (`srcset` and `sizes`)
- [ ] Explicit `width`/`height` on images and `<source>` (prevents CLS)
- [ ] Below-the-fold uses `loading="lazy"` and `decoding="async"`
- [ ] Hero/LCP image uses `fetchpriority="high"`, no lazy loading

### JavaScript
- [ ] Initial bundle under 200KB gzipped
- [ ] Code splitting with dynamic `import()` for routes and heavy features
- [ ] Tree shaking enabled (dependency ships ESM, `sideEffects: false`)
- [ ] No blocking JS in `<head>` (use `defer`/`async`)
- [ ] Heavy computation offloaded to Web Workers (if applicable)
- [ ] `React.memo()` / `useMemo()` / `useCallback()` only where profiling shows benefit
- [ ] Long tasks (> 50ms) broken up — main lever for INP
- [ ] `yieldToMain` inside long-running loops so input events run between chunks
- [ ] Modern scheduling where available: `scheduler.yield()` (preferred), `scheduler.postTask()`, `isInputPending()`
- [ ] `requestIdleCallback` for deferrable work (analytics flush, prefetch)
- [ ] Non-critical work deferred out of event handlers

### CSS
- [ ] Critical CSS inlined or preloaded
- [ ] No render-blocking CSS for non-critical styles
- [ ] No CSS-in-JS runtime cost in production (use extraction)

### Fonts
- [ ] 2–3 families, 2–3 weights each
- [ ] WOFF2 only
- [ ] Self-hosted when possible
- [ ] LCP-critical fonts preloaded (`<link rel="preload" as="font" crossorigin>`)
- [ ] `font-display: swap` (or `optional` for non-critical)
- [ ] Subsetted via `unicode-range`
- [ ] Variable fonts when multiple weights needed
- [ ] Fallback metrics adjusted (`size-adjust`, `ascent-override`) to reduce CLS
- [ ] System font stack considered first

### Network
- [ ] Static assets cached with long `max-age` + content hashing
- [ ] API responses cached where appropriate (`Cache-Control`)
- [ ] HTTP/2 or HTTP/3 enabled
- [ ] `preconnect` for known origins; `fetchpriority` on critical non-image resources
- [ ] No unnecessary redirects

### Rendering
- [ ] No layout thrashing (forced synchronous layouts)
- [ ] Animations use `transform`/`opacity` (GPU-accelerated)
- [ ] Long lists virtualized
- [ ] No unnecessary full-page re-renders
- [ ] Off-screen sections use `content-visibility: auto` + `contain-intrinsic-size`
- [ ] No `unload` handlers, no `Cache-Control: no-store` on HTML (preserves bfcache)

## Backend Checklist

### Database
- [ ] No N+1 query patterns (eager loading / joins)
- [ ] Appropriate indexes on filtered/sorted columns
- [ ] List endpoints paginated (never `SELECT * FROM table`)
- [ ] Connection pooling configured
- [ ] Slow query logging enabled

### API
- [ ] Response times < 200ms (p95)
- [ ] No synchronous heavy computation in request handlers
- [ ] Bulk operations instead of loops of individual calls
- [ ] Response compression (gzip/brotli)
- [ ] Appropriate caching (in-memory, Redis, CDN)

### Infrastructure
- [ ] CDN for static assets
- [ ] Server close to users (or edge deployment)
- [ ] Horizontal scaling configured (if needed)
- [ ] Health check endpoint for load balancer

## Measurement Commands

1. **Field data first** — check CrUX / RUM for real-user INP before optimizing.
2. **Identify slow interactions** — DevTools → Performance panel, record while interacting.
3. **Test on mid-range Android** — INP issues surface on slow hardware; throttle CPU 4×–6×.

```bash
npx lighthouse https://localhost:3000 --output json --output-path ./report.json
npx webpack-bundle-analyzer stats.json     # or: npx vite-bundle-visualizer
npx bundlesize

# Web Vitals in code
import { onLCP, onINP, onCLS } from 'web-vitals';
onLCP(console.log); onINP(console.log); onCLS(console.log);
```

## Common Anti-Patterns

| Anti-Pattern | Impact | Fix |
|---|---|---|
| N+1 queries | Linear DB load growth | Use joins, includes, or batch loading |
| Unbounded queries | Memory exhaustion, timeouts | Always paginate, add LIMIT |
| Missing indexes | Slow reads as data grows | Add indexes for filtered/sorted columns |
| Layout thrashing | Jank, dropped frames | Batch DOM reads, then batch writes |
| Unoptimized images | Slow LCP, wasted bandwidth | WebP, responsive sizes, lazy load |
| Large bundles | Slow Time to Interactive | Code split, tree shake, audit deps |
| Blocking main thread | Poor INP | Chunk long tasks (`scheduler.yield()`), Web Workers |
| Memory leaks | Growing memory, eventual crash | Clean up listeners, intervals, refs |
