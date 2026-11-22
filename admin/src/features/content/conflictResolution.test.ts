import { conflictResolution } from './conflictResolution';

const LOCAL = { content: 'mine', version: 8 };
const OTHER = { content: 'theirs', version: 9 };

describe('conflictResolution', () => {
  it('load-latest takes the latest content and version and clears the unsaved mark', () => {
    expect(conflictResolution('load-latest', LOCAL, OTHER)).toEqual({
      content: 'theirs',
      version: 9,
      unsaved: false,
    });
  });

  it('overwrite keeps the local content under the latest version, still unsaved', () => {
    expect(conflictResolution('overwrite', LOCAL, OTHER)).toEqual({
      content: 'mine',
      version: 9,
      unsaved: true,
    });
  });

  it('load-revision takes the snapshot content and keeps the draft version as the token, unsaved', () => {
    expect(conflictResolution('load-revision', LOCAL, { content: 'snapshot', version: 2 })).toEqual(
      {
        content: 'snapshot',
        version: 8,
        unsaved: true,
      },
    );
  });

  it('load-revision keeps the draft version when the snapshot carries a higher one', () => {
    expect(
      conflictResolution('load-revision', LOCAL, { content: 'snapshot', version: 12 }).version,
    ).toBe(8);
  });

  it('never mutates its inputs', () => {
    const local = { ...LOCAL };
    const other = { ...OTHER };
    conflictResolution('overwrite', local, other);
    expect(local).toEqual(LOCAL);
    expect(other).toEqual(OTHER);
  });
});
