import { readFileSync } from 'node:fs';

const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');

function rule(css: string, selector: string): string {
  const match = new RegExp(`(?:^|\\n)${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{([^}]*)\\}`).exec(css);
  return match?.[1] ?? '';
}

// The styles are light-only, so the page must not let a dark browser preference repaint the
// canvas or the form controls under them.
describe('colour scheme', () => {
  it('declares a light-only page', () => {
    expect(read('../../index.html')).toMatch(/<meta name="color-scheme" content="light" \/>/);
  });

  it('paints its own page background and button text', () => {
    const css = read('./base.css');

    expect(rule(css, 'body')).toMatch(/background:\s*#fff;/);
    expect(rule(css, 'body')).toMatch(/color:\s*#1a1a1a;/);
    expect(rule(css, 'button')).toMatch(/color:\s*inherit;/);
    expect(rule(css, 'button')).toMatch(/background:\s*#fff;/);
  });
});
