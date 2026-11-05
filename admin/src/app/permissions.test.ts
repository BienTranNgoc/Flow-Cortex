import { ACTIONS, can } from './permissions';

describe('can', () => {
  it('lists every action the console guards', () => {
    expect(ACTIONS).toEqual([
      'console.enter',
      'runs.view',
      'mediaJobs.view',
      'workflows.view',
      'approvals.view',
      'credits.view',
      'providers.view',
      'features.toggle',
      'audit.view',
      'voice.view',
      'health.view',
      'users.manage',
      'support.manage',
    ]);
  });

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
