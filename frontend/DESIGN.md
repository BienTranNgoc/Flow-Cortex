---
version: alpha
name: Flow-Cortex member
description: >-
  Dark-only design system for the Flow-Cortex member SPA. Values mirror
  `src/styles/tokens.css`, which wins on any conflict.
colors:
  paper: '#080c13'
  paper-2: '#0d131c'
  paper-3: '#101721'
  btn-bg: '#141d28'
  btn-bg-hover: '#141e2a'
  input-bg: '#0b1119'
  card-from: '#101a24'
  card-to: '#0c111a'
  glow: '#152b38'
  header-bg: '#0f0f1e'
  footer-bg: '#080812'
  aurora-top: '#0d1828'
  aurora-mid: '#060a12'
  aurora-bottom: '#04060c'
  disabled-from: '#1a3540'
  disabled-to: '#2a3a55'
  ink: '#e8f1f2'
  ink-2: '#c8d4dc'
  ink-3: '#9eacb6'
  ink-4: '#81919e'
  ink-5: '#5a6b78'
  nav-ink: '#8a9ab5'
  ink-disabled: '#a8b6c2'
  line: '#26313d'
  line-2: '#334352'
  line-3: '#2e3b49'
  line-soft: '#1c2935'
  line-subtle: '#233443'
  card-border: '#253746'
  control-border: '#2a3a4a'
  input-border: '#62727f'
  input-border-hover: '#3a4a5b'
  edge: '#62727f'
  primary: '#35e5e0'
  primary-foreground: '#061016'
  cta-from: '#1be0d1'
  cta-to: '#5174ff'
  accent: '#5f9cff'
  accent-soft: '#1b2440'
  primary-soft: '#0b2a2c'
  teal: '#00e5db'
  teal-bright: '#5ef0e0'
  teal-glow: '#5eead4'
  teal-deep: '#00d4ce'
  violet: '#b3a4ff'
  indigo: '#120074'
  aurora-teal: '#0fb5a8'
  aurora-indigo: '#4338ca'
  aurora-cyan: '#06b6d4'
  tone-blue-text: '#9fc4ff'
  tone-blue-bg: '#0a1a2c'
  tone-blue-line: '#1d3a66'
  tone-cyan-text: '#7fe1f5'
  tone-cyan-bg: '#062028'
  tone-cyan-line: '#175663'
  tone-cyan-from: '#5cd0ff'
  tone-cyan-to: '#7a5bff'
  tone-violet-text: '#c0b0ff'
  tone-violet-bg: '#16102a'
  tone-violet-line: '#382c6a'
  tone-amber-text: '#ffcd6b'
  tone-amber-bg: '#2a200a'
  tone-amber-line: '#5a4416'
  tone-amber-from: '#ffb46b'
  tone-amber-to: '#ff7a59'
  tone-amber-on: '#2a1106'
  tone-green-bg: '#0a2618'
  tone-green-line: '#1f4d34'
  tone-rose-text: '#ff9bb0'
  tone-rose-bg: '#2a0e16'
  tone-rose-line: '#6a2536'
  red: '#ff9a9a'
  red-soft: '#3b1518'
  red-strong: '#ff7a7a'
  red-light: '#ffb8b8'
  warning: '#ffd766'
  warning-soft: '#3a2f0b'
  green: '#7ee2a8'
  green-soft: '#0f3320'
  green-bright: '#69f0a8'
  green-vivid: '#4ade80'
  shadow-color: '#000000'
rounded:
  sm: 4px
  md: 8px
  lg: 14px
  full: 9999px
---

## Overview

The member app has one theme: dark. The document declares `color-scheme: dark`, so browser-drawn controls (file picker, progress bars) match. One accent family, cyan (`primary`) with blue (`accent`), carries links, eyebrows and the call to action.

## Colors

- Text: `ink` for body, `ink-3` for muted and secondary text. Both hold 4.5:1 on every surface.
- Surface: `paper` is the page, `paper-2` the header, `paper-3` a card, `input-bg` a field and raw output, `card-from` to `card-to` the auth card gradient.
- Border: `line` for cards and dividers, `line-2` for button outlines, `input-border` for fields (3:1 on every surface a field sits on), `edge` (the same colour) for pipeline connectors and their port dots (3:1 on `paper-2` and `paper-3`).
- Status: each state pairs a foreground with its `-soft` background (`primary`, `warning`, `green`, `red`, `accent`); pending uses `ink-3` on `line`. Running is `primary` on `primary-soft`; `warning` means "Needs you" and nothing else.
- Links use `primary`; the header navigation uses `nav-ink` on `header-bg`.
- Translucent fills and glows are `color-mix(in srgb, var(--token) N%, transparent)` over an opaque token (`teal-glow`, `indigo`, `cta-from`, `red-strong`, `green-bright`), never an `rgba()` literal.
- Workflow categories each pair a `tone-*-text` with its `tone-*-bg` and `tone-*-line`; icon tiles paint `tone-*-from` to `tone-*-to`.

## Layout

Pages stay free of horizontal scroll at 375, 768 and 1280px. Buttons, inputs and action links are at least 44px tall.

## Shapes

`sm` for fields, buttons and blocks, `md` for the step detail panel, `lg` for cards and pipeline nodes, `full` for badges.

## Motion

Personality: corporate, calm and exact. One curve, `ease-standard`, and three durations: `dur-quick` (120ms) for hover and press, `dur-standard` (220ms) for settling, the detail panel entrance and the route cross-fade, `dur-slow` (360ms) for the error shake. The two endless loops are the only exceptions to the curve and the durations: the running ring (1.6s) and the connector segment (1.4s), both `ease-in-out`. Only `transform` and `opacity` are animated or transitioned; nothing animates colour, shadow, size or position offsets.

- Buttons and action links lift 1px on hover (only where the device hovers) and shrink to 0.98 while pressed; a disabled button does neither. Keyboard focus draws a 2px `primary` outline offset by 2px, with no animation.
- A running step shows a ring that pulses in `opacity` and `scale`; the connector into it carries a short bright segment travelling along the line. The detail panel enters with `opacity` and an 8px rise when the selected step changes.
- A form error shakes once, 6px each way.
- Starting a run cross-fades the page where the browser supports view transitions and navigates at once elsewhere.
- Reduced motion removes every transition and animation above. Colour, icon, word, selected border and focus outline stay.

## Components

- Field: `input-bg` fill, `input-border` edge, `ink` text, `ink-3` placeholder.
- Button: `btn-bg` fill, `line-2` outline, text inherits; the primary button paints the `cta-from` to `cta-to` gradient with `primary-foreground` text.
- Badge: foreground on its `-soft` partner, fully rounded.
- Pipeline: a `paper-2` frame holding `paper-3` nodes joined by connectors; a node has a 4px left bar in its status colour, and the selected node is outlined in `primary`. Connectors are `green` when done, `primary` when active, `edge` (dashed when broken) otherwise.
- Card: `paper-3` (launchpad) or the card gradient (auth), `line` edge.

## Do's and Don'ts

- Do take every colour from a token; no literal colour outside `tokens.css`.
- Do edit `tokens.css` first; it wins over this file on any conflict.
- Do add a row to `src/styles/contrast.test.ts` for every new text or border pair.
- Don't use opacity to dim text; disabled controls are the only exception.
