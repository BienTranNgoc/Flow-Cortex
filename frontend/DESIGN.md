---
version: alpha
name: Flow-Cortex member
description: >-
  Light and dark design system for the Flow-Cortex member SPA. The colours below
  are the default light scheme; the dark scheme overrides them in
  `src/styles/tokens.css`, which wins on any conflict.
colors:
  paper: '#f3f6fa'
  paper-2: '#ffffff'
  paper-3: '#e9eef4'
  btn-bg: '#ffffff'
  btn-bg-hover: '#f1f4f9'
  input-bg: '#ffffff'
  card-from: '#ffffff'
  card-to: '#eef2f7'
  glow: '#d9e4f3'
  header-bg: '#ffffff'
  footer-bg: '#eef2f7'
  aurora-top: '#eef2f8'
  aurora-mid: '#f6f8fb'
  aurora-bottom: '#ffffff'
  disabled-from: '#5a6b78'
  disabled-to: '#354250'
  ink: '#0c1620'
  ink-2: '#1c2530'
  ink-3: '#2b3641'
  ink-4: '#4a5762'
  ink-5: '#76838f'
  nav-ink: '#2b3641'
  ink-disabled: '#ffffff'
  line: '#98a3ad'
  line-2: '#76838f'
  line-3: '#b6c0cb'
  line-soft: '#c4ccd5'
  line-subtle: '#b6c0cb'
  card-border: '#98a3ad'
  control-border: '#76838f'
  input-border: '#5a6b78'
  input-border-hover: '#3a4a5b'
  edge: '#5a6b78'
  primary: '#066058'
  primary-foreground: '#ffffff'
  cta-from: '#2a4acc'
  cta-to: '#6f5cff'
  accent: '#2a4acc'
  accent-soft: '#e3ebff'
  primary-soft: '#cfeae6'
  teal: '#066058'
  teal-bright: '#054842'
  teal-glow: '#066058'
  teal-deep: '#04332f'
  violet: '#6f5cff'
  indigo: '#2a2a8c'
  aurora-teal: '#14c8b6'
  aurora-indigo: '#4a6cff'
  aurora-cyan: '#14c8b6'
  tone-blue-text: '#1d4ed8'
  tone-blue-bg: '#e6efff'
  tone-blue-line: '#b9d0ff'
  tone-cyan-text: '#0b6f7a'
  tone-cyan-bg: '#dff7fb'
  tone-cyan-line: '#95dde6'
  tone-cyan-from: '#066058'
  tone-cyan-to: '#2a4acc'
  tone-violet-text: '#4f3bd6'
  tone-violet-bg: '#ece6ff'
  tone-violet-line: '#c4b6ff'
  tone-amber-text: '#92580a'
  tone-amber-bg: '#fff3dd'
  tone-amber-line: '#f4d99a'
  tone-amber-from: '#f4a04a'
  tone-amber-to: '#e25c3a'
  tone-amber-on: '#3a1503'
  tone-green-bg: '#d6f3df'
  tone-green-line: '#95d8aa'
  tone-rose-text: '#b8264b'
  tone-rose-bg: '#fde0e6'
  tone-rose-line: '#f0a8b6'
  red: '#b8264b'
  red-soft: '#fde0e6'
  red-strong: '#d12d57'
  red-light: '#b8264b'
  warning: '#92580a'
  warning-soft: '#fff3dd'
  green: '#0a6b3e'
  green-soft: '#cdebd6'
  green-bright: '#0a6b3e'
  green-vivid: '#0a6b3e'
  shadow-color: '#0c1620'
rounded:
  sm: 4px
  md: 8px
  lg: 14px
  full: 9999px
---

## Overview

The member app has two themes. Light is the default (`:root` in `tokens.css`); dark applies when the root element carries `data-theme="dark"`, and its block in `tokens.css` overrides every colour token above under the same name. The user picks a theme with the header toggle, which is stored on the device; with no stored choice the system preference decides. `public/theme-init.js` applies that choice before the app bundle loads, so the first paint is already in the right theme. The document advertises `color-scheme: light dark` and the active theme narrows it, so browser-drawn controls (file picker, progress bars) match. One accent family, teal-cyan (`primary`) with blue (`accent`), carries links, eyebrows and the call to action.

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
- Do give every new colour token a value in both the light and the dark block.
- Do add a row to `src/styles/contrast.test.ts` for every new text or border pair.
- Don't use opacity to dim text; disabled controls are the only exception.
