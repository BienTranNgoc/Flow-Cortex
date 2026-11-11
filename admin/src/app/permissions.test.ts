import type { MeResponse } from '@flow-cortex/api-client';
import { ADMIN_ME, MEMBER_ME, staffMe } from '../test/fakeApi';
import { can, canEnterConsole } from './permissions';

const SUPPORT: MeResponse = staffMe('CUSTOMER_SUPPORT', ['support.ticket.view', 'workflow.run.view']);
const DISABLED: MeResponse = { ...ADMIN_ME, adminDisabled: true };

describe('can', () => {
  it('is true only for a key among the permissions', () => {
    expect(can(SUPPORT, 'support.ticket.view')).toBe(true);
    expect(can(SUPPORT, 'user.account.view')).toBe(false);
    expect(can(ADMIN_ME, 'system.admin_account.manage')).toBe(true);
  });

  it('is false for every key once the admin account is deactivated', () => {
    expect(can(DISABLED, 'workflow.run.view')).toBe(false);
  });

  it('is false before the caller is known', () => {
    expect(can(undefined, 'workflow.run.view')).toBe(false);
  });

  it('is false for a key named like an inherited object property', () => {
    for (const key of ['toString', 'constructor', '__proto__', 'hasOwnProperty']) {
      expect(can(ADMIN_ME, key as never)).toBe(false);
    }
  });
});

describe('canEnterConsole', () => {
  it('needs at least one key', () => {
    expect(canEnterConsole(SUPPORT)).toBe(true);
    expect(canEnterConsole(MEMBER_ME)).toBe(false);
  });

  it('refuses a deactivated admin even though it still lists keys', () => {
    expect(canEnterConsole(DISABLED)).toBe(false);
  });

  it('refuses an unknown caller', () => {
    expect(canEnterConsole(undefined)).toBe(false);
  });
});
