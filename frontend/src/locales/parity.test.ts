import i18n from '../lib/i18n';

type LocaleTree = { [key: string]: string | LocaleTree };

const files = import.meta.glob<LocaleTree>('./*/*.json', { eager: true, import: 'default' });

const LOCALES = ['vi', 'en'] as const;

function flatten(tree: LocaleTree, prefix = ''): Map<string, string> {
  const entries = new Map<string, string>();
  for (const [key, value] of Object.entries(tree)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (typeof value === 'string') {
      entries.set(path, value);
    } else {
      for (const [nested, text] of flatten(value, path)) entries.set(nested, text);
    }
  }
  return entries;
}

function areaFile(locale: string, area: string): Map<string, string> | undefined {
  const tree = files[`./${locale}/${area}.json`];
  return tree === undefined ? undefined : flatten(tree);
}

const areas = [
  ...new Set(Object.keys(files).map((path) => path.replace(/^\.\/[^/]+\/(.+)\.json$/, '$1'))),
].sort();

describe('locale parity', () => {
  it('finds the locale files', () => {
    expect(areas.length).toBeGreaterThan(0);
  });

  it('registers every area file in the i18n resources', () => {
    const registered = Object.keys(i18n.options.resources?.en?.translation ?? {}).sort();

    expect(registered).toEqual(areas);
  });

  describe.each(areas)('%s', (area) => {
    it.each(LOCALES)('has a %s file', (locale) => {
      expect(files[`./${locale}/${area}.json`], `${locale}/${area}.json is missing`).toBeDefined();
    });

    it('holds the same keys in vi and en', () => {
      const vi = areaFile('vi', area) ?? new Map<string, string>();
      const en = areaFile('en', area) ?? new Map<string, string>();
      const missingInVi = [...en.keys()].filter((key) => !vi.has(key));
      const missingInEn = [...vi.keys()].filter((key) => !en.has(key));

      expect(missingInVi, `keys missing from vi/${area}.json`).toEqual([]);
      expect(missingInEn, `keys missing from en/${area}.json`).toEqual([]);
    });

    it.each(LOCALES)('has no empty %s value', (locale) => {
      const entries = areaFile(locale, area) ?? new Map<string, string>();
      const empty = [...entries].filter(([, text]) => text.trim() === '').map(([key]) => key);

      expect(empty, `empty values in ${locale}/${area}.json`).toEqual([]);
    });
  });
});
