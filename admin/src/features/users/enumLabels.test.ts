import i18n from '../../lib/i18n';
import { ADMIN_ME, staffMe } from '../../test/fakeApi';
import { assignableRoles, roleKind, statusLabel } from './enumLabels';

describe('enumLabels', () => {
  const t = i18n.t.bind(i18n);

  it('reads a missing account status as active', () => {
    expect(statusLabel(t, undefined)).toBe('Active');
  });

  it('shows a status code the console does not know as sent', () => {
    expect(statusLabel(t, 'PENDING')).toBe('PENDING');
  });

  it('reads a custom code as staff, MEMBER and CREATOR as end-user and ADMIN as super admin', () => {
    expect(roleKind('KIEM_DUYET')).toBe('staff');
    expect(roleKind('CONTENT_EDITOR')).toBe('staff');
    expect(roleKind('MEMBER')).toBe('endUser');
    expect(roleKind('CREATOR')).toBe('endUser');
    expect(roleKind('ADMIN')).toBe('superAdmin');
  });

  it('offers the custom roles only to a caller who may manage admin accounts', () => {
    const roles = [
      { code: 'ADMIN', system: true },
      { code: 'KIEM_DUYET', system: false },
    ];
    const manager = staffMe('OPERATION_MANAGER', ['user.account.change_role']);

    expect(assignableRoles(ADMIN_ME, roles)).toContain('KIEM_DUYET');
    expect(assignableRoles(ADMIN_ME, roles).filter((code) => code === 'ADMIN')).toHaveLength(1);
    expect(assignableRoles(manager, roles)).toEqual(['MEMBER', 'CREATOR']);
  });

  it('offers no role without the change-role key, end-user roles with it, every role with the admin-account key too', () => {
    const noKey = staffMe('CUSTOMER_SUPPORT', ['user.account.view']);
    const manager = staffMe('OPERATION_MANAGER', ['user.account.change_role']);

    expect(assignableRoles(undefined)).toEqual([]);
    expect(assignableRoles(noKey)).toEqual([]);
    expect(assignableRoles(manager)).toEqual(['MEMBER', 'CREATOR']);
    expect(assignableRoles(ADMIN_ME)).toEqual([
      'ADMIN',
      'OPERATION_MANAGER',
      'CUSTOMER_SUPPORT',
      'CONTENT_EDITOR',
      'PLATFORM_OPERATOR',
      'MEMBER',
      'CREATOR',
    ]);
  });
});
