import { readFileSync } from 'node:fs';

const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');

function rule(css: string, selector: string): string {
  const match = new RegExp(
    `(?:^|\\n)${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{([^}]*)\\}`,
  ).exec(css);
  return match?.[1] ?? '';
}

describe('colour scheme', () => {
  it('declares a dark-only page', () => {
    expect(read('../../index.html')).toMatch(/<meta name="color-scheme" content="dark" \/>/);
  });

  it('paints its own page background and button surface', () => {
    const css = read('./base.css');

    expect(rule(css, 'body')).toMatch(/background:\s*var\(--color-canvas\);/);
    expect(rule(css, 'body')).toMatch(/color:\s*var\(--color-text\);/);
    expect(rule(css, 'button')).toMatch(/color:\s*inherit;/);
    expect(rule(css, 'button')).toMatch(/background:\s*#141d28;/);
  });
});
