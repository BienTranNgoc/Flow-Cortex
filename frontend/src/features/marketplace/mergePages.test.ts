import { mergePages } from './mergePages';

const page = (...ids: string[]) => ({
  data: ids.map((id) => ({ workflow: { id }, tag: `${id}` })),
});

describe('mergePages', () => {
  it('appendedPageDropsIdsAlreadyShown', () => {
    const merged = mergePages([page('a', 'b'), page('b', 'c')]);
    expect(merged.map((item) => item.workflow.id)).toEqual(['a', 'b', 'c']);
  });

  it('firstOccurrenceKeepsItsPosition', () => {
    const first = { workflow: { id: 'b' }, tag: 'first' };
    const repeat = { workflow: { id: 'b' }, tag: 'repeat' };
    const merged = mergePages([
      { data: [{ workflow: { id: 'a' }, tag: 'a' }, first] },
      { data: [repeat, { workflow: { id: 'c' }, tag: 'c' }] },
    ]);
    expect(merged.map((item) => item.tag)).toEqual(['a', 'first', 'c']);
  });

  it('keepsAnItemThatHasNoId', () => {
    const merged = mergePages([{ data: [{ workflow: {} }, { workflow: {} }] }]);
    expect(merged).toHaveLength(2);
  });

  it('mergesNothingToNothing', () => {
    expect(mergePages([])).toEqual([]);
  });
});
