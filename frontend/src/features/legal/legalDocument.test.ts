import type { ContentSection } from '@flow-cortex/api-client';
import { labelIn, readLegalDocument } from './legalDocument';

const legalDoc = (data: Record<string, unknown>): ContentSection => ({
  type: 'legalDoc',
  schemaVersion: 1,
  data,
});

const DATA = {
  title: { vi: 'Chính sách', en: 'Policy' },
  body: { vi: 'Nội dung', en: 'Body' },
  docVersion: '1.0',
  effectiveDate: '2026-12-01',
};

describe('readLegalDocument', () => {
  it('reads the legalDoc section in the requested language', () => {
    expect(readLegalDocument([legalDoc(DATA)], 'en')).toEqual({
      title: 'Policy',
      body: 'Body',
      docVersion: '1.0',
      effectiveDate: '2026-12-01',
    });
    expect(readLegalDocument([legalDoc(DATA)], 'vi')?.title).toBe('Chính sách');
  });

  it('falls back to the other language where the requested one is blank', () => {
    const data = { ...DATA, title: { vi: '  ', en: 'Policy' }, body: { en: 'Body' } };

    expect(readLegalDocument([legalDoc(data)], 'vi')).toMatchObject({ title: 'Policy', body: 'Body' });
  });

  it('is null when the copy has no legalDoc section', () => {
    expect(readLegalDocument([], 'en')).toBeNull();
    expect(readLegalDocument([{ type: 'richText', schemaVersion: 1, data: DATA }], 'en')).toBeNull();
  });

  it('is null when the section does not parse', () => {
    expect(readLegalDocument([legalDoc({ ...DATA, docVersion: 3 })], 'en')).toBeNull();
    expect(readLegalDocument([legalDoc({ title: DATA.title })], 'en')).toBeNull();
  });

  it('is null when neither language has a title', () => {
    expect(readLegalDocument([legalDoc({ ...DATA, title: { vi: '', en: ' ' } })], 'en')).toBeNull();
  });
});

describe('labelIn', () => {
  it('reads the language, then the other one, then empty', () => {
    expect(labelIn({ vi: 'Bảo mật', en: 'Privacy' }, 'vi')).toBe('Bảo mật');
    expect(labelIn({ vi: ' ', en: 'Privacy' }, 'vi')).toBe('Privacy');
    expect(labelIn({}, 'en')).toBe('');
  });
});
