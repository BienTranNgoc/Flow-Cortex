import { ADMIN_ME, staffMe } from '../../test/fakeApi';
import { canManageAccount } from './userAccess';

const MEMBER_ROW = { staffTarget: false };

describe('canManageAccount', () => {
  const manager = staffMe('OPERATION_MANAGER', ['user.account.update']);

  it('allows a non-staff target for a caller with an account key', () => {
    expect(canManageAccount(manager, MEMBER_ROW)).toBe(true);
  });

  it.each(['CUSTOMER_SUPPORT', 'ADMIN', 'REGION_LEAD'])(
    'refuses staff target %s without the admin-account key',
    () => {
      expect(canManageAccount(manager, { staffTarget: true })).toBe(false);
    },
  );

  it('allows a staff target with the admin-account key', () => {
    const superAdmin = staffMe('ADMIN', ['user.account.update', 'system.admin_account.manage']);
    expect(canManageAccount(superAdmin, { staffTarget: true })).toBe(true);
    expect(canManageAccount(ADMIN_ME, { staffTarget: true })).toBe(true);
  });

  it('refuses a deactivated caller', () => {
    expect(canManageAccount({ ...ADMIN_ME, adminDisabled: true }, MEMBER_ROW)).toBe(false);
  });

  it('refuses when the caller is not loaded', () => {
    expect(canManageAccount(undefined, MEMBER_ROW)).toBe(false);
  });

  it('treats a target that does not report staffTarget as non-staff', () => {
    expect(canManageAccount(manager, {})).toBe(true);
  });
});
