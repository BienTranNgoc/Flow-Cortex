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
);

describe('token contrast', () => {
  it.each(textPairs)('text --%s on --%s is at least 4.5:1', (text, background) => {
    expect(contrastRatio(token(text), token(background))).toBeGreaterThanOrEqual(4.5);
  });

  it.each(borderPairs)('border --%s on --%s is at least 3:1', (border, background) => {
    expect(contrastRatio(token(border), token(background))).toBeGreaterThanOrEqual(3);
  });
});
