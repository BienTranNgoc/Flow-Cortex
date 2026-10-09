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
  input-bg: '#0b1119'
  card-from: '#101a24'
  card-to: '#0c111a'
  glow: '#152b38'
  ink: '#e8f1f2'
  ink-3: '#9eacb6'
  line: '#26313d'
  line-2: '#334352'
  input-border: '#62727f'
  primary: '#35e5e0'
  primary-foreground: '#061016'
  cta-from: '#1be0d1'
  cta-to: '#5174ff'
  accent: '#5f9cff'
  accent-soft: '#1b2440'
  red: '#ff9a9a'
  red-soft: '#3b1518'
  warning: '#ffd766'
  warning-soft: '#3a2f0b'
  green: '#7ee2a8'
  green-soft: '#0f3320'
rounded:
  sm: 4px
  lg: 14px
  full: 9999px
---

## Overview

The member app has one theme: dark. The document declares `color-scheme: dark`, so browser-drawn controls (file picker, progress bars) match. One accent family, cyan (`primary`) with blue (`accent`), carries links, eyebrows and the call to action.

## Colors

- Text: `ink` for body, `ink-3` for muted and secondary text. Both hold 4.5:1 on every surface.
- Surface: `paper` is the page, `paper-2` the header, `paper-3` a card, `input-bg` a field and raw output, `card-from` to `card-to` the auth card gradient.
- Border: `line` for cards and dividers, `line-2` for button outlines, `input-border` for fields (3:1 on every surface a field sits on).
- Status: each state pairs a foreground with its `-soft` background (`warning`, `green`, `red`, `accent`); pending uses `ink-3` on `line`.
- Links use `primary`; the header navigation keeps `ink-3`.

## Layout

Pages stay free of horizontal scroll at 375, 768 and 1280px. Buttons, inputs and action links are at least 44px tall.

## Shapes

`sm` for fields, buttons and blocks, `lg` for cards, `full` for badges.

## Components

- Field: `input-bg` fill, `input-border` edge, `ink` text, `ink-3` placeholder.
- Button: `btn-bg` fill, `line-2` outline, text inherits; the primary button paints the `cta-from` to `cta-to` gradient with `primary-foreground` text.
- Badge: foreground on its `-soft` partner, fully rounded.
- Card: `paper-3` (launchpad) or the card gradient (auth), `line` edge.

## Do's and Don'ts

- Do take every colour from a token; no literal colour outside `tokens.css`.
- Do edit `tokens.css` first; it wins over this file on any conflict.
- Do add a row to `src/styles/contrast.test.ts` for every new text or border pair.
- Don't use opacity to dim text; disabled controls are the only exception.
