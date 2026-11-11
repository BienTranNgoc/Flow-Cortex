import i18n from '../../lib/i18n';
import { ADMIN_ME, staffMe } from '../../test/fakeApi';
import {
  assignableRoles,
  roleLabel,
  statusLabel,
} from './enumLabels';

describe('enumLabels', () => {
  const t = i18n.t.bind(i18n);

  it('reads a missing account status as active', () => {
    expect(statusLabel(t, undefined)).toBe('Active');
  });

  it('shows a status code the console does not know as sent', () => {
    expect(statusLabel(t, 'PENDING')).toBe('PENDING');
  });

  it('shows no role label when the account has no role', () => {
    expect(roleLabel(t, undefined)).toBe('');
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
