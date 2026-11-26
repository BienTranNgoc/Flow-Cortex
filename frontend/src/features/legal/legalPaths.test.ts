import { docOfPath, hasNextPage, pageOf, slugForDoc } from './legalPaths';

describe('slugForDoc', () => {
  it('prefixes a well-formed document segment', () => {
    expect(slugForDoc('privacy')).toBe('legal-privacy');
    expect(slugForDoc('plan-change-renewal')).toBe('legal-plan-change-renewal');
    expect(slugForDoc('v2')).toBe('legal-v2');
  });

  it.each(['Privacy', '', '-x', 'x-', 'a--b', '../x', 'privacy%2F', 'a/b', 'a b', 'ơ'])(
    'refuses %j',
    (doc) => {
      expect(slugForDoc(doc)).toBeNull();
    },
  );
});

describe('pageOf', () => {
  it.each([
    ['1', 1],
    ['2', 2],
    ['100001', 100_001],
  ])('reads %j as page %i', (raw, page) => {
    expect(pageOf(raw)).toBe(page);
  });

  it.each([null, '', '0', '-1', '1.5', 'abc', '100002'])('reads %j as page 1', (raw) => {
    expect(pageOf(raw)).toBe(1);
  });
});

describe('hasNextPage', () => {
  it('is true only while publications remain beyond the page', () => {
    expect(hasNextPage(1, 20, 25)).toBe(true);
    expect(hasNextPage(1, 20, 21)).toBe(true);
    expect(hasNextPage(1, 20, 20)).toBe(false);
    expect(hasNextPage(2, 20, 25)).toBe(false);
    expect(hasNextPage(1, 20, 0)).toBe(false);
  });
});

describe('docOfPath', () => {
  it('reads the document segment of an index entry path', () => {
    expect(docOfPath('/legal/privacy')).toBe('privacy');
    expect(docOfPath('/legal/plan-change-renewal')).toBe('plan-change-renewal');
  });

  it.each(['/legal', '/legal/', '/legal/Privacy', '/legal/a/b', '/other/privacy', 'legal/privacy', ''])(
    'refuses %j',
    (path) => {
      expect(docOfPath(path)).toBeNull();
    },
  );
});
