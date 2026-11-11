import type { UserResponse } from '@flow-cortex/api-client';
import { can, type Caller } from '../../app/permissions';

/**
 * Whether the console offers edit, role and suspend controls for `user`. Staff-ness is the
 * server's `staffTarget` flag, so a custom staff role is read-only here exactly as the server refuses it.
 */
export function canManageAccount(
  me: Caller | undefined,
  user: Pick<UserResponse, 'staffTarget'>,
): boolean {
  if (me === undefined || me.adminDisabled) return false;
  return !user.staffTarget || can(me, 'system.admin_account.manage');
}
