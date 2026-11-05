import type { i18n as I18n } from 'i18next';
import { describe, expect, it } from 'vitest';
import { SUPPORTED_LOCALES } from './locale';

export type LocaleTree = { [key: string]: string | LocaleTree };

/** `./<locale>/<area>.json` paths to their parsed content, as an eager `import.meta.glob` returns them. */
export type LocaleFiles = Record<string, LocaleTree>;

export function flattenLocale(tree: LocaleTree, prefix = ''): Map<string, string> {
  const entries = new Map<string, string>();
  for (const [key, value] of Object.entries(tree)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (typeof value === 'string') {
      entries.set(path, value);
    } else {
      for (const [nested, text] of flattenLocale(value, path)) entries.set(nested, text);
    }
  }
  return entries;
}

export function localeAreas(files: LocaleFiles): string[] {
  return [
    ...new Set(Object.keys(files).map((path) => path.replace(/^\.\/[^/]+\/(.+)\.json$/, '$1'))),
  ].sort();
}

function areaFile(files: LocaleFiles, locale: string, area: string): Map<string, string> {
  const tree = files[`./${locale}/${area}.json`];
  return tree === undefined ? new Map<string, string>() : flattenLocale(tree);
}

/** Registers the parity suite: every area file registered in `i18n`, with the same non-empty keys in vi and en. */
export function describeLocaleParity(files: LocaleFiles, i18n: I18n): void {
  const areas = localeAreas(files);

  describe('locale parity', () => {
    it('finds the locale files', () => {
      expect(areas.length).toBeGreaterThan(0);
    });

    it('registers every area file in the i18n resources', () => {
      const registered = Object.keys(i18n.options.resources?.en?.translation ?? {}).sort();

      expect(registered).toEqual(areas);
    });

    describe.each(areas)('%s', (area) => {
      it.each(SUPPORTED_LOCALES)('has a %s file', (locale) => {
        expect(
          files[`./${locale}/${area}.json`],
          `${locale}/${area}.json is missing`,
        ).toBeDefined();
      });

      it('holds the same keys in vi and en', () => {
        const vi = areaFile(files, 'vi', area);
        const en = areaFile(files, 'en', area);
        const missingInVi = [...en.keys()].filter((key) => !vi.has(key));
        const missingInEn = [...vi.keys()].filter((key) => !en.has(key));

        expect(missingInVi, `keys missing from vi/${area}.json`).toEqual([]);
        expect(missingInEn, `keys missing from en/${area}.json`).toEqual([]);
      });

      it.each(SUPPORTED_LOCALES)('has no empty %s value', (locale) => {
        const empty = [...areaFile(files, locale, area)]
          .filter(([, text]) => text.trim() === '')
          .map(([key]) => key);

        expect(empty, `empty values in ${locale}/${area}.json`).toEqual([]);
      });
    });
  });
}
