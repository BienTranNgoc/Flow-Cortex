import {
  indexItems,
  moved,
  withIndexItems,
  withItemLabel,
  type IndexItem,
  type Section,
} from './documentModel';

const section = (items: unknown): Section => ({
  id: 'section-index',
  type: 'legalIndex',
  schemaVersion: 1,
  data: { items },
});

describe('indexItems', () => {
  it('reads rows in order, shown unless explicitly hidden, keeping only string labels', () => {
    const rows = indexItems(
      section([
        { slug: 'legal-terms', visible: false, label: { en: 'Terms', vi: 3 } },
        { slug: 'legal-privacy' },
      ]),
    );

    expect(rows).toEqual([
      { slug: 'legal-terms', visible: false, label: { en: 'Terms' } },
      { slug: 'legal-privacy', visible: true, label: {} },
    ]);
  });

  it('drops a row without a slug and reads a section with no list as empty', () => {
    expect(indexItems(section([{ visible: true }, 'x', null, { slug: 'legal-terms' }]))).toHaveLength(1);
    expect(indexItems(section(undefined))).toEqual([]);
  });
});

describe('withIndexItems', () => {
  it('saves a label key only for a row holding an override', () => {
    const rows: IndexItem[] = [
      { slug: 'legal-terms', visible: true, label: {} },
      { slug: 'legal-privacy', visible: false, label: { vi: 'Bảo mật' } },
    ];

    expect(withIndexItems(section([]), rows).data.items).toEqual([
      { slug: 'legal-terms', visible: true },
      { slug: 'legal-privacy', visible: false, label: { vi: 'Bảo mật' } },
    ]);
  });
});

describe('withItemLabel', () => {
  const row: IndexItem = { slug: 'legal-terms', visible: true, label: { vi: 'Điều khoản' } };

  it('sets the override of one language and leaves the other', () => {
    expect(withItemLabel(row, 'en', 'Terms').label).toEqual({ vi: 'Điều khoản', en: 'Terms' });
  });

  it('removes the override when the value is cleared', () => {
    expect(withItemLabel(row, 'vi', '').label).toEqual({});
  });
});

describe('moved', () => {
  it('swaps a row with its neighbour', () => {
    expect(moved(['a', 'b', 'c'], 1, -1)).toEqual(['b', 'a', 'c']);
    expect(moved(['a', 'b', 'c'], 1, 1)).toEqual(['a', 'c', 'b']);
  });

  it('returns the list unchanged when the move leaves it', () => {
    expect(moved(['a', 'b'], 0, -1)).toEqual(['a', 'b']);
    expect(moved(['a', 'b'], 1, 1)).toEqual(['a', 'b']);
  });
});
