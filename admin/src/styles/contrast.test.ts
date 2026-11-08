import { readFileSync } from 'node:fs';

// Vite rewrites a literal `new URL('./x.css', import.meta.url)`; a variable path stays a file URL.
const tokensPath = './tokens.css';
const css = readFileSync(new URL(tokensPath, import.meta.url), 'utf8');

function block(opener: RegExp): string {
  const start = opener.exec(css);
  if (!start) throw new Error(`block ${opener} not found in tokens.css`);
  return css.slice(start.index + start[0].length, css.indexOf('\n}', start.index));
}

const blocks = {
  light: block(/(?:^|\n):root\s*\{/),
  dark: block(/(?:^|\n):root\[data-theme='dark'\]\s*\{/),
};
type Scheme = keyof typeof blocks;

function declared(scheme: Scheme, name: string): string | undefined {
  const find = (source: string) =>
    new RegExp(`(?:^|\\s)--${name}:\\s*([^;]+);`).exec(source)?.[1]?.trim();
  return find(blocks[scheme]) ?? (scheme === 'dark' ? find(blocks.light) : undefined);
}

function token(scheme: Scheme, name: string): string {
  const value = declared(scheme, `admin-${name}`) ?? declared(scheme, name);
  if (!value) throw new Error(`token --admin-${name} not declared`);
  const alias = /^var\(--(?:admin-)?([\w-]+)\)$/.exec(value)?.[1];
  return alias ? token(scheme, alias) : value;
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

/** Opaque colour of `top` laid at `pct`% over `bottom`, per channel. */
function mix(top: string, bottom: string, pct: number): string {
  const channel = (start: number) => {
    const t = parseInt(top.slice(start, start + 2), 16);
    const b = parseInt(bottom.slice(start, start + 2), 16);
    return Math.round((pct / 100) * t + (1 - pct / 100) * b)
      .toString(16)
      .padStart(2, '0');
  };
  return `#${channel(1)}${channel(3)}${channel(5)}`;
}

const translucent =
  /^color-mix\(in srgb,\s*var\(--(?:admin-)?([\w-]+)\)\s+(\d+)%,\s*transparent\)$/;

/** A token that is a hex or a `color-mix(in srgb, <token> N%, transparent)`, flattened over `base`. */
function flatten(scheme: Scheme, name: string, base: string): string {
  const value = token(scheme, name);
  const match = translucent.exec(value);
  const [, overlay, pct] = match ?? [];
  return overlay && pct ? mix(token(scheme, overlay), base, Number(pct)) : value;
}

const surfaces = ['paper', 'paper-2', 'paper-3', 'chrome-hover', 'chrome-active'];

const textPairs: [string, string][] = [
  ...['ink', 'ink-3'].flatMap((text) => surfaces.map((s): [string, string] => [text, s])),
  ['primary', 'chrome-active'],
  ['primary-foreground', 'primary'],
  ['primary-foreground', 'primary-hover'],
  ['green', 'green-soft'],
];

// [text, translucent pill background, surface under it]: the info and muted pills.
const pillOverSurface: [string, string, string][] = [
  ['pill-info-fg', 'pill-info-bg', 'card-surface-2'],
  ['ink-3', 'pill-muted-bg', 'card-surface-2'],
];

const borderPairs: [string, string][] = [
  ['input-border', 'paper'],
  ['input-border', 'paper-2'],
];

describe.each(['light', 'dark'] as const)('admin token contrast, %s scheme', (scheme) => {
  it.each(textPairs)('text --admin-%s on --admin-%s is at least 4.5:1', (text, background) => {
    expect(contrastRatio(token(scheme, text), token(scheme, background))).toBeGreaterThanOrEqual(
      4.5,
    );
  });

  it.each(pillOverSurface)(
    'pill text --admin-%s on --admin-%s over --admin-%s is at least 4.5:1',
    (text, background, surface) => {
      const flat = flatten(scheme, background, token(scheme, surface));
      expect(contrastRatio(token(scheme, text), flat)).toBeGreaterThanOrEqual(4.5);
    },
  );

  it.each(borderPairs)('border --admin-%s on --admin-%s is at least 3:1', (border, background) => {
    expect(contrastRatio(token(scheme, border), token(scheme, background))).toBeGreaterThanOrEqual(
      3,
    );
  });
});
