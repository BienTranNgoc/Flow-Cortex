import { readFileSync } from 'node:fs';

// Vite rewrites a literal `new URL('./x.css', import.meta.url)`; a variable path stays a file URL.
const tokensPath = './tokens.css';
const css = readFileSync(new URL(tokensPath, import.meta.url), 'utf8');

const opener = /(?:^|\n):root\s*\{/.exec(css);
if (!opener) throw new Error(':root block not found in tokens.css');
const root = css.slice(opener.index + opener[0].length, css.indexOf('\n}', opener.index));

function token(name: string): string {
  const value = new RegExp(`(?:^|\\s)--${name}:\\s*([^;]+);`).exec(root)?.[1]?.trim();
  if (!value) throw new Error(`token --${name} not declared`);
  const alias = /^var\(--([\w-]+)\)$/.exec(value)?.[1];
  return alias ? token(alias) : value;
}

function relativeLuminance(hex: string): number {
  if (!/^#[0-9a-fA-F]{6}$/.test(hex)) throw new Error(`not a six-digit hex colour: ${hex}`);
  const linear = (start: number) => {
    const c = parseInt(hex.slice(start, start + 2), 16) / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * linear(1) + 0.7152 * linear(3) + 0.0722 * linear(5);
}

function contrastRatio(a: string, b: string): number {
  const la = relativeLuminance(a);
  const lb = relativeLuminance(b);
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}

const surfaces = ['paper', 'paper-2', 'paper-3', 'input-bg', 'btn-bg', 'card-from', 'card-to'];

const textPairs: [string, string][] = [
  ...surfaces.map((s): [string, string] => ['ink', s]),
  ...['paper', 'paper-2', 'paper-3', 'input-bg', 'card-from', 'card-to'].map(
    (s): [string, string] => ['ink-3', s],
  ),
  ...['paper', 'paper-3', 'card-from', 'card-to'].map((s): [string, string] => ['primary', s]),
  ...['paper', 'paper-3', 'card-from', 'card-to', 'btn-bg'].map((s): [string, string] => [
    'red',
    s,
  ]),
  ['accent', 'paper'],
  ['input-placeholder', 'input-bg'],
  ['input-text', 'input-bg'],
  ['primary-foreground', 'cta-from'],
  ['primary-foreground', 'cta-to'],
  ['ink-3', 'line'],
  ['primary', 'primary-soft'],
  ['warning', 'warning-soft'],
  ['green', 'green-soft'],
  ['red', 'red-soft'],
  ['accent', 'accent-soft'],
  ...['paper', 'paper-3', 'input-bg'].map((s): [string, string] => ['ink-2', s]),
  ['ink-4', 'paper'],
  ['ink-4', 'paper-3'],
  ['accent', 'paper-3'],
  ['nav-ink', 'header-bg'],
  ['ink', 'header-bg'],
  ['ink', 'footer-bg'],
  ['ink-3', 'footer-bg'],
  ['teal', 'footer-bg'],
  ['teal-bright', 'paper-2'],
  ['teal-bright', 'paper-3'],
  ['ink-disabled', 'disabled-from'],
  ['ink-disabled', 'disabled-to'],
  ['primary-foreground', 'green-bright'],
  ['green-bright', 'paper-3'],
  ['green-vivid', 'paper'],
  ['green-vivid', 'paper-3'],
  ['red-light', 'paper-3'],
  ['tone-blue-text', 'tone-blue-bg'],
  ['tone-cyan-text', 'tone-cyan-bg'],
  ['tone-violet-text', 'tone-violet-bg'],
  ['tone-amber-text', 'tone-amber-bg'],
  ['green-bright', 'tone-green-bg'],
  ['tone-rose-text', 'tone-rose-bg'],
  ['primary-foreground', 'tone-cyan-from'],
  ['tone-amber-on', 'tone-amber-from'],
  ['tone-amber-on', 'tone-amber-to'],
];

const borderPairs: [string, string][] = [
  'input-bg',
  'paper',
  'paper-3',
  'card-from',
  'card-to',
].map((s) => ['input-border', s]);
borderPairs.push(
  ['edge', 'paper-2'],
  ['edge', 'paper-3'],
  ['green', 'paper-2'],
  ['primary', 'paper-2'],
  ['red-strong', 'input-bg'],
);

describe('token contrast', () => {
  it.each(textPairs)('text --%s on --%s is at least 4.5:1', (text, background) => {
    expect(contrastRatio(token(text), token(background))).toBeGreaterThanOrEqual(4.5);
  });

  it.each(borderPairs)('border --%s on --%s is at least 3:1', (border, background) => {
    expect(contrastRatio(token(border), token(background))).toBeGreaterThanOrEqual(3);
  });
});
