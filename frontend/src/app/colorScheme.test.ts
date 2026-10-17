import { readdirSync, readFileSync } from 'node:fs';

const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');

const srcDir = '../';
const stylesheets = readdirSync(new URL(srcDir, import.meta.url), {
  recursive: true,
  encoding: 'utf8',
}).filter((file) => file.endsWith('.css') && !file.endsWith('tokens.css'));

function rule(css: string, selector: string): string {
  const match = new RegExp(
    `(?:^|\\n)${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{([^}]*)\\}`,
  ).exec(css);
  return match?.[1] ?? '';
}

const COLOUR_LITERAL = /#[0-9a-fA-F]{3,8}\b|rgba?\(|hsla?\(/;

describe('colour scheme', () => {
  it('advertises light + dark to the browser and the design tokens default to light', () => {
    expect(read('../../index.html')).toMatch(
      /<meta name="color-scheme" content="light dark" \/>/,
    );
    expect(rule(read('../styles/tokens.css'), ':root')).toMatch(/color-scheme:\s*light;/);
    expect(read('../styles/tokens.css')).toMatch(/\[data-theme=['"]dark['"]\]/);
  });

  it('uses dedicated palette overrides for the dark theme instead of inline hex values', () => {
    const tokens = read('../styles/tokens.css');
    // Dark theme is an opt-in, additive override block — it must not change `:root`.
    expect(tokens).not.toMatch(/:root\s*\{[^}]*color-scheme:\s*dark/);
    expect(tokens).toMatch(/:root\[data-theme=['"]dark['"]\][\s\S]*color-scheme:\s*dark;/);
  });

  it('paints the page and button text from tokens', () => {
    const css = read('./base.css');

    expect(rule(css, 'body')).toMatch(/background:\s*var\(--paper\);/);
    expect(rule(css, 'body')).toMatch(/color:\s*var\(--ink\);/);
    expect(rule(css, 'button')).toMatch(/color:\s*inherit;/);
    expect(rule(css, 'button')).toMatch(/background:\s*var\(--btn-bg\);/);
  });

  it('finds the member stylesheets', () => {
    expect(stylesheets.length).toBeGreaterThanOrEqual(2);
  });

  it.each(stylesheets)('%s holds no colour literal', (file) => {
    expect(read(`../${file}`)).not.toMatch(COLOUR_LITERAL);
  });
});
