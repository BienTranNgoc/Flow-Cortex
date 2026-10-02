import { ACTIONS, can } from './permissions';

describe('can', () => {
  it.each(ACTIONS)('lets an ADMIN %s', (action) => {
    expect(can('ADMIN', action)).toBe(true);
  });

  it.each(ACTIONS)('refuses a MEMBER %s', (action) => {
    expect(can('MEMBER', action)).toBe(false);
  });

  it('refuses an unknown or missing role', () => {
    expect(can(undefined, 'runs.view')).toBe(false);
    expect(can('OWNER', 'runs.view')).toBe(false);
  });

  it('refuses a role named like an inherited object key', () => {
    for (const role of ['toString', 'constructor', '__proto__', 'hasOwnProperty']) {
      expect(can(role, 'runs.view')).toBe(false);
    }
  });
});
