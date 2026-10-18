import { readFileSync } from 'node:fs';

const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');

function rule(css: string, selector: string): string {
  const match = new RegExp(
    `(?:^|\\n)${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{([^}]*)\\}`,
  ).exec(css);
  return match?.[1] ?? '';
}

describe('colour scheme', () => {
  it('declares a dark default via tokens', () => {
    const tokens = read('../styles/tokens.css');
    expect(tokens).toMatch(/--admin-paper:\s*#080c13;/);
  });

  it('paints its own page background and button surface from tokens', () => {
    const css = read('../styles/base.css');

    expect(rule(css, 'body')).toMatch(/background:\s*var\(--admin-paper\)/);
    expect(rule(css, 'body')).toMatch(/color:\s*var\(--admin-ink\)/);
    expect(rule(css, '.cortex-admin-btn')).toMatch(/background:\s*var\(--admin-btn-bg\)/);
    expect(rule(css, '.cortex-admin-btn')).toMatch(/color:\s*var\(--admin-ink\)/);
  });

  it('applies the saved theme from a first-party script that loads before the bundle', () => {
    const html = read('../../index.html');
    const init = read('../../public/theme-init.js');

    expect(html).toMatch(/<meta name="color-scheme" content="light dark" \/>/);
    expect(html.indexOf('src="/theme-init.js"')).toBeGreaterThan(-1);
    expect(html.indexOf('src="/theme-init.js"')).toBeLessThan(html.indexOf('src="/src/main.tsx"'));
    expect(init).toContain("'flow-cortex:admin:theme'");
    expect(read('./useTheme.ts')).toContain("storageKey: 'flow-cortex:admin:theme'");
  });
});
