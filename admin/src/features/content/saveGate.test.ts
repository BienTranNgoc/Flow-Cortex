import { saveGate } from './saveGate';

const BASE = { canManage: true, pending: false, unsaved: true, version: 3 };

describe('saveGate', () => {
  it('allows saving changes made to a stored draft', () => {
    expect(saveGate(BASE)).toBe(true);
  });

  it('blocks saving a stored draft that has no changes', () => {
    expect(saveGate({ ...BASE, unsaved: false })).toBe(false);
  });

  it('allows the first save of a document with no draft, even with nothing typed', () => {
    expect(saveGate({ ...BASE, unsaved: false, version: 0 })).toBe(true);
  });

  it('blocks while a save is in flight', () => {
    expect(saveGate({ ...BASE, pending: true })).toBe(false);
  });

  it('blocks a caller without the manage key', () => {
    expect(saveGate({ ...BASE, canManage: false })).toBe(false);
  });
});
