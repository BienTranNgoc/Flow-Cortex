import { readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import postcss from 'postcss';

// vitest runs from the app directory
const appRoot = process.cwd();
const roots = ['src/', '../packages/ui-kit/src/'];
// The auth shell keeps its gradient look; its `--auth-*` tokens are skipped in tokens.css.
const EXCLUDED_FILES = ['src/features/auth/AuthShell.css'];

const SHADOW_TOKEN = /^var\(--(?:shadow(?:-[a-z0-9-]+)?|focus-ring)\)$/;
const FORBIDDEN_TOKEN = /gradient|glow|aurora|glass|^cta-|violet|indigo/;
// Neutral status swatches (--tone-*) are a palette, not an accent family.
const TONE_TOKEN = /^tone-/;
const INLINE_STYLE_VALUE = /gradient\(|blur\(|backdrop/;

type Source = { file: string; text: string };

function filesUnder(root: string, extensions: string[]): Source[] {
  const rootDir = resolve(appRoot, root);
  return readdirSync(rootDir, { recursive: true, encoding: 'utf8' })
    .filter((file) => extensions.some((ext) => file.endsWith(ext)) && !/\.test\.[a-z]+$/.test(file))
    .map((file) => ({
      file: `${root}${file}`.replace(/\\/g, '/'),
      text: readFileSync(join(rootDir, file), 'utf8'),
    }))
    .filter(({ file }) => !EXCLUDED_FILES.includes(file));
}

const stylesheets = roots.flatMap((root) => filesUnder(root, ['.css']));
const components = roots.flatMap((root) => filesUnder(root, ['.tsx']));

type Violation = { file: string; selector: string; prop: string; value: string; reason: string };

function declarationViolations(file: string, css: string): Violation[] {
  const violations: Violation[] = [];
  const isTokens = file.endsWith('styles/tokens.css');
  postcss.parse(css).walkDecls((decl) => {
    const prop = decl.prop.toLowerCase();
    const value = decl.value;
    if (isTokens && prop.startsWith('--auth-')) return;
    const selector = decl.parent && 'selector' in decl.parent ? String(decl.parent.selector) : '';
    const report = (reason: string) => violations.push({ file, selector, prop, value, reason });

    if (/[a-z-]*gradient\(/i.test(value)) report('gradient function');
    if (prop === 'backdrop-filter' || prop === '-webkit-backdrop-filter') report('backdrop-filter');
    if ((prop === 'filter' || prop === '-webkit-filter') && /blur\(/i.test(value)) {
      report('filter blur');
    }
    if (prop === 'box-shadow') {
      const parts = value.split(/,(?![^(]*\))/).map((part) => part.trim());
      const allowed = value.trim() === 'none' || parts.every((part) => SHADOW_TOKEN.test(part));
      if (!allowed) report('box-shadow not from a token');
    }
  });
  return violations;
}

function inlineStyleViolations(file: string, source: string): string[] {
  const found: string[] = [];
  const attribute = /\bstyle=/g;
  for (let match = attribute.exec(source); match; match = attribute.exec(source)) {
    const start = match.index + match[0].length;
    const quote = source[start];
    let end = start;
    if (quote === '{') {
      let depth = 0;
      for (; end < source.length; end += 1) {
        if (source[end] === '{') depth += 1;
        if (source[end] === '}') {
          depth -= 1;
          if (depth === 0) break;
        }
      }
    } else if (quote === '"' || quote === "'") {
      end = source.indexOf(quote, start + 1);
    }
    if (INLINE_STYLE_VALUE.test(source.slice(start, end + 1))) found.push(file);
  }
  return found;
}

function tokenNames(css: string): string[] {
  const names: string[] = [];
  postcss.parse(css).walkDecls(/^--/, (decl) => {
    names.push(decl.prop.slice(2));
  });
  return names;
}

describe('no-gradient guard', () => {
  it('scans a non-empty set of stylesheets and components', () => {
    expect(stylesheets.length).toBeGreaterThan(0);
    expect(components.length).toBeGreaterThan(0);
  });

  it('rejects each known violation fixture with the same checker', () => {
    const fixtures = [
      'a { background: linear-gradient(red, blue); }',
      'a { filter:blur(4px); }',
      'a { -webkit-backdrop-filter: blur(2px); }',
      'a { box-shadow: 0 0 40px #7c5cff; }',
    ];
    for (const css of fixtures) {
      expect(declarationViolations('src/x.css', css), css).toHaveLength(1);
    }
    expect(
      declarationViolations('src/x.css', 'a { box-shadow: var(--shadow), var(--focus-ring); }'),
    ).toEqual([]);
    expect(
      declarationViolations(
        'src/x.css',
        '/* linear-gradient(red, blue) */ a { box-shadow: none; }',
      ),
    ).toEqual([]);
    expect(
      inlineStyleViolations('x.tsx', "<a style={{ background: 'linear-gradient(red, blue)' }} />"),
    ).toHaveLength(1);
    expect(inlineStyleViolations('x.tsx', '<a style={{ width: 4 }} />')).toEqual([]);
  });

  it('carries no gradient, backdrop-filter or blur, and takes box-shadow from tokens', () => {
    const violations = stylesheets.flatMap(({ file, text }) => declarationViolations(file, text));
    expect(violations).toEqual([]);
  });

  it('carries no inline gradient, blur or backdrop style in a component', () => {
    const violations = components.flatMap(({ file, text }) => inlineStyleViolations(file, text));
    expect(violations).toEqual([]);
  });

  it('names no gradient, glow, aurora, glass, cta, violet or indigo token outside the auth group', () => {
    const tokens = readFileSync(join(appRoot, 'src/styles/tokens.css'), 'utf8');
    const offenders = tokenNames(tokens).filter(
      (name) => !name.startsWith('auth-') && !TONE_TOKEN.test(name) && FORBIDDEN_TOKEN.test(name),
    );
    expect(offenders).toEqual([]);
  });
});
