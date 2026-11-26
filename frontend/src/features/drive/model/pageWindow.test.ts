import { pageAfterShrink, pageWindow } from './pageWindow';

describe('pageWindow', () => {
  it('one page gives [1]', () => {
    expect(pageWindow(1, 1)).toEqual([1]);
  });

  it('gives no page for no results', () => {
    expect(pageWindow(1, 0)).toEqual([]);
  });

  it('lists every page when there are few', () => {
    expect(pageWindow(2, 5)).toEqual([1, 2, 3, 4, 5]);
  });

  it('page 5 of 10 gives 1 … 4 5 6 … 10', () => {
    expect(pageWindow(5, 10)).toEqual([1, '…', 4, 5, 6, '…', 10]);
  });

  it('the first and last pages keep their neighbours without a gap', () => {
    expect(pageWindow(1, 10)).toEqual([1, 2, '…', 10]);
    expect(pageWindow(10, 10)).toEqual([1, '…', 9, 10]);
    expect(pageWindow(2, 10)).toEqual([1, 2, 3, '…', 10]);
    expect(pageWindow(9, 10)).toEqual([1, '…', 8, 9, 10]);
  });
});

describe('pageAfterShrink', () => {
  it('steps back to the last page when a page comes back empty with total > 0', () => {
    expect(pageAfterShrink(2, 40, 20)).toBe(1);
    expect(pageAfterShrink(5, 41, 20)).toBe(2);
  });

  it('keeps a page that is inside the results', () => {
    expect(pageAfterShrink(1, 40, 20)).toBe(1);
    expect(pageAfterShrink(0, 3, 20)).toBe(0);
  });

  it('stays on the first page when nothing is left', () => {
    expect(pageAfterShrink(0, 0, 20)).toBe(0);
    expect(pageAfterShrink(2, 0, 20)).toBe(0);
  });
});
