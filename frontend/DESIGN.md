---
version: alpha
name: Flow-Cortex member
description: >-
  Light and dark design system for the Flow-Cortex member SPA, with a fixed light
  palette for the public auth screens. The colours below are the default light
  scheme; the dark scheme overrides them in `src/styles/tokens.css`, which wins
  on any conflict.
colors:
  paper: '#f7f9fc'
  paper-2: '#ffffff'
  paper-3: '#eef1f6'
  btn-bg: '#ffffff'
  btn-bg-hover: '#f1f4f9'
  input-bg: '#ffffff'
  chrome-hover: '#f1f4f8'
  chrome-active: '#cfeae6'
  chrome-border: '#e6e9ed'
  card-edge: '#e6e9ed'
  ink: '#0c1620'
  ink-2: '#1c2530'
  ink-3: '#2b3641'
  ink-4: '#4a5762'
  ink-5: '#76838f'
  nav-ink: '#2b3641'
  line: '#98a3ad'
  line-2: '#76838f'
  line-3: '#b6c0cb'
  line-soft: '#c4ccd5'
  card-border: '#98a3ad'
  input-border: '#5a6b78'
  input-border-hover: '#3a4a5b'
  edge: '#5a6b78'
  primary: '#066058'
  primary-foreground: '#ffffff'
  primary-hover: '#054842'
  accent: '#066058'
  primary-soft: '#cfeae6'
  teal: '#066058'
  teal-bright: '#054842'
  teal-deep: '#04332f'
  tone-blue-text: '#1d4ed8'
  tone-blue-bg: '#e6efff'
  tone-cyan-text: '#0b6f7a'
  tone-cyan-bg: '#dff7fb'
  tone-violet-text: '#4f3bd6'
  tone-violet-bg: '#ece6ff'
  tone-amber-text: '#92580a'
  tone-amber-bg: '#fff3dd'
  tone-green-bg: '#d6f3df'
  tone-rose-text: '#b8264b'
  tone-rose-bg: '#fde0e6'
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
  auth-ocean: '#0b57a6'
  auth-card: '#f1f6f8'
  auth-ink: '#061016'
  auth-ink-2: '#26343d'
  auth-ink-3: '#4d5f6b'
  auth-ink-4: '#5b6b76'
  auth-field: '#e9f0f3'
  auth-field-border: '#6b7b86'
  auth-accent: '#0b6a80'
  auth-google: '#0d1a24'
  auth-glass-dark: '#062b52'
  auth-on-ocean: '#ffffff'
  auth-line: '#d3dee4'
  auth-glow: '#066058'
  auth-progress-from: '#f4a04a'
  auth-progress-to: '#e25c3a'
  auth-progress-on: '#3a1503'
  nodi-body: '#35e5e0'
  nodi-ink: '#061016'
  nodi-cheek: '#5174ff'
  nodi-blush: '#ff8fa3'
  nodi-eye: '#ffffff'
  nodi-port: '#0b6a80'
  nodi-headset: '#ff7a59'
  nodi-earcup: '#ffb46b'
typography:
  sans:
    fontFamily: Google Sans Flex Variable
rounded:
  sm: 6px
  md: 10px
  lg: 12px
  xl: 16px
  full: 9999px
  auth-card: 20px
  auth-control: 6px
---

## Overview

The member app has two themes. Light is the default (`:root` in `tokens.css`); dark applies when the root element carries `data-theme="dark"`, and its block in `tokens.css` overrides every colour token above under the same name. The user picks a theme with the header toggle, which is stored on the device; with no stored choice the system preference decides. `public/theme-init.js` applies that choice before the app bundle loads, so the first paint is already in the right theme. The document advertises `color-scheme: light dark` and the active theme narrows it, so browser-drawn controls (file picker, progress bars) match. One accent family, teal (`primary`), carries links, eyebrows and the call to action; `accent` is an alias of `primary`, never a second accent. Chrome is flat: no gradient, glow, blur or glass outside the auth shell. The public auth screens are the exception: they use the fixed light auth palette (see Colors) whatever theme is chosen.

## Colors

- Text: `ink` for body, `ink-3` for muted and secondary text. Both hold 4.5:1 on every surface.
- Surface: `paper` is the page canvas, `paper-2` the sidebar, topbar, cards and popovers, `paper-3` a sunken well, a pipeline node or a disabled fill, `input-bg` a field and raw output. Sidebar entries use `chrome-hover` on hover and `chrome-active` (with `primary` text) for the current entry; `ink` and `ink-3` hold 4.5:1 on both.
- Chrome and card edges: `chrome-border` and `card-edge` (decorative, no 3:1 floor); `line` keeps its value for dividers that carry meaning.
- Border: `line` for cards and dividers, `line-2` for button outlines, `input-border` for fields (3:1 on every surface a field sits on), `edge` (the same colour) for pipeline connectors and their port dots (3:1 on `paper-2` and `paper-3`).
- Status: each state pairs a foreground with its `-soft` background (`primary`, `warning`, `green`, `red`, `accent`); pending uses `ink-3` on `line`. Running is `primary` on `primary-soft`; `warning` means "Needs you" and nothing else.
- Links use `primary`; the header navigation uses `nav-ink` on `paper-2`.
- Translucent fills are `color-mix(in srgb, var(--token) N%, transparent)` over an opaque token (`primary`, `ink`, `red-strong`, `green-bright`), never an `rgba()` literal.
- Shadows: `shadow-sm`, `shadow` and `shadow-lg` are ink-tinted (black in dark), and `focus-ring` is a 2px `primary` ring. A stylesheet takes every `box-shadow` from these tokens; raw shadow values live only in `tokens.css`.
- Primary button: `primary` fill with `primary-foreground` text, `primary-hover` on hover and press; disabled is flat `paper-3` with `ink-4`; success is `green-soft` with `green` text.
- Workflow categories each pair a `tone-*-text` with its `tone-*-bg`.

### Auth palette

The public auth screens (sign in, register, confirm email, set password) are a fixed light brand surface: a frosted light card on an ocean banner. Their shell root sets `color-scheme: light` and ignores both the operating-system preference and the member's chosen theme; nothing outside that root changes, so signed-in screens keep the chosen theme. The VI | EN toggle sits in the shell's top bar, restyled with the auth tokens, with 44px controls.

The verify-phone page, which a signed-in member the server holds for an unverified number sees before any product page, uses the same card. It reuses the profile phone form, so its root remaps that form's app tokens (`ink`, `input-bg`, `primary`, `red`, `green` and the like) to the auth palette instead of adding colours; the form's own heading stays for screen readers only, and its title is 28px so the card fits a 1366×650 window.

| token               | value     | use                                                                |
| ------------------- | --------- | ------------------------------------------------------------------ |
| `auth-ocean`        | `#0b57a6` | banner base; `auth-on-ocean` text 7.17:1                           |
| `auth-card`         | `#f1f6f8` | card fill, never below 90% where text sits                         |
| `auth-ink`          | `#061016` | headings and body on the card (17.63:1)                            |
| `auth-ink-2`        | `#26343d` | field labels                                                       |
| `auth-ink-3`        | `#4d5f6b` | secondary text on the card (6.09:1)                                |
| `auth-ink-4`        | `#5b6b76` | placeholder on the field (4.78:1)                                  |
| `auth-field`        | `#e9f0f3` | field fill                                                         |
| `auth-field-border` | `#6b7b86` | field edge (3.79:1 on the field, 4.01:1 on the card)               |
| `auth-accent`       | `#0b6a80` | links and focus outline on the card (5.69:1)                       |
| `auth-google`       | `#0d1a24` | Google button fill; `auth-on-ocean` text 17.64:1                   |
| `auth-glass-dark`   | `#062b52` | banner chips at 45% over `auth-ocean`; `auth-on-ocean` text 9.88:1 |
| `auth-on-ocean`     | `#ffffff` | text on the banner, chips and the Google button                    |
| `auth-line`         | `#d3dee4` | decorative divider only, never a field edge                        |
| `nodi-body`         | `#35e5e0` | mascot body (non-text art, as are all `nodi-*`)                    |
| `nodi-ink`          | `#061016` | mascot outline and pupils                                          |
| `nodi-cheek`        | `#5174ff` | mascot cheek panel                                                 |
| `nodi-blush`        | `#ff8fa3` | mascot blush                                                       |
| `nodi-eye`          | `#ffffff` | mascot eye white                                                   |
| `nodi-port`         | `#0b6a80` | mascot port dots                                                   |

The call to action keeps the `auth-gradient-cta` gradient (`#2a4acc` to `#6f5cff`, a dark-scheme override in `tokens.css`) with `primary-foreground` text; the shell's glow is `auth-glow`, and the promo pill and progress bar keep `auth-progress-from` to `auth-progress-to` with `auth-progress-on`. These are the only gradients in the member app. The promo card is light frosted glass, `auth-on-ocean` from 50% to 34% over the banner, with `auth-ink` text (5.73:1 at the 34% end over `auth-ocean`). Auth cards round to `auth-card` (20px), auth controls to `auth-control` (6px).

The auth card is frosted, but its gradient never drops below 90% `auth-card` anywhere from the brand row to the footnote: at 90% over `auth-ink`, taken as the worst-case backdrop, `auth-accent` holds 4.61:1 and `auth-ink-3` 4.94:1. The invalid field edge is `red-strong` at 55% over `auth-ink`, 5.60:1 on `auth-field` and 5.92:1 on `auth-card`.

## Typography

One family, `--font-sans`: Google Sans Flex Variable, self-hosted from `@fontsource-variable/google-sans-flex` (latin, latin-ext and vietnamese subsets) and imported once in `main.tsx`, falling back to Inter, then the system sans-serif. Every screen, signed-in and auth, uses it; controls inherit it.

## Layout

Pages stay free of horizontal scroll at 375, 768 and 1280px. Controls (inputs, the search field, buttons, icon buttons) are `control-h` tall: 36px, and `hit-min` (44px) on `(any-pointer: coarse)` or below 1024px. Sidebar entries are `hit-min` everywhere. Adjacent controls keep at least 8px apart.

## Shapes

`sm` (6px) for small blocks, `md` (10px) for fields and buttons, `lg` (12px) for cards and pipeline nodes, `xl` (16px) for popovers and large panels, `full` for badges.

## Motion

Personality: corporate, calm and exact. One curve, `ease-standard`, and three durations: `dur-quick` (120ms) for hover and press, `dur-standard` (220ms) for settling, the detail panel entrance and the route cross-fade, `dur-slow` (360ms) for the error shake. The two endless loops are the only exceptions to the curve and the durations: the running ring (1.6s) and the connector segment (1.4s), both `ease-in-out`. Only `transform` and `opacity` are animated or transitioned; nothing animates colour, shadow, size or position offsets.

- Buttons and action links shrink to 0.98 while pressed; a disabled button does not. Keyboard focus draws a 2px `primary` outline offset by 2px, with no animation.
- A running step shows a ring that pulses in `opacity` and `scale`; the connector into it carries a short bright segment travelling along the line. The detail panel enters with `opacity` and an 8px rise when the selected step changes.
- A form error shakes once, 6px each way.
- Starting a run cross-fades the page where the browser supports view transitions and navigates at once elsewhere.
- Reduced motion removes every transition and animation above. Colour, icon, word, selected border and focus outline stay.

## Components

- Field: `input-bg` fill, `input-border` edge, `ink` text, `ink-3` placeholder.
- Button: `btn-bg` fill, `line-2` outline, text inherits; the primary button is solid `primary` with `primary-foreground` text, no gradient.
- Badge: foreground on its `-soft` partner, fully rounded.
- Pipeline: a `paper-2` frame holding `paper-3` nodes joined by connectors; a node has a 4px left bar in its status colour, and the selected node is outlined in `primary`. Connectors are `green` when done, `primary` when active, `edge` (dashed when broken) otherwise.
- Card: `paper-2` fill, `card-edge` edge, `shadow-sm`.
- Icons: Phosphor, one weight family. `ThemeToggle` keeps its own sun and moon glyphs as the one exception.

### Password re-check and API tokens

- Step-up dialog: a native modal `dialog`, `min(26rem, 100vw − 2rem)` wide, `paper-3` fill, `line` edge, `md` radius, `shadow`, over a `shadow-color` backdrop; it enters with `opacity` and an 8px rise over `dur-standard`. Focus starts on the password field, Escape or Cancel closes it and focus returns to the opener. A wrong password shows a `red` message, clears the field and refocuses it. Below 480px the buttons stack full width.
- API tokens section (profile, admins only): a title and `ink-3` hint, a create form (name field, lifetime select 30/90/180/365 days), then rows separated by `line-soft` rules. A row wraps a long name, shows `ink-3` dates beneath it and a `red` on `red-soft` expired badge when expired. Revoke opens an inline confirmation naming the token, with focus on "Keep token".
- Reveal: a `paper-3` panel with a read-only token field, a copy button announced by a polite `green` status line, and the one-time warning in `ink-2`; closing it drops the token from the page.

### Help Center

- Launcher: a 56px button fixed bottom-right (20px from the edges, safe-area aware), `paper-3` fill, `line-2` outline, fully rounded, `shadow`. It holds the headset mascot, painted only from the `nodi-*` tokens (`nodi-headset` and `nodi-earcup` belong to the launcher alone); from 640px up the label "Trợ giúp" shows beside it in `ink`. Its accessible name is "Trợ giúp" at every width, and it reports `aria-expanded`.
- Clearance: while the widget is on, the page body gains `support-launcher-clearance` (88px) of bottom padding, so the launcher never covers the last control of a page.
- Panel: a `role="dialog"` labelled "Trung tâm trợ giúp", 400 × min(640px, 100svh − 96px), sitting above the launcher; `paper-3` body, `paper-2` header with `line` dividers, `lg` radius, `shadow-lg`. It enters with `opacity` and an 8px rise over `dur-standard`. Below 640px it fills the screen with no radius and a 44px close button. Escape or the close button closes it and returns focus to the launcher.
- Tabs: a `role="tablist"` with a roving tabindex; arrow keys, Home and End move and select. A guest sees Trang chủ and Trợ lý; a member also sees Hướng dẫn and Yêu cầu. A tab is `ink-3`, the selected one `ink` with a 2px `primary` underline.
- Lists (Bắt đầu articles, suggested questions, guide groups, search results): full-width 44px rows in the button style, section titles in small uppercase `ink-3`.
- States: loading is an `ink-3` status line; a failed read shows a `red` message with "Thử lại"; empty and no-match states offer "Hỏi trợ lý"; an article that is no longer published says so and leads back to Hướng dẫn (Trang chủ for a guest).
- Article: a "Quay lại" link-style button (focused while the article loads), the title (focused when it loads) and the Markdown body in `ink-2`, with code on `input-bg`. Raw HTML shows as text and an image as its alt text; only http(s), mailto and app-relative links are links (`//host` is not), and external ones open in a new tab. An unpublished article is announced as a status and focuses its way back.
- Small screens: below 640px the open panel is modal (`aria-modal="true"`) and everything outside it is `inert`; the desktop panel stays non-modal. Escape closes the panel only when pressed inside it.
- Trợ lý: an `ink-3` intro, then a `role="log"` (`aria-live="polite"`, focusable) of messages. A member's question is a `btn-bg` bubble with a `line-2` edge and `md` radius; an answer is Markdown with its sources as fully rounded `btn-bg` chips, and under every member answer a `primary` link-style "Vẫn chưa giải quyết? Gửi yêu cầu hỗ trợ" that opens the request form prefilled with the question and linked to the conversation. "Trợ lý đang trả lời…" is a `role="status"` line; errors are `role="alert"` with "Thử lại". The question box is a field with an `ink-3` counter ("n/1000") that turns `red` over the limit.
- Yêu cầu: the member's requests as list rows, newest activity first, each with a status chip (foreground on its `-soft` partner: Mới `accent`, Đang xử lý `primary`, Chờ bạn phản hồi `warning`, Đã giải quyết `green`, Đã đóng `ink-3` on `line`). A thread shows member messages on `input-bg` and team messages on `paper`. An unread admin reply puts an 8px `red` dot on the launcher, the Yêu cầu tab and the request row, with "Có phản hồi mới" in their accessible names.

## Do's and Don'ts

- Do take every colour from a token; no literal colour outside `tokens.css`.
- Do edit `tokens.css` first; it wins over this file on any conflict.
- Do give every new colour token a value in both the light and the dark block.
- Do add a row to `src/styles/contrast.test.ts` for every new text or border pair.
- Don't use opacity to dim text; disabled controls are the only exception.
- Don't write `gradient(`, `backdrop-filter`, a blur or a glow shadow outside the auth shell; there is one accent, `primary`.
