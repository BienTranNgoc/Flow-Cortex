---
version: alpha
name: Flow-Cortex admin
description: >-
  Light and dark design system for the Flow-Cortex admin console. The colours
  below are the default light scheme; the dark scheme overrides them in
  `src/styles/tokens.css`, which wins on any conflict. Every colour name is the
  token name without its `--admin-` prefix, except the unprefixed `line-*`,
  `input-border` and `nodi-*` groups.
colors:
  paper: '#f7f9fc'
  paper-2: '#ffffff'
  paper-3: '#eef1f6'
  btn-bg: '#ffffff'
  btn-bg-hover: '#f1f4f9'
  input-bg: '#ffffff'
  chrome-hover: '#f1f4f8'
  chrome-border: '#e6e9ed'
  card-surface: '#ffffff'
  card-surface-2: '#f7f9fc'
  ink: '#0c1620'
  ink-2: '#1c2530'
  ink-3: '#2b3641'
  ink-4: '#4a5762'
  ink-5: '#76838f'
  nav-ink: '#2b3641'
  primary: '#066058'
  primary-hover: '#054842'
  primary-foreground: '#ffffff'
  primary-soft: '#cfeae6'
  red: '#b8264b'
  red-soft: '#fde0e6'
  warning: '#92580a'
  warning-soft: '#fff3dd'
  green: '#0a6b3e'
  green-soft: '#cdebd6'
  shadow-color: '#0c1620'
  line-2: '#76838f'
  line-3: '#b6c0cb'
  input-border: '#5a6b78'
  nodi-body: '#35e5e0'
  nodi-ink: '#061016'
  nodi-eye: '#ffffff'
  nodi-port: '#0b6a80'
typography:
  sans:
    fontFamily: Google Sans Flex Variable
rounded:
  sm: 6px
  md: 10px
  lg: 12px
  xl: 16px
  full: 9999px
---

## Overview

The admin console has two themes. Light is the default (`:root` in `tokens.css`); dark applies when the root element carries `data-theme="dark"`, and its block overrides every colour token above under the same name. The dark scheme paints first (`public/theme-init.js` applies the stored choice before the bundle loads). One accent family, teal (`primary`), carries links, the current navigation entry and the call to action. The chrome is flat: no gradient, no frosted glass, no glow. `accent` is an alias of `primary` and `accent-soft` of `primary-soft`, not a second accent.

## Colors

- Text: `ink` for body, `ink-3` for muted and secondary text. Both hold 4.5:1 on `paper`, `paper-2`, `paper-3`, `chrome-hover` and `chrome-active`.
- Surface: `paper` is the page, `paper-2` the sidebar, topbar and cards, `paper-3` a sunken well, `chrome-hover` a hovered navigation entry, `chrome-active` (`primary-soft`) the current entry, `input-bg` a field.
- Border: `chrome-border` (also `card-edge`) is the decorative edge of the sidebar, topbar and cards, with no contrast floor. `input-border` is the field edge and holds 3:1 on `paper` and `paper-2`.
- Status: each pill pairs a foreground with its soft background (`warning`, `green`, `red`); `info` and `muted` pills use `pill-info-bg` and `pill-muted-bg`, translucent fills over `card-surface-2`. Every pair holds 4.5:1.
- Links use `primary`. Hover and press on a primary button use `primary-hover`.
- Translucent fills are `color-mix(in srgb, var(--token) N%, transparent)` over an opaque token, never an `rgba()` literal.
- `nodi-*` is the mascot art of the shared `NodiMark`; they are the one unprefixed group because the component reads the same names in both apps. They are non-text art.

## Typography

One family, `--admin-font-sans`: Google Sans Flex Variable, self-hosted from `@fontsource-variable/google-sans-flex` and imported once in `main.tsx`, falling back to Inter, then the system sans-serif. Controls inherit it.

## Layout

Pages stay free of horizontal scroll at 375, 768 and 1280px. `--admin-control-h` (36px) sets the height of inputs, buttons and icon buttons; it becomes `--admin-hit-min` (44px) on `(any-pointer: coarse)` or below 1024px. Sidebar entries are `--admin-hit-min` everywhere. Adjacent controls stay 8px apart.

## Shapes

`sm` for chips and small blocks, `md` for fields, buttons and navigation entries, `lg` for cards and the popover, `xl` for dialogs, `full` for pills.

## Elevation

Shadows come only from tokens: `--admin-shadow-sm`, `--admin-shadow`, `--admin-shadow-lg` and the focus ring `--admin-focus-ring` (`0 0 0 2px var(--admin-primary)`). Raw shadow values live only in `tokens.css`. No gradient, `backdrop-filter`, blur or glow anywhere.

## Motion

Hover and press take `dur-quick` (120ms) on `ease-standard`; settling and the drawer take `dur-standard` (220ms). Only `transform` and `opacity` are animated; nothing animates colour, shadow, size or offsets. Reduced motion removes every transition and animation. Keyboard focus draws a 2px `primary` outline offset by 2px.

## Components

- Field: `input-bg` fill, `input-border` edge, `ink` text, `ink-3` placeholder.
- Button: `btn-bg` fill, `chrome-border` outline; the primary button is a solid `primary` fill with `primary-foreground` text and `primary-hover` on hover and press.
- Pill: foreground on its soft partner, fully rounded.
- Card: `card-surface` fill, `card-edge` edge, `--admin-shadow-sm`.
- Icons: Phosphor, for every interface and navigation icon. The one exception is the ui-kit `ThemeToggle`, which keeps its inline SVG; brand and provider marks keep their own artwork.

## Do's and Don'ts

- Do take every colour from a token; no literal colour outside `tokens.css`.
- Do edit `tokens.css` first; it wins over this file on any conflict.
- Do give every new colour token a value in both the light and the dark block.
- Do add a row to `src/styles/contrast.test.ts` for every new text or border pair.
- Don't paint a gradient, frosted backdrop or glow, and don't add a second accent hue.
- Don't use opacity to dim text; disabled controls are the only exception.
