# Accessibility Checklist

WCAG 2.1 AA reference for the osf kit. Adapted from addyosmani/agent-skills (MIT).

> **When osf kit pulls this in (load on-demand):**
> - `/ui` *QA-Signoff* A11y group reads it as the source of truth (the DNA's numeric
>   floor is a summary of the Visual section below).
> - `/review` *UI/UX Feedback* dimension cites it for missing labels / live regions.
> - For a deeper audit, `/web-design-guidelines` remains the dedicated path.

## Essential Checks

### Keyboard Navigation
- [ ] All interactive elements focusable via Tab
- [ ] Focus order follows visual/logical order
- [ ] Focus is visible (outline/ring on focused elements)
- [ ] Custom widgets have keyboard support (Enter to activate, Escape to close)
- [ ] No keyboard traps (user can always Tab away)
- [ ] Skip-to-content link at top of page, visible on keyboard focus
- [ ] Modals trap focus while open, return focus on close

### Screen Readers
- [ ] All images have `alt` text (or `alt=""` for decorative)
- [ ] All form inputs have associated labels (`<label>` or `aria-label`)
- [ ] Buttons and links have descriptive text (not "Click here")
- [ ] Icon-only buttons have `aria-label`
- [ ] One `<h1>`, headings don't skip levels
- [ ] Dynamic content changes announced (`aria-live`)
- [ ] Tables have `<th>` headers with scope

### Visual
- [ ] Text contrast ≥ 4.5:1 (normal) or ≥ 3:1 (large text, 18px+)
- [ ] UI components contrast ≥ 3:1 against background
- [ ] Color is not the only way to convey information
- [ ] Text resizable to 200% without breaking layout
- [ ] No content flashes more than 3 times per second

### Forms
- [ ] Every input has a visible label
- [ ] Required fields indicated (not by color alone)
- [ ] Error messages specific and associated with the field
- [ ] Error state visible by more than color (icon, text, border)
- [ ] Submission errors summarized and focusable
- [ ] Known fields use autocomplete (`type="email" autocomplete="email"`)

### Content
- [ ] Language declared (`<html lang="en">`)
- [ ] Descriptive `<title>`
- [ ] Links distinguish from surrounding text (not by color alone)
- [ ] Touch targets ≥ 44×44px on mobile
- [ ] Meaningful empty states (not blank screens)

## Common HTML Patterns

```html
<!-- Use <button> for actions, <a> for navigation. NEVER div/span as buttons. -->
<button onClick={handleDelete}>Delete Task</button>
<a href="/tasks/123">View Task</a>

<!-- Explicit label -->
<label htmlFor="email">Email address</label>
<input id="email" type="email" required />

<!-- Hidden label (visible preferred) -->
<input type="search" aria-label="Search tasks" />

<!-- Status / alert / modal -->
<div role="status" aria-live="polite">Task saved</div>
<div role="alert">Error: Title is required</div>
<dialog aria-modal="true" aria-labelledby="dialog-title">…</dialog>
```

## Testing Tools

```bash
npx axe-core   # programmatic a11y testing
npx pa11y      # CLI checker
# Chrome DevTools → Lighthouse → Accessibility; Elements → Accessibility tree
# Screen readers: VoiceOver (Cmd+F5) / NVDA / Orca
```

## Quick Reference: ARIA Live Regions

| Value | Behavior | Use For |
|-------|----------|---------|
| `aria-live="polite"` | Announced at next pause | Status updates, saved confirmations |
| `aria-live="assertive"` | Announced immediately | Errors, time-sensitive alerts |
| `role="status"` | Same as `polite` | Status messages |
| `role="alert"` | Same as `assertive` | Error messages |

## Common Anti-Patterns

| Anti-Pattern | Problem | Fix |
|---|---|---|
| `div` as button | Not focusable, no keyboard support | Use `<button>` |
| Missing `alt` text | Images invisible to screen readers | Add descriptive `alt` |
| Color-only states | Invisible to color-blind users | Add icons, text, or patterns |
| Autoplaying media | Disorienting, can't be stopped | Add controls, don't autoplay |
| Custom dropdown, no ARIA | Unusable by keyboard/screen reader | Native `<select>` or proper ARIA listbox |
| Removed focus outlines | Users can't see where they are | Style outlines, don't remove |
| Empty links/buttons | "Link" announced with no description | Add text or `aria-label` |
| `tabindex > 0` | Breaks natural tab order | Use `tabindex="0"` or `-1` only |
